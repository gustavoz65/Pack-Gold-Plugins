package br.goldsuite.crates;

import br.goldsuite.api.ItemData;
import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.List;

public final class CratesTest {
    public static void main(String[] args) {
        List<CrateState.Reward> rewards = new ArrayList<>();
        rewards.add(new CrateState.Reward(item(264, 1), 90));
        rewards.add(new CrateState.Reward(item(276, 1), 10));
        check(CrateState.roll(rewards, t -> 0).item.id == 264 && CrateState.roll(rewards, t -> 89).item.id == 264 && CrateState.roll(rewards, t -> 90).item.id == 276 && CrateState.roll(rewards, t -> 99).item.id == 276, "Sorteio por peso errado.");
        check(Math.abs(CrateState.chance(rewards, rewards.get(1)) - 10.0) < 1e-9, "Chance errada.");
        CrateState s = new CrateState();
        CrateState.Crate c = new CrateState.Crate(); c.rewards.addAll(rewards);
        s.crates.put("rara", c);
        s.blocks.put("world:10:64:-3", "rara");
        CrateState.validate(s);
        CrateState back = new Gson().fromJson(new Gson().toJson(s), CrateState.class);
        CrateState.validate(back);
        back.blocks.put("world:1:64:1", "inexistente");
        rejects(() -> CrateState.validate(back));
        rejects(() -> CrateState.roll(new ArrayList<>(), t -> 0));
        System.out.println("GoldCrates: sorteio por peso, chances e validacao aprovados.");
    }
    private static ItemData item(int id, int count) { ItemData d = new ItemData(); d.id = id; d.count = count; return d; }
    private static void check(boolean c, String m) { if (!c) { throw new AssertionError(m); } }
    private static void rejects(Runnable r) { try { r.run(); } catch (IllegalArgumentException e) { return; } throw new AssertionError("Entrada invalida aceita."); }
}
