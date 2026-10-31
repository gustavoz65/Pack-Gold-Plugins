package br.goldsuite.comandos;

import com.google.gson.Gson;
import java.util.*;

public final class ComandosTest {
    public static void main(String[] args) {
        CmdState s = new CmdState();
        CmdState.Zone pvp = new CmdState.Zone();
        pvp.worlds.add("PvP"); pvp.whitelist = true; pvp.commands.addAll(Arrays.asList("kit", "perfil"));
        CmdState.Zone jogos = new CmdState.Zone();
        jogos.worlds.add("jogos"); jogos.commands.add("fly");
        jogos.items.put(0, new CmdState.HubItem(345, 0, "&eJogos", "sw entrar solo"));
        s.zones.put("pvp", pvp); s.zones.put("jogos", jogos);
        CmdState.validate(s);
        Map<String, String> idx = s.worldIndex();
        check(idx.get("pvp").equals("pvp") && idx.get("jogos").equals("jogos") && !idx.containsKey("lobby"), "Indice de mundos errado.");
        check(s.allowed(pvp, "kit", "kit") && !s.allowed(pvp, "fly", "fly") && s.allowed(pvp, "suporte", "suporte") && s.allowed(pvp, "x", "spawn"), "Zona que so libera a lista errada (ou sempre liberados nao valem).");
        check(!s.allowed(jogos, "voar", "fly") && s.allowed(jogos, "kit", "kit") && s.allowed(null, "fly", "fly"), "Zona que bloqueia a lista errada (alias nao foi bloqueado).");
        check(CmdState.command("/GoldEssentials:FLY").equals("fly"), "Nome de comando nao normalizado.");
        rejects(() -> CmdState.command("say oi"));
        s.opIps.put("dono", new TreeSet<>(Collections.singletonList(CmdState.ip("177.10.20.30"))));
        check(s.opAllowed("Dono", "177.10.20.30") && !s.opAllowed("Dono", "1.2.3.4") && !s.opAllowed("outro", "177.10.20.30"), "IP do OP errado.");
        rejects(() -> CmdState.ip("1.2.3"));
        rejects(() -> CmdState.ip("evil.com"));
        check(s.visit("ana", "pvp") && !s.visit("ana", "pvp") && s.visit("ana", "jogos"), "Primeira visita errada.");
        CmdState back = new Gson().fromJson(new Gson().toJson(s), CmdState.class);
        CmdState.validate(back);
        check(back.zones.get("jogos").items.get(0).command.equals("sw entrar solo") && back.opIps.get("dono").contains("177.10.20.30"), "Serializacao perdeu dados.");
        back.zones.get("jogos").worlds.add("pvp");
        rejects(() -> CmdState.validate(back));
        back.zones.get("jogos").worlds.remove("pvp");
        back.zones.get("jogos").items.put(9, new CmdState.HubItem(1, 0, "x", "y"));
        rejects(() -> CmdState.validate(back));
        System.out.println("GoldComandos: zonas, liberar/bloquear, alias, sempre liberados, OP por IP, boas-vindas e validacao aprovados.");
    }
    private static void check(boolean c, String m) { if (!c) { throw new AssertionError(m); } }
    private static void rejects(Runnable r) { try { r.run(); } catch (IllegalArgumentException e) { return; } throw new AssertionError("Entrada invalida aceita."); }
}
