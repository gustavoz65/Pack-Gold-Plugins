package teste;

import cn.nukkit.Player;
import cn.nukkit.block.Block;
import cn.nukkit.command.Command;
import cn.nukkit.entity.Entity;
import cn.nukkit.event.block.BlockBreakEvent;
import cn.nukkit.event.entity.EntityDamageByEntityEvent;
import cn.nukkit.event.entity.EntityDamageEvent;
import cn.nukkit.item.Item;
import cn.nukkit.level.Level;
import cn.nukkit.plugin.Plugin;
import cn.nukkit.plugin.PluginBase;
import java.lang.reflect.*;
import java.util.*;

/** Troca de janela pelo caminho real do cliente: clique = ContainerSetSlotPacket; conta fechamentos e aberturas. */
public final class TesteMenus extends PluginBase {
    final List<Runnable> steps = new ArrayList<>();
    final List<String> errors = new ArrayList<>();
    Player admin;

    void step(int delayTicks, Runnable r) { steps.add(() -> getServer().getScheduler().scheduleDelayedTask(() -> { try { r.run(); } catch (Throwable t) { errors.add(t.toString()); getLogger().error("passo", t); } next(); }, delayTicks)); }
    void next() { if (!steps.isEmpty()) { steps.remove(0).run(); } else { getLogger().info(errors.isEmpty() ? "MENUS_OK" : "MENUS_FALHOU " + errors); getLogger().info("FIM_TESTE"); } }
    void check(boolean ok, String msg) { if (!ok) { errors.add(msg); getLogger().error("FALHOU: " + msg); } else { getLogger().info("ok: " + msg); } }

    Player fake(String name, boolean op, long client, String ip, boolean online) throws Exception {
        cn.nukkit.network.SourceInterface si = (cn.nukkit.network.SourceInterface) Proxy.newProxyInstance(cn.nukkit.network.SourceInterface.class.getClassLoader(), new Class<?>[]{cn.nukkit.network.SourceInterface.class}, (proxy, m, args) -> {
            if (m.getReturnType() == Integer.class || m.getReturnType() == int.class) { return 0; }
            if (m.getReturnType() == boolean.class) { return false; }
            return null;
        });
        Player p = new Player(si, client, ip, 19133) {
            @Override public int dataPacket(cn.nukkit.network.protocol.DataPacket pk, boolean ack) {
                if (pk instanceof cn.nukkit.network.protocol.ContainerOpenPacket) { opens.merge(getName(), 1, Integer::sum); }
                if (pk instanceof cn.nukkit.network.protocol.ContainerClosePacket) { closes.merge(getName(), 1, Integer::sum); }
                return super.dataPacket(pk, ack);
            }
            @Override public boolean isOnline() { return online().containsValue(this); }
            @Override public boolean kick(cn.nukkit.event.player.PlayerKickEvent.Reason why, String reason, boolean admin) { getLogger().info("[" + getName() + "] KICK " + cn.nukkit.utils.TextFormat.clean(reason)); kicked.add(getName()); online().values().remove(this); return true; }
            @Override public boolean isConnected() { return true; }
            @Override public void sendMessage(String m) { getLogger().info("[" + getName() + "] " + cn.nukkit.utils.TextFormat.clean(m)); }
            @Override public boolean hasPermission(String perm) { return op || !(perm.contains("admin") || perm.contains("punir") || perm.contains("desbanir") || perm.contains("enquetes")); }
        };
        for (String f : new String[] {"username", "displayName", "iusername"}) { Field x = Player.class.getDeclaredField(f); x.setAccessible(true); x.set(p, name); }
        // Inicializa como o login real: dados padrao, entidade no mundo, inventario, voo e fome.
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
        Field rc = Player.class.getDeclaredField("randomClientId"); rc.setAccessible(true); rc.set(p, client);
        Field uu = cn.nukkit.entity.EntityHuman.class.getDeclaredField("uuid"); uu.setAccessible(true); uu.set(p, UUID.nameUUIDFromBytes(("aparelho" + client).getBytes()));
        Field perm = Player.class.getDeclaredField("perm"); perm.setAccessible(true); perm.set(p, new cn.nukkit.permission.PermissibleBase(p));
        p.spawned = true;
        if (!online) { return p; }
        online().put(UUID.randomUUID(), p);
        getServer().getPluginManager().callEvent(new cn.nukkit.event.player.PlayerJoinEvent(p, ""));
        return p;
    }

