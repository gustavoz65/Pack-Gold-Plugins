package br.goldsuite.api;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Grupos persistentes. Expiracao zero significa grupo permanente. */
public interface PermissionService {
    String getGroup(String player);
    /** Instante em que o grupo atual expira; 0 quando permanente ou padrao. */
    long getExpiry(String player);
    Map<String, Boolean> getPermissions(String player);
    CompletableFuture<Void> setGroup(String player, String group, long expiresAt);
}
