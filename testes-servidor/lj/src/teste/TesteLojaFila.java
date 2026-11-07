package teste;

import cn.nukkit.Player;
import cn.nukkit.entity.Entity;
import cn.nukkit.item.Item;
import cn.nukkit.level.Level;
import cn.nukkit.network.protocol.TextPacket;
import cn.nukkit.plugin.PluginBase;
import br.goldsuite.api.EconomyService;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** /comprar, /caixas, arena de SkyWars e BedWars so pelo console e a fila do lobby do SkyWars (minimo, contagem, mesma sala). */
@SuppressWarnings("unchecked")
public final class TesteLojaFila extends PluginBase {
    final List<Runnable> steps = new ArrayList<>();
    final List<String> errors = new ArrayList<>();
    final Map<String, List<String>> got = new ConcurrentHashMap<>();
    Player ana, bia, caio;

    void step(int delayTicks, Runnable r) { steps.add(() -> getServer().getScheduler().scheduleDelayedTask(() -> { try { r.run(); } catch (Throwable t) { errors.add(t.toString()); getLogger().error("passo", t); } next(); }, delayTicks)); }
    void next() { if (!steps.isEmpty()) { steps.remove(0).run(); } else { getLogger().info(errors.isEmpty() ? "LOJAFILA_OK" : "LOJAFILA_FALHOU " + errors); getLogger().info("FIM_TESTE"); } }
    void check(boolean ok, String msg) { if (!ok) { errors.add(msg); getLogger().error("FALHOU: " + msg); } else { getLogger().info("ok: " + msg); } }
    List<String> got(Player p) { return got.computeIfAbsent(p.getName(), k -> Collections.synchronizedList(new ArrayList<>())); }
    boolean saw(Player p, String part) { synchronized (got(p)) { for (String s : got(p)) { if (s.contains(part)) { return true; } } } return false; }
    void forget() { got.clear(); }
    void console(String line) { getServer().dispatchCommand(getServer().getConsoleSender(), line); }

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

    Object engine() { return getServer().getServiceManager().load(br.goldsuite.api.MenuService.class); }
    Object menuOf(Player p) throws Exception { Field f = engine().getClass().getDeclaredField("open"); f.setAccessible(true); return ((Map<?, ?>) f.get(engine())).get(p); }
    void menuClick(Player p, int slot) throws Exception {
        Object menu = menuOf(p);
        if (menu == null) { throw new RuntimeException("nenhum menu aberto para " + p.getName()); }
        Field c = menu.getClass().getDeclaredField("onClick"); c.setAccessible(true);
        ((java.util.function.BiConsumer<Player, Integer>) c.get(menu)).accept(p, slot);
    }
    long money(Player p) { return getServer().getServiceManager().load(EconomyService.class).balance(p.getName(), EconomyService.Currency.MONEY); }
    int count(Player p, int id) { int n = 0; for (Item it : p.getInventory().getContents().values()) { if (it.getId() == id) { n += it.getCount(); } } return n; }
    Object plugin(String name) { return getServer().getPluginManager().getPlugin(name); }
    Object field(Object o, String f) throws Exception { Field x = o.getClass().getDeclaredField(f); x.setAccessible(true); return x.get(o); }

    @Override public void onEnable() {
        getServer().getScheduler().scheduleDelayedTask(() -> {
            try {
                getServer().loadLevel("sw_ilhas"); getServer().loadLevel("bw_quatro");
                ana = fake("Ana", false); bia = fake("Bia", false); caio = fake("Caio", false);
            } catch (Exception e) { errors.add(e.toString()); getLogger().error("setup", e); }
            plan();
            next();
        }, 80);
    }

