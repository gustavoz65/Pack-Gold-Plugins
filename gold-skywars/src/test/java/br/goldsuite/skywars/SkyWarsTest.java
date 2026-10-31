package br.goldsuite.skywars;

import cn.nukkit.block.Block;
import cn.nukkit.item.Item;
import cn.nukkit.item.enchantment.Enchantment;
import com.google.gson.Gson;
import java.util.*;

public final class SkyWarsTest {
    public static void main(String[] args) {
        Block.init(); Item.init(); Enchantment.init();
        check(Kits.ALL.size() >= 18 && Kits.ALL.get(Kits.DEFAULT).price == 0, "Kits faltando.");
        for (Kits.Kit k : Kits.ALL.values()) { List<Item> items = Kits.items(k); check(!items.isEmpty(), "Kit vazio: " + k.id); for (Item i : items) { check(i.getId() != 0, "Item invalido no kit " + k.id); } }
        check(Items.parse("276:0:1/9:2").getEnchantment(9).getLevel() == 2 && Items.armorSlot(311) == 1 && Items.armorSlot(301) == 3, "Itens/encantamentos errados.");
        Random r = new Random(7);
        for (int i = 0; i < 500; i++) {
            List<Item> island = Loot.roll(r, false, false);
            boolean weapon = false, blocks = false;
            for (Item it : island) { int id = it.getId(); if (id == 272 || id == 267 || Items.armor(id)) { weapon = true; } if (id == 4 || id == 5 || id == 1) { blocks = true; } }
            check(weapon && blocks && island.size() >= 4 && island.size() <= 6, "Bau da ilha sem garantia de arma e blocos.");
            check(Loot.roll(r, true, false).size() >= 5 && Loot.roll(r, false, true).size() >= 5, "Bau do meio/insano pequeno.");
        }
        SwState s = new SwState();
        SwState.Arena a = new SwState.Arena(); a.world = "sw1"; a.spawns.add(new double[] {0.5, 80, 0.5, 0});
        s.arenas.put("ilhas", a);
        SwState.validate(new Gson().fromJson(new Gson().toJson(s), SwState.class));
        for (String bad : new String[] {"../../config", "a/b", "..", "", "nome com espaco"}) { rejects(() -> SwState.name(bad)); }
        a.world = "../plugins"; rejects(() -> SwState.validate(s));
        System.out.println("GoldSkyWars: " + Kits.ALL.size() + " kits, baus com garantia, encantamentos e bloqueio de caminho de arquivo aprovados.");
    }
    private static void check(boolean c, String m) { if (!c) { throw new AssertionError(m); } }
    private static void rejects(Runnable r) { try { r.run(); } catch (IllegalArgumentException e) { return; } throw new AssertionError("Entrada invalida aceita."); }
}
