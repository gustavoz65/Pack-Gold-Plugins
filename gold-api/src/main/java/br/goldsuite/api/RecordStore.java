package br.goldsuite.api;

import com.google.gson.Gson;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Registros independentes, publicados somente depois do commit. API exclusiva da thread principal. */
public final class RecordStore<T> {
    private final Gson gson = new Gson();
    private final Thread thread = Thread.currentThread();
    private final AsyncStore store;
    private final String namespace;
    private final Class<T> type;
    private final Supplier<T> defaults;
    private final Consumer<T> validator;
    private final Map<String, T> records = new HashMap<>();
    private final Map<String, CompletableFuture<Void>> tails = new HashMap<>();
    private final CompletableFuture<Void> ready;
    private int pending;
    private boolean closed;
    private final boolean eager;
    private final Map<String, Integer> references = new HashMap<>();
    private final Map<String, CompletableFuture<Void>> loading = new HashMap<>();

    public RecordStore(AsyncStore store, String namespace, Class<T> type, Supplier<T> defaults, Consumer<T> validator) {
        this(store, namespace, type, defaults, validator, true);
    }
    public RecordStore(AsyncStore store, String namespace, Class<T> type, Supplier<T> defaults, Consumer<T> validator, boolean eager) {
        this.eager = eager;
        this.store = store; this.namespace = namespace; this.type = type; this.defaults = defaults; this.validator = validator;
        ready = eager ? store.all(namespace).thenAccept(rows -> {
            checkThread();
            for (Map.Entry<String, String> row : rows.entrySet()) {
                T value = gson.fromJson(row.getValue(), type);
                validator.accept(value); records.put(row.getKey(), value);
            }
        }) : CompletableFuture.completedFuture(null);
    }
    public CompletableFuture<Void> retain(String key) {
        check();
        references.merge(key, 1, Integer::sum);
        if (records.containsKey(key)) { return CompletableFuture.completedFuture(null); }
        if (loading.containsKey(key)) { return loading.get(key); }
        CompletableFuture<Void> result = new CompletableFuture<>();
        loading.put(key, result);
        store.get(namespace, key).whenComplete((json, error) -> {
            try {
                if (error != null) { throw new java.util.concurrent.CompletionException(error); }
                T value = json.isPresent() ? gson.fromJson(json.get(), type) : defaults.get();
                validator.accept(value); records.put(key, value); result.complete(null);
            } catch (RuntimeException e) { result.completeExceptionally(e); }
            finally { loading.remove(key); evict(key); }
        });
        return result;
    }
    public void release(String key) {
        checkThread();
        references.computeIfPresent(key, (k, n) -> n <= 1 ? null : n - 1); evict(key);
    }
    private void evict(String key) { if (!eager && !references.containsKey(key) && !tails.containsKey(key) && !loading.containsKey(key)) { records.remove(key); } }
    public int cachedRecords() { return records.size(); }
    public CompletableFuture<Void> ready() { return ready; }
    public boolean isLoaded() { return !closed && ready.isDone() && !ready.isCompletedExceptionally(); }
    public Set<String> keys() { check(); return new HashSet<>(records.keySet()); }
    public T snapshot(String key) { check(); if (!eager && !records.containsKey(key)) { throw new IllegalStateException("Perfil carregando."); } return copy(records.getOrDefault(key, defaults.get())); }
    private T copy(T value) { return gson.fromJson(gson.toJson(value), type); }
    public CompletableFuture<T> change(String key, Consumer<T> mutation) {
        checkThread();
        if ((!eager && !records.containsKey(key)) || !isLoaded() || pending >= 1024 || key == null || key.length() > 128) {
            return PersistentState.failed(new IllegalStateException("Dados indisponiveis ou fila cheia."));
        }
        pending++;
        CompletableFuture<T> result = new CompletableFuture<>();
        CompletableFuture<Void> previous = tails.getOrDefault(key, CompletableFuture.completedFuture(null));
        // Reserva antes de executar: callbacks sincronos/reentrantes tambem entram depois desta operacao.
        CompletableFuture<Void> gate = new CompletableFuture<>();
        tails.put(key, gate);
        previous.handle((v, e) -> null).thenCompose(v -> {
            checkThread();
            T next = copy(records.getOrDefault(key, defaults.get()));
            mutation.accept(next); validator.accept(next);
            String json = gson.toJson(next);
            if (json.length() > 1024 * 1024) { throw new IllegalArgumentException("Registro excessivo."); }
            return store.put(namespace, key, json).thenApply(done -> {
                checkThread(); records.put(key, next); return copy(next);
            });
        }).whenComplete((value, error) -> {
            pending--;
            if (error == null) { result.complete(value); } else { result.completeExceptionally(error); }
            tails.remove(key, gate);
            gate.complete(null);
            evict(key);
        });
        return result;
    }
    public void close() { checkThread(); closed = true; }
    private void check() { checkThread(); if (!isLoaded()) { throw new IllegalStateException("Dados indisponiveis."); } }
    private void checkThread() { if (Thread.currentThread() != thread) { throw new IllegalStateException("Use a thread principal."); } }
}

