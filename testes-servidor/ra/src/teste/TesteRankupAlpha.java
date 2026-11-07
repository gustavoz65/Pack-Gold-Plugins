package teste;

import br.goldsuite.api.EconomyService;
import cn.nukkit.Player;
import cn.nukkit.block.Block;
import cn.nukkit.entity.Entity;
import cn.nukkit.event.block.BlockBreakEvent;
import cn.nukkit.event.block.BlockPlaceEvent;
import cn.nukkit.item.Item;
import cn.nukkit.level.Level;
import cn.nukkit.math.Vector3;
import cn.nukkit.plugin.Plugin;
import cn.nukkit.plugin.PluginBase;
import java.lang.reflect.*;
import java.util.*;

/**
 * Rankup alpha de ponta a ponta, com os menus tocados pelo caminho real do cliente (ContainerSetSlotPacket):
 * jogador novo -> /minas -> minera -> vende -> /rankup -> mina nova -> /plot auto -> constroi -> outro nao constroi
 * -> sai e volta com tudo; mais os abusos (sem dinheiro, toque duplo, mina de rank alto, colocar na mina, dois
 * comprando o mesmo plot, limite, regeneracao por % e por tempo, /plot limpar) e as telas da loja e das caixas.
 */
@SuppressWarnings("unchecked")
public final class TesteRankupAlpha extends PluginBase {
    final List<Runnable> steps = new ArrayList<>();
    final List<String> errors = new ArrayList<>();
    final Map<String, List<String>> got = new HashMap<>();
    final Map<String, Integer> opens = new HashMap<>(), closes = new HashMap<>();
    Player ana, bia, caio;
    int window;
    long before;

    void step(int delayTicks, Runnable r) { steps.add(() -> getServer().getScheduler().scheduleDelayedTask(() -> { try { r.run(); } catch (Throwable t) { errors.add(t.toString()); getLogger().error("passo", t); } next(); }, delayTicks)); }
    void next() { if (!steps.isEmpty()) { steps.remove(0).run(); } else { getLogger().info(errors.isEmpty() ? "RANKUP_ALPHA_OK" : "RANKUP_ALPHA_FALHOU " + errors); getLogger().info("FIM_TESTE"); } }
    void check(boolean ok, String msg) { if (!ok) { errors.add(msg); getLogger().error("FALHOU: " + msg); } else { getLogger().info("ok: " + msg); } }
    List<String> got(Player p) { return got.computeIfAbsent(p.getName(), k -> new ArrayList<>()); }
    boolean saw(Player p, String part) { for (String s : got(p)) { if (s.contains(part)) { return true; } } return false; }
    void console(String line) { getServer().dispatchCommand(getServer().getConsoleSender(), line); }

