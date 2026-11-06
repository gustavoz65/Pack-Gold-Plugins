package teste;

import cn.nukkit.Player;
import cn.nukkit.entity.Entity;
import cn.nukkit.event.player.PlayerCommandPreprocessEvent;
import cn.nukkit.level.Level;
import cn.nukkit.network.protocol.DataPacket;
import cn.nukkit.network.protocol.TextPacket;
import cn.nukkit.plugin.Plugin;
import cn.nukkit.plugin.PluginBase;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.lang.reflect.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** /suporte de ponta a ponta: cargos, triagem com uma Groq falsa, aviso para a equipe, chat isolado, finalizar, nota e metricas. */
@SuppressWarnings("unchecked")
public final class TesteSuporte extends PluginBase {
    final List<Runnable> steps = new ArrayList<>();
    final List<String> errors = new ArrayList<>();
    final Map<String, List<String>> got = new ConcurrentHashMap<>();
    final List<String> requests = Collections.synchronizedList(new ArrayList<>());
    Player gerente, mod, adm, ana, bia, carlos;
    HttpServer groq;
    int port;

    void step(int delayTicks, Runnable r) { steps.add(() -> getServer().getScheduler().scheduleDelayedTask(() -> { try { r.run(); } catch (Throwable t) { errors.add(t.toString()); getLogger().error("passo", t); } next(); }, delayTicks)); }
    void next() { if (!steps.isEmpty()) { steps.remove(0).run(); } else { getLogger().info(errors.isEmpty() ? "SUPORTE_OK" : "SUPORTE_FALHOU " + errors); getLogger().info("FIM_TESTE"); if (groq != null) { groq.stop(0); } } }
    void check(boolean ok, String msg) { if (!ok) { errors.add(msg); getLogger().error("FALHOU: " + msg); } else { getLogger().info("ok: " + msg); } }

    List<String> got(Player p) { return got.computeIfAbsent(p.getName(), k -> Collections.synchronizedList(new ArrayList<>())); }
    boolean saw(Player p, String part) { synchronized (got(p)) { for (String s : got(p)) { if (s.contains(part)) { return true; } } } return false; }
    void forget() { got.clear(); }

    Player fake(String name, boolean op) throws Exception {
        cn.nukkit.network.SourceInterface si = (cn.nukkit.network.SourceInterface) Proxy.newProxyInstance(cn.nukkit.network.SourceInterface.class.getClassLoader(), new Class<?>[]{cn.nukkit.network.SourceInterface.class}, (proxy, m, args) -> {
            if (m.getName().equals("putPacket") && args[1] instanceof TextPacket) {
                TextPacket t = (TextPacket) args[1];
                if (t.type != TextPacket.TYPE_TIP && t.type != TextPacket.TYPE_POPUP) { got((Player) args[0]).add(cn.nukkit.utils.TextFormat.clean(t.message)); }
            }
            if (m.getReturnType() == Integer.class || m.getReturnType() == int.class) { return 0; }
            if (m.getReturnType() == boolean.class) { return false; }
            return null;
        });
        Player p = new Player(si, (long) name.hashCode(), "10.0.1." + Math.abs(name.hashCode() % 200), 19133) {
            @Override public boolean isOnline() { return true; }
        };
        for (String f : new String[] {"username", "displayName", "iusername"}) { Field x = Player.class.getDeclaredField(f); x.setAccessible(true); x.set(p, f.equals("iusername") ? name.toLowerCase() : name); }
        cn.nukkit.nbt.tag.CompoundTag nbt = getServer().getOfflinePlayerData(name);
        Level lv = getServer().getDefaultLevel();
        cn.nukkit.math.Vector3 sp = lv.getSafeSpawn();
        nbt.putList(new cn.nukkit.nbt.tag.ListTag<cn.nukkit.nbt.tag.DoubleTag>("Pos").add(new cn.nukkit.nbt.tag.DoubleTag("0", sp.x)).add(new cn.nukkit.nbt.tag.DoubleTag("1", sp.y)).add(new cn.nukkit.nbt.tag.DoubleTag("2", sp.z)));
        p.setLevel(lv);
        Method init = Entity.class.getDeclaredMethod("init", cn.nukkit.level.format.FullChunk.class, cn.nukkit.nbt.tag.CompoundTag.class);
        init.setAccessible(true);
        init.invoke(p, lv.getChunk(sp.getFloorX() >> 4, sp.getFloorZ() >> 4, true), nbt);
        Field adv = Player.class.getDeclaredField("adventureSettings"); adv.setAccessible(true);
        adv.set(p, new cn.nukkit.AdventureSettings.Builder(p).canDestroyBlock(true).autoJump(true).canFly(false).build());
        Field food = Player.class.getDeclaredField("foodData"); food.setAccessible(true);
        food.set(p, new cn.nukkit.PlayerFood(p, 20, 20));
        p.spawned = true;
        if (op) { p.setOp(true); }
        getServer().getPluginManager().subscribeToPermission(cn.nukkit.Server.BROADCAST_CHANNEL_USERS, p);
        Field list = cn.nukkit.Server.class.getDeclaredField("playerList"); list.setAccessible(true);
        ((Map<UUID, Player>) list.get(getServer())).put(UUID.randomUUID(), p);
        getServer().getPluginManager().callEvent(new cn.nukkit.event.player.PlayerJoinEvent(p, ""));
        return p;
    }

