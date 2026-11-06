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
public final class TesteSkills extends PluginBase {
    final List<Runnable> steps = new ArrayList<>();
    final List<String> errors = new ArrayList<>();
    final Map<String, List<String>> got = new ConcurrentHashMap<>();
    final List<String> requests = Collections.synchronizedList(new ArrayList<>());
    Player gerente, mod, adm, ana, bia, carlos;
    HttpServer groq;
    int port;

    void step(int delayTicks, Runnable r) { steps.add(() -> getServer().getScheduler().scheduleDelayedTask(() -> { try { r.run(); } catch (Throwable t) { errors.add(t.toString()); getLogger().error("passo", t); } next(); }, delayTicks)); }
    void next() { if (!steps.isEmpty()) { steps.remove(0).run(); } else { getLogger().info(errors.isEmpty() ? "SKILLS_OK" : "SKILLS_FALHOU " + errors); getLogger().info("FIM_TESTE");  } }
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

    Object skills() { return getServer().getPluginManager().getPlugin("GoldSkills"); }
    int level(Player p, String skill) { try { Object st = ((Map<?, ?>) f(skills(), "states")).get(p); return ((int[]) f(st, "level"))[skillIdx(skill)]; } catch (Exception e) { throw new RuntimeException(e); } }
    int xp(Player p, String skill) { try { Object st = ((Map<?, ?>) f(skills(), "states")).get(p); return ((int[]) f(st, "xp"))[skillIdx(skill)]; } catch (Exception e) { throw new RuntimeException(e); } }
    static int skillIdx(String s) { String[] all = {"MINERACAO", "LENHADOR", "ESCAVACAO", "HERBALISMO", "ESPADAS", "MACHADO", "ARQUEIRO", "DESARMADO", "ACROBACIA", "REPARACAO"}; return Arrays.asList(all).indexOf(s); }
    static Object f(Object o, String name) throws Exception { Class<?> c = o.getClass(); while (c != null) { try { Field x = c.getDeclaredField(name); x.setAccessible(true); return x.get(o); } catch (NoSuchFieldException e) { c = c.getSuperclass(); } } throw new RuntimeException(name); }
    cn.nukkit.block.Block put(int x, int y, int z, int id, int meta) { cn.nukkit.level.Level lv = getServer().getDefaultLevel(); lv.setBlock(new cn.nukkit.math.Vector3(x, y, z), cn.nukkit.block.Block.get(id, meta), true, false); return lv.getBlock(new cn.nukkit.math.Vector3(x, y, z)); }
    boolean breakBlock(Player p, cn.nukkit.block.Block b, cn.nukkit.item.Item tool) { cn.nukkit.event.block.BlockBreakEvent e = new cn.nukkit.event.block.BlockBreakEvent(p, b, tool); getServer().getPluginManager().callEvent(e); return !e.isCancelled(); }

    @Override public void onEnable() {
        getServer().getScheduler().scheduleDelayedTask(() -> {
            try { gerente = fake("Gerente", true); ana = fake("Ana", false); bia = fake("Bia", false); } catch (Exception e) { errors.add(e.toString()); }
            plan(); next();
        }, 60);
    }

