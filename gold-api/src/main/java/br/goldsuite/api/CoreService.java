package br.goldsuite.api;

import java.util.concurrent.CompletableFuture;

/** Consumidores aguardam whenReady antes de acessar os servicos persistentes. */
public interface CoreService {
    CompletableFuture<Void> whenReady();
    AsyncStore getStore();
    ItemSigner getItemSigner();
}