    /** Digita no chat como o cliente: passa pelo comando ou pelo chat do servidor. */
    void type(Player p, String text) {
        try { Field mc = Player.class.getDeclaredField("messageCounter"); mc.setAccessible(true); mc.setInt(p, 10); } catch (Exception ignored) { }
        TextPacket t = new TextPacket();
        t.type = TextPacket.TYPE_CHAT; t.source = p.getName(); t.message = text;
        p.handleDataPacket(t);
    }

    boolean blocked(Player p, String line) { PlayerCommandPreprocessEvent e = new PlayerCommandPreprocessEvent(p, line); getServer().getPluginManager().callEvent(e); return e.isCancelled(); }

    void console(String line) { getServer().dispatchCommand(getServer().getConsoleSender(), line); }

    void quit(Player p) {
        getServer().getPluginManager().callEvent(new cn.nukkit.event.player.PlayerQuitEvent(p, "", ""));
        try { Field list = cn.nukkit.Server.class.getDeclaredField("playerList"); list.setAccessible(true); ((Map<UUID, Player>) list.get(getServer())).values().remove(p); } catch (Exception e) { errors.add(e.toString()); }
    }

    static String reply(String json) { return "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":" + new com.google.gson.JsonPrimitive(json) + "}}]}"; }

    void startGroq() throws IOException {
        groq = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = groq.getAddress().getPort();
        groq.createContext("/v1/chat", ex -> {
            String raw = new String(readAll(ex.getRequestBody()), StandardCharsets.UTF_8);
            requests.add(ex.getRequestHeaders().getFirst("Authorization") + " " + raw);
            // So o que o jogador digitou decide a resposta (o prompt do sistema cita /tpa e "resolvido").
            StringBuilder user = new StringBuilder();
            for (com.google.gson.JsonElement m : new com.google.gson.JsonParser().parse(raw).getAsJsonObject().getAsJsonArray("messages")) {
                if (m.getAsJsonObject().get("role").getAsString().equals("user")) { user.append(m.getAsJsonObject().get("content").getAsString()).append(" | "); }
            }
            String body = user.toString();
            String out;
            if (!"Bearer gsk_teste_1234567890".equals(ex.getRequestHeaders().getFirst("Authorization"))) { out = null; }
            else if (body.contains("tpa")) {
                out = body.contains("resolvi") ? reply("{\"mensagem\":\"Otimo!\",\"pronto\":true,\"resolvido\":true,\"categoria\":\"duvida\",\"prioridade\":\"baixa\",\"resumo\":\"Duvida sobre /tpa\"}")
                        : reply("{\"mensagem\":\"Use /tpa <jogador>. Resolveu?\",\"pronto\":false,\"resolvido\":false}");
            } else {
                out = body.contains("skywars") ? reply("{\"mensagem\":\"Entendi, vou passar para a equipe.\",\"pronto\":true,\"resolvido\":false,\"categoria\":\"bug\",\"prioridade\":\"alta\",\"resumo\":\"Ana caiu no void no SkyWars por bug e perdeu os itens.\"}")
                        : reply("{\"mensagem\":\"Em qual modo de jogo isso aconteceu?\",\"pronto\":false}");
            }
            byte[] b = (out == null ? "{\"error\":\"unauthorized\"}" : out).getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(out == null ? 401 : 200, b.length);
            try (OutputStream o = ex.getResponseBody()) { o.write(b); }
        });
        groq.start();
    }

    static byte[] readAll(InputStream in) throws IOException { ByteArrayOutputStream b = new ByteArrayOutputStream(); byte[] buf = new byte[4096]; int n; while ((n = in.read(buf)) > 0) { b.write(buf, 0, n); } return b.toByteArray(); }

    Object tickets() throws Exception { Field f = getServer().getPluginManager().getPlugin("GoldStaff").getClass().getDeclaredField("tickets"); f.setAccessible(true); return f.get(getServer().getPluginManager().getPlugin("GoldStaff")); }
    boolean isolated(Player p) throws Exception { Field f = tickets().getClass().getDeclaredField("isolated"); f.setAccessible(true); return ((Set<?>) f.get(tickets())).contains(p); }

    @Override public void onEnable() {
        getServer().getScheduler().scheduleDelayedTask(() -> {
            try {
                startGroq();
                gerente = fake("Gerente", true); mod = fake("Mod", false); adm = fake("Adm", false); ana = fake("Ana", false); bia = fake("Bia", false); carlos = fake("Carlos", false);
            } catch (Exception e) { errors.add(e.toString()); getLogger().error("setup", e); }
            plan();
            next();
        }, 60);
    }

    void plan() {
        // ---------- cargos ----------
        step(20, () -> {
            console("painel set GoldStaff groq_url http://127.0.0.1:" + port + "/v1/chat");
            console("painel set GoldStaff groq_chave gsk_teste_1234567890");
            type(gerente, "/cargo Mod moderador");
            type(gerente, "/cargo Adm admin");
        });
        step(40, () -> {
            br.goldsuite.api.PermissionService perms = getServer().getServiceManager().load(br.goldsuite.api.PermissionService.class);
            check(perms.getWeight("mod") == 40 && perms.getWeight("adm") == 60 && perms.isStaff("mod") && !perms.isStaff("ana"), "Cargos com peso e equipe");
            check(mod.hasPermission("goldstaff.suporte") && mod.hasPermission("goldstaff.reports") && !mod.hasPermission("goldstaff.metricas") && !ana.hasPermission("goldstaff.suporte"), "Moderador herda o suporte do ajudante e nao ve metricas");
            check(saw(getServer().getPlayerExact("Mod"), "Seu cargo agora"), "Jogador avisado do novo cargo");
            forget();
            type(mod, "/cargo Ana ajudante");
            type(adm, "/cargo Mod ajudante");
            check(perms.getWeight("mod") == 40, "Admin sem goldperms.cargo nao rebaixa ninguem");
            check(blocked(mod, "/kick Adm spam") && !blocked(mod, "/kick Ana spam") && !blocked(adm, "/kick Mod teste") && blocked(mod, "/mute Gerente 5"), "Ninguem pune cargo igual ou acima (nem OP)");
            check(blocked(ana, "/pl") && blocked(mod, "/pl") && !blocked(gerente, "/pl"), "Comando bloqueado para quem nao tem liberacao");
            console("grupo permissao moderador goldstaff.cmd.pl true");
        });
        step(40, () -> {
            check(!blocked(mod, "/pl") && blocked(ana, "/pl"), "Liberar comando bloqueado por cargo (goldstaff.cmd.pl)");
            forget();
            type(carlos, "oi pessoal");
            check(saw(ana, "oi pessoal") && saw(mod, "oi pessoal"), "Chat normal antes do suporte");
            forget();
            // Tag do cargo no chat.
            type(mod, "bom dia");
            check(saw(carlos, "[MOD] Mod: bom dia"), "Tag do cargo no chat");
        });
        // ---------- triagem ----------
        step(40, () -> {
            forget();
            type(ana, "/suporte");
            check(saw(ana, "SUPORTE") && saw(ana, "Me conte o que aconteceu"), "Suporte abre com o assistente sem gastar chamada");
            try { check(isolated(ana), "Jogadora isolada durante a triagem"); } catch (Exception e) { errors.add(e.toString()); }
            forget();
            type(carlos, "alguem no hg?");
            check(!saw(ana, "alguem no hg") && saw(mod, "alguem no hg"), "Chat do servidor some so para quem esta no suporte");
            type(ana, "cai no void e perdi tudo");
            check(!saw(carlos, "cai no void") && !saw(mod, "cai no void"), "Mensagem da triagem nao vai para o chat publico");
        });
        step(40, () -> {
            check(saw(ana, "[Assistente] Em qual modo de jogo"), "IA faz pergunta na triagem");
            String req = requests.isEmpty() ? "" : requests.get(requests.size() - 1);
            check(req.contains("\"response_format\":{\"type\":\"json_object\"}") && req.contains("openai/gpt-oss-20b") && req.contains("reasoning_effort") && req.contains("cai no void"), "Pedido para a Groq no formato certo (modelo, JSON, conversa)");
            forget();
            type(ana, "foi no skywars");
        });
        step(40, () -> {
            check(saw(ana, "Ticket #1 aberto"), "Ticket aberto depois da triagem");
            check(saw(mod, "SUPORTE #1") && saw(mod, "Ana caiu no void no SkyWars") && saw(mod, "/atender 1") && saw(gerente, "SUPORTE #1"), "Bloco de aviso com o resumo da IA para a equipe");
            check(!saw(carlos, "SUPORTE #1"), "Jogador comum nao recebe o aviso");
            check(saw(mod, "Prioridade: alta") && saw(mod, "Categoria: bug"), "Categoria e prioridade da IA no aviso");
            forget();
            type(ana, "vou esperar");
            check(saw(ana, "anotado no ticket #1") && !saw(carlos, "vou esperar"), "Mensagem na fila fica anotada no ticket");
            forget();
            type(mod, "/atender 1");
        });
        // ---------- atendimento ----------
        step(30, () -> {
            check(saw(ana, "ATENDIMENTO #1") && saw(ana, "falando so com Mod") && saw(mod, "Resumo da IA: Ana caiu no void"), "Atendimento comeca para os dois com o resumo");
            check(saw(mod, "vou esperar"), "Staff ve as ultimas mensagens anotadas");
            check(saw(gerente, "Ticket #1 assumido por Mod"), "Outros da equipe sabem quem assumiu");
            try { check(isolated(ana) && isolated(mod), "Os dois isolados no atendimento"); } catch (Exception e) { errors.add(e.toString()); }
            forget();
            type(carlos, "partiu pvp");
            type(ana, "oi, perdi meu kit");
            type(mod, "vou verificar");
            check(!saw(ana, "partiu pvp") && !saw(mod, "partiu pvp"), "Nenhum dos dois ve o chat do servidor");
            check(saw(mod, "[#1] Ana: oi, perdi meu kit") && saw(ana, "[#1] Mod: vou verificar"), "Os dois se veem");
            check(!saw(carlos, "perdi meu kit") && !saw(gerente, "vou verificar"), "Ninguem de fora ve a conversa");
            forget();
            type(ana, "/ajuda");
            check(saw(ana, "Ajuda"), "Resposta de comando digitado chega mesmo isolado");
        });
        step(2, () -> {
            forget();
            getServer().broadcastMessage("anuncio geral");
            check(!saw(ana, "anuncio geral") && saw(carlos, "anuncio geral"), "Anuncios do servidor tambem ficam de fora");
            type(mod, "/finalizar itens devolvidos");
        });
        step(30, () -> {
            check(saw(ana, "Atendimento #1 finalizado (itens devolvidos)") && saw(ana, "/avaliar"), "Finalizar avisa e pede nota");
            try { check(!isolated(ana) && !isolated(mod), "Chat volta ao normal depois de finalizar"); } catch (Exception e) { errors.add(e.toString()); }
            forget();
            type(carlos, "voltou?");
            check(saw(ana, "voltou?"), "Jogadora volta a ver o chat");
            type(ana, "/avaliar 5");
        });
        step(30, () -> {
            check(saw(ana, "Obrigado pela avaliacao"), "Nota registrada");
            forget();
            type(gerente, "/tickets metricas");
            check(saw(gerente, "mod - 1 no periodo") && saw(gerente, "5.0/5"), "Metricas por staff com nota");
            forget();
            type(mod, "/tickets metricas");
            check(saw(mod, "So gerentes e donos"), "Moderador nao ve metricas");
            type(gerente, "/tickets ver 1");
            check(saw(gerente, "Ana: oi, perdi meu kit") && saw(gerente, "itens devolvidos"), "Historico do ticket guarda a conversa");
            forget();
            type(gerente, "/painel ver GoldStaff");
            check(saw(gerente, "gsk_****7890") && !saw(gerente, "gsk_teste_1234567890"), "Chave da Groq escondida no painel");
            forget();
            type(bia, "/suporte como uso o tpa?");
        });
        // ---------- IA resolve sozinha ----------
        step(40, () -> {
            check(saw(bia, "Use /tpa"), "IA responde duvida simples");
            type(bia, "resolvi, valeu");
        });
        step(40, () -> {
            check(saw(bia, "Que bom que resolveu"), "Ticket resolvido pela IA fecha sozinho");
            try { check(!isolated(bia), "Quem resolveu com a IA volta ao chat"); } catch (Exception e) { errors.add(e.toString()); }
            check(!saw(mod, "SUPORTE #2"), "Equipe nao e chamada quando a IA resolve");
            // IA fora do ar: vai direto para a equipe.
            console("painel set GoldStaff groq_url http://127.0.0.1:1/v1/chat");
        });
        step(20, () -> {
            forget();
            type(carlos, "/suporte bug no hg, o bau nao abre");
        });
        step(60, () -> {
            check(saw(carlos, "assistente esta indisponivel") && saw(carlos, "Ticket #3 aberto") && saw(mod, "SUPORTE #3") && saw(mod, "bau nao abre"), "Sem IA o pedido vai direto para a equipe");
            forget();
            type(gerente, "/atender 3");
        });
        step(20, () -> {
            check(saw(carlos, "ATENDIMENTO #3"), "Gerente atende o terceiro ticket");
            forget();
            quit(gerente);
        });
        step(20, () -> {
            check(saw(carlos, "voltou para a fila") && saw(mod, "SUPORTE #3"), "Staff saiu: ticket volta para a fila e e anunciado de novo");
            forget();
            type(carlos, "/suporte cancelar");
            check(saw(carlos, "foi cancelado"), "Jogador cancela o ticket");
            type(carlos, "/suporte de novo");
            check(saw(carlos, "Espere"), "Espera entre tickets");
        });
    }
}
