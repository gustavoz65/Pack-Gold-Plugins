package br.goldsuite.skywars;

import cn.nukkit.item.Item;
import java.util.*;

/**
 * Baus no estilo do Hypixel: os da ilha sempre trazem algo para defender e construir (uma arma ou armadura e blocos);
 * os do meio sao mais fortes. O modo Insano usa tabelas melhores.
 */
final class Loot {
    /** {peso, item} */
    static final Object[][] ISLAND = {
            {10, "4:0:32"}, {8, "5:0:24"}, {6, "1:0:16"}, {7, "272"}, {4, "267"}, {6, "306"}, {6, "307"}, {6, "308"}, {6, "309"},
            {5, "302"}, {5, "303"}, {5, "304"}, {5, "305"}, {4, "261"}, {6, "262:0:8"}, {6, "332:0:8"}, {6, "344:0:8"},
            {8, "364:0:4"}, {6, "297:0:6"}, {3, "322"}, {4, "325:8:1"}, {3, "325:10:1"}, {4, "274"}, {3, "257"}, {3, "30:0:4"}, {2, "46:0:2"}};
    static final Object[][] MIDDLE = {
            {6, "276"}, {7, "267:0:1/9:1"}, {5, "310"}, {5, "311"}, {5, "312"}, {5, "313"}, {6, "307:0:1/0:2"}, {5, "261:0:1/19:1"},
            {6, "262:0:16"}, {6, "322:0:2"}, {4, "373:21:1"}, {4, "373:16:1"}, {4, "438:23:1"}, {5, "332:0:16"}, {4, "46:0:4"},
            {3, "259"}, {4, "325:10:1"}, {5, "278"}, {4, "pearl"}, {6, "4:0:32"}};
    static final Object[][] INSANE = {
            {7, "276:0:1/9:2"}, {6, "310:0:1/0:2"}, {6, "311:0:1/0:2"}, {6, "312:0:1/0:2"}, {6, "313:0:1/0:2"}, {5, "261:0:1/19:2"},
            {6, "262:0:24"}, {6, "322:0:3"}, {4, "466"}, {5, "373:22:1"}, {5, "438:24:1"}, {4, "pearl"}, {5, "46:0:8"}, {6, "4:0:64"}, {4, "278:0:1/15:3"}};
    /** Garantias da ilha: algo para lutar e algo para construir. */
    static final String[] WEAPONS = {"272", "267", "306", "307", "308", "302", "303"};
    static final String[] BLOCKS = {"4:0:32", "5:0:24", "1:0:16"};

    private Loot() {}

    static Item item(String spec) { return spec.equals("pearl") ? Items.named(Item.get(332, 0, 1), Kits.PEARL) : Items.parse(spec); }

    static List<Item> roll(Random r, boolean middle, boolean insane) {
        Object[][] table = insane ? INSANE : middle ? MIDDLE : ISLAND;
        int n = middle || insane ? 5 + r.nextInt(4) : 4 + r.nextInt(3);
        List<Item> out = new ArrayList<>();
        if (!middle && !insane) { out.add(item(WEAPONS[r.nextInt(WEAPONS.length)])); out.add(item(BLOCKS[r.nextInt(BLOCKS.length)])); n -= 2; }
        int total = 0; for (Object[] row : table) { total += (Integer) row[0]; }
        for (int i = 0; i < n; i++) {
            int pick = r.nextInt(total);
            for (Object[] row : table) { pick -= (Integer) row[0]; if (pick < 0) { out.add(item((String) row[1])); break; } }
        }
        return out;
    }
}
