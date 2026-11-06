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
public final class TesteMensagens extends PluginBase {
    final List<Runnable> steps = new ArrayList<>();
    final List<String> errors = new ArrayList<>();
    final Map<String, List<String>> got = new ConcurrentHashMap<>();
    final List<String> requests = Collections.synchronizedList(new ArrayList<>());
    Player gerente, mod, adm, ana, bia, carlos;
    HttpServer groq;
    int port;

    void step(int delayTicks, Runnable r) { steps.add(() -> getServer().getScheduler().scheduleDelayedTask(() -> { try { r.run(); } catch (Throwable t) { errors.add(t.toString()); getLogger().error("passo", t); } next(); }, delayTicks)); }
    void next() { if (!steps.isEmpty()) { steps.remove(0).run(); } else { getLogger().info(errors.isEmpty() ? "MENSAGENS_OK" : "MENSAGENS_FALHOU " + errors); getLogger().info("FIM_TESTE");  } }
    void check(boolean ok, String msg) { if (!ok) { errors.add(msg); getLogger().error("FALHOU: " + msg); } else { getLogger().info("ok: " + msg); } }

    List<String> got(Player p) { return got.computeIfAbsent(p.getName(), k -> Collections.synchronizedList(new ArrayList<>())); }
    boolean saw(Player p, String part) { synchronized (got(p)) { for (String s : got(p)) { if (s.contains(part)) { return true; } } } return false; }
    void forget() { got.clear(); }

