package br.goldsuite.npc;

import br.goldsuite.api.Destination;
import br.goldsuite.api.TravelService.Transition;
import com.google.gson.Gson;

public final class NpcTest {
    public static void main(String[] args) {
        NpcState state = new NpcState();
        NpcState.Npc npc = new NpcState.Npc();
        npc.world = "lobby"; npc.x = 0.5; npc.y = 65; npc.z = 0.5;
        npc.name = NpcState.name("&6HG|{mundo:hg} jogando");
        npc.destination = Destination.world("hg");
        npc.transition = Transition.parse("espiral").name();
        state.npcs.put(state.nextId++, npc);
        NpcState.validate(state);
        check(npc.name.equals("§6HG\n{mundo:hg} jogando"), "Quebra de linha ou cor perdida.");

        Gson gson = new Gson();
        NpcState reopened = gson.fromJson(gson.toJson(state), NpcState.class);
        NpcState.validate(reopened);
        NpcState.Npc back = reopened.require(1);
        check(back.destination.type == Destination.Type.WORLD && back.destination.target.equals("hg") && back.transition() == Transition.ESPIRAL, "Serializacao perdeu destino ou transicao.");

        rejects(() -> NpcState.type("dragao"));
        rejects(() -> Destination.world(".."));
        rejects(() -> Destination.world("../worlds"));
        rejects(() -> Destination.position("lobby", Double.NaN, 64, 0, 0, 0));
        rejects(() -> Transition.parse("teletransporte"));
        rejects(() -> NpcState.name("a|b|c|d|e|f"));
        rejects(() -> NpcState.command("say oi\nop todos"));
        check(NpcState.command("/warp hg").equals("warp hg"), "Barra inicial nao removida.");
        back.ranking = "ranked:1";
        NpcState.validate(reopened);
        back.ranking = "ranked:11";
        rejects(() -> NpcState.validate(reopened));
        back.ranking = "";
        back.skin = "../../etc";
        rejects(() -> NpcState.validate(reopened));
        back.skin = "";
        reopened.npcs.put(99, back);
        rejects(() -> NpcState.validate(reopened));
        // Posicao pelo console: <mundo> <x> <y> <z> [yaw]
        br.goldsuite.api.Destination d = br.goldsuite.api.Destination.parse(new String[] {"criar", "humano", "LobbyRush", "10.5", "65", "-3", "90", "&aPvP"}, 2);
        check(d != null && d.target.equals("LobbyRush") && d.x == 10.5 && d.y == 65 && d.z == -3 && d.yaw == 90, "posicao com yaw");
        check(br.goldsuite.api.Destination.parsedLength(new String[] {"criar", "humano", "LobbyRush", "10.5", "65", "-3", "90", "&aPvP"}, 2) == 5, "yaw consome 5 argumentos");
        check(br.goldsuite.api.Destination.parsedLength(new String[] {"criar", "humano", "LobbyRush", "1", "2", "3", "&aPvP"}, 2) == 4, "sem yaw consome 4");
        check(br.goldsuite.api.Destination.parse(new String[] {"criar", "humano", "Fulano"}, 2) == null, "sem coordenadas devolve null");
        check(br.goldsuite.api.Destination.parse(new String[] {"m", "1", "x", "3"}, 0) == null, "coordenada nao numerica devolve null");
        check(br.goldsuite.api.Destination.parse(new String[] {"m", "1", "NaN", "3"}, 0) == null, "NaN recusado");
        try { br.goldsuite.api.Destination.parse(new String[] {"m", "1", "999", "3"}, 0); check(false, "y fora do mundo"); } catch (IllegalArgumentException expected) { }
        try { br.goldsuite.api.Destination.parse(new String[] {"../x", "1", "2", "3"}, 0); check(false, "nome de mundo invalido"); } catch (IllegalArgumentException expected) { }
        System.out.println("GoldNPC: nomes, tipos, destinos, transicoes, comandos e serializacao aprovados.");
    }
    private static void check(boolean condition, String message) { if (!condition) { throw new AssertionError(message); } }
    private static void rejects(Runnable action) { try { action.run(); } catch (IllegalArgumentException expected) { return; } throw new AssertionError("Entrada invalida aceita."); }
}
