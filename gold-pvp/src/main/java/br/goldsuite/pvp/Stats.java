package br.goldsuite.pvp;

import java.util.*;

/** Abates, mortes e sequencias por jogador. */
public final class Stats {
    public Map<String, int[]> players = new TreeMap<>();
    /** Indices de cada vetor. */
    static final int KILLS = 0, DEATHS = 1, STREAK = 2, BEST = 3;

    int[] of(String name) { return players.computeIfAbsent(name, k -> new int[4]); }

    void kill(String killer, String victim) {
        int[] k = of(killer), v = of(victim);
        k[KILLS]++; k[STREAK]++; k[BEST] = Math.max(k[BEST], k[STREAK]);
        v[DEATHS]++; v[STREAK] = 0;
    }

    static String kdr(int[] s) { return s[DEATHS] == 0 ? String.valueOf(s[KILLS]) : String.format(Locale.ROOT, "%.2f", (double) s[KILLS] / s[DEATHS]); }

    public static void validate(Stats s) {
        if (s == null || s.players == null || s.players.size() > 100000) { throw new IllegalArgumentException("Estatisticas invalidas."); }
        for (Map.Entry<String, int[]> e : s.players.entrySet()) {
            if (!e.getKey().matches("[a-z0-9_]{3,16}") || e.getValue() == null || e.getValue().length != 4) { throw new IllegalArgumentException("Perfil invalido."); }
            for (int n : e.getValue()) { if (n < 0) { throw new IllegalArgumentException("Perfil negativo."); } }
        }
    }
}
