package teste;

import cn.nukkit.Player;
import cn.nukkit.entity.Entity;
import cn.nukkit.level.Level;
import cn.nukkit.network.protocol.InteractPacket;
import cn.nukkit.network.protocol.TextPacket;
import cn.nukkit.plugin.PluginBase;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** NPCs e warps criados, movidos e configurados so pelo console, com <mundo> <x> <y> <z> [yaw]; clique no NPC executa o comando. */
@SuppressWarnings("unchecked")
public final class TesteNpcConsole extends PluginBase {
    final List<Runnable> steps = new ArrayList<>();
    final List<String> errors = new ArrayList<>();
    final Map<String, List<String>> got = new ConcurrentHashMap<>();
    Player adm, ana;

    void step(int delayTicks, Runnable r) { steps.add(() -> getServer().getScheduler().scheduleDelayedTask(() -> { try { r.run(); } catch (Throwable t) { errors.add(t.toString()); getLogger().error("passo", t); } next(); }, delayTicks)); }
    void next() { if (!steps.isEmpty()) { steps.remove(0).run(); } else { getLogger().info(errors.isEmpty() ? "NPCCONSOLE_OK" : "NPCCONSOLE_FALHOU " + errors); getLogger().info("FIM_TESTE"); } }
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

    long eidOf(int id) throws Exception {
        Object npc = getServer().getPluginManager().getPlugin("GoldNPC");
        Field f = npc.getClass().getDeclaredField("views"); f.setAccessible(true);
        Object view = ((Map<Integer, Object>) f.get(npc)).get(id);
        Field e = view.getClass().getDeclaredField("eid"); e.setAccessible(true);
        return e.getLong(view);
    }

    @Override public void onEnable() {
        getServer().getScheduler().scheduleDelayedTask(() -> {
            try {
                getServer().generateLevel("lobby");
                adm = fake("Adm", true); ana = fake("Ana", false);
            } catch (Exception e) { errors.add(e.toString()); getLogger().error("setup", e); }
            plan();
            next();
        }, 60);
    }

    void plan() {
        step(20, () -> {
            console("npc criar humano lobby 10.5 70 10.5 90 &aPvP|&7{mundo:lobby} jogando");
            console("npc criar humano");                         // sem coordenadas: so uso, nada criado
            console("npc criar humano naoexiste 1 2 3 Fantasma"); // mundo inexistente: nada criado
            console("setwarp pvp lobby 1 70 2 45");
            console("setwarp semcoord");                         // console sem coordenadas: recusado
        });
        step(40, () -> {
            forget();
            type(adm, "/npc lista");
            check(saw(adm, "#1 humano em lobby 11 70 11") && saw(adm, "PvP"), "NPC criado pelo console na posicao dada (lista: " + got(adm) + ")");
            check(!saw(adm, "#2") && !saw(adm, "Fantasma"), "Sem coordenadas ou com mundo inexistente nao cria NPC");
            forget();
            type(adm, "/warp");
            check(saw(adm, "Warps: pvp") && !saw(adm, "semcoord"), "setwarp pelo console com coordenadas (" + got(adm) + ")");
            console("npc mover lobby 20 71 -20 180");
            console("npc comando warp");
            console("npc nome &bNovo|Nome");
            console("npc mover");                                // console sem coordenadas: posicao nao muda
        });
        step(40, () -> {
            forget();
            type(adm, "/npc lista");
            check(saw(adm, "#1 humano em lobby 20 71 -20") && saw(adm, "comando: /warp") && saw(adm, "Novo"), "mover, comando e nome pelo console no NPC selecionado (" + got(adm) + ")");
            console("npc criar vaca lobby 5 70 5 Mu");
            console("npc selecionar 1");
            console("npc olhar nao");
        });
        step(40, () -> {
            forget();
            type(adm, "/npc lista");
            check(saw(adm, "#2 vaca em lobby 5 70 5"), "Segundo NPC (criatura) pelo console");
            // Clique no NPC #1: o comando roda como o jogador que clicou.
            ana.teleport(new cn.nukkit.level.Location(20, 71, -18, getServer().getLevelByName("lobby")));
            try {
                InteractPacket ip = new InteractPacket(); ip.target = eidOf(1); ip.action = 2;
                forget();
                getServer().getPluginManager().callEvent(new cn.nukkit.event.server.DataPacketReceiveEvent(ana, ip));
            } catch (Exception e) { errors.add(e.toString()); }
            check(saw(ana, "Warps:") && saw(ana, "pvp"), "Clique no NPC executa o comando como o jogador (" + got(ana) + ")");
            console("npc remover");
            console("delwarp pvp");
        });
        step(40, () -> {
            forget();
            type(adm, "/npc lista");
            check(!saw(adm, "#1 ") && saw(adm, "#2 vaca"), "Remover pelo console usa a selecao do console");
            forget();
            type(adm, "/warp");
            check(!saw(adm, "pvp"), "delwarp pelo console");
            // A selecao do jogador e separada da do console.
            forget();
            type(adm, "/npc info");
            check(saw(adm, "Selecione um NPC"), "Selecao do jogador nao herda a do console");
        });
    }

}