    void plan() {
        step(20, () -> {
            for (Player p : new Player[] {ana, bia, caio}) { try { ((br.goldsuite.api.MenuService) engine()).close(p); } catch (Exception ignored) { } }
            console("economia adicionar money Ana 1000");
            console("comprar categoria blocos 1 &eBlocos");
            console("comprar add blocos 1 64 50");
            console("comprar add blocos 4 32 10");
            console("caixa criar basica");
            console("caixa premioitem basica 10 264 2");
            console("caixa preco basica 100");
            // Arena de SkyWars so pelo console (coordenadas do pontos.txt do arena-builder).
            console("sw criar ilhas solo sw_ilhas");
        });
        // A criacao grava no SQLite de forma assincrona: espere antes de marcar as jaulas.
        step(80, () -> {
            console("sw meio 0.5 67 0.5");
            for (String j : new String[] {"34.5 68 0.5", "24.5 68 24.5", "0.5 68 34.5", "-23.5 68 24.5", "-33.5 68 0.5", "-23.5 68 -23.5", "0.5 68 -33.5", "24.5 68 -23.5"}) { console("sw jaula " + j); }
            console("swmap espectador 0.5 90 0.5");
            console("swmap ativar");
            console("bw criar quatro solo bw_quatro");
        });
        step(20, () -> {
            console("sw salvar");
            console("bw espera 0.5 95 0.5");
            console("bw time vermelho spawn 0.5 65 -41.5");
            console("bw time vermelho cama 0 65 -46");
            console("bw time vermelho forja 0.5 65 -37.5");
            console("painel set GoldSkyWars fila.minimo 2");
            console("painel set GoldSkyWars fila.contagem 3");
        });
        step(100, () -> {
            try {
                Object sw = plugin("GoldSkyWars");
                Object game = ((Map<String, Object>) field(sw, "games")).get("ilhas");
                check(game != null, "SkyWars: arena criada pelo console");
                Method arena = game.getClass().getDeclaredMethod("arena"); arena.setAccessible(true);
                Object a = arena.invoke(game);
                check(((List<?>) field(a, "spawns")).size() == 8 && Boolean.TRUE.equals(field(a, "enabled")) && ((double[]) field(a, "spectator")).length == 3, "SkyWars: 8 jaulas, espectador e ativa pelo console");
                Object bw = plugin("GoldBedWars");
                Object bg = ((Map<String, Object>) field(bw, "games")).get("quatro");
                Method barena = bg.getClass().getDeclaredMethod("arena"); barena.setAccessible(true);
                Object ba = barena.invoke(bg);
                Object red = ((Map<String, Object>) field(ba, "teams")).get("vermelho");
                check(red != null && field(red, "bed") != null && field(red, "spawn") != null && field(red, "forge") != null, "BedWars: time com spawn, forja e cama achada pela coordenada (console)");
            } catch (Exception e) { errors.add("arenas: " + e); }
            // /comprar
            forget();
            ana.getInventory().clearAll();
            getServer().dispatchCommand(ana, "comprar");
        });
        step(10, () -> {
            try { check(menuOf(ana) != null, "/comprar abre a loja"); menuClick(ana, 1); } catch (Exception e) { errors.add("comprar: " + e); }
        });
        step(10, () -> {
            try { menuClick(ana, 0); } catch (Exception e) { errors.add("comprar item: " + e); }
        });
        step(20, () -> {
            check(count(ana, 1) == 64 && money(ana) == 100000 - 5000, "Compra na loja: 64 pedras por $50 (" + count(ana, 1) + ", " + money(ana) + ")");
            ((br.goldsuite.api.MenuService) engine()).close(ana);
            getServer().dispatchCommand(ana, "caixas");
        });
        step(15, () -> {
            try { check(menuOf(ana) != null, "/caixas abre o menu"); menuClick(ana, 10); } catch (Exception e) { errors.add("caixas: " + e); }
        });
        step(20, () -> {
            check(count(ana, 54) == 1 && money(ana) == 100000 - 5000 - 10000, "Compra de caixa: 1 caixa por $100 (" + money(ana) + ")");
            // Fila do lobby: Bia sozinha nao comeca; com Caio, contagem e os dois vao para a mesma sala.
            forget();
            getServer().dispatchCommand(bia, "sw fila solo");
        });
        step(40, () -> {
            check(saw(bia, "fila"), "Entrar na fila mostra a mensagem");
            try { check(((Map<?, ?>) field(plugin("GoldSkyWars"), "inGame")).isEmpty(), "Abaixo do minimo ninguem vai para a sala"); } catch (Exception e) { errors.add(e.toString()); }
            getServer().dispatchCommand(caio, "sw fila solo");
        });
        step(120, () -> {
            try {
                Map<?, ?> in = (Map<?, ?>) field(plugin("GoldSkyWars"), "inGame");
                check(in.containsKey(bia) && in.containsKey(caio) && in.get(bia) == in.get(caio), "Com o minimo, contagem e os dois vao juntos para a mesma sala");
            } catch (Exception e) { errors.add(e.toString()); }
        });
    }
}
