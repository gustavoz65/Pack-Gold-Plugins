package br.goldsuite.api;

import cn.nukkit.Player;
import java.util.concurrent.CompletableFuture;

/** A sessao pertence ao objeto Player, sem confiar no UUID declarado pelo cliente. */
public interface AuthService {
    boolean isAuthenticated(Player player);
    CompletableFuture<Boolean> register(Player player, char[] password);
    CompletableFuture<Boolean> login(Player player, char[] password);
    CompletableFuture<Boolean> changePassword(Player player, char[] current, char[] replacement);
    /**
     * Antes do login nenhum menu abre. Isto libera so o menu com este titulo (ex.: boas-vindas) para este jogador;
     * comandos e acoes disparados pelos botoes continuam exigindo login.
     */
    default void allowGuestMenu(Player player, String title) {}
}
