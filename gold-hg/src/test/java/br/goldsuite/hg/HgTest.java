package br.goldsuite.hg;

import com.google.gson.Gson;
import java.util.HashSet;
import java.util.Set;

public final class HgTest {
    public static void main(String[] args) {
        HgState state = new HgState();
        HgState.Arena arena = new HgState.Arena();
        arena.spawns.add(new double[] {0.5, 70, 0.5, 90});
        arena.spawns.add(new double[] {4.5, 70, 0.5, 270});
        arena.finale = new double[] {0, 70, 0};
        state.arenas.put("hg", arena);
        state.soupOnSelect.add("gustavo");
        HgState.validate(state);

        Gson gson = new Gson();
        HgState back = gson.fromJson(gson.toJson(state), HgState.class);
        HgState.validate(back);
        check(back.require("hg").spawns.size() == 2 && back.rules.soupHeal == 7 && back.soupOnSelect.contains("gustavo"), "Serializacao perdeu dados.");

        Game game = new Game("hg", 10);
        check(game.freeSpawn(2) == 0 && game.waiting() && game.protectedPhase(), "Estado inicial incorreto.");

        // Kits: nomes unicos, descricao, Surpresa nunca sorteia a si mesmo nem Sem kit.
        Set<String> titles = new HashSet<>();
        for (Kit kit : Kit.values()) { check(titles.add(kit.title.toLowerCase()) && !kit.description.isEmpty(), "Kit duplicado: " + kit.title); }
        check(Kit.values().length == 38, "Esperados 36 kits mais Sem kit e Surpresa.");
        for (int i = 0; i < 500; i++) { Kit k = Kit.random(); check(k != Kit.NENHUM && k != Kit.SURPRESA, "Surpresa sorteou kit invalido."); }
        check(Kit.parse("kangaroo") == Kit.CANGURU && Kit.parse("NINJA") == Kit.NINJA, "Busca de kit falhou.");

        back.rules.soupHeal = 0;
        rejects(() -> HgState.validate(back));
        back.rules.soupHeal = 7;
        back.require("hg").spawns.add(new double[] {Double.NaN, 70, 0, 0});
        rejects(() -> HgState.validate(back));
        back.require("hg").spawns.remove(2);
        for (int i = 0; i < 23; i++) { back.require("hg").spawns.add(new double[] {i, 70, 0, 0}); }
        rejects(() -> HgState.validate(back));
        rejects(() -> HgState.arenaName("../world"));
        rejects(() -> Kit.parse("dragao"));
        System.out.println("GoldHG: regras, arenas, serializacao, " + Kit.values().length + " kits e validacoes aprovados.");
    }
    private static void check(boolean condition, String message) { if (!condition) { throw new AssertionError(message); } }
    private static void rejects(Runnable action) { try { action.run(); } catch (IllegalArgumentException expected) { return; } throw new AssertionError("Entrada invalida aceita."); }
}