    void plan() {
        cn.nukkit.item.Item pick = cn.nukkit.item.Item.get(257, 0, 1);
        step(30, () -> {
            breakBlock(ana, put(100, 60, 100, 56, 0), pick);
            check(xp(ana, "MINERACAO") == 10, "Diamante com picareta da 10 XP de mineracao (5 x 2) (" + xp(ana, "MINERACAO") + ")");
            breakBlock(ana, put(101, 60, 100, 56, 0), cn.nukkit.item.Item.get(0));
            check(xp(ana, "MINERACAO") == 10, "Sem picareta nao da XP");
            cn.nukkit.block.Block placedOre = put(102, 60, 100, 56, 0);
            getServer().getPluginManager().callEvent(new cn.nukkit.event.block.BlockPlaceEvent(ana, placedOre, placedOre, placedOre, cn.nukkit.item.Item.get(56)));
            breakBlock(ana, placedOre, pick);
            check(xp(ana, "MINERACAO") == 10, "Bloco colocado por jogador nao da XP (anti-farm)");
            breakBlock(ana, put(103, 60, 100, 17, 3), cn.nukkit.item.Item.get(258));
            check(xp(ana, "LENHADOR") == 100, "Tronco da selva com machado: 100 XP");
            breakBlock(ana, put(104, 60, 100, 59, 7), cn.nukkit.item.Item.get(0));
            breakBlock(ana, put(105, 60, 100, 59, 2), cn.nukkit.item.Item.get(0));
            check(xp(ana, "HERBALISMO") == 500, "Trigo maduro da 50 x 10 e verde nao da (" + xp(ana, "HERBALISMO") + ")");
            ana.getInventory().setItem(0, cn.nukkit.item.Item.get(267)); ana.getInventory().setHotbarSlotIndex(0, 0); ana.getInventory().setHeldItemIndex(0);
            bia.setHealth(20);
            bia.attack(new cn.nukkit.event.entity.EntityDamageByEntityEvent(ana, bia, cn.nukkit.event.entity.EntityDamageEvent.CAUSE_ENTITY_ATTACK, 4));
            check(xp(ana, "ESPADAS") > 0, "Golpe de espada em jogador da XP de espadas (" + xp(ana, "ESPADAS") + ")");
            ana.attack(new cn.nukkit.event.entity.EntityDamageEvent(ana, cn.nukkit.event.entity.EntityDamageEvent.CAUSE_FALL, 3));
            check(xp(ana, "ACROBACIA") > 0, "Queda da XP de acrobacia");
            console("mmoedit Ana mineracao 49");
            console("darbooster Ana todas 2 10");
            check(saw(ana, "Booster 2x"), "Booster entregue");
        });
        step(10, () -> {
            forget();
            int before = xp(ana, "MINERACAO");
            for (int i = 0; i < 200; i++) { breakBlock(ana, put(110 + i % 10, 60, 110 + i / 10, 56, 0), pick); }
            check(level(ana, "MINERACAO") >= 50, "Sobe de nivel com XP suficiente (" + level(ana, "MINERACAO") + ")");
            check(saw(bia, "atingiu o nivel 50") && saw(bia, "Mineracao"), "Anuncio para todos ao chegar no nivel 50");
            
            // Reparo: picareta de ferro gasta + lingote, tocar no bloco de ferro.
            cn.nukkit.item.Item worn = cn.nukkit.item.Item.get(257, 200, 1);
            ana.getInventory().setItem(0, worn); ana.getInventory().setItem(5, cn.nukkit.item.Item.get(265, 0, 2));
            cn.nukkit.block.Block anvil = put(130, 60, 130, 42, 0);
            getServer().getPluginManager().callEvent(new cn.nukkit.event.player.PlayerInteractEvent(ana, worn, anvil, 1, cn.nukkit.event.player.PlayerInteractEvent.RIGHT_CLICK_BLOCK));
            check(ana.getInventory().getItemInHand().getDamage() < 200 && xp(ana, "REPARACAO") > 0 && ana.getInventory().getItem(5).getCount() == 1, "Reparo na bigorna de ferro gasta um lingote e da XP");
            forget();
            type(ana, "/mctop mineracao");
            check(saw(ana, "Top Mineracao") && saw(ana, "1. ana"), "/mctop mostra o ranking");
            br.goldsuite.api.PlaceholderService ph = getServer().getServiceManager().load(br.goldsuite.api.PlaceholderService.class);
            check(Integer.parseInt(ph.apply(ana, "{poder}")) >= 50 && ph.apply(ana, "{nivel_mineracao}").equals(String.valueOf(level(ana, "MINERACAO"))), "Placeholders {poder} e {nivel_*}");
            try { java.lang.reflect.Method m = skills().getClass().getDeclaredMethod("refreshTop"); m.setAccessible(true); m.invoke(skills()); } catch (Exception e) { errors.add(e.toString()); }
            check(!ph.apply(ana, "{mctop}").isEmpty() && ph.apply(bia, "{mctop}").isEmpty(), "Tag do top 1 so para o primeiro");
            forget();
            type(ana, "bom dia");
            check(saw(bia, "Ana: bom dia") && saw(bia, "\u2692"), "Tag do top 1 aparece no chat");
        });
        // ---------- zonas controlando os plugins ----------
        step(30, () -> {
            String w = getServer().getDefaultLevel().getFolderName();
            console("zona criar rankup");
            console("zona mundo rankup " + w);
            console("zona plugin rankup GoldFactions");
            console("zona recurso rankup goldskills.xp");
            console("zona hud rankup 1 &aRANKUP &f{money}");
        });
        step(30, () -> {
            int before = xp(bia, "MINERACAO");
            breakBlock(bia, put(140, 60, 140, 56, 0), pick);
            check(xp(bia, "MINERACAO") == before, "Zona sem goldskills.xp nao da XP");
            forget();
            check(blocked(bia, "/f criar teste"), "Plugin desligado na zona: comandos dele bloqueados");
            check(!blocked(bia, "/suporte"), "Comandos sempre liberados continuam");
            blocked(bia, "/help");
            check(!saw(bia, "/f ") && saw(bia, "/booster"), "/help sem os comandos do plugin desligado");
            forget();
        });
        step(30, () -> {
            check(saw(bia, "RANKUP"), "Barra propria da zona");
        });
    }
}
