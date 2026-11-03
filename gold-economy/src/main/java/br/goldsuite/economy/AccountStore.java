package br.goldsuite.economy;

import br.goldsuite.api.AsyncStore;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Contas em memoria (3 numeros por jogador) e uma linha por conta no SQLite: sem limite de contas e cada operacao grava
 * so as contas que mudaram, em lote, uma vez por segundo. Substitui o documento unico de 1 MiB / 10 mil contas.
 * Uma operacao que falha no meio e desfeita por inteiro.
 */
final class AccountStore {
    static final String ACCOUNTS = "goldeconomy-contas", OLD = "goldeconomy", CONFIG = "goldeconomy-config";
    private final AsyncStore store;
    private final Consumer<String> log;
    private final Ledger ledger = new Ledger();
    private final Set<String> dirty = new HashSet<>();
    private final CompletableFuture<Void> ready = new CompletableFuture<>();

    AccountStore(AsyncStore store, Consumer<String> log) {
        this.log = log;
        this.store = store;
        store.all(ACCOUNTS).thenCompose(rows -> {
            int bad = 0;
            for (Map.Entry<String, String> e : rows.entrySet()) {
                try { ledger.accounts.put(Ledger.name(e.getKey()), Ledger.decode(e.getValue())); } catch (RuntimeException ex) { bad++; }
            }
            if (bad > 0) { log.accept(bad + " conta(s) invalida(s) ignorada(s)."); }
            return store.get(CONFIG, "centsPerCash");
        }).thenCompose(cfg -> {
            cfg.ifPresent(v -> { try { ledger.centsPerCash = Math.max(1, Long.parseLong(v)); } catch (NumberFormatException ignored) { } });
            // Primeira vez com este formato: traz as contas do documento antigo.
            return ledger.accounts.isEmpty() ? store.get(OLD, "estado") : CompletableFuture.completedFuture(Optional.<String>empty());
        }).thenCompose(old -> {
            if (!old.isPresent()) { return CompletableFuture.completedFuture((Void) null); }
            Ledger previous = new com.google.gson.Gson().fromJson(old.get(), Ledger.class);
            Map<String, String> rows = new HashMap<>();
            for (Map.Entry<String, Ledger.Account> e : previous.accounts.entrySet()) { ledger.accounts.put(e.getKey(), e.getValue()); rows.put(e.getKey(), Ledger.encode(e.getValue())); }
            ledger.centsPerCash = previous.centsPerCash;
            log.accept("Economia migrada para uma linha por conta: " + rows.size() + " conta(s).");
            return store.putAll(ACCOUNTS, rows).thenCompose(v -> store.put(CONFIG, "centsPerCash", String.valueOf(previous.centsPerCash))).thenCompose(v -> store.delete(OLD, "estado"));
        }).whenComplete((v, e) -> { if (e != null) { ready.completeExceptionally(e); } else { ready.complete(null); } });
    }

    CompletableFuture<Void> ready() { return ready; }
    boolean isLoaded() { return ready.isDone() && !ready.isCompletedExceptionally(); }
    int size() { return ledger.accounts.size(); }

    synchronized <R> R read(Function<Ledger, R> query) { return query.apply(ledger); }

    /** Aplica na hora; a gravacao vai no proximo lote. */
    synchronized CompletableFuture<Void> change(Consumer<Ledger> mutation) {
        if (!isLoaded()) { CompletableFuture<Void> f = new CompletableFuture<>(); f.completeExceptionally(new IllegalStateException(M.t("economia_ainda_carregando"))); return f; }
        try { mutation.accept(ledger); }
        catch (RuntimeException e) { ledger.rollback(); CompletableFuture<Void> f = new CompletableFuture<>(); f.completeExceptionally(e); return f; }
        dirty.addAll(ledger.commit());
        return CompletableFuture.completedFuture(null);
    }

    /** Grava as contas alteradas numa transacao (a fila do GoldCore e serial: o lote mais novo sempre vence). */
    synchronized CompletableFuture<Void> flush() {
        if (dirty.isEmpty()) { return CompletableFuture.completedFuture(null); }
        Map<String, String> rows = new HashMap<>();
        for (String k : dirty) { Ledger.Account a = ledger.accounts.get(k); if (a != null) { rows.put(k, Ledger.encode(a)); } }
        Set<String> sent = new HashSet<>(dirty);
        dirty.clear();
        return store.putAll(ACCOUNTS, rows).whenComplete((v, e) -> {
            if (e != null) { synchronized (this) { dirty.addAll(sent); } log.accept("Falha ao gravar " + sent.size() + " conta(s); nova tentativa em 1 s: " + e.getMessage()); }
        });
    }

    /** No desligamento: manda o ultimo lote; o GoldCore so fecha o banco depois de esvaziar a fila. */
    void close() { flush(); }
}