    Player fake(String name) throws Exception {
        cn.nukkit.network.SourceInterface si = (cn.nukkit.network.SourceInterface) Proxy.newProxyInstance(cn.nukkit.network.SourceInterface.class.getClassLoader(), new Class<?>[]{cn.nukkit.network.SourceInterface.class}, (proxy, m, args) -> {
            if (m.getReturnType() == Integer.class || m.getReturnType() == int.class) { return 0; }
            if (m.getReturnType() == boolean.class) { return false; }
            return null;
        });
        long client = Math.abs(name.hashCode()) + 1;
        Player p = new Player(si, client, "10.0.3." + (client % 200), 19133) {
            @Override public int dataPacket(cn.nukkit.network.protocol.DataPacket pk, boolean ack) {
                if (pk instanceof cn.nukkit.network.protocol.ContainerOpenPacket) { opens.merge(getName(), 1, Integer::sum); }
                if (pk instanceof cn.nukkit.network.protocol.ContainerClosePacket) { closes.merge(getName(), 1, Integer::sum); }
                return super.dataPacket(pk, ack);
            }
            @Override public boolean isOnline() { return online().containsValue(this); }
            @Override public boolean isConnected() { return true; }
            @Override public void sendMessage(String m) { got(this).add(cn.nukkit.utils.TextFormat.clean(m)); getLogger().info("[" + getName() + "] " + cn.nukkit.utils.TextFormat.clean(m)); }
            @Override public void sendPopup(String m) { got(this).add(cn.nukkit.utils.TextFormat.clean(m)); }
        };
        for (String f : new String[] {"username", "displayName", "iusername"}) { Field x = Player.class.getDeclaredField(f); x.setAccessible(true); x.set(p, f.equals("iusername") ? name.toLowerCase() : name); }
        cn.nukkit.nbt.tag.CompoundTag nbt = getServer().getOfflinePlayerData(name);
        Level lv = getServer().getDefaultLevel();
        Vector3 sp = lv.getSafeSpawn();
        nbt.putList(new cn.nukkit.nbt.tag.ListTag<cn.nukkit.nbt.tag.DoubleTag>("Pos").add(new cn.nukkit.nbt.tag.DoubleTag("0", sp.x)).add(new cn.nukkit.nbt.tag.DoubleTag("1", sp.y)).add(new cn.nukkit.nbt.tag.DoubleTag("2", sp.z)));
        p.setLevel(lv);
        Method init = Entity.class.getDeclaredMethod("init", cn.nukkit.level.format.FullChunk.class, cn.nukkit.nbt.tag.CompoundTag.class);
        init.setAccessible(true);
        init.invoke(p, lv.getChunk(sp.getFloorX() >> 4, sp.getFloorZ() >> 4, true), nbt);
        Field adv = Player.class.getDeclaredField("adventureSettings"); adv.setAccessible(true);
        adv.set(p, new cn.nukkit.AdventureSettings.Builder(p).canDestroyBlock(true).autoJump(true).canFly(false).build());
        Field food = Player.class.getDeclaredField("foodData"); food.setAccessible(true);
        food.set(p, new cn.nukkit.PlayerFood(p, 20, 20));
        Field rc = Player.class.getDeclaredField("randomClientId"); rc.setAccessible(true); rc.set(p, client);
        Field uu = cn.nukkit.entity.EntityHuman.class.getDeclaredField("uuid"); uu.setAccessible(true); uu.set(p, UUID.nameUUIDFromBytes(("aparelho" + client).getBytes()));
        Field perm = Player.class.getDeclaredField("perm"); perm.setAccessible(true); perm.set(p, new cn.nukkit.permission.PermissibleBase(p));
        for (int i = 0; i < 9; i++) { p.getInventory().setHotbarSlotIndex(i, i); }
        p.spawned = true;
        online().put(UUID.randomUUID(), p);
        getServer().getPluginManager().callEvent(new cn.nukkit.event.player.PlayerJoinEvent(p, ""));
        return p;
    }

    void quit(Player p) {
        getServer().getPluginManager().callEvent(new cn.nukkit.event.player.PlayerQuitEvent(p, "", ""));
        online().values().remove(p);
    }

