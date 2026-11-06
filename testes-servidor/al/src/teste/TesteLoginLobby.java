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

/** Antes do login: anda no lobby, qualquer acao pede login e so o menu de boas-vindas abre. */
public final class TesteLoginLobby extends PluginBase {
    final List<Runnable> steps = new ArrayList<>();
    final List<String> errors = new ArrayList<>();
    Player admin;

    void step(int delayTicks, Runnable r) { steps.add(() -> getServer().getScheduler().scheduleDelayedTask(() -> { try { r.run(); } catch (Throwable t) { errors.add(t.toString()); getLogger().error("passo", t); } next(); }, delayTicks)); }
    void next() { if (!steps.isEmpty()) { steps.remove(0).run(); } else { getLogger().info(errors.isEmpty() ? "LOGINLOBBY_OK" : "LOGINLOBBY_FALHOU " + errors); getLogger().info("FIM_TESTE"); } }
    void check(boolean ok, String msg) { if (!ok) { errors.add(msg); getLogger().error("FALHOU: " + msg); } else { getLogger().info("ok: " + msg); } }

    Player fake(String name, boolean op, long client, String ip, boolean online) throws Exception {
        cn.nukkit.network.SourceInterface si = (cn.nukkit.network.SourceInterface) Proxy.newProxyInstance(cn.nukkit.network.SourceInterface.class.getClassLoader(), new Class<?>[]{cn.nukkit.network.SourceInterface.class}, (proxy, m, args) -> {
            if (m.getReturnType() == Integer.class || m.getReturnType() == int.class) { return 0; }
            if (m.getReturnType() == boolean.class) { return false; }
            return null;
        });
        Player p = new Player(si, client, ip, 19133) {
            @Override public boolean isOnline() { return online().containsValue(this); }
            @Override public boolean kick(cn.nukkit.event.player.PlayerKickEvent.Reason why, String reason, boolean admin) { getLogger().info("[" + getName() + "] KICK " + cn.nukkit.utils.TextFormat.clean(reason)); kicked.add(getName()); online().values().remove(this); return true; }
            @Override public boolean isConnected() { return true; }
            @Override public void sendMessage(String m) { String c = cn.nukkit.utils.TextFormat.clean(m); said.computeIfAbsent(getName(), k -> new ArrayList<>()).add(c); getLogger().info("[" + getName() + "] " + c); }
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

    final Map<String, List<String>> said = new HashMap<>();
    boolean heard(Player p, String part) { for (String s : said.getOrDefault(p.getName(), Collections.emptyList())) { if (s.contains(part)) { return true; } } return false; }
    br.goldsuite.api.AuthService auth() { return getServer().getServiceManager().load(br.goldsuite.api.AuthService.class); }
    Player ana;
    @Override public void onEnable() {
        getServer().getScheduler().scheduleDelayedTask(() -> {
            try { ana = fake("ana", false, 22, "10.0.0.22", true); } catch (Exception e) { errors.add(e.toString()); }
            plan(); next();
        }, 60);
    }
    void plan() {
        step(60, () -> {
            try {
                check(!auth().isAuthenticated(ana), "comeca sem login");
                check(menuOpen(ana), "boas-vindas abre antes do login");
                menuClick(ana, 26);
            } catch (Exception e) { errors.add(e.toString()); }
        });
        step(20, () -> {
            try {
                check(menuOpen(ana), "boas-vindas: pagina 2 abre antes do login");
                said.clear(); menuClick(ana, 22);
            } catch (Exception e) { errors.add(e.toString()); }
        });
        step(20, () -> {
            check(heard(ana, "/registrar"), "boas-vindas: botao de suporte pede para criar a conta");
            Level lobby = getServer().getDefaultLevel();
            cn.nukkit.level.Location from = ana.getLocation(), to = from.clone(); to.x += 2;
            cn.nukkit.event.player.PlayerMoveEvent mv = new cn.nukkit.event.player.PlayerMoveEvent(ana, from, to);
            getServer().getPluginManager().callEvent(mv);
            check(!mv.isCancelled(), "anda livre no lobby sem login");
            cn.nukkit.level.Location fall = from.clone(); fall.y = -5;
            cn.nukkit.event.player.PlayerMoveEvent mv2 = new cn.nukkit.event.player.PlayerMoveEvent(ana, from, fall);
            getServer().getPluginManager().callEvent(mv2);
            check(mv2.isCancelled(), "cair no vazio sem login volta ao spawn");
        });
        step(50, () -> {
            Level lobby = getServer().getDefaultLevel();
            said.clear();
            cn.nukkit.event.player.PlayerInteractEvent it = new cn.nukkit.event.player.PlayerInteractEvent(ana, Item.get(0), lobby.getBlock(lobby.getSafeSpawn().add(0, -1, 0)), 1, 1);
            getServer().getPluginManager().callEvent(it);
            check(it.isCancelled() && heard(ana, "Crie sua conta"), "tocar em algo pede registro");
        });
        step(50, () -> {
            said.clear();
            cn.nukkit.event.player.PlayerCommandPreprocessEvent cmd = new cn.nukkit.event.player.PlayerCommandPreprocessEvent(ana, "/sw entrar");
            getServer().getPluginManager().callEvent(cmd);
            check(cmd.isCancelled() && heard(ana, "/registrar"), "comando de jogo pede registro");
            br.goldsuite.api.MenuService menus = getServer().getServiceManager().load(br.goldsuite.api.MenuService.class);
            menus.close(ana);
            Map<Integer, Item> icons = new HashMap<>(); icons.put(0, Item.get(1));
            menus.open(ana, "Loja", icons, (w, s) -> errors.add("menu de outro plugin clicado sem login"));
        });
        step(10, () -> {
            try { check(!menuOpen(ana), "outro menu nao abre sem login"); } catch (Exception e) { errors.add(e.toString()); }
            auth().register(ana, "senha123".toCharArray());
        });
        step(40, () -> {
            check(auth().isAuthenticated(ana), "registro feito");
            said.clear();
            cn.nukkit.event.player.PlayerCommandPreprocessEvent cmd = new cn.nukkit.event.player.PlayerCommandPreprocessEvent(ana, "/sw");
            getServer().getPluginManager().callEvent(cmd);
            check(!cmd.isCancelled(), "depois do login comandos liberados");
            getServer().getScheduler().scheduleDelayedTask(() -> {}, 1);
        });
        step(60, () -> { try { check(!menuOpen(ana), "boas-vindas nao reabre depois do login"); } catch (Exception e) { errors.add(e.toString()); } });
    }
}
