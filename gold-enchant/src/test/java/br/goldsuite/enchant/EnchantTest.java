package br.goldsuite.enchant;

import cn.nukkit.item.Item;
import cn.nukkit.item.enchantment.Enchantment;

public final class EnchantTest {
    public static void main(String[] args) {
        Enchantment.init();
        GoldEnchant.Prices p = new GoldEnchant.Prices();
        GoldEnchant.Prices.validate(p);
        check(GoldEnchant.cost(50000, 3) == 150000 && GoldEnchant.money(150000).equals("1500.00"), "Custo errado.");
        Item sword = Item.get(276, 0, 1);
        check(GoldEnchant.level(sword, Enchantment.ID_DAMAGE_ALL) == 0, "Espada nova deveria ter nivel 0.");
        Enchantment sharp = Enchantment.getEnchantment(Enchantment.ID_DAMAGE_ALL);
        sharp.setLevel(2, false);
        sword.addEnchantment(sharp);
        check(GoldEnchant.level(sword, Enchantment.ID_DAMAGE_ALL) == 2, "Nivel nao lido.");
        p.perLevel.put(999, 1L);
        try { GoldEnchant.Prices.validate(p); throw new AssertionError("Encantamento inexistente aceito."); } catch (IllegalArgumentException expected) { }
        System.out.println("GoldEnchant: precos, niveis e validacao aprovados.");
    }
    private static void check(boolean c, String m) { if (!c) { throw new AssertionError(m); } }
}
