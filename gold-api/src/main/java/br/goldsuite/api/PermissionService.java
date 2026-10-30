package br.goldsuite.api;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Grupos persistentes. Expiracao zero significa grupo permanente. */
public interface PermissionService {
    String getGroup(String player);
    Map<String, Boolean> getPermissions(String player);
    CompletableFuture<Void> setGroup(String player, String group, long expiresAt);
}
