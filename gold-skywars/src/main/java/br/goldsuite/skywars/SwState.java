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
        public boolean enabled = true;
        public int minimum = 2;
        public String display = "", author = "";
        public double[] spectator = new double[0];
        public Map<String, Boolean> chests = new HashMap<>();
        /** Jaulas: {x, y, z, yaw}, onde o jogador fica de pe. */
        public List<double[]> spawns = new ArrayList<>();
        /** Centro do mapa {x, y, z}: baus a ate 12 blocos dele sao "do meio". */
        public double[] center = new double[0];
    }

    /** Estatisticas de um jogador (uma chave por jogador). */
    public static final class Stats {
        public int wins, kills, deaths, games;
        public long xp, coins;
        public int souls, spins, prestige;
        public String cage = "glass";
        public Set<String> cages = new TreeSet<>();
        public Map<String, Integer> perks = new HashMap<>();
        public Set<String> disabledPerks = new TreeSet<>();
        public String kit = Kits.DEFAULT;
        public Set<String> kits = new TreeSet<>();
    }

    static void validateStats(Stats s) {
        if (s == null || s.kits == null || s.wins < 0 || s.kills < 0 || s.deaths < 0 || s.games < 0 || s.souls < 0 || s.souls > 1000000 || s.xp < 0 || s.coins < 0 || s.coins > 1000000000000L) { throw new IllegalArgumentException(M.t("perfil_invalido")); }
        if (!Kits.ALL.containsKey(s.kit)) { s.kit = Kits.DEFAULT; }
        if (s.cages == null) { s.cages = new TreeSet<>(); }
        if (s.cage == null) { s.cage = "glass"; }
        if (s.prestige < 0 || s.prestige >= Progression.PRESTIGE_LEVELS.length) { s.prestige = 0; }
        if (s.perks == null) { s.perks = new HashMap<>(); }
        if (s.disabledPerks == null) { s.disabledPerks = new TreeSet<>(); }
        if (s.kits.size() > 128 || s.cages.size() > 32 || s.perks.size() > 32) { throw new IllegalArgumentException(M.t("perfil_invalido")); }
        for (Integer level : s.perks.values()) { if (level == null || level < 1 || level > 4) { throw new IllegalArgumentException(M.t("perfil_invalido")); } }
    }

    static String name(String s) {
        String n = s == null ? "" : s.toLowerCase(Locale.ROOT);
        if (!n.matches("[a-z0-9_]{1,16}")) { throw new IllegalArgumentException(M.t("nome_1_16_letras_numeros_ou")); }
        return n;
    }

    static void offer(Map<String, Integer> board, String player, int value) {
        board.put(player, value);
        if (board.size() > 100) { String low = null; for (Map.Entry<String, Integer> e : board.entrySet()) { if (low == null || e.getValue() < board.get(low)) { low = e.getKey(); } } board.remove(low); }
    }

    public static void validate(SwState s) {
        if (s == null || s.arenas == null || s.topWins == null || s.arenas.size() > 64 || s.topWins.size() > 100) { throw new IllegalArgumentException(M.t("dados_do_skywars_invalidos")); }
        Set<String> worlds = new HashSet<>();
        for (Map.Entry<String, Arena> e : s.arenas.entrySet()) {
            name(e.getKey());
            Arena a = e.getValue();
            if (a == null || a.world == null || !a.world.matches("[A-Za-z0-9_.-]{1,32}") || a.world.contains("..") || a.spawns == null || a.spawns.size() > 24 || a.teamSize < 1 || a.teamSize > 4 || a.center == null) { throw new IllegalArgumentException(M.t("arena_invalida", e.getKey())); }
            if (!worlds.add(a.world.toLowerCase(Locale.ROOT))) { throw new IllegalArgumentException(M.t("mundo_duplicado")); }
            if (a.chests == null) { a.chests = new HashMap<>(); }
            if (a.spectator == null) { a.spectator = new double[0]; }
            if (a.display == null) { a.display = ""; }
            if (a.author == null) { a.author = ""; }
            if (a.display.length() > 32 || a.author.length() > 32) { throw new IllegalArgumentException(M.t("arena_invalida", e.getKey())); }
            if (a.chests.size() > 256 || a.minimum < 2 || a.minimum > 96) { throw new IllegalArgumentException(M.t("arena_invalida", e.getKey())); }
            for (double[] point : Arrays.asList(a.center, a.spectator)) { if (point.length != 0 && point.length != 3) { throw new IllegalArgumentException(M.t("jaula_invalida")); } for (double v : point) { if (!Double.isFinite(v) || Math.abs(v) > 3e7) { throw new IllegalArgumentException(M.t("jaula_invalida")); } } }
            for (double[] p : a.spawns) { if (p == null || p.length != 4) { throw new IllegalArgumentException(M.t("jaula_invalida")); } for (double v : p) { if (Double.isNaN(v) || Double.isInfinite(v) || Math.abs(v) > 3e7) { throw new IllegalArgumentException(M.t("jaula_invalida")); } } }
        }
    }
}
