package br.goldsuite.protect;

import br.goldsuite.api.Destination;
import java.util.*;

/** Areas, protecoes por mundo e anti-void. Flags ausentes = permitido. */
public final class ProtectState {
    public static final int MAX_AREAS = 500;
    public static final List<String> FLAGS = Arrays.asList("construir", "pvp", "interagir", "dano");
    public Map<String, Area> areas = new TreeMap<>();
    /** Mundo -> flags negadas para todos sem goldprotect.admin. */
    public Map<String, Set<String>> worlds = new TreeMap<>();
    public Set<String> antiVoid = new TreeSet<>();

    public static final class Area {
        public String world;
        public int minX, minY, minZ, maxX, maxY, maxZ;
        /** Flags negadas para quem nao e dono nem membro. */
        public Set<String> deny = new TreeSet<>(Arrays.asList("construir", "interagir"));
        public Set<String> members = new TreeSet<>();
        public String owner = "";
        /** Efeito aplicado a quem esta dentro: {id, nivel} ou vazio. */
        public int[] effect = new int[0];
        public String greeting = "";

        boolean contains(String w, int x, int y, int z) {
            return world.equals(w) && x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
        }
        long volume() { return (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1); }
    }

    public static String name(String text) {
        String name = text == null ? "" : text.toLowerCase(Locale.ROOT);
        if (!name.matches("[a-z0-9_-]{1,24}")) { throw new IllegalArgumentException("Nome: ate 24 letras, numeros, _ ou -."); }
        return name;
    }

    public static String flag(String text) {
        String flag = text.toLowerCase(Locale.ROOT);
        if (!FLAGS.contains(flag)) { throw new IllegalArgumentException("Flags: " + FLAGS); }
        return flag;
    }

    public static Area area(String world, int x1, int y1, int z1, int x2, int y2, int z2) {
        Destination.world(world);
        Area a = new Area();
        a.world = world;
        a.minX = Math.min(x1, x2); a.maxX = Math.max(x1, x2);
        a.minY = Math.max(0, Math.min(y1, y2)); a.maxY = Math.min(255, Math.max(y1, y2));
        a.minZ = Math.min(z1, z2); a.maxZ = Math.max(z1, z2);
        if (a.maxX - a.minX > 2048 || a.maxZ - a.minZ > 2048) { throw new IllegalArgumentException("Area maior que 2048 blocos de lado."); }
        return a;
    }

    public static void validate(ProtectState s) {
        if (s == null || s.areas == null || s.worlds == null || s.antiVoid == null || s.areas.size() > MAX_AREAS || s.worlds.size() > 64 || s.antiVoid.size() > 64) { throw new IllegalArgumentException("Protecoes invalidas."); }
        for (Map.Entry<String, Area> e : s.areas.entrySet()) {
            name(e.getKey());
            Area a = e.getValue();
            if (a == null || a.world == null || a.deny == null || a.members == null || a.owner == null || a.effect == null || a.greeting == null || a.members.size() > 64 || a.greeting.length() > 100) { throw new IllegalArgumentException("Area invalida: " + e.getKey()); }
            Destination.world(a.world);
            if (a.minX > a.maxX || a.minY > a.maxY || a.minZ > a.maxZ || a.maxX - a.minX > 2048 || a.maxZ - a.minZ > 2048) { throw new IllegalArgumentException("Limites invalidos: " + e.getKey()); }
            for (String f : a.deny) { flag(f); }
            if (a.effect.length != 0 && (a.effect.length != 2 || a.effect[0] < 1 || a.effect[0] > 23 || a.effect[1] < 0 || a.effect[1] > 4)) { throw new IllegalArgumentException("Efeito invalido."); }
        }
        for (Map.Entry<String, Set<String>> e : s.worlds.entrySet()) { Destination.world(e.getKey()); for (String f : e.getValue()) { flag(f); } }
        for (String w : s.antiVoid) { Destination.world(w); }
    }
}