    void cmd(Player p, String plugin, String line) {
        Plugin pl = getServer().getPluginManager().getPlugin(plugin);
        String[] parts = line.split(" ");
        pl.onCommand(p, getServer().getCommandMap().getCommand(parts[0]), parts[0], Arrays.copyOfRange(parts, 1, parts.length));
    }

    void at(Player p, String world, double x, double y, double z) {
        Level lv = getServer().getLevelByName(world);
        p.level = lv; p.setPosition(new cn.nukkit.math.Vector3(x, y, z));
    }

    static Object field(Object o, String name) throws Exception { Field f = o.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(o); }
    Object game(String plugin, String arena) throws Exception { return ((Map<?, ?>) field(getServer().getPluginManager().getPlugin(plugin), "games")).get(arena); }
    String phase(String plugin, String arena) throws Exception { return String.valueOf(field(game(plugin, arena), "phase")); }


    final Set<String> kicked = new HashSet<>();
    @SuppressWarnings("unchecked") Map<UUID, Player> online() { try { Field f = cn.nukkit.Server.class.getDeclaredField("playerList"); f.setAccessible(true); return (Map<UUID, Player>) f.get(getServer()); } catch (Exception e) { throw new RuntimeException(e); } }
    Object menuClick(Player p, int slot) throws Exception {
        Object menus = getServer().getServiceManager().load(br.goldsuite.api.MenuService.class);
        Field f = menus.getClass().getDeclaredField("open"); f.setAccessible(true);
        Object menu = ((Map<?, ?>) f.get(menus)).get(p);
        if (menu == null) { throw new RuntimeException("nenhum menu aberto para " + p.getName()); }
        Field c = menu.getClass().getDeclaredField("onClick"); c.setAccessible(true);
        ((java.util.function.BiConsumer<Player, Integer>) c.get(menu)).accept(p, slot);
        return menu;
    }
    boolean menuOpen(Player p) throws Exception { Object menus = getServer().getServiceManager().load(br.goldsuite.api.MenuService.class); Field f = menus.getClass().getDeclaredField("open"); f.setAccessible(true); return ((Map<?, ?>) f.get(menus)).containsKey(p); }

