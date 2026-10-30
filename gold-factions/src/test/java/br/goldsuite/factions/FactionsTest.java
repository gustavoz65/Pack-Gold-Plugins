package br.goldsuite.factions;

import com.google.gson.Gson;

public final class FactionsTest {
    public static void main(String[] args) {
        FactionState s = new FactionState();
        FactionState.Faction f = new FactionState.Faction();
        f.tag = FactionState.tag("gld"); f.roster.put("ana", FactionState.Role.LIDER); f.roster.put("bob", FactionState.Role.MEMBRO);
        s.factions.put("gold", f); s.members.put("ana", "gold"); s.members.put("bob", "gold");
        s.claims.put(FactionState.chunk("world", -1, 40), "gold");
        FactionState.validate(s);
        check(FactionState.chunk("world", -1, 40).equals("world:-1:2") && FactionState.maxClaims(f) == 6 && s.claimsOf("gold") == 1, "Chunks ou limites errados.");
        FactionState back = new Gson().fromJson(new Gson().toJson(s), FactionState.class);
        FactionState.validate(back);
        back.remove("gold");
        check(back.factions.isEmpty() && back.members.isEmpty() && back.claims.isEmpty(), "Desfazer deixou restos.");
        FactionState.validate(back);
        FactionState two = new Gson().fromJson(new Gson().toJson(s), FactionState.class);
        two.factions.get("gold").roster.put("bob", FactionState.Role.LIDER);
        rejects(() -> FactionState.validate(two));
        rejects(() -> FactionState.tag("A")); rejects(() -> FactionState.name("ab"));
        System.out.println("GoldFactions: cargos, territorio, limites, desfazer e validacao aprovados.");
    }
    private static void check(boolean c, String m) { if (!c) { throw new AssertionError(m); } }
    private static void rejects(Runnable r) { try { r.run(); } catch (IllegalArgumentException e) { return; } throw new AssertionError("Entrada invalida aceita."); }
}
