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
        back.skin = "../../etc";
        rejects(() -> NpcState.validate(reopened));
        back.skin = "";
        reopened.npcs.put(99, back);
        rejects(() -> NpcState.validate(reopened));
        System.out.println("GoldNPC: nomes, tipos, destinos, transicoes, comandos e serializacao aprovados.");
    }
    private static void check(boolean condition, String message) { if (!condition) { throw new AssertionError(message); } }
    private static void rejects(Runnable action) { try { action.run(); } catch (IllegalArgumentException expected) { return; } throw new AssertionError("Entrada invalida aceita."); }
}