    Map<UUID, Player> online() { try { Field f = cn.nukkit.Server.class.getDeclaredField("playerList"); f.setAccessible(true); return (Map<UUID, Player>) f.get(getServer()); } catch (Exception e) { throw new RuntimeException(e); } }
    void cmd(Player p, String line) { getServer().dispatchCommand(p, line); }
    Object engine() { return getServer().getServiceManager().load(br.goldsuite.api.MenuService.class); }
    Object menuOf(Player p) throws Exception { Field f = engine().getClass().getDeclaredField("open"); f.setAccessible(true); return ((Map<?, ?>) f.get(engine())).get(p); }
    int windowId(Player p) { try { Object m = menuOf(p); return m == null ? -1 : p.getWindowId((cn.nukkit.inventory.Inventory) m); } catch (Exception e) { return -1; } }
    Item slot(Player p, int i) throws Exception { return ((cn.nukkit.inventory.Inventory) menuOf(p)).getItem(i); }
    void tap(Player p, int slot) {
        cn.nukkit.network.protocol.ContainerSetSlotPacket pk = new cn.nukkit.network.protocol.ContainerSetSlotPacket();
        pk.windowid = windowId(p); pk.slot = slot; pk.item = Item.get(0);
        getServer().getPluginManager().callEvent(new cn.nukkit.event.server.DataPacketReceiveEvent(p, pk));
    }
    int n(Map<String, Integer> m, Player p) { return m.getOrDefault(p.getName(), 0); }
    long money(Player p) { return getServer().getServiceManager().load(EconomyService.class).balance(p.getName(), EconomyService.Currency.MONEY); }
    Plugin plugin(String n) { return getServer().getPluginManager().getPlugin(n); }
    static Object field(Object o, String name) throws Exception { Field f = o.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(o); }
    Object call(Object o, String name, Class<?>[] types, Object... args) throws Exception { Method m = o.getClass().getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(o, args); }
    int rank(Player p) { try { return (Integer) call(plugin("GoldRankup"), "index", new Class<?>[] {Player.class}, p); } catch (Exception e) { errors.add(e.toString()); return -1; } }
    Object runtime(String mine) throws Exception { Object mines = field(plugin("GoldRankup"), "mines"); return call(mines, "rt", new Class<?>[] {String.class}, mine); }
    int mined(String mine) throws Exception { return (Integer) field(runtime(mine), "mined"); }
    Object pw(String field) { try { Field f = plugin("GoldPlots").getClass().getDeclaredField(field); f.setAccessible(true); return f.get(plugin("GoldPlots")); } catch (Exception e) { errors.add(e.toString()); return null; } }
    String owner(String id) { return owner("plotsWorld", id); }
    String owner(String world, String id) { try { Object w = pw(world); return (String) call(plugin("GoldPlots"), "owner", new Class<?>[] {w.getClass(), String.class}, w, id); } catch (Exception e) { errors.add(e.toString()); return "?"; } }
    boolean breakAt(Player p, Level lv, int x, int y, int z) {
        BlockBreakEvent ev = new BlockBreakEvent(p, lv.getBlock(new Vector3(x, y, z)), Item.get(274), false);
        getServer().getPluginManager().callEvent(ev);
        return !ev.isCancelled();
    }
    boolean placeAt(Player p, Level lv, int x, int y, int z) {
        Block place = Block.get(1); place.position(new cn.nukkit.level.Position(x, y, z, lv));
        BlockPlaceEvent ev = new BlockPlaceEvent(p, place, lv.getBlock(new Vector3(x, y, z)), lv.getBlock(new Vector3(x, y - 1, z)), Item.get(1));
        getServer().getPluginManager().callEvent(ev);
        return !ev.isCancelled();
    }
    Level minas() { return getServer().getLevelByName("minas"); }
    Level plots() { return getServer().getLevelByName("plots"); }

    @Override public void onEnable() {
        getServer().getScheduler().scheduleDelayedTask(() -> { plan(); next(); }, 80);
    }

