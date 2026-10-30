package br.goldsuite.api;

import com.google.gson.Gson;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** Estado limitado a 1 MiB, com alteracoes serializadas e publicacao apos persistir.
 * A funcao de alteracao so modifica a copia recebida, sem efeitos externos.
 * Todas as chamadas e conclusoes ocorrem na thread de criacao (principal).
 */
public final class PersistentState<T> {
    private final Gson gson = new Gson();
    private final Thread thread = Thread.currentThread();
    private final AsyncStore store;
    private final String namespace;
    private final Class<T> type;
    private final Consumer<T> validator;
    private final CompletableFuture<Void> loaded;
    private CompletableFuture<Void> tail;
    private T state;
    private boolean closed;
    private int pending;

    public PersistentState(AsyncStore store, String namespace, Class<T> type, Supplier<T> defaults, Consumer<T> validator) {
        this.store = store; this.namespace = namespace; this.type = type; this.validator = validator;
        this.loaded = store.get(namespace, "estado").thenAccept(value -> {
            checkThread();
            T initial = value.isPresent() ? gson.fromJson(value.get(), type) : defaults.get();
            validator.accept(initial);
            this.state = initial;
        });
        this.tail = loaded;
    }

    /** Conclui quando a carga inicial termina; nao depende das gravacoes pendentes. */
    public CompletableFuture<Void> ready() { checkThread(); return loaded; }
    public boolean isLoaded() { checkThread(); return !closed && state != null; }

    /** Leitura sem copia do estado confirmado. A funcao nao pode alterar o objeto recebido. */
    public <R> R read(Function<T, R> query) {
        checkThread();
        if (closed || state == null) { throw new IllegalStateException("Dados indisponiveis. Aguarde a inicializacao."); }
        return query.apply(state);
    }

    /** Copia profunda; use apenas quando precisar alterar o resultado. */
    public T snapshot() { return read(current -> gson.fromJson(gson.toJson(current), type)); }

    public CompletableFuture<Void> change(Consumer<T> mutation) {
        checkThread();
        if (closed || pending >= 128) { return failed(new IllegalStateException("Modulo encerrado ou fila de gravacao cheia.")); }
        pending++;
        CompletableFuture<Void> operation = tail.thenCompose(ignored -> {
            checkThread();
            T next = snapshot();
            mutation.accept(next);
            validator.accept(next);
            String encoded = gson.toJson(next);
            if (encoded.length() > 1024 * 1024) { throw new IllegalStateException("Limite de dados do modulo atingido."); }
            return store.put(namespace, "estado", encoded).thenRun(() -> { checkThread(); this.state = next; });
        });
        CompletableFuture<Void> completed = operation.whenComplete((unused, error) -> pending--);
        // Uma recusa de negocio nao bloqueia as proximas operacoes; falha de carga continua fechada.
        tail = completed.handle((unused, error) -> {
            if (state == null) { throw new IllegalStateException("Nao foi possivel carregar os dados.", error); }
            return null;
        });
        return completed;
    }

    public void close() { checkThread(); closed = true; }
    private void checkThread() { if (Thread.currentThread() != thread) { throw new IllegalStateException("Use a API na thread principal."); } }
    public static <V> CompletableFuture<V> failed(Throwable error) {
        CompletableFuture<V> future = new CompletableFuture<>(); future.completeExceptionally(error); return future;
    }
}
