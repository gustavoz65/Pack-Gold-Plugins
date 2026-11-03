package br.goldsuite.economy;

import br.goldsuite.api.EconomyService.Currency;
import cn.nukkit.utils.PlayerName;
import java.math.BigDecimal;
import java.util.*;

/** Cada transferencia modifica uma unica copia persistida atomicamente. */
public final class Ledger {
    public static final long MAX_BALANCE = 1_000_000_000_000L;
    public Map<String, Account> accounts = new HashMap<>();
    /** Contas mexidas na operacao atual (para gravar so elas) e o valor original de cada uma (para desfazer). */
    transient Set<String> touched = new HashSet<>();
    transient Map<String, long[]> undo = new HashMap<>();

    /** Guarda o valor original antes da primeira mudanca na conta nesta operacao. */
    private Account touch(String key) {
        if (touched == null) { touched = new HashSet<>(); undo = new HashMap<>(); }
        Account a = accounts.get(key);
        boolean existed = a != null;
        if (a == null) { a = new Account(); accounts.put(key, a); }
        if (touched.add(key)) { undo.put(key, new long[] {a.money, a.cash, a.bank, existed ? 1 : 0}); }
        return a;
    }

    /** Desfaz a operacao que falhou no meio. */
    void rollback() {
        if (touched == null) { return; }
        for (Map.Entry<String, long[]> e : undo.entrySet()) {
            if (e.getValue()[3] == 0) { accounts.remove(e.getKey()); continue; }
            Account a = accounts.get(e.getKey());
            if (a != null) { a.money = e.getValue()[0]; a.cash = e.getValue()[1]; a.bank = e.getValue()[2]; }
        }
        touched.clear(); undo.clear();
    }

    /** Contas mexidas desde a ultima chamada (a operacao deu certo). */
    Set<String> commit() {
        if (touched == null || touched.isEmpty()) { return Collections.emptySet(); }
        Set<String> out = new HashSet<>(touched);
        touched.clear(); undo.clear();
        return out;
    }

    /** "money,cash,banco" de uma conta, para gravar em uma linha. */
    static String encode(Account a) { return a.money + "," + a.cash + "," + a.bank; }
    static Account decode(String v) {
        String[] p = v.split(",");
        Account a = new Account();
        a.money = Long.parseLong(p[0]); a.cash = Long.parseLong(p[1]); a.bank = p.length > 2 ? Long.parseLong(p[2]) : 0;
        if (a.money < 0 || a.money > MAX_BALANCE || a.cash < 0 || a.cash > MAX_BALANCE || a.bank < 0 || a.bank > MAX_BALANCE) { throw new IllegalArgumentException("Conta invalida."); }
        return a;
    }
    public long centsPerCash = 10000;
    public static final class Account { public long money; public long cash; /** Dinheiro guardado no Banco Central (centavos). */ public long bank; }
    public static String name(String name) {
        if (!PlayerName.isValid(name)) { throw new IllegalArgumentException("Jogador invalido."); }
        return name.toLowerCase(Locale.ROOT);
    }
    public static long parse(String text, Currency currency) { long amount = parseBalance(text, currency); positive(amount); return amount; }
    /** Aceita zero; usado apenas para definir saldo. */
    public static long parseBalance(String text, Currency currency) {
        if (text == null || !text.matches(currency == Currency.MONEY ? "[0-9]{1,12}([.,][0-9]{1,2})?" : "[0-9]{1,12}")) { throw new IllegalArgumentException("Informe um valor positivo, sem notacao cientifica."); }
        long amount = new BigDecimal(text.replace(',', '.')).movePointRight(currency == Currency.MONEY ? 2 : 0).longValueExact();
        if (amount > MAX_BALANCE) { throw new IllegalArgumentException("Valor fora do limite."); }
        return amount;
    }
    public static String format(long amount, Currency currency) { return currency == Currency.MONEY ? BigDecimal.valueOf(amount, 2).toPlainString() : Long.toString(amount); }
    public long balance(String player, Currency currency) {
        Account account = accounts.get(name(player));
        return account == null ? 0 : currency == Currency.MONEY ? account.money : account.cash;
    }
    public void set(String player, Currency currency, long amount) {
        if (amount < 0 || amount > MAX_BALANCE) { throw new IllegalArgumentException("Saldo fora do limite."); }
        Account account = touch(name(player));
        if (currency == Currency.MONEY) { account.money = amount; } else { account.cash = amount; }
    }
    public void credit(String player, Currency currency, long amount) { positive(amount); set(player, currency, Math.addExact(balance(player, currency), amount)); }
    public void debit(String player, Currency currency, long amount) { positive(amount); long balance = balance(player, currency); if (balance < amount) { throw new IllegalArgumentException("Saldo insuficiente."); } set(player, currency, balance - amount); }
    public void transfer(String from, String to, Currency currency, long amount) {
        if (name(from).equals(name(to))) { throw new IllegalArgumentException("Nao e possivel pagar a si mesmo."); }
        positive(amount);
        long source = balance(from, currency), destination = balance(to, currency);
        if (source < amount || destination > MAX_BALANCE - amount) { throw new IllegalArgumentException("Saldo insuficiente ou limite do destinatario atingido."); }
        set(from, currency, source - amount); set(to, currency, destination + amount);
    }
    public void exchange(String player, long cash) {
        positive(cash);
        long cents = Math.multiplyExact(cash, centsPerCash);
        if (balance(player, Currency.CASH) < cash || cents > MAX_BALANCE - balance(player, Currency.MONEY)) { throw new IllegalArgumentException("Saldo insuficiente ou limite atingido."); }
        debit(player, Currency.CASH, cash); credit(player, Currency.MONEY, cents);
    }
    public long bank(String player) { Account a = accounts.get(name(player)); return a == null ? 0 : a.bank; }

    /** Carteira -> banco, numa unica alteracao. */
    public void deposit(String player, long cents) {
        positive(cents);
        Account a = touch(name(player));
        if (a.money < cents) { throw new IllegalArgumentException("Voce nao tem esse valor na carteira."); }
        if (a.bank > MAX_BALANCE - cents) { throw new IllegalArgumentException("Limite do banco atingido."); }
        a.money -= cents; a.bank += cents;
    }

    /** Banco -> carteira, numa unica alteracao. */
    public void withdraw(String player, long cents) {
        positive(cents);
        Account a = touch(name(player));
        if (a.bank < cents) { throw new IllegalArgumentException("Voce nao tem esse valor no banco."); }
        if (a.money > MAX_BALANCE - cents) { throw new IllegalArgumentException("Limite da carteira atingido."); }
        a.bank -= cents; a.money += cents;
    }

    public static void positive(long amount) { if (amount <= 0 || amount > MAX_BALANCE) { throw new IllegalArgumentException("Valor fora do limite."); } }
    public static void validate(Ledger ledger) {
        if (ledger == null || ledger.accounts == null || ledger.centsPerCash < 1 || ledger.centsPerCash > MAX_BALANCE) { throw new IllegalArgumentException("Livro de contas invalido."); }
        for (Map.Entry<String, Account> entry : ledger.accounts.entrySet()) {
            Account account = entry.getValue();
            if (!name(entry.getKey()).equals(entry.getKey()) || account == null || account.money < 0 || account.money > MAX_BALANCE || account.cash < 0 || account.cash > MAX_BALANCE || account.bank < 0 || account.bank > MAX_BALANCE) { throw new IllegalArgumentException("Conta invalida."); }
        }
    }
}
