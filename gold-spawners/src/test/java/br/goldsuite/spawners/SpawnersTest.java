package br.goldsuite.spawners;

/** Regressao sem servidor: tabela dos 10 tipos, posicoes e validacao do estado. */
public final class SpawnersTest {
    public static void main(String[] args) {
        check(Types.ALL.length == 10, "10 tipos");
        for (int i = 0; i < Types.ALL.length; i++) {
            Types.Type t = Types.ALL[i];
            check(t.id == i + 1 && Types.get(t.id) == t && Types.parse(t.key) == t && Types.parse(String.valueOf(t.id)) == t, "tipo " + t.key);
            check(t.drops.length > 0, "drops de " + t.key);
        }
        check(Types.get(0) == null && Types.get(11) == null, "fora da tabela");
        try { Types.parse("dragao"); check(false, "tipo invalido aceito"); } catch (IllegalArgumentException expected) { }
        GoldSpawners.Loc l = GoldSpawners.Loc.parse("plots:-130:65:42");
        check(l != null && l.world.equals("plots") && l.x == -130 && l.y == 65 && l.z == 42, "posicao");
        check(GoldSpawners.Loc.parse("x:1:2") == null && GoldSpawners.Loc.parse("a b:1:2:3") == null && GoldSpawners.Loc.parse("p:a:2:3") == null, "posicao invalida");
        GoldSpawners.SpawnerState s = new GoldSpawners.SpawnerState();
        GoldSpawners.Spawner sp = new GoldSpawners.Spawner(); sp.type = 5; sp.owner = "ana"; sp.mobs = 3;
        s.spawners.put("plots:1:65:1", sp);
        GoldSpawners.SpawnerState.validate(s);
        sp.type = 11;
        try { GoldSpawners.SpawnerState.validate(s); check(false, "tipo 11 aceito"); } catch (IllegalArgumentException expected) { }
        sp.type = 5; sp.owner = "Ana!";
        try { GoldSpawners.SpawnerState.validate(s); check(false, "dono invalido aceito"); } catch (IllegalArgumentException expected) { }
        System.out.println("GoldSpawners: 10 tipos, posicoes e validacao aprovados.");
    }

    private static void check(boolean ok, String msg) { if (!ok) { throw new AssertionError(msg); } }
}
