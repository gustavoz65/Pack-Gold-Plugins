package br.goldsuite.hud;

import cn.nukkit.item.Item;

public final class HudTest {
    public static void main(String[] args) {
        check(GoldHud.direction(0).equals("S") && GoldHud.direction(90).equals("O") && GoldHud.direction(180).equals("N") && GoldHud.direction(270).equals("L") && GoldHud.direction(-90).equals("L"), "Direcao errada.");
        check(GoldHud.armorPercent(new Item[] {Item.get(0), Item.get(0), Item.get(0), Item.get(0)}) == -1, "Sem armadura deveria ser -1.");
        check(GoldHud.armorPercent(new Item[] {Item.get(310, 0, 1), Item.get(311, 529, 1), Item.get(0), Item.get(0)}) == 50, "Media da armadura errada.");
        System.out.println("GoldHud: direcao e armadura aprovadas.");
    }
    private static void check(boolean c, String m) { if (!c) { throw new AssertionError(m); } }
}
