package br.goldsuite.economy;

import br.goldsuite.api.EconomyService.Currency;
import cn.nukkit.utils.PlayerName;
import java.math.BigDecimal;
import java.util.*;

/** Cada transferencia modifica uma unica copia persistida atomicamente. */
public final class Ledger {
    public static final long MAX_BALANCE = 1_000_000_000_000L;
    public Map<String, Account> accounts = new TreeMap<>();
    public long centsPerCash = 10000;
    public static final class Account { public long money; public long cash; }
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
        Account account = accounts.computeIfAbsent(name(player), ignored -> new Account());
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
    public static void positive(long amount) { if (amount <= 0 || amount > MAX_BALANCE) { throw new IllegalArgumentException("Valor fora do limite."); } }
    public static void validate(Ledger ledger) {
        if (ledger == null || ledger.accounts == null || ledger.accounts.size() > 10000 || ledger.centsPerCash < 1 || ledger.centsPerCash > MAX_BALANCE) { throw new IllegalArgumentException("Livro de contas invalido ou cheio."); }
        for (Map.Entry<String, Account> entry : ledger.accounts.entrySet()) {
            Account account = entry.getValue();
            if (!name(entry.getKey()).equals(entry.getKey()) || account == null || account.money < 0 || account.money > MAX_BALANCE || account.cash < 0 || account.cash > MAX_BALANCE) { throw new IllegalArgumentException("Conta invalida."); }
        }
    }
}
