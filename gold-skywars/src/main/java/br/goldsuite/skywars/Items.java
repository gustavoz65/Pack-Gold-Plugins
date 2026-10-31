package br.goldsuite.skywars;

import cn.nukkit.item.Item;
import cn.nukkit.item.enchantment.Enchantment;
import java.util.ArrayList;
import java.util.List;

/** Itens em texto curto: "id:meta:quantidade" + encantamentos "/ench:nivel", ex.: "267:0:1/9:1" = espada de ferro Afiada I. */
final class Items {
    private Items() {}

    static Item parse(String spec) {
        String[] parts = spec.split("/");
        String[] base = parts[0].split(":");
        Item item = Item.get(Integer.parseInt(base[0]), base.length > 1 ? Integer.parseInt(base[1]) : 0, base.length > 2 ? Integer.parseInt(base[2]) : 1);
        for (int i = 1; i < parts.length; i++) {
            String[] e = parts[i].split(":");
            Enchantment ench = Enchantment.getEnchantment(Integer.parseInt(e[0]));
            ench.setLevel(Integer.parseInt(e[1]));
            item.addEnchantment(ench);
        }
        return item;
    }

    static List<Item> parse(String... specs) {
        List<Item> out = new ArrayList<>();
        for (String s : specs) { out.add(parse(s)); }
        return out;
    }

    static boolean armor(int id) { return id >= 298 && id <= 317; }
    /** Encaixe de armadura: 0 capacete, 1 peitoral, 2 calca, 3 bota. */
    static int armorSlot(int id) { return (id - 298) % 4; }

    static Item named(Item item, String name) { item.setCustomName(name); return item; }
}
