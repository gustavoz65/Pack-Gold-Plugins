package br.goldsuite.api;

import cn.nukkit.Player;
import java.util.concurrent.CompletableFuture;

/** A sessao pertence ao objeto Player, sem confiar no UUID declarado pelo cliente. */
public interface AuthService {
    boolean isAuthenticated(Player player);
    CompletableFuture<Boolean> register(Player player, char[] password);
    CompletableFuture<Boolean> login(Player player, char[] password);
    CompletableFuture<Boolean> changePassword(Player player, char[] current, char[] replacement);
}
