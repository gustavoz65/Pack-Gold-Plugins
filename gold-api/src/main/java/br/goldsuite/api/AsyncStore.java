package br.goldsuite.api;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Operacoes confirmadas somente depois da escrita; conclusoes na thread principal. */
public interface AsyncStore {
    CompletableFuture<Optional<String>> get(String namespace, String key);
    CompletableFuture<Void> put(String namespace, String key, String value);
    CompletableFuture<Void> delete(String namespace, String key);
    /** Todas as chaves de um espaco (carga inicial de dados por jogador). */
    default CompletableFuture<java.util.Map<String, String>> all(String namespace) { throw new UnsupportedOperationException("Armazenamento sem leitura em lote."); }
    /** Grava varias chaves numa unica transacao: ou todas ou nenhuma. */
    default CompletableFuture<Void> putAll(String namespace, java.util.Map<String, String> values) { throw new UnsupportedOperationException("Armazenamento sem escrita em lote."); }
}
