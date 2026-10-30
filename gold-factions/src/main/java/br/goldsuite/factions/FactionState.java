package br.goldsuite.factions;

import br.goldsuite.api.Destination;
import java.util.*;

/** Faccoes, membros por cargo, territorio por chunk, spawners nos claims, pesos do poder e mini mapa. */
public final class FactionState {
    public Map<String, Faction> factions = new TreeMap<>();
    /** Jogador -> faccao (indice reverso). */
    public Map<String, String> members = new TreeMap<>();
    /** "mundo:cx:cz" -> faccao. */
    public Map<String, String> claims = new TreeMap<>();
    /** "mundo:cx:cz" -> spawners dentro do chunk (so chunks com claim). */
    public Map<String, Integer> spawners = new TreeMap<>();
    /** Mundos onde se pode dominar terra; vazio = qualquer mundo. */
    public Set<String> worlds = new TreeSet<>();
    public Weights weights = new Weights();
    public MiniMap map;

    public enum Role { MEMBRO, OFICIAL, LIDER }

    public static final class Faction {
        public String tag;
        public Map<String, Role> roster = new TreeMap<>();
        public Destination home;
        public int kills, deaths;
        public long created;
    }

    /** Peso de cada fator no poder. Mortes descontam. */
    public static final class Weights {
        public int terras = 10, membros = 20, spawners = 50, abates = 5, mortes = 8;
    }

    /** Mini mapa fisico: cada bloco e um chunk do mundo das faccoes, centrado em (sourceCx, sourceCz). */
    public static final class MiniMap {
        public String world, source;
        public int x, y, z, radius, sourceCx, sourceCz;
    }

    public static final int MAX_MEMBERS = 20;
    static int maxClaims(Faction f) { return Math.min(60, 2 + f.roster.size() * 3); }

    int claimsOf(String faction) { int n = 0; for (String f : claims.values()) { if (f.equals(faction)) { n++; } } return n; }

    int spawnersOf(String faction) {
        int n = 0;
        for (Map.Entry<String, Integer> e : spawners.entrySet()) { if (faction.equals(claims.get(e.getKey()))) { n += e.getValue(); } }
        return n;
    }

    /** Poder = terras x peso + membros x peso + spawners x peso + abates x peso - mortes x peso (minimo 0). */
    long power(String faction) {
        Faction f = factions.get(faction);
        if (f == null) { return 0; }
        Weights w = weights;
        long p = (long) claimsOf(faction) * w.terras + (long) f.roster.size() * w.membros + (long) spawnersOf(faction) * w.spawners + (long) f.kills * w.abates - (long) f.deaths * w.mortes;
        return Math.max(0, p);
    }

    /** Chunk na borda do territorio do dono: algum vizinho nao pertence a ele. So a borda pode ser invadida. */
    boolean border(String world, int cx, int cz, String owner) {
        int[][] around = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : around) { if (!owner.equals(claims.get(world + ":" + (cx + d[0]) + ":" + (cz + d[1])))) { return true; } }
        return false;
    }

    public static String name(String text) {
        String n = text == null ? "" : text.toLowerCase(Locale.ROOT);
        if (!n.matches("[a-z0-9_]{3,16}")) { throw new IllegalArgumentException("Nome: 3 a 16 letras, numeros ou _."); }
        return n;
    }

    public static String tag(String text) {
        String t = text == null ? "" : text.toUpperCase(Locale.ROOT);
        if (!t.matches("[A-Z0-9]{2,4}")) { throw new IllegalArgumentException("Tag: 2 a 4 letras ou numeros."); }
        return t;
    }

    static String chunk(String world, int x, int z) { return world + ":" + (x >> 4) + ":" + (z >> 4); }

    void remove(String faction) {
        Faction f = factions.remove(faction);
        if (f != null) { for (String m : f.roster.keySet()) { members.remove(m); } }
        Set<String> lost = new HashSet<>();
        for (Map.Entry<String, String> e : claims.entrySet()) { if (e.getValue().equals(faction)) { lost.add(e.getKey()); } }
        claims.keySet().removeAll(lost);
        spawners.keySet().removeAll(lost);
    }

    public static void validate(FactionState s) {
        if (s == null || s.factions == null || s.members == null || s.claims == null || s.spawners == null || s.worlds == null || s.weights == null
                || s.factions.size() > 500 || s.claims.size() > 20000 || s.worlds.size() > 16) { throw new IllegalArgumentException("Faccoes invalidas."); }
        Weights w = s.weights;
        for (int v : new int[] {w.terras, w.membros, w.spawners, w.abates, w.mortes}) { if (v < 0 || v > 1000) { throw new IllegalArgumentException("Peso entre 0 e 1000."); } }
        Set<String> tags = new HashSet<>();
        for (Map.Entry<String, Faction> e : s.factions.entrySet()) {
            name(e.getKey());
            Faction f = e.getValue();
            if (f == null || f.roster == null || f.roster.isEmpty() || f.roster.size() > MAX_MEMBERS || !tags.add(tag(f.tag)) || f.kills < 0 || f.deaths < 0) { throw new IllegalArgumentException("Faccao invalida: " + e.getKey()); }
            int leaders = 0;
            for (Map.Entry<String, Role> m : f.roster.entrySet()) {
                if (!m.getKey().matches("[a-z0-9_]{3,16}") || m.getValue() == null || !e.getKey().equals(s.members.get(m.getKey()))) { throw new IllegalArgumentException("Membro invalido em " + e.getKey()); }
                if (m.getValue() == Role.LIDER) { leaders++; }
            }
            if (leaders != 1) { throw new IllegalArgumentException("Cada faccao precisa de exatamente um lider."); }
            if (f.home != null) { f.home.validate(); }
        }
        for (Map.Entry<String, String> m : s.members.entrySet()) { Faction f = s.factions.get(m.getValue()); if (f == null || !f.roster.containsKey(m.getKey())) { throw new IllegalArgumentException("Indice de membros inconsistente."); } }
        for (String f : s.claims.values()) { if (!s.factions.containsKey(f)) { throw new IllegalArgumentException("Territorio sem faccao."); } }
        for (Map.Entry<String, Integer> e : s.spawners.entrySet()) { if (!s.claims.containsKey(e.getKey()) || e.getValue() == null || e.getValue() < 0 || e.getValue() > 4096) { throw new IllegalArgumentException("Contagem de spawners invalida."); } }
        for (String world : s.worlds) { Destination.world(world); }
        if (s.map != null) {
            Destination.world(s.map.world); Destination.world(s.map.source);
            if (s.map.radius < 4 || s.map.radius > 24 || s.map.y < 1 || s.map.y > 126) { throw new IllegalArgumentException("Mini mapa invalido."); }
        }
    }
}
