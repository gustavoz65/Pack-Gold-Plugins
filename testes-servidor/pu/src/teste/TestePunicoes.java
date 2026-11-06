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

/** Punicoes (advertencias, ban por conta/aparelho/IP, identidade offline, desban), boas-vindas e enquetes. */
public final class TestePunicoes extends PluginBase {
    final List<Runnable> steps = new ArrayList<>();
    final List<String> errors = new ArrayList<>();
    Player admin;

    void step(int delayTicks, Runnable r) { steps.add(() -> getServer().getScheduler().scheduleDelayedTask(() -> { try { r.run(); } catch (Throwable t) { errors.add(t.toString()); getLogger().error("passo", t); } next(); }, delayTicks)); }
    void next() { if (!steps.isEmpty()) { steps.remove(0).run(); } else { getLogger().info(errors.isEmpty() ? "PUNICOES_OK" : "PUNICOES_FALHOU " + errors); getLogger().info("FIM_TESTE"); } }
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
    Object pun() throws Exception { return field(getServer().getPluginManager().getPlugin("GoldStaff"), "punishments"); }
    boolean blockedAtLogin(String name, long client, String ip) throws Exception {
        Player p = fake(name, false, client, ip, false);
        cn.nukkit.event.player.PlayerPreLoginEvent e = new cn.nukkit.event.player.PlayerPreLoginEvent(p, "");
        getServer().getPluginManager().callEvent(e);
        if (e.isCancelled()) { getLogger().info("login " + name + " bloqueado: " + cn.nukkit.utils.TextFormat.clean(e.getKickMessage())); }
        return e.isCancelled();
    }
    Object record(String name) throws Exception { Object store = field(pun(), "records"); Method m = store.getClass().getMethod("peek", String.class); return m.invoke(store, name); }
    int warnings(String name) throws Exception { return ((Number) field(record(name), "warnings")).intValue(); }
    Object polls() throws Exception { return field(getServer().getPluginManager().getPlugin("GoldChat"), "polls"); }
    Object poll(String id) throws Exception { Object st = field(polls(), "state"); Method r = st.getClass().getMethod("read", java.util.function.Function.class); return r.invoke(st, (java.util.function.Function<Object, Object>) s -> { try { return ((Map<?, ?>) field(s, "polls")).get(id); } catch (Exception e) { throw new RuntimeException(e); } }); }

    Player ana, bia;
    @Override public void onEnable() {
        getServer().getScheduler().scheduleDelayedTask(() -> {
            try {
                admin = fake("gerente", true, 1, "10.0.0.1", true);
                ana = fake("ana", false, 22, "10.0.0.22", true);
                bia = fake("bia", false, 33, "10.0.0.33", true);
            } catch (Exception e) { errors.add(e.toString()); }
            plan(); next();
        }, 60);
    }

