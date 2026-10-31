package br.goldsuite.bedwars;

import java.util.*;

/** Arenas do BedWars (times com cama, base e forja; geradores do meio) e o top 100 de vitorias. */
public final class BwState {
    public Map<String, Arena> arenas = new TreeMap<>();
    public Map<String, Integer> topWins = new HashMap<>();

    public static final class Arena {
        public String world;
        /** 1 solo, 2 dupla, 4 quarteto. */
        public int teamSize = 1;
        public double[] waiting = new double[0];
        public Map<String, Team> teams = new TreeMap<>();
        public List<double[]> diamonds = new ArrayList<>(), emeralds = new ArrayList<>();
    }

    public static final class Team {
        public double[] spawn = new double[0], forge = new double[0];
        public int[] bed = new int[0];
    }

    public static final class Stats {
        public int wins, kills, finals, deaths, beds, games;
    }

    /** Cores dos times: nome -> {meta da la/argila, codigo de cor do chat}. */
    static final Map<String, String[]> COLORS = new LinkedHashMap<>();
    static {
        COLORS.put("vermelho", new String[] {"14", "c"}); COLORS.put("azul", new String[] {"11", "9"}); COLORS.put("verde", new String[] {"5", "a"}); COLORS.put("amarelo", new String[] {"4", "e"});
        COLORS.put("ciano", new String[] {"9", "b"}); COLORS.put("branco", new String[] {"0", "f"}); COLORS.put("rosa", new String[] {"6", "d"}); COLORS.put("cinza", new String[] {"7", "7"});
    }
    static int wool(String team) { return Integer.parseInt(COLORS.get(team)[0]); }
    static String color(String team) { return "§" + COLORS.get(team)[1]; }
    static String label(String team) { return color(team) + Character.toUpperCase(team.charAt(0)) + team.substring(1); }

    static String name(String s) {
        String n = s == null ? "" : s.toLowerCase(Locale.ROOT);
        if (!n.matches("[a-z0-9_]{1,16}")) { throw new IllegalArgumentException("Nome: 1 a 16 letras, numeros ou _."); }
        return n;
    }

    static void offer(Map<String, Integer> board, String player, int value) {
        board.put(player, value);
        if (board.size() > 100) { String low = null; for (Map.Entry<String, Integer> e : board.entrySet()) { if (low == null || e.getValue() < board.get(low)) { low = e.getKey(); } } board.remove(low); }
    }

    public static void validate(BwState s) {
        if (s == null || s.arenas == null || s.topWins == null || s.arenas.size() > 64 || s.topWins.size() > 100) { throw new IllegalArgumentException("Dados do BedWars invalidos."); }
        for (Map.Entry<String, Arena> e : s.arenas.entrySet()) {
            name(e.getKey());
            Arena a = e.getValue();
            if (a == null || a.world == null || !a.world.matches("[A-Za-z0-9_.-]{1,32}") || a.world.contains("..") || a.teams == null || a.teams.size() > 8 || a.diamonds == null || a.emeralds == null
                    || a.diamonds.size() > 16 || a.emeralds.size() > 16 || a.waiting == null || !(a.teamSize == 1 || a.teamSize == 2 || a.teamSize == 4)) { throw new IllegalArgumentException("Arena invalida: " + e.getKey()); }
            for (Map.Entry<String, Team> t : a.teams.entrySet()) { if (!COLORS.containsKey(t.getKey()) || t.getValue() == null || t.getValue().spawn == null || t.getValue().forge == null || t.getValue().bed == null) { throw new IllegalArgumentException("Time invalido: " + t.getKey()); } }
        }
    }
}
