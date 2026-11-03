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
        Ledger bank = new Ledger();
        bank.credit("ana", br.goldsuite.api.EconomyService.Currency.MONEY, 10_000);
        bank.deposit("ana", 7_000);
        if (bank.balance("ana", br.goldsuite.api.EconomyService.Currency.MONEY) != 3_000 || bank.bank("ana") != 7_000) { throw new AssertionError("Deposito no banco errado."); }
        bank.withdraw("ana", 2_000);
        if (bank.balance("ana", br.goldsuite.api.EconomyService.Currency.MONEY) != 5_000 || bank.bank("ana") != 5_000) { throw new AssertionError("Saque do banco errado."); }
        try { bank.withdraw("ana", 9_000); throw new AssertionError("Sacou mais que o saldo do banco."); } catch (IllegalArgumentException expected) { }
        try { bank.deposit("ana", 9_000); throw new AssertionError("Depositou mais que a carteira."); } catch (IllegalArgumentException expected) { }
        Ledger.validate(new com.google.gson.Gson().fromJson(new com.google.gson.Gson().toJson(bank), Ledger.class));
        if (!br.goldsuite.api.PlaceholderService.compact(1234).equals("1.23k") || !br.goldsuite.api.PlaceholderService.compact(5_600_000).equals("5.6M") || !br.goldsuite.api.PlaceholderService.compact(999).equals("999") || !br.goldsuite.api.PlaceholderService.compact(100_000).equals("100k")) { throw new AssertionError("Formato curto errado: " + br.goldsuite.api.PlaceholderService.compact(1234)); }
        accountStore();
        System.out.println("GoldEconomy: transferencia, conservacao, limites, cash, precisao e serializacao aprovadas.");
    }
    private static void check(boolean condition, String message) { if (!condition) { throw new AssertionError(message); } }
    private static void rejects(Runnable action) { try { action.run(); } catch (IllegalArgumentException | ArithmeticException expected) { return; } throw new AssertionError("Valor invalido aceito."); }

    /** Armazenamento em memoria que conclui na hora (como o GoldCore, mas sincrono). */
    static final class Mem implements br.goldsuite.api.AsyncStore {
        final java.util.Map<String, String> rows = new java.util.HashMap<>();
        int batches;
        boolean fail;
        @Override public java.util.concurrent.CompletableFuture<java.util.Optional<String>> get(String ns, String k) { return java.util.concurrent.CompletableFuture.completedFuture(java.util.Optional.ofNullable(rows.get(ns + "/" + k))); }
        @Override public java.util.concurrent.CompletableFuture<Void> put(String ns, String k, String v) { rows.put(ns + "/" + k, v); return java.util.concurrent.CompletableFuture.completedFuture(null); }
        @Override public java.util.concurrent.CompletableFuture<Void> delete(String ns, String k) { rows.remove(ns + "/" + k); return java.util.concurrent.CompletableFuture.completedFuture(null); }
        @Override public java.util.concurrent.CompletableFuture<java.util.Map<String, String>> all(String ns) {
            java.util.Map<String, String> out = new java.util.HashMap<>();
            for (java.util.Map.Entry<String, String> e : rows.entrySet()) { if (e.getKey().startsWith(ns + "/")) { out.put(e.getKey().substring(ns.length() + 1), e.getValue()); } }
            return java.util.concurrent.CompletableFuture.completedFuture(out);
        }
        @Override public java.util.concurrent.CompletableFuture<Void> putAll(String ns, java.util.Map<String, String> values) {
            if (fail) { java.util.concurrent.CompletableFuture<Void> f = new java.util.concurrent.CompletableFuture<>(); f.completeExceptionally(new java.io.IOException("disco")); return f; }
            batches++;
            for (java.util.Map.Entry<String, String> e : values.entrySet()) { rows.put(ns + "/" + e.getKey(), e.getValue()); }
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
    }

    private static void accountStore() {
        br.goldsuite.api.EconomyService.Currency M = br.goldsuite.api.EconomyService.Currency.MONEY;
        Mem mem = new Mem();
        // Documento antigo (formato de antes) e migrado sozinho.
        Ledger old = new Ledger();
        old.credit("ana", M, 500); old.credit("bia", br.goldsuite.api.EconomyService.Currency.CASH, 7); old.centsPerCash = 250;
        mem.rows.put("goldeconomy/estado", new com.google.gson.Gson().toJson(old));
        AccountStore store = new AccountStore(mem, msg -> { });
        if (!store.isLoaded() || store.read(l -> l.balance("ana", M)) != 500 || store.read(l -> l.balance("bia", br.goldsuite.api.EconomyService.Currency.CASH)) != 7 || store.read(l -> l.centsPerCash) != 250) { throw new AssertionError("Migracao do documento antigo falhou."); }
        if (mem.rows.containsKey("goldeconomy/estado") || !"500,0,0".equals(mem.rows.get("goldeconomy-contas/ana"))) { throw new AssertionError("Migracao nao gravou uma linha por conta: " + mem.rows); }
        // Mais de 10 mil contas (o limite antigo) e so as alteradas sao gravadas, em um lote.
        for (int i = 0; i < 12_000; i++) { String n = "jogador" + i; store.change(l -> l.credit(n, M, 1)); }
        int before = mem.batches;
        store.flush();
        if (mem.batches != before + 1 || mem.all("goldeconomy-contas").join().size() != 12_002) { throw new AssertionError("Lote de contas errado."); }
        store.change(l -> l.credit("ana", M, 1));
        java.util.Map<String, String> snap = new java.util.HashMap<>(mem.rows);
        store.flush();
        int changed = 0; for (java.util.Map.Entry<String, String> e : mem.rows.entrySet()) { if (!e.getValue().equals(snap.get(e.getKey()))) { changed++; } }
        if (changed != 1) { throw new AssertionError("Gravou contas que nao mudaram: " + changed); }
        // Operacao que falha no meio e desfeita, e conta criada por ela some.
        if (!store.change(l -> { l.credit("caio", M, 100); l.debit("ana", M, 999_999); }).isCompletedExceptionally()) { throw new AssertionError("Operacao invalida aceita."); }
        if (store.read(l -> l.accounts.containsKey("caio")) || store.read(l -> l.balance("ana", M)) != 501) { throw new AssertionError("Falha no meio nao foi desfeita."); }
        // Disco falhou: tenta de novo no proximo lote.
        store.change(l -> l.credit("ana", M, 1));
        mem.fail = true; store.flush(); mem.fail = false; store.flush();
        if (!"502,0,0".equals(mem.rows.get("goldeconomy-contas/ana"))) { throw new AssertionError("Conta nao regravada depois da falha."); }
        // Reabrir le tudo do disco.
        AccountStore reopened = new AccountStore(mem, msg -> { });
        if (reopened.size() != 12_002 || reopened.read(l -> l.balance("ana", M)) != 502) { throw new AssertionError("Reabrir perdeu contas."); }
    }
}
