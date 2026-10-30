package br.goldsuite.vault;

import cn.nukkit.item.Item;
import java.util.HashMap;
import java.util.Map;

public final class VaultTest {
    public static void main(String[] args) {
        Map<Integer, Item> items = new HashMap<>();
        items.put(0, Item.get(276, 0, 1));
        items.put(26, Item.get(264, 0, 12));
        items.put(5, Item.get(0, 0, 0));
        Map<Integer, Item> back = GoldVault.decode(GoldVault.encode(items));
        check(back.size() == 2 && back.get(0).getId() == 276 && back.get(26).getCount() == 12, "Conteudo perdido ou espaco vazio salvo.");
        check(GoldVault.decode("{}").isEmpty(), "Bau vazio invalido.");
        rejects(() -> GoldVault.decode("{\"27\":{\"id\":1,\"meta\":0,\"count\":1,\"nbt\":\"\"}}"));
        rejects(() -> GoldVault.decode("{\"1\":{\"id\":1,\"meta\":0,\"count\":99,\"nbt\":\"\"}}"));
        System.out.println("GoldVault: serializacao, espacos vazios e limites aprovados.");
    }
    private static void check(boolean c, String m) { if (!c) { throw new AssertionError(m); } }
    private static void rejects(Runnable r) { try { r.run(); } catch (IllegalArgumentException e) { return; } throw new AssertionError("Entrada invalida aceita."); }
}
