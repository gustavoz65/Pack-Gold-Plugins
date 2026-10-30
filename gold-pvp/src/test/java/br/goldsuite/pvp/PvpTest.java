package br.goldsuite.pvp;

import com.google.gson.Gson;

public final class PvpTest {
    public static void main(String[] args) {
        Stats s = new Stats();
        s.kill("ana", "bob"); s.kill("ana", "bob"); s.kill("bob", "ana"); s.kill("ana", "bob");
        int[] ana = s.of("ana"), bob = s.of("bob");
        check(ana[Stats.KILLS] == 3 && ana[Stats.DEATHS] == 1 && ana[Stats.STREAK] == 1 && ana[Stats.BEST] == 2, "Contagem da ana errada.");
        check(bob[Stats.KILLS] == 1 && bob[Stats.DEATHS] == 3 && bob[Stats.STREAK] == 0, "Contagem do bob errada.");
        check(Stats.kdr(ana).equals("3.00") && Stats.kdr(new int[] {4, 0, 0, 0}).equals("4"), "KDR errado.");
        Stats back = new Gson().fromJson(new Gson().toJson(s), Stats.class);
        Stats.validate(back);
        back.players.put("../x", new int[4]);
        rejects(() -> Stats.validate(back));
        System.out.println("GoldPvP: abates, mortes, sequencias, KDR e validacao aprovados.");
    }
    private static void check(boolean c, String m) { if (!c) { throw new AssertionError(m); } }
    private static void rejects(Runnable r) { try { r.run(); } catch (IllegalArgumentException e) { return; } throw new AssertionError("Entrada invalida aceita."); }
}
