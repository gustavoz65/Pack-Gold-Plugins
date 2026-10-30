package br.goldsuite.breaker;

import cn.nukkit.block.Block;
import cn.nukkit.item.Item;

public final class BreakerTest {
    public static void main(String[] args) {
        Block.init();
        Item.init();
        check(GoldBreaker.needed(Block.OBSIDIAN, 60) == 3 && GoldBreaker.needed(Block.BEDROCK, 60) == 10, "Contagem de explosoes errada.");
        check(GoldBreaker.needed(Block.BEDROCK, 4) == 0 && GoldBreaker.needed(Block.BEDROCK, 0) == 0, "Bedrock do fundo do mundo nao pode quebrar.");
        check(GoldBreaker.needed(Block.STONE, 60) == 0, "Pedra nao e tratada aqui.");
        Item sword = Item.get(276, 100, 1);
        check(GoldBreaker.repairCost(sword) == 100 * GoldBreaker.REPAIR_CENTS_PER_POINT && GoldBreaker.repairCost(Item.get(276, 0, 1)) == 0 && GoldBreaker.repairCost(Item.get(1, 0, 1)) == 0, "Custo de reparo errado.");
        System.out.println("GoldBreaker: explosoes, bedrock do fundo e custo de reparo aprovados.");
    }
    private static void check(boolean c, String m) { if (!c) { throw new AssertionError(m); } }
}
