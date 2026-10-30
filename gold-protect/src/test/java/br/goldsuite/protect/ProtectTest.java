package br.goldsuite.protect;

import com.google.gson.Gson;

public final class ProtectTest {
    public static void main(String[] args) {
        ProtectState s = new ProtectState();
        ProtectState.Area a = ProtectState.area("world", 10, 70, 10, -5, 60, -5);
        check(a.minX == -5 && a.maxX == 10 && a.minY == 60 && a.contains("world", 0, 65, 0) && !a.contains("world", 11, 65, 0) && !a.contains("pvp", 0, 65, 0), "Limites incorretos.");
        a.owner = "gustavo"; a.effect = new int[] {1, 1};
        s.areas.put("lobby", a);
        s.worlds.put("world", new java.util.TreeSet<>(java.util.Collections.singleton("pvp")));
        s.antiVoid.add("world");
        ProtectState.validate(s);
        ProtectState back = new Gson().fromJson(new Gson().toJson(s), ProtectState.class);
        ProtectState.validate(back);
        check(back.areas.get("lobby").deny.contains("construir") && back.worlds.get("world").contains("pvp"), "Serializacao perdeu flags.");
        rejects(() -> ProtectState.flag("voar"));
        rejects(() -> ProtectState.area("world", 0, 0, 0, 5000, 10, 10));
        rejects(() -> ProtectState.area("..", 0, 0, 0, 1, 1, 1));
        back.areas.get("lobby").effect = new int[] {99, 0};
        rejects(() -> ProtectState.validate(back));
        System.out.println("GoldProtect: limites, flags, mundos, anti-void e validacao aprovados.");
    }
    private static void check(boolean c, String m) { if (!c) { throw new AssertionError(m); } }
    private static void rejects(Runnable r) { try { r.run(); } catch (IllegalArgumentException e) { return; } throw new AssertionError("Entrada invalida aceita."); }
}
