package br.goldsuite.api;

import java.util.concurrent.CompletableFuture;

/** Cosmeticos (pingentes, trilhas, efeitos de abate, cor do nome). Outros plugins liberam recompensas por aqui. */
public interface CosmeticService {
    /** Libera o cosmetico para o jogador (online ou nao). Conclui com true se ele ainda nao tinha. */
    CompletableFuture<Boolean> unlock(String player, String id);
    boolean exists(String id);
    /** Abre o menu de cosmeticos. */
    void openMenu(cn.nukkit.Player player);
}
