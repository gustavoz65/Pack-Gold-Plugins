package br.goldsuite.essentials;

import br.goldsuite.api.Destination;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Warps e spawn (documento unico, pequeno). Homes ficam em chaves por jogador. */
public final class Places {
    public static final int MAX_WARPS = 200;
    public Map<String, Destination> warps = new TreeMap<>();
    public Destination spawn;

    /** Homes de um jogador, salvas em "homes:<nome>". */
    public static final class Homes {
        public Map<String, Destination> homes = new TreeMap<>();
    }

    public static String name(String text) {
        String name = text == null ? "" : text.toLowerCase(Locale.ROOT);
        if (!name.matches("[a-z0-9_-]{1,24}")) { throw new IllegalArgumentException("Nome: ate 24 letras, numeros, _ ou -."); }
        return name;
    }

    public static void validate(Places places) {
        if (places == null || places.warps == null || places.warps.size() > MAX_WARPS) { throw new IllegalArgumentException("Warps invalidas."); }
        for (Map.Entry<String, Destination> e : places.warps.entrySet()) { name(e.getKey()); check(e.getValue()); }
        if (places.spawn != null) { check(places.spawn); }
    }

    public static void validate(Homes homes, int max) {
        if (homes == null || homes.homes == null || homes.homes.size() > max) { throw new IllegalArgumentException("Homes invalidas."); }
        for (Map.Entry<String, Destination> e : homes.homes.entrySet()) { name(e.getKey()); check(e.getValue()); }
    }

    private static void check(Destination d) {
        if (d == null || d.type != Destination.Type.POSITION) { throw new IllegalArgumentException("Local invalido."); }
        d.validate();
    }
}