    void plan() {
        step(10, () -> {
            console("mundo criar minas void");
            console("mundo criar plots goldplots");
            console("mundo criar terrenos goldterrenos");
            console("painel set GoldRankup minas.blocos_por_tick 4000");
            console("preco 4 0,40");
            console("preco 263 3");
            console("comprar categoria blocos 1 &eBlocos");
        });
        step(40, () -> {
            check(minas() != null && plots() != null, "mundos minas (vazio) e plots (grade) criados");
            console("mina gerarmundo minas");
            for (int i = 0; i < 20; i++) { console("comprar add blocos " + (i % 2 == 0 ? 1 : 4) + " 64 " + (10 + i)); }
        });
        step(400, () -> {
            try {
                Level lv = minas();
                int b = lv.getBlockIdAt(27, 50, -13);
                check(b == 1 || b == 16, "mina de pedra preenchida com a composicao (" + b + ")");
                check(lv.getBlockIdAt(27, 64, -4) == 98 && lv.getBlockIdAt(27, 43, -13) == 7, "patio, caminho e fundo de bedrock montados");
                cn.nukkit.level.format.FullChunk ch = lv.getChunk(1, -1, false);
                check(ch != null && ch.isGenerated() && ch.isPopulated() && blocksSaved(ch), "chunk da mina marcado como gerado (o save grava os blocos) " + (ch == null ? "null" : ch.isGenerated() + "/" + ch.isPopulated()));
                check(Boolean.TRUE.equals(field(runtime("pedra"), "filled")) && Boolean.TRUE.equals(field(runtime("lendario"), "filled")), "as 10 minas preenchidas");
                ana = fake("Ana"); bia = fake("Bia"); caio = fake("Caio");
                // Jogador falso nao segura chunk: um carregador de teste mantem a area das minas (como um jogador la perto).
                cn.nukkit.level.ChunkLoader loader = (cn.nukkit.level.ChunkLoader) Proxy.newProxyInstance(cn.nukkit.level.ChunkLoader.class.getClassLoader(), new Class<?>[] {cn.nukkit.level.ChunkLoader.class}, (proxy, m, args) -> {
                    switch (m.getName()) {
                        case "getLoaderId": return 987654;
                        case "isLoaderActive": return true;
                        case "getLevel": return minas();
                        case "getX": case "getZ": return 0.0;
                        case "getPosition": return new cn.nukkit.level.Position(0, 64, 0, minas());
                        case "hashCode": return 987654;
                        case "equals": return proxy == args[0];
                        default: return null;
                    }
                });
                for (int cx = -2; cx <= 10; cx++) { for (int cz = -2; cz <= 2; cz++) { minas().registerChunkLoader(loader, cx, cz); } }
            } catch (Exception e) { errors.add("mundos: " + e); }
        });
        // Jogador novo -> /minas (menu real) -> mina de pedra.
        step(40, () -> {
            check(rank(ana) == 0, "jogador novo comeca no primeiro rank");
            cmd(ana, "minas");
        });
        step(10, () -> {
            try {
                check(windowId(ana) >= 0 && slot(ana, 10).getId() == 1 && slot(ana, 11).getId() == 101, "/minas: pedra liberada, carvao bloqueada (grade de ferro)");
                tap(ana, 11);
            } catch (Exception e) { errors.add(e.toString()); }
        });
        step(10, () -> { check(saw(ana, "Bloqueada"), "mina bloqueada nao leva"); tap(ana, 10); });
        step(10, () -> { cmd(ana, "minas"); });
        step(10, () -> { tap(ana, 10); });
        step(120, () -> {
            check(ana.level == minas() && Math.abs(ana.z + 4.5) < 2, "toque na mina de pedra leva a borda dela (" + ana.level.getFolderName() + " " + ana.z + ")");
            check(breakAt(ana, minas(), 27, 60, -13), "minerar dentro da mina liberada");
            try { check(mined("pedra") >= 1, "conta o bloco minerado"); } catch (Exception e) { errors.add(e.toString()); }
            check(!breakAt(ana, minas(), 27, 60, 13), "mina de rank alto (carvao) bloqueada");
            check(!breakAt(ana, minas(), 0, 64, 0), "fora das minas nao quebra");
            check(!placeAt(ana, minas(), 27, 60, -13), "nao coloca bloco na mina");
            // Vende o que minerou.
            ana.getInventory().addItem(Item.get(4, 0, 64), Item.get(263, 0, 20));
            before = money(ana);
            cmd(ana, "vender tudo");
        });
        step(30, () -> {
            check(money(ana) - before == 64 * 40 + 20 * 300, "vender: 64 pedregulhos e 20 carvoes = $85,60 (" + (money(ana) - before) + ")");
            cmd(ana, "rankup");
        });
        step(10, () -> {
            try { check(windowId(ana) >= 0 && slot(ana, 21).getId() == 35, "/rankup abre a confirmacao"); } catch (Exception e) { errors.add(e.toString()); }
            tap(ana, 21);
        });
        step(30, () -> {
            check(rank(ana) == 0 && saw(ana, "faltam"), "rankup sem dinheiro recusa");
            console("economia adicionar money Ana 2000");
        });
        step(30, () -> {
            before = money(ana);
            try {
                // Dois toques rapidos: o segundo nao cobra de novo.
                Object rk = plugin("GoldRankup");
                Method up = rk.getClass().getDeclaredMethod("rankup", Player.class, String.class); up.setAccessible(true);
                up.invoke(rk, ana, "pedra_1"); up.invoke(rk, ana, "pedra_1");
            } catch (Exception e) { errors.add(e.toString()); }
        });
        step(60, () -> {
            check(rank(ana) == 1 && before - money(ana) == 450_00, "toque duplo: subiu um rank e cobrou uma vez (" + (before - money(ana)) + ")");
            cmd(ana, "rankup");
        });
        step(10, () -> { tap(ana, 21); });
        step(60, () -> {
            check(rank(ana) == 2, "segundo rankup pelo menu real (Carvao I)");
            check(saw(ana, "Mina liberada"), "aviso de mina liberada");
            check(breakAt(ana, minas(), 27, 60, 13), "mina de carvao liberada depois do rankup");
            // Regeneracao por %: 10% de 4500 blocos.
            console("mina porcentagem pedra 10");
        });
        step(20, () -> {
            ana.teleport(new cn.nukkit.level.Location(27.5, 60, -13.5, minas()));
            for (int i = 0; i < 460; i++) { breakAt(bia.level == minas() ? bia : ana, minas(), 20 + i % 15, 44 + (i / 15) % 20, -20 + (i / 300)); }
            try { check(mined("pedra") >= 450, "contagem passou de 10%"); } catch (Exception e) { errors.add(e.toString()); }
        });
        step(60, () -> { check(saw(ana, "regenera em 10 segundos"), "aviso 10 s antes da regeneracao"); });
        step(260, () -> {
            try {
                check(mined("pedra") == 0 && Boolean.FALSE.equals(field(runtime("pedra"), "resetting")), "mina regenerou pela porcentagem");
                check(ana.y >= 64.5, "quem estava dentro foi para a borda (y=" + ana.y + ")");
                // Regeneracao por tempo: proxima em 12 s.
                Field next = runtime("carvao").getClass().getDeclaredField("nextAt"); next.setAccessible(true);
                next.setLong(runtime("carvao"), System.currentTimeMillis() + 12_000);
                Field m = runtime("carvao").getClass().getDeclaredField("mined"); m.setAccessible(true); m.setInt(runtime("carvao"), 5);
            } catch (Exception e) { errors.add(e.toString()); }
        });
        step(400, () -> { try { check(mined("carvao") == 0, "mina regenerou pelo tempo"); } catch (Exception e) { errors.add(e.toString()); } });
        // Plots: /plot (menu real) -> auto.
        step(10, () -> {
            console("economia adicionar money Ana 5000");
            console("economia adicionar money Bia 5000");
            console("economia adicionar money Caio 5000");
        });
        // O jogador falso nao pede chunks como o cliente: pede a geracao do mundo de plots como o envio de chunks faria.
        step(10, () -> { for (int cx = -2; cx <= 4; cx++) { for (int cz = -2; cz <= 4; cz++) { plots().populateChunk(cx, cz, true); } } });
        step(100, () -> { for (int cx = -2; cx <= 4; cx++) { for (int cz = -2; cz <= 4; cz++) { plots().populateChunk(cx, cz, true); } } });
        step(30, () -> { cmd(ana, "plot"); });
        step(60, () -> { check(ana.level == plots() && saw(ana, "ainda nao tem"), "/plot fora do mapa leva ao mapa de plots"); cmd(ana, "plot"); });
        step(10, () -> { try { check(windowId(ana) >= 0 && slot(ana, 11).getId() == 345, "/plot abre o menu"); } catch (Exception e) { errors.add(e.toString()); } before = money(ana); tap(ana, 11); });
        step(120, () -> {
            check(owner("0;0").equals("ana") && before - money(ana) == 1500_00, "plot auto: comprou o 0;0 por $1.500");
            check(ana.level == plots(), "plot auto leva ao plot");
            check(plots().getBlockIdAt(30, 64, 30) == 2 && plots().getBlockIdAt(6, 65, 30) == 158 && plots().getBlockIdAt(0, 64, 0) == 3, "mundo de plots: grama no plot, laje na borda, rua de terra (" + plots().getBlockIdAt(30, 64, 30) + "/" + plots().getBlockIdAt(6, 65, 30) + "/" + plots().getBlockIdAt(0, 64, 0) + ")");
            check(placeAt(ana, plots(), 30, 65, 30), "dono constroi no plot");
            check(!placeAt(ana, plots(), 5, 65, 5), "ninguem constroi na rua");
            check(!placeAt(bia, plots(), 30, 65, 30), "outro jogador nao constroi no plot dele");
            got(ana).clear();
            cmd(ana, "plot auto");
        });
        step(30, () -> {
            check(saw(ana, "maximo de plots") && owner("1;0").isEmpty() && owner("0;1").isEmpty(), "limite de plots (1 no rank Carvao)");
            try {
                // Dois comprando o mesmo plot no mesmo tick: so um leva.
                Object pl = plugin("GoldPlots");
                Object w = pw("plotsWorld");
                Method buy = pl.getClass().getDeclaredMethod("buy", Player.class, w.getClass(), String.class, boolean.class); buy.setAccessible(true);
                buy.invoke(pl, bia, w, "5;5", false); buy.invoke(pl, caio, w, "5;5", false);
            } catch (Exception e) { errors.add(e.toString()); }
        });
        step(60, () -> {
            String o = owner("5;5");
            check(o.equals("bia") && money(bia) == 3500_00 && money(caio) == 5000_00, "dois compradores do mesmo plot: so um leva e o outro nao paga (dono " + o + ")");
            plots().setBlock(new Vector3(30, 70, 30), Block.get(1), true, false);
            try { Object pl = plugin("GoldPlots"); Object w = pw("plotsWorld"); Method c = pl.getClass().getDeclaredMethod("clear", Player.class, w.getClass(), String.class); c.setAccessible(true); c.invoke(pl, ana, w, "0;0"); } catch (Exception e) { errors.add(e.toString()); }
        });
        step(200, () -> {
            check(plots().getBlockIdAt(30, 70, 30) == 0 && plots().getBlockIdAt(30, 64, 30) == 2, "/plot limpar repoe o plot");
            cmd(caio, "terreno auto");
        });
        // Terrenos: outro mundo, outra grade (rua de quartzo de 5), compra separada dos plots.
        step(10, () -> { Level t = getServer().getLevelByName("terrenos"); for (int cx = -2; cx <= 3; cx++) { for (int cz = -2; cz <= 3; cz++) { t.populateChunk(cx, cz, true); } } });
        step(120, () -> {
            Level t = getServer().getLevelByName("terrenos");
            check(owner("terrenosWorld", "0;0").equals("caio") && caio.level == t, "/terreno auto compra no mapa de terrenos e leva ate la");
            check(t.getBlockIdAt(0, 64, 0) == 155 && t.getBlockIdAt(10, 64, 10) == 2 && t.getBlockIdAt(3, 65, 10) == 44, "terrenos: rua de quartzo, grama e laje de pedra na borda (" + t.getBlockIdAt(0, 64, 0) + "/" + t.getBlockIdAt(10, 64, 10) + "/" + t.getBlockIdAt(3, 65, 10) + ")");
            check(placeAt(caio, t, 10, 65, 10) && !placeAt(bia, t, 10, 65, 10), "terrenos: dono constroi, outro nao");
            quit(ana);
        });
        step(40, () -> { try { ana = fake("Ana"); } catch (Exception e) { errors.add(e.toString()); } });
        step(60, () -> {
            check(rank(ana) == 2 && owner("0;0").equals("ana"), "sai e volta: rank e plot mantidos");
            // Telas da loja: categoria -> proxima pagina -> voltar -> item -> quantidade -> confirmar, na mesma janela.
            cmd(ana, "comprar");
        });
        step(10, () -> { window = windowId(ana); tap(ana, 1); });
        step(10, () -> {
            try {
                check(windowId(ana) == window && slot(ana, 24).getId() == 262, "loja: categoria na mesma janela, com proxima pagina");
                tap(ana, 24);
            } catch (Exception e) { errors.add(e.toString()); }
        });
        step(10, () -> { try { check(windowId(ana) == window && slot(ana, 20).getId() == 262 && slot(ana, 0).getId() != 0, "loja: pagina 2"); tap(ana, 20); } catch (Exception e) { errors.add(e.toString()); } });
        step(10, () -> { tap(ana, 0); });
        step(10, () -> { try { check(windowId(ana) == window && slot(ana, 13).getCount() == 16, "loja: escolher quantidade (1, 16, 64)"); tap(ana, 13); } catch (Exception e) { errors.add(e.toString()); } });
        step(10, () -> { try { check(windowId(ana) == window && slot(ana, 11).getId() == 35, "loja: tela de confirmar"); before = money(ana); tap(ana, 11); tap(ana, 11); } catch (Exception e) { errors.add(e.toString()); } });
        step(30, () -> {
            int stone = 0; for (Item it : ana.getInventory().getContents().values()) { if (it.getId() == 1) { stone += it.getCount(); } }
            check(stone == 16 && before - money(ana) == 250, "loja: 16 pedras por $2,50, toque duplo cobra uma vez (" + stone + ", " + (before - money(ana)) + ")");
            check(windowId(ana) == window, "loja: depois de comprar volta aos itens na mesma janela");
            cmd(ana, "ranks");
        });
        step(10, () -> { try { check(windowId(ana) >= 0 && slot(ana, 2).getCustomName().contains("Seu rank"), "/ranks mostra o rank atual"); window = windowId(ana); tap(ana, 26); } catch (Exception e) { errors.add(e.toString()); } });
        step(10, () -> { try { check(windowId(ana) == window && slot(ana, 21).getId() == 35, "/ranks -> /rankup na mesma janela"); tap(ana, 23); } catch (Exception e) { errors.add(e.toString()); } });
        step(20, () -> {
            console("caixa criar basica");
            console("caixa premioitem basica 10 264 1");
            console("caixa preco basica 100");
        });
        step(40, () -> { cmd(ana, "caixas"); });
        step(10, () -> { before = money(ana); tap(ana, 10); });
        step(30, () -> {
            int boxes = 0; for (Item it : ana.getInventory().getContents().values()) { if (it.getId() == 54) { boxes += it.getCount(); } }
            check(boxes == 1 && before - money(ana) == 100_00, "/caixas pelo toque real compra uma caixa");
            check(menuSafe(ana), "hotbar volta normal ao fechar (inventario reenviado)");
        });
    }

    boolean menuSafe(Player p) { try { return menuOf(p) == null; } catch (Exception e) { return false; } }
    private static boolean blocksSaved(cn.nukkit.level.format.FullChunk ch) {
        try {
            byte[] raw = ch.toBinary();
            java.util.zip.Inflater inf = new java.util.zip.Inflater(); inf.setInput(raw);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(); byte[] buf = new byte[8192];
            while (!inf.finished()) { int n = inf.inflate(buf); if (n == 0 && (inf.needsInput() || inf.needsDictionary())) { break; } out.write(buf, 0, n); }
            return new String(out.toByteArray(), java.nio.charset.StandardCharsets.ISO_8859_1).contains("Blocks");
        } catch (Exception e) { return false; }
    }
}
