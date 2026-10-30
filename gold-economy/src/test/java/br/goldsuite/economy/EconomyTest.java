package br.goldsuite.economy;

import br.goldsuite.api.EconomyService.Currency;
import com.google.gson.Gson;

public final class EconomyTest {
    public static void main(String[] args) {
        Ledger ledger = new Ledger();
        ledger.credit("Alice", Currency.MONEY, 10000);
        ledger.transfer("ALICE", "Bruno", Currency.MONEY, 4000);
        check(ledger.balance("alice", Currency.MONEY) == 6000 && ledger.balance("bruno", Currency.MONEY) == 4000, "Transferencia perdeu dinheiro.");
        rejects(() -> ledger.transfer("alice", "BRUNO", Currency.MONEY, 6001));
        check(ledger.balance("alice", Currency.MONEY) + ledger.balance("bruno", Currency.MONEY) == 10000, "Recusa alterou saldos.");
        rejects(() -> ledger.transfer("alice", "ALICE", Currency.MONEY, 1));
        rejects(() -> ledger.credit("alice", Currency.MONEY, -1));
        rejects(() -> ledger.credit("alice", Currency.MONEY, Long.MAX_VALUE));
        ledger.set("bruno", Currency.MONEY, Ledger.MAX_BALANCE);
        rejects(() -> ledger.transfer("alice", "bruno", Currency.MONEY, 1));
        check(ledger.balance("alice", Currency.MONEY) == 6000, "Limite do destinatario debitou o remetente.");
        ledger.credit("Alice", Currency.CASH, 5);
        ledger.exchange("alice", 3);
        check(ledger.balance("alice", Currency.MONEY) == 36000 && ledger.balance("alice", Currency.CASH) == 2, "Conversao incorreta.");
        rejects(() -> ledger.exchange("alice", 3));
        check(Ledger.parse("1,23", Currency.MONEY) == 123 && Ledger.parse("2", Currency.CASH) == 2, "Precisao decimal perdida.");
        check(Ledger.parseBalance("0,00", Currency.MONEY) == 0 && Ledger.parseBalance("0", Currency.CASH) == 0, "Definir saldo deve aceitar zero.");
        rejects(() -> Ledger.parseBalance("1000000000000", Currency.CASH));
        for (String invalid : new String[] {"NaN", "Infinity", "1e9", "-1", "0", "1.001", "1000000000000"}) { rejects(() -> Ledger.parse(invalid, Currency.MONEY)); }
        Gson gson = new Gson();
        Ledger reopened = gson.fromJson(gson.toJson(ledger), Ledger.class);
        Ledger.validate(reopened);
        check(reopened.balance("ALICE", Currency.CASH) == 2, "Serializacao alterou a conta.");
        System.out.println("GoldEconomy: transferencia, conservacao, limites, cash, precisao e serializacao aprovadas.");
    }
    private static void check(boolean condition, String message) { if (!condition) { throw new AssertionError(message); } }
    private static void rejects(Runnable action) { try { action.run(); } catch (IllegalArgumentException | ArithmeticException expected) { return; } throw new AssertionError("Valor invalido aceito."); }
}
