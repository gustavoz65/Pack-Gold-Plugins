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
public final class TesteParty extends PluginBase {
    final List<Runnable> steps = new ArrayList<>();
    final List<String> errors = new ArrayList<>();
    final Map<String, List<String>> got = new ConcurrentHashMap<>();
    final List<String> requests = Collections.synchronizedList(new ArrayList<>());
    Player gerente, mod, adm, ana, bia, carlos;
    HttpServer groq;
    int port;

    void step(int delayTicks, Runnable r) { steps.add(() -> getServer().getScheduler().scheduleDelayedTask(() -> { try { r.run(); } catch (Throwable t) { errors.add(t.toString()); getLogger().error("passo", t); } next(); }, delayTicks)); }
    void next() { if (!steps.isEmpty()) { steps.remove(0).run(); } else { getLogger().info(errors.isEmpty() ? "PARTY_OK" : "PARTY_FALHOU " + errors); getLogger().info("FIM_TESTE");  } }
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

    Player d1, d2, d3, d4;
    final Set<String> tested = new HashSet<>();
    double minTps = 20;

    static Object f(Object o, String name) { try { Class<?> c = o.getClass(); while (c != null) { try { Field x = c.getDeclaredField(name); x.setAccessible(true); return x.get(o); } catch (NoSuchFieldException e) { c = c.getSuperclass(); } } } catch (Exception e) { throw new RuntimeException(e); } throw new RuntimeException("campo " + name); }
    static void setf(Object o, String name, Object v) { try { Class<?> c = o.getClass(); while (c != null) { try { Field x = c.getDeclaredField(name); x.setAccessible(true); x.set(o, v); return; } catch (NoSuchFieldException e) { c = c.getSuperclass(); } } } catch (Exception e) { throw new RuntimeException(e); } }
    Object party() { return getServer().getPluginManager().getPlugin("GoldParty"); }
    List<?> rooms() { return (List<?>) f(party(), "rooms"); }
    Object room(Player p) { return ((Map<?, ?>) f(party(), "byPlayer")).get(p); }
    String phase(Object room) { return String.valueOf(f(room, "phase")); }
    Object mini(Object room) { return f(room, "mini"); }
    boolean alive(Object room, Player p) { return ((List<?>) f(room, "players")).contains(p) && !((Set<?>) f(room, "spectators")).contains(p); }
    String miniId(Object m) { try { java.lang.reflect.Method x = m.getClass().getDeclaredMethod("id"); x.setAccessible(true); return (String) x.invoke(m); } catch (Exception e) { throw new RuntimeException(e); } }
    void interactAir(Player p) { getServer().getPluginManager().callEvent(new cn.nukkit.event.player.PlayerInteractEvent(p, p.getInventory().getItemInHand(), new cn.nukkit.math.Vector3(0, 0, 0), 0, cn.nukkit.event.player.PlayerInteractEvent.RIGHT_CLICK_AIR)); }
    int ox(Object m) { return (int) f(m, "ox"); }
    int oz(Object m) { return (int) f(m, "oz"); }
    Player[] all() { return new Player[] {d1, d2, d3}; }

    @Override public void onEnable() {
        getServer().getScheduler().scheduleDelayedTask(() -> {
            try {
                console("painel set GoldParty contagem 5");
                console("painel set GoldParty provas_por_partida 10");
                d1 = fake("Dan", false); d2 = fake("Eva", false); d3 = fake("Fabi", false); d4 = fake("Gil", false);
            } catch (Exception e) { errors.add(e.toString()); getLogger().error("setup", e); }
            plan();
            next();
        }, 60);
    }

    void plan() {
        step(20, () -> {
            type(d1, "/party entrar");
            type(d2, "/party entrar");
            check(rooms().size() == 1 && phase(room(d1)).equals("ESPERANDO"), "Sala criada em espera");
            type(d3, "/party entrar");
            check(((List<?>) f(room(d1), "players")).size() == 3 && d1.level.getFolderName().equals("party"), "Tres jogadores na sala do Party");
        });
        // A partida comeca sozinha; a cada meio segundo o teste age conforme a prova em andamento.
        steps.add(() -> getServer().getScheduler().scheduleDelayedTask(this::drive, 10));
    }

    long lastMiniAt;
    Object lastMini;
    int stepInMini;