    final Map<String, Integer> opens = new HashMap<>(), closes = new HashMap<>();
    Object engine() { return getServer().getServiceManager().load(br.goldsuite.api.MenuService.class); }
    Object menuOf(Player p) throws Exception { Field f = engine().getClass().getDeclaredField("open"); f.setAccessible(true); return ((Map<?, ?>) f.get(engine())).get(p); }
    int windowId(Player p) throws Exception { Object m = menuOf(p); return m == null ? -1 : p.getWindowId((cn.nukkit.inventory.Inventory) m); }
    Item slot(Player p, int i) throws Exception { return ((cn.nukkit.inventory.Inventory) menuOf(p)).getItem(i); }
    /** Clique como o cliente manda: o item sai do bau (slot vazio). */
    void tapReal(Player p, int slot) throws Exception {
        cn.nukkit.network.protocol.ContainerSetSlotPacket pk = new cn.nukkit.network.protocol.ContainerSetSlotPacket();
        pk.windowid = windowId(p); pk.slot = slot; pk.item = Item.get(0);
        getServer().getPluginManager().callEvent(new cn.nukkit.event.server.DataPacketReceiveEvent(p, pk));
    }
    int n(Map<String, Integer> m, Player p) { return m.getOrDefault(p.getName(), 0); }
    Player ana; final List<String> clicks = new ArrayList<>(); int window; int o0, c0; double holderA;
    @Override public void onEnable() {
        getServer().getScheduler().scheduleDelayedTask(() -> {
            try { ana = fake("ana", false, 22, "10.0.0.22", true); } catch (Exception e) { errors.add(e.toString()); }
            plan(); next();
        }, 60);
    }
    br.goldsuite.api.MenuService menus() { return (br.goldsuite.api.MenuService) engine(); }
    Map<Integer, Item> icons(int slot, int id) { Map<Integer, Item> m = new HashMap<>(); m.put(slot, Item.get(id)); return m; }
    void plan() {
        // Boas-vindas abre sozinha; passa de pagina pelo pacote real.
        step(90, () -> {
            try {
                check(windowId(ana) >= 0, "boas-vindas abriu de verdade (janela no cliente)");
                window = windowId(ana); o0 = n(opens, ana); c0 = n(closes, ana);
                tapReal(ana, 26);
            } catch (Exception e) { errors.add(e.toString()); }
        });
        step(5, () -> {
            try {
                check(windowId(ana) == window && n(opens, ana) == o0 && n(closes, ana) == c0, "boas-vindas: proxima pagina na mesma janela, sem fechar e reabrir");
                check(slot(ana, 18).getId() == 262, "boas-vindas: pagina 2 tem a seta de voltar");
                tapReal(ana, 13);
            } catch (Exception e) { errors.add(e.toString()); }
        });
        step(5, () -> {
            try {
                check(windowId(ana) == window, "boas-vindas: tocar no livro nao fecha");
                tapReal(ana, 18);
            } catch (Exception e) { errors.add(e.toString()); }
        });
        step(5, () -> { try { check(windowId(ana) == window && slot(ana, 18).getId() == 0 && slot(ana, 26).getId() == 262, "boas-vindas: voltou para a pagina 1"); menus().close(ana); } catch (Exception e) { errors.add(e.toString()); } });
        // /hgkits: proxima pagina e voltar.
        step(15, () -> { cmd(ana, "GoldHG", "hgkits"); });
        step(15, () -> {
            try {
                check(windowId(ana) >= 0 && slot(ana, 26).getId() == 262, "hgkits abriu com a seta de proxima");
                window = windowId(ana); o0 = n(opens, ana); c0 = n(closes, ana);
                Item first = slot(ana, 0); clicks.add(first.getName());
                tapReal(ana, 26);
            } catch (Exception e) { errors.add(e.toString()); }
        });
        step(5, () -> {
            try {
                check(windowId(ana) == window && n(opens, ana) == o0 && n(closes, ana) == c0, "hgkits: proxima pagina na mesma janela");
                check(!slot(ana, 0).getName().equals(clicks.get(0)) && slot(ana, 21).getId() == 262, "hgkits: mostra os kits da pagina 2");
                check(slot(ana, 26).getId() != 262 || slot(ana, 26).getName().contains("/"), "hgkits: numero da pagina na seta");
                tapReal(ana, 21);
            } catch (Exception e) { errors.add(e.toString()); }
        });
        step(5, () -> {
            try {
                check(windowId(ana) == window && slot(ana, 0).getName().equals(clicks.get(0)), "hgkits: voltar para a pagina 1");
                tapReal(ana, 0);
            } catch (Exception e) { errors.add(e.toString()); }
        });
        step(5, () -> { try { check(menuOf(ana) == null, "hgkits: escolher um kit fecha a janela"); } catch (Exception e) { errors.add(e.toString()); } });
        // Menu que abre outro so depois (ex.: depois de gravar no banco): fecha e abre outro bau em outra posicao.
        step(15, () -> {
            menus().open(ana, "A", icons(0, 1), (who, s) -> getServer().getScheduler().scheduleDelayedTask(() -> menus().open(who, "B", icons(4, 2), (w2, s2) -> clicks.add("B" + s2)), 2));
        });
        step(10, () -> {
            try {
                Object m = menuOf(ana); Field h = m.getClass().getDeclaredField("holder"); h.setAccessible(true); holderA = ((cn.nukkit.math.Vector3) h.get(m)).y;
                tapReal(ana, 0);
            } catch (Exception e) { errors.add(e.toString()); }
        });
        step(20, () -> {
            try {
                Object m = menuOf(ana); Field h = m.getClass().getDeclaredField("holder"); h.setAccessible(true);
                check(windowId(ana) >= 0 && slot(ana, 4).getId() == 2, "menu aberto depois: abre a janela nova");
                check(((cn.nukkit.math.Vector3) h.get(m)).y != holderA, "menu aberto depois: bau novo em outra altura (" + holderA + " -> " + ((cn.nukkit.math.Vector3) h.get(m)).y + ")");
                tapReal(ana, 4);
            } catch (Exception e) { errors.add(e.toString()); }
        });
        step(5, () -> { try { check(clicks.contains("B4") && menuOf(ana) == null, "menu aberto depois: clique funciona e fecha"); } catch (Exception e) { errors.add(e.toString()); } });
        // Clique duplo rapido nao executa duas vezes.
        step(15, () -> { menus().open(ana, "C", icons(0, 1), (who, s) -> clicks.add("C")); });
        step(10, () -> { try { tapReal(ana, 0); tapReal(ana, 0); } catch (Exception e) { errors.add(e.toString()); } });
        step(5, () -> { long c = clicks.stream().filter("C"::equals).count(); check(c == 1, "clique duplo conta uma vez (" + c + ")"); });
    }
}