    void plan() {
        // ---------- boas-vindas ----------
        step(60, () -> {
            try {
                check(menuOpen(ana), "boas-vindas: menu aberto ao entrar");
                menuClick(ana, 26);
            } catch (Exception e) { errors.add(e.toString()); }
        });
        step(20, () -> { try { check(menuOpen(ana), "boas-vindas: pagina 2 abriu"); cmd(admin, "GoldStaff", "welcomebook reload"); cmd(ana, "GoldStaff", "welcomebook reload"); } catch (Exception e) { errors.add(e.toString()); } });
        // ---------- punicoes ----------
        step(10, () -> {
            check(!kicked.contains("ana"), "inicio: ana online");
            for (int i = 0; i < 2; i++) { cmd(admin, "GoldStaff", "advertir ana spam no chat"); }
            cmd(bia, "GoldStaff", "advertir ana sem permissao");
        });
        step(20, () -> {
            try {
                check(warnings("ana") == 2 && !kicked.contains("ana"), "advertencias: 2 sem ban e jogador comum nao pune (" + warnings("ana") + ")");
                cmd(admin, "GoldStaff", "advertir ana terceira");
            } catch (Exception e) { errors.add(e.toString()); }
        });
        step(20, () -> {
            try {
                check(kicked.contains("ana"), "advertencias: 3a da ban temporario e expulsa");
                check(blockedAtLogin("ana", 22, "10.0.0.22"), "ban temporario: conta bloqueada");
                check(blockedAtLogin("ana2", 22, "10.9.9.9"), "ban temporario: mesmo aparelho com outra conta bloqueado");
                check(!blockedAtLogin("vizinho", 77, "10.0.0.22"), "ban temporario: mesmo IP com outro aparelho liberado");
                cmd(admin, "GoldStaff", "advertir ana quarta");
            } catch (Exception e) { errors.add(e.toString()); }
        });
        step(20, () -> {
            try {
                check(((Boolean) field(record("ana"), "permanent")), "advertencias: 4a da ban permanente");
                check(blockedAtLogin("vizinho", 77, "10.0.0.22"), "ban permanente: mesmo IP bloqueado");
                cmd(admin, "GoldStaff", "advertencias ana");
                cmd(admin, "GoldStaff", "desbanir ana contestacao aceita no discord");
            } catch (Exception e) { errors.add(e.toString()); }
        });
        step(20, () -> {
            try {
                check(warnings("ana") == 0 && !blockedAtLogin("ana", 22, "10.0.0.22") && !blockedAtLogin("vizinho", 77, "10.0.0.22"), "desbanir: libera conta, aparelho e IP e zera advertencias");
                // Bia sai do servidor; o ban offline usa o IP e o aparelho do ultimo login dela.
                online().values().remove(bia);
                cmd(admin, "GoldStaff", "banir bia hack");
            } catch (Exception e) { errors.add(e.toString()); }
        });
        step(20, () -> {
            try {
                check(blockedAtLogin("bia_alt", 33, "10.8.8.8"), "ban offline: aparelho conhecido da conta bloqueado");
                check(blockedAtLogin("bia_alt2", 99, "10.0.0.33"), "ban offline: IP conhecido da conta bloqueado");
                Object ids = field(pun(), "identities");
                check((Integer) ids.getClass().getMethod("cachedRecords").invoke(ids) == 0, "identidade: nada fica na memoria");
            } catch (Exception e) { errors.add(e.toString()); }
        });
        // ---------- enquetes ----------
        step(5, () -> {
            try { ana = fake("ana", false, 22, "10.0.0.22", true); bia = fake("carol", false, 44, "10.0.0.44", true); } catch (Exception e) { errors.add(e.toString()); }
            cmd(admin, "GoldChat", "enquete criar mapa 5 Qual mapa? | Ilhas | Deserto | Gelo");
            cmd(ana, "GoldChat", "enquete criar outra 5 X | A | B");
        });
        step(20, () -> {
            try {
                check(poll("mapa") != null && poll("outra") == null, "enquete: admin cria e jogador comum nao");
                cmd(ana, "GoldChat", "entrar mapa 2"); cmd(ana, "GoldChat", "entrar mapa 1");
                cmd(bia, "GoldChat", "entrar mapa 2");
            } catch (Exception e) { errors.add(e.toString()); }
        });
        step(20, () -> {
            try {
                int[] counts = (int[]) field(poll("mapa"), "counts");
                check(counts[0] == 0 && counts[1] == 2 && counts[2] == 0, "enquete: um voto por conta, mesmo com dois cliques seguidos (" + Arrays.toString(counts) + ")");
                cmd(ana, "GoldChat", "entrar mapa 1");
                cmd(admin, "GoldChat", "enquete encerrar mapa");
            } catch (Exception e) { errors.add(e.toString()); }
        });
        step(260, () -> {
            try {
                int[] counts = (int[]) field(poll("mapa"), "counts");
                check(counts[1] == 2 && counts[0] == 0, "enquete: voto repetido depois recusado");
                check((Boolean) field(poll("mapa"), "announced"), "enquete: resultado anunciado ao encerrar");
            } catch (Exception e) { errors.add(e.toString()); }
        });
    }
}
