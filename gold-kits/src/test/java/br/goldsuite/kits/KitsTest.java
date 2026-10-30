package br.goldsuite.kits;

import br.goldsuite.api.ItemData;
import com.google.gson.Gson;

public final class KitsTest {
    public static void main(String[] args) {
        KitState s = new KitState();
        KitState.KitDef kit = new KitState.KitDef();
        ItemData sword = new ItemData(); sword.id = 276; sword.count = 1;
        kit.items.add(sword); kit.cooldownMinutes = 60;
        s.kits.put("pvp", kit);
        s.cooldowns.put("ana:pvp", 1000L);
        s.cooldowns.put("bob:pvp", Long.MAX_VALUE);
        KitState.validate(s);
        KitState back = new Gson().fromJson(new Gson().toJson(s), KitState.class);
        KitState.validate(back);
        back.prune(2000L);
        check(!back.cooldowns.containsKey("ana:pvp") && back.cooldowns.containsKey("bob:pvp"), "Recarga vencida nao removida.");
        check(GoldKits.time(90 * 60_000L).equals("1 h 30 min") && GoldKits.time(0).equals("sem recarga"), "Formato de tempo errado.");
        ItemData bad = new ItemData(); bad.id = 1; bad.count = 65;
        back.kits.get("pvp").items.add(bad);
        rejects(() -> KitState.validate(back));
        rejects(() -> KitState.name("a b"));
        System.out.println("GoldKits: kits, recargas, limpeza e validacao aprovados.");
    }
    private static void check(boolean c, String m) { if (!c) { throw new AssertionError(m); } }
    private static void rejects(Runnable r) { try { r.run(); } catch (IllegalArgumentException e) { return; } throw new AssertionError("Entrada invalida aceita."); }
}
