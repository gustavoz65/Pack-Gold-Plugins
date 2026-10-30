package br.goldsuite.factions;

import com.google.gson.Gson;

public final class FactionsTest {
    public static void main(String[] args) {
        FactionState s = new FactionState();
        add(s, "gold", "GLD", "ana", "bob");
        add(s, "fraca", "FRC", "caio");
        s.claims.put("world:0:0", "gold"); s.claims.put("world:1:0", "gold");
        s.claims.put("world:5:5", "fraca"); s.claims.put("world:6:5", "fraca"); s.claims.put("world:7:5", "fraca");
        s.claims.put("world:6:4", "fraca"); s.claims.put("world:6:6", "fraca");
        s.spawners.put("world:0:0", 2);
        s.factions.get("gold").kills = 4; s.factions.get("gold").deaths = 1;
        FactionState.validate(s);
        // terras 2x10 + membros 2x20 + spawners 2x50 + abates 4x5 - mortes 1x8 = 172
        check(s.power("gold") == 172, "Poder errado: " + s.power("gold"));
        check(s.power("fraca") == 5 * 10 + 20, "Poder da fraca errado.");
        check(!s.border("world", 6, 5, "fraca") && s.border("world", 5, 5, "fraca"), "Borda errada.");
        s.factions.get("fraca").deaths = 100;
        check(s.power("fraca") == 0, "Poder nao pode ser negativo.");
        FactionState back = new Gson().fromJson(new Gson().toJson(s), FactionState.class);
        FactionState.validate(back);
        back.remove("gold");
        check(!back.claims.containsValue("gold") && back.spawners.isEmpty() && !back.members.containsKey("ana"), "Desfazer deixou restos.");
        FactionState.validate(back);
        back.weights.spawners = -1;
        rejects(() -> FactionState.validate(back));
        back.weights.spawners = 50;
        back.spawners.put("world:9:9", 1);
        rejects(() -> FactionState.validate(back));
        check(MiniMap.ascii(s, "world", 0, 0, "gold").size() == 12, "Mapa em texto com tamanho errado.");
        rejects(() -> FactionState.tag("A")); rejects(() -> FactionState.name("ab"));
        System.out.println("GoldFactions: poder com pesos, borda, spawners, mapa em texto e validacao aprovados.");
    }
    private static void add(FactionState s, String name, String tag, String leader, String... members) {
        FactionState.Faction f = new FactionState.Faction(); f.tag = tag; f.roster.put(leader, FactionState.Role.LIDER); s.members.put(leader, name);
        for (String m : members) { f.roster.put(m, FactionState.Role.MEMBRO); s.members.put(m, name); }
        s.factions.put(name, f);
    }
    private static void check(boolean c, String m) { if (!c) { throw new AssertionError(m); } }
    private static void rejects(Runnable r) { try { r.run(); } catch (IllegalArgumentException e) { return; } throw new AssertionError("Entrada invalida aceita."); }
}
