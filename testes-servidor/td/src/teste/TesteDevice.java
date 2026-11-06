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
public final class TesteDevice extends PluginBase {
    final List<Runnable> steps = new ArrayList<>();
    final List<String> errors = new ArrayList<>();
    final Map<String, List<String>> got = new ConcurrentHashMap<>();
    final List<String> requests = Collections.synchronizedList(new ArrayList<>());
    Player gerente, mod, adm, ana, bia, carlos;
    HttpServer groq;
    int port;

    void step(int delayTicks, Runnable r) { steps.add(() -> getServer().getScheduler().scheduleDelayedTask(() -> { try { r.run(); } catch (Throwable t) { errors.add(t.toString()); getLogger().error("passo", t); } next(); }, delayTicks)); }
    void next() { if (!steps.isEmpty()) { steps.remove(0).run(); } else { getLogger().info(errors.isEmpty() ? "DEVICE_OK" : "DEVICE_FALHOU " + errors); getLogger().info("FIM_TESTE");  } }
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

    Player pc, cel;
    final Random rnd = new Random(3);

    @Override public void onEnable() {
        getServer().getScheduler().scheduleDelayedTask(() -> {
            try { pc = fake("Pcplayer", false); cel = fake("Celular", false); } catch (Exception e) { errors.add(e.toString()); }
            plan(); next();
        }, 60);
    }

    /** Clique num bloco: o ponto e o olho + direcao * 4 (PC) ou fora do centro da tela (celular). */
    void click(Player p, boolean centered) {
        p.yaw = rnd.nextInt(360); p.pitch = 20 + rnd.nextInt(40);
        double y = Math.toRadians(p.yaw), pt = Math.toRadians(p.pitch);
        double dx = -Math.sin(y) * Math.cos(pt), dy = -Math.sin(pt), dz = Math.cos(y) * Math.cos(pt);
        if (!centered) { double off = Math.toRadians(15 + rnd.nextInt(25)) * (rnd.nextBoolean() ? 1 : -1); double ny = y + off; dx = -Math.sin(ny) * Math.cos(pt + off / 2); dz = Math.cos(ny) * Math.cos(pt + off / 2); dy = -Math.sin(pt + off / 2); }
        double px = p.x + dx * 4, py = p.y + p.getEyeHeight() + dy * 4, pz = p.z + dz * 4;
        cn.nukkit.network.protocol.UseItemPacket u = new cn.nukkit.network.protocol.UseItemPacket();
        u.x = (int) Math.floor(px); u.y = (int) Math.floor(py); u.z = (int) Math.floor(pz); u.face = 1;
        u.fx = (float) (px - u.x); u.fy = (float) (py - u.y); u.fz = (float) (pz - u.z);
        getServer().getPluginManager().callEvent(new cn.nukkit.event.server.DataPacketReceiveEvent(p, u));
    }

    void airTap(Player p, boolean centered) {
        p.yaw = rnd.nextInt(360); p.pitch = rnd.nextInt(60) - 30;
        double y = Math.toRadians(p.yaw + (centered ? 0 : 30)), pt = Math.toRadians(p.pitch + (centered ? 0 : 12));
        cn.nukkit.network.protocol.UseItemPacket u = new cn.nukkit.network.protocol.UseItemPacket();
        u.face = 0xff; u.x = (int) (-Math.sin(y) * Math.cos(pt) * 32768); u.y = (int) (-Math.sin(pt) * 32768); u.z = (int) (Math.cos(y) * Math.cos(pt) * 32768);
        getServer().getPluginManager().callEvent(new cn.nukkit.event.server.DataPacketReceiveEvent(p, u));
    }

    String platform(Player p) { br.goldsuite.api.DeviceService d = getServer().getServiceManager().load(br.goldsuite.api.DeviceService.class); return String.valueOf(d.platform(p)); }

    void plan() {
        step(20, () -> {
            check(platform(pc).equals("ANDROID") && platform(cel).equals("ANDROID"), "Sem informacao do sistema, comeca pelo padrao do painel");
            for (int i = 0; i < 4; i++) { click(pc, true); airTap(pc, true); click(cel, i == 0); airTap(cel, false); }
            check(platform(pc).equals("WINDOWS10"), "PC reconhecido pela mira (" + platform(pc) + ")");
            check(platform(cel).equals("ANDROID"), "Celular continua celular (" + platform(cel) + ")");
        });
        step(5, () -> {
            for (int i = 0; i < 10; i++) { click(pc, false); }
            check(platform(pc).equals("WINDOWS10"), "Depois de confirmado nao fica trocando");
        });
    }
}