    Player fake(String name, boolean op) throws Exception {
        cn.nukkit.network.SourceInterface si = (cn.nukkit.network.SourceInterface) Proxy.newProxyInstance(cn.nukkit.network.SourceInterface.class.getClassLoader(), new Class<?>[]{cn.nukkit.network.SourceInterface.class}, (proxy, m, args) -> {
            if (m.getName().equals("putPacket") && args[1] instanceof TextPacket) {
                TextPacket t = (TextPacket) args[1];
                got((Player) args[0]).add(cn.nukkit.utils.TextFormat.clean((t.type == TextPacket.TYPE_POPUP ? t.source + " " : "") + t.message));
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

    br.goldsuite.api.EconomyService eco() { return getServer().getServiceManager().load(br.goldsuite.api.EconomyService.class); }
    long money(Player p) { return eco().balance(p.getName(), br.goldsuite.api.EconomyService.Currency.MONEY); }
    Object menuClick(Player p, int slot) throws Exception {
        Object menus = getServer().getServiceManager().load(br.goldsuite.api.MenuService.class);
        Field f = menus.getClass().getDeclaredField("open"); f.setAccessible(true);
        Object menu = ((Map<?, ?>) f.get(menus)).get(p);
        if (menu == null) { throw new RuntimeException("nenhum menu aberto"); }
        Field c = menu.getClass().getDeclaredField("onClick"); c.setAccessible(true);
        ((java.util.function.BiConsumer<Player, Integer>) c.get(menu)).accept(p, slot);
        return menu;
    }

    static Object f(Object o, String name) throws Exception { Class<?> c = o.getClass(); while (c != null) { try { java.lang.reflect.Field x = c.getDeclaredField(name); x.setAccessible(true); return x.get(o); } catch (NoSuchFieldException e) { c = c.getSuperclass(); } } throw new RuntimeException(name); }
    @Override public void onEnable() {
        getServer().getScheduler().scheduleDelayedTask(() -> {
            try { gerente = fake("Gerente", true); ana = fake("Ana", false); bia = fake("Bia", false); } catch (Exception e) { errors.add(e.toString()); }
            plan(); next();
        }, 60);
    }

    void plan() {
        java.io.File f = new java.io.File(getServer().getPluginPath(), "GoldEconomy/mensagens.yml");
        step(20, () -> {
            check(f.isFile(), "mensagens.yml criado na pasta do plugin");
            String txt = ""; try { txt = new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8"); } catch (Exception e) { errors.add(e.toString()); }
            check(txt.contains("autentique_se_primeiro: ") && txt.contains("# {0} ="), "Arquivo traz as chaves e a dica dos valores");
            forget();
            type(ana, "/money");
            check(saw(ana, "Saldo de Ana: "), "Texto padrao igual ao de antes");
            try {
                String novo = txt.replaceFirst("(?m)^saldo_de: .*$", "saldo_de: \"&bCarteira de {0}: &f{1} {2}\"");
                java.nio.file.Files.write(f.toPath(), novo.getBytes("UTF-8"));
            } catch (Exception e) { errors.add(e.toString()); }
            console("painel mensagens recarregar");
        });
        step(10, () -> {
            forget();
            type(ana, "/money");
            check(saw(ana, "Carteira de Ana: ") && br.goldsuite.api.Messages.of("GoldEconomy").t("saldo_de", "A", "1", "x").startsWith("\u00a7bCarteira"), "Mensagem editada no arquivo vale depois de recarregar");
            console("painel msg GoldEconomy pagar");
            console("painel msg GoldPerms grupos &aGrupos do servidor: &f{0}");
        });
        step(10, () -> {
            forget();
            type(gerente, "/grupo listar");
            check(saw(gerente, "Grupos do servidor: "), "/painel msg troca a mensagem na hora");
            String txt = ""; try { txt = new String(java.nio.file.Files.readAllBytes(new java.io.File(getServer().getPluginPath(), "GoldPerms/mensagens.yml").toPath()), "UTF-8"); } catch (Exception e) { errors.add(e.toString()); }
            check(txt.contains("grupos: \"&aGrupos do servidor: &f{0}\""), "/painel msg grava no arquivo");
            console("painel set Servidor cor.erro &4");
            console("painel msg GoldPerms grupos {erro}Erro de teste {0}");
        });
        step(10, () -> {
            forget();
            type(gerente, "/grupo listar");
            check(saw(gerente, "Erro de teste") && br.goldsuite.api.Messages.of("GoldPerms").t("grupos", "x").startsWith("\u00a74Erro"), "Paleta {erro} do painel nas mensagens");
            console("painel msg GoldPerms grupos padrao");
            forget();
            type(gerente, "/grupo listar");
        });
        step(10, () -> {
            check(!saw(gerente, "Erro de teste"), "padrao volta ao texto original");
            br.goldsuite.api.Messages m = br.goldsuite.api.Messages.of("GoldSkyWars");
            check(m != null && m.keys().size() > 50, "SkyWars registrado com suas mensagens");
            int total = 0; for (br.goldsuite.api.Messages x : br.goldsuite.api.Messages.all()) { total += x.keys().size(); }
            getLogger().info("Total de mensagens configuraveis: " + total + " em " + br.goldsuite.api.Messages.all().size() + " plugins");
            check(br.goldsuite.api.Messages.all().size() >= 30, "Todos os plugins com mensagens (" + br.goldsuite.api.Messages.all().size() + ")");
        });
        step(5, () -> {
            console("chat formato padrao &7[P] {nome}&8: &f{mensagem}");
            console("chat anuncio adicionar &bAnuncio de teste");
            console("chat anuncio intervalo 45");
            console("chat formato padrao remover");
        });
        step(20, () -> {
            forget();
            type(ana, "oi pessoal");
            check(saw(bia, "[P] Ana: oi pessoal"), "/chat formato muda o formato do grupo");
            Object st = null; try { Object d = f(getServer().getPluginManager().getPlugin("GoldChat"), "data"); st = d.getClass().getMethod("read", java.util.function.Function.class).invoke(d, (java.util.function.Function<Object, Object>) x -> x); } catch (Exception e) { errors.add(e.toString()); }
            try { check(((java.util.List<?>) f(st, "announcements")).contains("&bAnuncio de teste") && (int) f(st, "announceSeconds") == 45 && ((java.util.Map<?, ?>) f(st, "formats")).containsKey("padrao"), "/chat anuncio adiciona e muda o intervalo; padrao nao pode ser removido"); } catch (Exception e) { errors.add(e.toString()); }
        });
    }
}