    void drive() {
        try {
            minTps = Math.min(minTps, getServer().getTicksPerSecond());
            Object r = room(d1);
            if (r == null) { finishAll(); return; }
            String ph = phase(r);
            if (ph.equals("JOGANDO")) {
                Object m = mini(r);
                if (m != lastMini) { lastMini = m; stepInMini = 0; }
                stepInMini++;
                act(r, m, miniId(m));
            }
            if (ph.equals("JOGANDO") && rooms().size() == 1 && !tested.contains("sala2")) {
                tested.add("sala2");
                type(d4, "/party entrar");
                Object r2 = room(d4);
                check(rooms().size() == 2 && r2 != r && (int) f(r2, "slot") != (int) f(r, "slot") && phase(r2).equals("ESPERANDO"), "Segunda sala abre na outra vaga enquanto a primeira joga");
            }
        } catch (Throwable t) { errors.add(t.toString()); getLogger().error("drive", t); }
        getServer().getScheduler().scheduleDelayedTask(this::drive, 10);
    }

    void fastForward(Object m) { int secs; try { java.lang.reflect.Method x = m.getClass().getDeclaredMethod("seconds"); x.setAccessible(true); secs = (int) x.invoke(m); } catch (Exception e) { throw new RuntimeException(e); } setf(m, "elapsedTicks", secs * 20 - 2); }

