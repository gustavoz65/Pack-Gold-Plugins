package br.goldsuite.factions;

import br.goldsuite.api.Destination;
import java.util.*;

/** Faccoes, membros por cargo e territorio por chunk. */
public final class FactionState {
    public Map<String, Faction> factions = new TreeMap<>();
    /** Jogador -> faccao (indice reverso). */
    public Map<String, String> members = new TreeMap<>();
    /** "mundo:cx:cz" -> faccao. */
    public Map<String, String> claims = new TreeMap<>();

    public enum Role { MEMBRO, OFICIAL, LIDER }

    public static final class Faction {
        public String tag;
        public Map<String, Role> roster = new TreeMap<>();
        public Destination home;
        public int kills;
        public long created;
    }

    public static final int MAX_MEMBERS = 20;
    static int maxClaims(Faction f) { return Math.min(50, 2 + f.roster.size() * 2); }
    int claimsOf(String faction) { int n = 0; for (String f : claims.values()) { if (f.equals(faction)) { n++; } } return n; }

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
        claims.values().removeIf(faction::equals);
    }

    public static void validate(FactionState s) {
        if (s == null || s.factions == null || s.members == null || s.claims == null || s.factions.size() > 500 || s.claims.size() > 10000) { throw new IllegalArgumentException("Faccoes invalidas."); }
        Set<String> tags = new HashSet<>();
        for (Map.Entry<String, Faction> e : s.factions.entrySet()) {
            name(e.getKey());
            Faction f = e.getValue();
            if (f == null || f.roster == null || f.roster.isEmpty() || f.roster.size() > MAX_MEMBERS || !tags.add(tag(f.tag)) || f.kills < 0) { throw new IllegalArgumentException("Faccao invalida: " + e.getKey()); }
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
    }
}
