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
public final class TesteBuild2 extends PluginBase {
    final List<Runnable> steps = new ArrayList<>();
    final List<String> errors = new ArrayList<>();
    final Map<String, List<String>> got = new ConcurrentHashMap<>();
    final List<String> requests = Collections.synchronizedList(new ArrayList<>());
    Player gerente, mod, adm, ana, bia, carlos;
    HttpServer groq;
    int port;

    void step(int delayTicks, Runnable r) { steps.add(() -> getServer().getScheduler().scheduleDelayedTask(() -> { try { r.run(); } catch (Throwable t) { errors.add(t.toString()); getLogger().error("passo", t); } next(); }, delayTicks)); }
    void next() { if (!steps.isEmpty()) { steps.remove(0).run(); } else { getLogger().info(errors.isEmpty() ? "BUILD_OK" : "BUILD_FALHOU " + errors); getLogger().info("FIM_TESTE");  } }
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

    String menuTitle(Player p) throws Exception {
        Object menus = getServer().getServiceManager().load(br.goldsuite.api.MenuService.class);
        Field f = menus.getClass().getDeclaredField("open"); f.setAccessible(true);
        Object menu = ((Map<?, ?>) f.get(menus)).get(p);
        return menu == null ? null : ((cn.nukkit.inventory.Inventory) menu).getTitle();
    }
    cn.nukkit.level.Level lv() { return getServer().getDefaultLevel(); }
    int id(int x, int y, int z) { return lv().getBlockIdAt(x, y, z); }
    cn.nukkit.block.Block put(int x, int y, int z, int bid) { lv().setBlock(new cn.nukkit.math.Vector3(x, y, z), cn.nukkit.block.Block.get(bid, 0), true, false); return lv().getBlock(new cn.nukkit.math.Vector3(x, y, z)); }
    void hold(Player p, cn.nukkit.item.Item it) { p.getInventory().setItem(0, it); p.getInventory().setHotbarSlotIndex(0, 0); p.getInventory().setHeldItemIndex(0); }

    @Override public void onEnable() {
        getServer().getScheduler().scheduleDelayedTask(() -> {
            try { gerente = fake("Gerente", true); ana = fake("Ana", false); bia = fake("Bia", false); } catch (Exception e) { errors.add(e.toString()); }
            plan(); next();
        }, 60);
    }