    void act(Object r, Object m, String id) {
        int ox = ox(m), oz = oz(m);
        tested.add(id);
        cn.nukkit.level.Level lv = d1.level;
        switch (id) {
            case "corrida_mortal": case "corrida_kangaroo": {
                if (stepInMini == 2) {
                    d1.teleport(new cn.nukkit.math.Vector3(ox + 48.5, findGold(lv, ox, oz) + 1, oz + 89.5));
                    if (id.equals("corrida_kangaroo")) { d3.getInventory().setHeldItemIndex(0); interactAir(d3); check(d3.motionY > 0, id + ": o item Kangaroo da o impulso"); }
                }
                if (stepInMini == 3) {
                    check(!alive(r, d1) && ((Map<?, ?>) f(m, "finishedAt")).containsKey(d1), id + ": chegada no ouro termina a corrida do jogador");
                    d2.teleport(new cn.nukkit.math.Vector3(ox + 48, 20, oz + 30));
                }
                if (stepInMini == 4) { check(alive(r, d2) && d2.y > 55, id + ": quem cai volta ao checkpoint, sem ser eliminado"); fastForward(m); tested.add(id); }
                return;
            }
            case "corrida_grappler": {
                if (stepInMini == 2) { d1.teleport(new cn.nukkit.math.Vector3(ox + 45.5, 70, oz + 45.5)); d1.teleport(new cn.nukkit.level.Location(ox + 45.5, 70, oz + 45.5, 0, -80, d1.level)); d1.motionY = 0; d1.getInventory().setHeldItemIndex(0); interactAir(d1); check(d1.motionY > 0, "corrida_grappler: a vara puxa o jogador para cima"); }
                if (stepInMini == 3) { d2.teleport(new cn.nukkit.math.Vector3(ox + 45.5, 118, oz + 45.5)); }
                if (stepInMini == 4) { check(!alive(r, d2), "corrida_grappler: chegar ao topo termina a corrida"); fastForward(m); tested.add(id); }
                return;
            }
            case "color_floor": {
                if (stepInMini == 16) {
                    int wool = 0;
                    for (int x = 36; x <= 59; x++) { for (int z = 36; z <= 59; z++) { if (lv.getBlockIdAt(ox + x, 64, oz + z) == 35) { wool++; } } }
                    check(d1.getInventory().getItem(0).getId() == 35 && wool > 0 && wool < 576, "color_floor: cor na hotbar e o resto do chao some (" + wool + " blocos)");
                    d3.teleport(new cn.nukkit.math.Vector3(ox + 48, 30, oz + 48));
                }
                if (stepInMini == 17) { check(!alive(r, d3), "color_floor: quem cai e eliminado"); d2.teleport(new cn.nukkit.math.Vector3(ox + 48, 30, oz + 48)); }
                if (stepInMini == 18) { check(!phase(r).equals("JOGANDO") || mini(r) != m, "color_floor: acaba quando sobra um"); tested.add(id); }
                return;
            }
            case "tnt_run": {
                if (stepInMini == 6) { d1.teleport(new cn.nukkit.math.Vector3(ox + 40.5, 81, oz + 40.5)); }
                if (stepInMini == 8) {
                    check(lv.getBlockIdAt(ox + 40, 80, oz + 40) == 0, "tnt_run: o bloco pisado some");
                    d2.teleport(new cn.nukkit.math.Vector3(ox + 48, 30, oz + 48)); d3.teleport(new cn.nukkit.math.Vector3(ox + 48, 30, oz + 48));
                }
                if (stepInMini == 9) { check(!phase(r).equals("JOGANDO") || mini(r) != m, "tnt_run: ultimo vivo vence"); tested.add(id); }
                return;
            }
            case "pvp_mlg": case "sumo": case "fisherman": {
                if (stepInMini == 2) {
                    float before = d2.getHealth();
                    d2.attack(new cn.nukkit.event.entity.EntityDamageByEntityEvent(d1, d2, cn.nukkit.event.entity.EntityDamageEvent.CAUSE_ENTITY_ATTACK, 3));
                    if (id.equals("pvp_mlg")) { check(d2.getHealth() < before, "pvp_mlg: tem dano"); }
                    else { check(d2.getHealth() == before, id + ": so empurrao, sem dano"); }
                    if (id.equals("fisherman")) {
                        d1.teleport(new cn.nukkit.math.Vector3(d3.x - 6 * 0, d3.y, d3.z - 6)); d1.yaw = 0; d1.pitch = 0;
                        d1.teleport(new cn.nukkit.level.Location(d3.x, d3.y, d3.z - 6, 0, 0, d3.level));
                        d3.motionX = d3.motionY = d3.motionZ = 0;
                        d1.getInventory().setHeldItemIndex(0); interactAir(d1);
                        check(d3.motionY > 0, "fisherman: a vara puxa o adversario");
                    }
                }
                if (stepInMini == 3) {
                    d2.attack(new cn.nukkit.event.entity.EntityDamageByEntityEvent(d1, d2, cn.nukkit.event.entity.EntityDamageEvent.CAUSE_ENTITY_ATTACK, 1));
                    d2.teleport(new cn.nukkit.math.Vector3(d2.x, 10, d2.z));
                }
                if (stepInMini == 4) {
                    check(!alive(r, d2) && Integer.valueOf(1).equals(((Map<?, ?>) f(m, "score")).get(d1)), id + ": quem cai no vazio e eliminado e o abate vai para quem empurrou");
                    d3.teleport(new cn.nukkit.math.Vector3(d3.x, 10, d3.z));
                }
                if (stepInMini == 5) { check(!phase(r).equals("JOGANDO") || mini(r) != m, id + ": acaba com um vivo"); tested.add(id); }
                return;
            }
            case "water_drop": {
                if (stepInMini == 2) {
                    int px = (int) f(m, "poolX"), pz = (int) f(m, "poolZ");
                    d1.teleport(new cn.nukkit.math.Vector3(ox + px + 0.5, 63.2, oz + pz + 0.5));
                    d2.teleport(new cn.nukkit.math.Vector3(ox + 39.5, 64, oz + 39.5)); d2.onGround = true;
                    if (lv.getBlockIdAt(ox + 39, 63, oz + 39) == 9) { d2.teleport(new cn.nukkit.math.Vector3(ox + 57.5, 64, oz + 57.5)); }
                }
                if (stepInMini == 3) {
                    check(Integer.valueOf(1).equals(((Map<?, ?>) f(m, "score")).get(d1)) && !((Map<?, ?>) f(m, "score")).containsKey(d2), "water_drop: cair na agua pontua, no chao nao");
                    fastForward(m); tested.add(id);
                }
                return;
            }
            case "arqueiro": {
                if (stepInMini == 2) {
                    d2.attack(new cn.nukkit.event.entity.EntityDamageByEntityEvent(d1, d2, cn.nukkit.event.entity.EntityDamageEvent.CAUSE_ENTITY_ATTACK, 50));
                    check(alive(r, d2) && d2.getHealth() == d2.getMaxHealth() && Integer.valueOf(1).equals(((Map<?, ?>) f(m, "score")).get(d1)) && d1.getInventory().contains(cn.nukkit.item.Item.get(262, 0, 3)), "arqueiro: abate conta, da flecha e a vitima renasce");
                    fastForward(m); tested.add(id);
                }
                return;
            }
            default:
        }
    }

    int findGold(cn.nukkit.level.Level lv, int ox, int oz) { for (int y = 55; y < 80; y++) { if (lv.getBlockIdAt(ox + 48, y, oz + 89) == 41) { return y; } } return 64; }

    boolean done;
    void finishAll() {
        if (done) { return; }
        done = true;
        check(tested.size() >= 11, "Todas as 10 provas passaram pelo teste (" + tested + ")");
        check(saw(d1, "RANKING FINAL") && saw(d1, "venceu o Party"), "Ranking final e anuncio do campeao");
        br.goldsuite.api.EconomyService eco = getServer().getServiceManager().load(br.goldsuite.api.EconomyService.class);
        long sum = 0; for (Player p : all()) { sum += eco.balance(p.getName().toLowerCase(), br.goldsuite.api.EconomyService.Currency.MONEY); }
        check(sum > 0, "Premios pagos ao top 3");
        check(!d1.level.getFolderName().equals("party") && !d1.getAllowFlight(), "Todos voltam ao lobby sem voo");
        check(minTps >= 18, "TPS durante o Party: minimo " + minTps);
        next();
    }
}
