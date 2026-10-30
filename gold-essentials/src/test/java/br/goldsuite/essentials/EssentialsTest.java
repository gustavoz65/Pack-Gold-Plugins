package br.goldsuite.essentials;

import br.goldsuite.api.Destination;
import com.google.gson.Gson;

public final class EssentialsTest {
    public static void main(String[] args) {
        Places places = new Places();
        places.warps.put("loja", Destination.position("world", 10, 64, 10, 90, 0));
        places.spawn = Destination.position("world", 0.5, 70, 0.5, 0, 0);
        Places.validate(places);
        Gson gson = new Gson();
        Places back = gson.fromJson(gson.toJson(places), Places.class);
        Places.validate(back);
        check(back.warps.get("loja").x == 10 && back.spawn.y == 70, "Serializacao perdeu dados.");
        back.warps.put("pvp", Destination.world("pvp"));
        rejects(() -> Places.validate(back));
        Places.Homes homes = new Places.Homes();
        homes.homes.put("casa", Destination.position("world", 1, 64, 1, 0, 0));
        homes.homes.put("base", Destination.position("world", 2, 64, 2, 0, 0));
        Places.validate(homes, 2);
        rejects(() -> Places.validate(homes, 1));
        rejects(() -> Places.name("../x"));
        rejects(() -> Places.name(""));
        check(Places.name("Casa_1").equals("casa_1"), "Nome nao normalizado.");
        System.out.println("GoldEssentials: warps, spawn, homes, limites e nomes aprovados.");
    }
    private static void check(boolean c, String m) { if (!c) { throw new AssertionError(m); } }
    private static void rejects(Runnable a) { try { a.run(); } catch (IllegalArgumentException e) { return; } throw new AssertionError("Entrada invalida aceita."); }
}
