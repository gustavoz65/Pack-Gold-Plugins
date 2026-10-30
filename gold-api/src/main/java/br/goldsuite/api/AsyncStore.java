package br.goldsuite.api;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Operacoes confirmadas somente depois da escrita; conclusoes na thread principal. */
public interface AsyncStore {
    CompletableFuture<Optional<String>> get(String namespace, String key);
    CompletableFuture<Void> put(String namespace, String key, String value);
    CompletableFuture<Void> delete(String namespace, String key);
}
