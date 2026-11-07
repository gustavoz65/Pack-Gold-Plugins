package br.goldsuite.comandos;

import br.goldsuite.api.ItemData;
import java.util.*;

/** Inventarios guardados de um jogador por grupo de zonas (lobby, rankup, factions...) e o grupo do inventario atual. */
public final class InvBags {
    public static final int MAX_GROUPS = 16;
    /** Grupo do inventario que o jogador esta usando agora ("" = ainda nao separado). */
    public String group = "";
    public Map<String, Bag> bags = new TreeMap<>();

    public static final class Bag {
        public Map<Integer, ItemData> items = new TreeMap<>();
        public Map<Integer, ItemData> armor = new TreeMap<>();
    }

    public static String group(String text) {
        String g = text == null ? "" : text.toLowerCase(Locale.ROOT);
        if (!g.matches("[a-z0-9_]{0,16}")) { throw new IllegalArgumentException(M.t("grupo_de_inventario_invalido")); }
        return g;
    }

    public static void validate(InvBags b) {
        if (b == null || b.group == null || b.bags == null || b.bags.size() > MAX_GROUPS) { throw new IllegalArgumentException(M.t("grupo_de_inventario_invalido")); }
        group(b.group);
        for (Map.Entry<String, Bag> e : b.bags.entrySet()) {
            group(e.getKey());
            Bag bag = e.getValue();
            if (bag == null || bag.items == null || bag.armor == null || bag.items.size() > 36 || bag.armor.size() > 4) { throw new IllegalArgumentException(M.t("grupo_de_inventario_invalido")); }
            for (Map.Entry<Integer, ItemData> i : bag.items.entrySet()) { if (i.getKey() < 0 || i.getKey() > 35) { throw new IllegalArgumentException(M.t("grupo_de_inventario_invalido")); } i.getValue().validate(); }
            for (Map.Entry<Integer, ItemData> i : bag.armor.entrySet()) { if (i.getKey() < 0 || i.getKey() > 3) { throw new IllegalArgumentException(M.t("grupo_de_inventario_invalido")); } i.getValue().validate(); }
        }
    }
}