    void plan() {
        step(20, () -> {
            for (int x = 200; x <= 204; x++) for (int z = 200; z <= 204; z++) for (int y = 60; y <= 62; y++) put(x, y, z, 1);
            cn.nukkit.item.Item axe = cn.nukkit.item.Item.get(271, 0, 1);
            hold(gerente, axe);
            forget();
            cn.nukkit.block.Block a = lv().getBlock(new cn.nukkit.math.Vector3(200, 60, 200));
            cn.nukkit.event.block.BlockBreakEvent be = new cn.nukkit.event.block.BlockBreakEvent(gerente, a, axe);
            getServer().getPluginManager().callEvent(be);
            check(be.isCancelled() && id(200, 60, 200) == 1, "Varinha: quebrar nao quebra o bloco");
            check(saw(gerente, "Ponto 1: 200 60 200"), "Varinha: quebrar marca o ponto 1");
            cn.nukkit.block.Block b = lv().getBlock(new cn.nukkit.math.Vector3(204, 62, 204));
            cn.nukkit.event.player.PlayerInteractEvent ie = new cn.nukkit.event.player.PlayerInteractEvent(gerente, axe, b, 1, cn.nukkit.event.player.PlayerInteractEvent.RIGHT_CLICK_BLOCK);
            getServer().getPluginManager().callEvent(ie);
            check(ie.isCancelled() && saw(gerente, "Ponto 2: 204 62 204") && saw(gerente, "75 blocos"), "Varinha: tocar marca o ponto 2 (75 blocos)");
            // Jogador sem permissao com machado: nada acontece
            hold(ana, axe);
            cn.nukkit.event.block.BlockBreakEvent be2 = new cn.nukkit.event.block.BlockBreakEvent(ana, lv().getBlock(new cn.nukkit.math.Vector3(201, 60, 201)), axe);
            getServer().getPluginManager().callEvent(be2);
            check(!saw(ana, "Ponto 1"), "Machado de quem nao e admin continua machado");
            // Menu: item de blaze rod
            gerente.getInventory().clearAll();
            type(gerente, "/we varinha");
            boolean hasMenuItem = false, hasAxe = false;
            for (cn.nukkit.item.Item it : gerente.getInventory().getContents().values()) { if (it.getId() == 369 && it.hasCustomName()) hasMenuItem = true; if (it.getId() == 271) hasAxe = true; }
            check(hasMenuItem && hasAxe, "/we varinha entrega o machado e o menu");
            gerente.getInventory().addItem(cn.nukkit.item.Item.get(5, 0, 64), cn.nukkit.item.Item.get(20, 0, 64));
            try {
                java.lang.reflect.Method m = getServer().getPluginManager().getPlugin("GoldBuild").getClass().getDeclaredMethod("openTools", Player.class);
                m.setAccessible(true); m.invoke(getServer().getPluginManager().getPlugin("GoldBuild"), gerente);
            } catch (Exception e) { errors.add(e.toString()); }
        });
        step(15, () -> {
            try {
                check("Ferramentas do WE".equals(menuTitle(gerente)), "Menu do WE abre");
                menuClick(gerente, 10); // preencher
            } catch (Exception e) { errors.add(e.toString()); }
        });
        step(15, () -> {
            try {
                check("Preencher com qual bloco?".equals(menuTitle(gerente)), "Preencher pede o bloco");
                menuClick(gerente, 0); // primeiro bloco do inventario (madeira)
            } catch (Exception e) { errors.add(e.toString()); }
        });
        step(30, () -> {
            check(id(200, 60, 200) == 5 && id(204, 62, 204) == 5 && id(202, 61, 202) == 5, "Preencher pelo menu com o bloco escolhido");
            try {
                java.lang.reflect.Method m = getServer().getPluginManager().getPlugin("GoldBuild").getClass().getDeclaredMethod("openTools", Player.class);
                m.setAccessible(true); m.invoke(getServer().getPluginManager().getPlugin("GoldBuild"), gerente);
            } catch (Exception e) { errors.add(e.toString()); }
        });
        step(15, () -> { try { menuClick(gerente, 12); } catch (Exception e) { errors.add(e.toString()); } });
        step(15, () -> { try { check("Trocar QUAL bloco?".equals(menuTitle(gerente)), "Trocar pede o bloco de origem"); menuClick(gerente, 0); } catch (Exception e) { errors.add(e.toString()); } });
        step(15, () -> { try { check("Trocar PARA qual bloco?".equals(menuTitle(gerente)), "Trocar pede o bloco de destino"); menuClick(gerente, 1); } catch (Exception e) { errors.add(e.toString()); } });
        step(30, () -> {
            check(id(200, 60, 200) == 20 && id(203, 61, 203) == 20, "Trocar pelo menu (madeira por vidro)");
            forget();
            type(gerente, "/we undo");
        });
        step(30, () -> {
            check(id(200, 60, 200) == 5, "Desfazer volta a madeira");
            // Cosmetico sem saldo: confirmacao mostra saldo insuficiente e volta para a categoria
            try {
                Object cos = getServer().getPluginManager().getPlugin("GoldCosmetics");
                Class<?> cat = Class.forName("br.goldsuite.cosmetics.Catalog", true, cos.getClass().getClassLoader());
                Field all = cat.getDeclaredField("ALL"); all.setAccessible(true); Object c = ((Map<?, ?>) all.get(null)).get("pingente_coracao");
                java.lang.reflect.Method m = cos.getClass().getDeclaredMethod("confirm", Player.class, c.getClass());
                m.setAccessible(true); m.invoke(cos, bia, c);
            } catch (Exception e) { errors.add(e.toString()); }
        });
        step(15, () -> {
            try {
                check("Confirmar compra".equals(menuTitle(bia)), "Confirmacao abre para quem nao tem saldo");
                long before = money(bia);
                menuClick(bia, 11);
                check(money(bia) == before, "Sem saldo: nao cobra nada");
            } catch (Exception e) { errors.add(e.toString()); }
        });
        step(20, () -> {
            try { String t = menuTitle(bia); check(t != null && !t.equals("Confirmar compra"), "Sem saldo: volta para a categoria (" + t + ")"); } catch (Exception e) { errors.add(e.toString()); }
            forget();
            console("painel discord teste");
        });
        step(100, () -> {
            check(logHas("Nenhum webhook configurado"), "Teste do Discord sem webhook explica o que falta");
        });
    }
    boolean logHas(String s) { try { return new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("log.txt")), "UTF-8").contains(s); } catch (Exception e) { return false; } }
}
