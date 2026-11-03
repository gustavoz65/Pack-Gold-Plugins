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
        if (!"MUITO RARO".equals(CrateState.rarity(2.0, 5, 2.5)) || !"RARO".equals(CrateState.rarity(4.9, 5, 2.5)) || CrateState.rarity(10, 5, 2.5) != null) { throw new AssertionError("Raridade errada."); }
        CrateState.Reward cmd = new CrateState.Reward(br.goldsuite.api.ItemData.of(cn.nukkit.item.Item.get(339, 0, 1)), 5);
        cmd.command = CrateState.command("/money adicionar {jogador} 100000"); cmd.commandOnly = true;
        CrateState withCmd = new CrateState(); withCmd.crates.put("basica", new CrateState.Crate()); withCmd.crates.get("basica").rewards.add(cmd); withCmd.crates.get("basica").display = "&6Basica";
        CrateState.validate(new com.google.gson.Gson().fromJson(new com.google.gson.Gson().toJson(withCmd), CrateState.class));
        if (!cmd.command.equals("money adicionar {jogador} 100000")) { throw new AssertionError("Comando do premio nao normalizado."); }
        System.out.println("GoldCrates: sorteio por peso, chances e validacao aprovados.");
    }
    private static ItemData item(int id, int count) { ItemData d = new ItemData(); d.id = id; d.count = count; return d; }
    private static void check(boolean c, String m) { if (!c) { throw new AssertionError(m); } }
    private static void rejects(Runnable r) { try { r.run(); } catch (IllegalArgumentException e) { return; } throw new AssertionError("Entrada invalida aceita."); }
}
