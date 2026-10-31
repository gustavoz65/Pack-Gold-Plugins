package br.goldsuite.bedwars;

import cn.nukkit.block.Block;
import cn.nukkit.item.Item;
import cn.nukkit.item.enchantment.Enchantment;
import com.google.gson.Gson;

public final class BedWarsTest {
    public static void main(String[] args) {
        Block.init(); Item.init(); Enchantment.init();
        int offers = 0;
        for (java.util.List<Shop.Offer> list : Shop.CATEGORIES.values()) {
            check(list.size() <= 18, "Categoria nao cabe no menu.");
            for (Shop.Offer o : list) {
                offers++;
                check(o.price > 0 && (o.currency == Shop.IRON || o.currency == Shop.GOLD || o.currency == Shop.DIAMOND || o.currency == Shop.EMERALD), "Preco invalido: " + o.name);
                String[] ic = o.icon.split(":"); check(Item.get(Integer.parseInt(ic[0])).getId() != 0, "Icone invalido: " + o.name);
                if (o.give.matches("\\d+(:\\d+){0,2}(/\\d+:\\d+)*")) { check(Items.parse(o.give).getId() != 0, "Item invalido: " + o.name); }
            }
        }
        check(offers >= 28 && Shop.CATEGORIES.size() == 7, "Loja incompleta.");
        check(Items.parse("261:0:1/19:1/20:1").getEnchantment(20).getLevel() == 1, "Encantamento do arco errado.");
        // Forja fica mais rapida a cada nivel; geradores do meio aceleram por nivel.
        for (int l = 1; l <= 4; l++) { check(Shop.forge(l)[0] <= Shop.forge(l - 1)[0] && Shop.forge(l)[1] <= Shop.forge(l - 1)[1], "Forja nao acelera."); }
        check(Shop.DIAMOND_TIERS[2] < Shop.DIAMOND_TIERS[0] && Shop.EMERALD_TIERS[2] < Shop.EMERALD_TIERS[0], "Geradores nao aceleram.");
        check(Shop.TOOL_COST.length == Shop.PICKAXE.length && Shop.ARMOR.length == 4, "Escadas de equipamento erradas.");
        for (String c : BwState.COLORS.keySet()) { check(BwState.wool(c) >= 0 && BwState.wool(c) <= 15 && BwState.label(c).startsWith("§"), "Cor invalida."); }
        BwState s = new BwState();
        BwState.Arena a = new BwState.Arena(); a.world = "bw1"; a.teamSize = 2;
        BwState.Team t = new BwState.Team(); t.spawn = new double[] {0.5, 70, 0.5, 0}; t.bed = new int[] {3, 70, 0}; t.forge = new double[] {0, 70, 3};
        a.teams.put("vermelho", t); a.diamonds.add(new double[] {20, 70, 20});
        s.arenas.put("castelo", a);
        BwState.validate(new Gson().fromJson(new Gson().toJson(s), BwState.class));
        a.teams.put("roxo-hack", t); rejects(() -> BwState.validate(s)); a.teams.remove("roxo-hack");
        a.teamSize = 3; rejects(() -> BwState.validate(s)); a.teamSize = 2;
        for (String bad : new String[] {"../x", "a/b", ""}) { rejects(() -> BwState.name(bad)); }
        System.out.println("GoldBedWars: loja (" + offers + " itens em 7 categorias), forja, geradores, cores e validacao aprovados.");
    }
    private static void check(boolean c, String m) { if (!c) { throw new AssertionError(m); } }
    private static void rejects(Runnable r) { try { r.run(); } catch (IllegalArgumentException e) { return; } throw new AssertionError("Entrada invalida aceita."); }
}
