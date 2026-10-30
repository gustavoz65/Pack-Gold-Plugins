package br.goldsuite.kits;

import br.goldsuite.api.ItemData;
import java.util.*;

/** Kits do servidor e recargas ("jogador:kit" -> instante liberado). */
public final class KitState {
    public Map<String, KitDef> kits = new TreeMap<>();
    public Map<String, Long> cooldowns = new TreeMap<>();

    public static final class KitDef {
        public List<ItemData> items = new ArrayList<>();
        public int cooldownMinutes;
        /** Vazio = todos podem; senao exige goldkits.kit.<nome>. */
        public boolean restricted;
    }

    public static String name(String text) {
        String n = text == null ? "" : text.toLowerCase(Locale.ROOT);
        if (!n.matches("[a-z0-9_-]{1,20}")) { throw new IllegalArgumentException("Nome: ate 20 letras, numeros, _ ou -."); }
        return n;
    }

    /** Remove recargas vencidas antes de gravar, para o documento nao crescer sem limite. */
    void prune(long now) { cooldowns.values().removeIf(until -> until <= now); }

    public static void validate(KitState s) {
        if (s == null || s.kits == null || s.cooldowns == null || s.kits.size() > 54 || s.cooldowns.size() > 50000) { throw new IllegalArgumentException("Kits invalidos."); }
        for (Map.Entry<String, KitDef> e : s.kits.entrySet()) {
            name(e.getKey());
            KitDef k = e.getValue();
            if (k == null || k.items == null || k.items.isEmpty() || k.items.size() > 36 || k.cooldownMinutes < 0 || k.cooldownMinutes > 525600) { throw new IllegalArgumentException("Kit invalido: " + e.getKey()); }
            for (ItemData item : k.items) { if (item == null) { throw new IllegalArgumentException("Item nulo."); } item.validate(); }
        }
    }
}
