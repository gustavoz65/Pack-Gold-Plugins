package br.goldsuite.skywars;

import java.util.*;

/** Arenas do SkyWars e o top 100 de vitorias. Regras numericas ficam no /painel. */
public final class SwState {
    public Map<String, Arena> arenas = new TreeMap<>();
    public Map<String, Integer> topWins = new HashMap<>();

    public static final class Arena {
        public String world;
        /** 1 = solo, 2 = dupla (cada jaula recebe o time inteiro). */
        public int teamSize = 1;
        public boolean insane;
        /** Jaulas: {x, y, z, yaw}, onde o jogador fica de pe. */
        public List<double[]> spawns = new ArrayList<>();
        /** Centro do mapa {x, y, z}: baus a ate 12 blocos dele sao "do meio". */
        public double[] center = new double[0];
    }

    /** Estatisticas de um jogador (uma chave por jogador). */
    public static final class Stats {
        public int wins, kills, deaths, games;
        public String kit = Kits.DEFAULT;
        public Set<String> kits = new TreeSet<>();
    }

    static String name(String s) {
        String n = s == null ? "" : s.toLowerCase(Locale.ROOT);
        if (!n.matches("[a-z0-9_]{1,16}")) { throw new IllegalArgumentException("Nome: 1 a 16 letras, numeros ou _ (sem barras ou pontos)."); }
        return n;
    }

    static void offer(Map<String, Integer> board, String player, int value) {
        board.put(player, value);
        if (board.size() > 100) { String low = null; for (Map.Entry<String, Integer> e : board.entrySet()) { if (low == null || e.getValue() < board.get(low)) { low = e.getKey(); } } board.remove(low); }
    }

    public static void validate(SwState s) {
        if (s == null || s.arenas == null || s.topWins == null || s.arenas.size() > 64 || s.topWins.size() > 100) { throw new IllegalArgumentException("Dados do SkyWars invalidos."); }
        for (Map.Entry<String, Arena> e : s.arenas.entrySet()) {
            name(e.getKey());
            Arena a = e.getValue();
            if (a == null || a.world == null || !a.world.matches("[A-Za-z0-9_.-]{1,32}") || a.world.contains("..") || a.spawns == null || a.spawns.size() > 24 || a.teamSize < 1 || a.teamSize > 4 || a.center == null) { throw new IllegalArgumentException("Arena invalida: " + e.getKey()); }
            for (double[] p : a.spawns) { if (p == null || p.length != 4) { throw new IllegalArgumentException("Jaula invalida."); } for (double v : p) { if (Double.isNaN(v) || Double.isInfinite(v) || Math.abs(v) > 3e7) { throw new IllegalArgumentException("Jaula invalida."); } } }
        }
    }
}
