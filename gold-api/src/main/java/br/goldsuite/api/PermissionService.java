package br.goldsuite.api;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Grupos persistentes (cargos). Expiracao zero significa grupo permanente. */
public interface PermissionService {
    String getGroup(String player);
    /** Instante em que o grupo atual expira; 0 quando permanente ou padrao. */
    long getExpiry(String player);
    Map<String, Boolean> getPermissions(String player);
    CompletableFuture<Void> setGroup(String player, String group, long expiresAt);
    /** Peso do cargo na hierarquia (0 = jogador comum). Quem tem peso maior manda em quem tem peso menor. */
    default int getWeight(String player) { return 0; }
    /** Tag do cargo com cores (§), vazia para quem nao tem. */
    default String getTag(String player) { return ""; }
    /** Cargo da equipe (ajudante, moderador, admin...). */
    default boolean isStaff(String player) { return false; }
}
