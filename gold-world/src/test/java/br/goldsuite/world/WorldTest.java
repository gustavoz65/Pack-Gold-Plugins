package br.goldsuite.world;

import com.google.gson.Gson;
import java.time.ZoneId;
import java.time.ZonedDateTime;

public final class WorldTest {
    public static void main(String[] args) {
        ZoneId z = ZoneId.of("America/Sao_Paulo");
        check(GoldWorld.ticksFor(ZonedDateTime.of(2026, 1, 1, 6, 0, 0, 0, z)) == 0, "06:00 deveria ser amanhecer.");
        check(GoldWorld.ticksFor(ZonedDateTime.of(2026, 1, 1, 12, 0, 0, 0, z)) == 6000, "12:00 deveria ser meio-dia.");
        check(GoldWorld.ticksFor(ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, z)) == 18000, "00:00 deveria ser meia-noite.");
        GoldWorld.WorldState s = new GoldWorld.WorldState();
        s.realTime.add("world");
        GoldWorld.WorldState back = new Gson().fromJson(new Gson().toJson(s), GoldWorld.WorldState.class);
        GoldWorld.WorldState.validate(back);
        back.autoInv.add("..");
        try { GoldWorld.WorldState.validate(back); throw new AssertionError("Mundo invalido aceito."); } catch (IllegalArgumentException expected) { }
        System.out.println("GoldWorld: hora real e validacao aprovadas.");
    }
    private static void check(boolean c, String m) { if (!c) { throw new AssertionError(m); } }
}
