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
public final class TesteComandos extends PluginBase {
    final List<Runnable> steps = new ArrayList<>();
    final List<String> errors = new ArrayList<>();
    final Map<String, List<String>> got = new ConcurrentHashMap<>();
    final List<String> requests = Collections.synchronizedList(new ArrayList<>());
    Player gerente, mod, adm, ana, bia, carlos;
    HttpServer groq;
    int port;

    void step(int delayTicks, Runnable r) { steps.add(() -> getServer().getScheduler().scheduleDelayedTask(() -> { try { r.run(); } catch (Throwable t) { errors.add(t.toString()); getLogger().error("passo", t); } next(); }, delayTicks)); }
    void next() { if (!steps.isEmpty()) { steps.remove(0).run(); } else { getLogger().info(errors.isEmpty() ? "COMANDOS_OK" : "COMANDOS_FALHOU " + errors); getLogger().info("FIM_TESTE");  } }
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

    @Override public void onEnable() {
        getServer().getScheduler().scheduleDelayedTask(() -> {
            try {
                getServer().generateLevel("pvp");
                gerente = fake("Gerente", true); ana = fake("Ana", false); bia = fake("Bia", false);
            } catch (Exception e) { errors.add(e.toString()); getLogger().error("setup", e); }
            plan();
            next();
        }, 60);
    }

    void plan() {
        step(20, () -> {
            console("zona criar pvp");
            console("zona mundo pvp pvp");
            console("zona modo pvp liberar");
            console("zona cmd pvp kit perfil");
            console("zona boasvindas pvp &aBem vindo ao PvP, {nome}!");
            console("zona item pvp 1 345 &eJogos ajuda");
            console("zona ajuda pvp &7Aqui vale tudo: so kit e perfil.");
        });
        step(30, () -> {
            forget();
            ana.teleport(getServer().getLevelByName("pvp").getSafeSpawn());
        });
        step(20, () -> {
            check(saw(ana, "Bem vindo ao PvP, Ana!"), "Boas-vindas na primeira entrada da zona");
            check(ana.getInventory().getItem(0).getId() == 345 && ana.getInventory().getItem(0).getCustomName().equals("\u00a7eJogos"), "Item da zona na hotbar");
            forget();
            type(ana, "/tpa Bia");
            check(saw(ana, "nao esta disponivel aqui"), "Comando fora da lista bloqueado na zona");
            forget();
            type(ana, "/equipe");
            check(!saw(ana, "nao esta disponivel"), "Comando sempre liberado funciona na zona");
            forget();
            type(ana, "/help");
            check(saw(ana, "Comandos (pvp)") && saw(ana, "Aqui vale tudo") && saw(ana, "/perfil") && !saw(ana, "/tpa") && !saw(ana, "/gamemode") && !saw(ana, "/op "), "/help so mostra o que a zona libera e o jogador pode");
            forget();
            for (int pg = 1; pg <= 12; pg++) { blocked(bia, "/help " + pg); }
            check(saw(bia, "/tpa") && !saw(bia, "/gamemode") && !saw(bia, "/op "), "/help fora da zona mostra so o que o jogador tem permissao");
            forget();
            try {
                for (int i = 0; i < 9; i++) { ana.getInventory().setHotbarSlotIndex(i, i); }
                ana.getInventory().setHeldItemIndex(0);
                getServer().getPluginManager().callEvent(new cn.nukkit.event.player.PlayerItemHeldEvent(ana, ana.getInventory().getItem(0), 0, 0));
                check(!saw(ana, "Ajuda"), "Selecionar o item nao executa (padrao: tocar no chao)");
                cn.nukkit.block.Block chao = ana.level.getBlock(new cn.nukkit.math.Vector3(ana.getFloorX(), ana.getFloorY() - 1, ana.getFloorZ()));
                getServer().getPluginManager().callEvent(new cn.nukkit.event.player.PlayerInteractEvent(ana, ana.getInventory().getItemInHand(), chao, 1, cn.nukkit.event.player.PlayerInteractEvent.RIGHT_CLICK_BLOCK));
            } catch (Throwable t) { errors.add(t.toString()); }
            check(saw(ana, "Ajuda"), "Tocar no chao com o item executa o comando dele");
            ana.teleport(getServer().getDefaultLevel().getSafeSpawn());
        });
        step(20, () -> {
            check(ana.getInventory().getItem(0).getId() != 345, "Item da zona sai ao trocar de mundo");
            forget();
            type(ana, "/tpa Bia");
            check(!saw(ana, "nao esta disponivel"), "Fora da zona o comando volta a funcionar");
            ana.teleport(getServer().getLevelByName("pvp").getSafeSpawn());
        });
        step(20, () -> {
            check(!saw(ana, "Bem vindo ao PvP"), "Boas-vindas so na primeira vez");
            // ---------- OP ----------
            forget();
            type(gerente, "/op Ana");
            check(saw(gerente, "OP so pode ser dado pelo console") && !ana.isOp(), "/op pelo jogo bloqueado sem IP autorizado");
            forget();
            type(gerente, "/opseguro add gerente 9.9.9.9");
            check(saw(gerente, "So pelo console"), "Lista de IPs do OP so pelo console");
            console("opseguro add gerente 9.9.9.9");
        });
        step(20, () -> {
            getServer().getPluginManager().callEvent(new cn.nukkit.event.player.PlayerJoinEvent(gerente, ""));
            check(!gerente.isOp(), "OP suspenso ao entrar de IP nao autorizado");
            console("opseguro add gerente " + gerente.getAddress());
        });
        // A gravacao do SQLite e assincrona: espere ela terminar antes do proximo login.
        step(60, () -> {
            getServer().getPluginManager().callEvent(new cn.nukkit.event.player.PlayerJoinEvent(gerente, ""));
            check(!gerente.isOp(), "OP nao volta so por entrar (antes do login)");
            getServer().getPluginManager().callEvent(new br.goldsuite.api.event.PlayerAuthEvent(gerente, br.goldsuite.api.event.PlayerAuthEvent.Method.LOGIN));
        });
        step(60, () -> {
            check(gerente.isOp(), "OP volta depois do login do IP autorizado");
            forget();
            type(gerente, "/op Ana");
            check(ana.isOp(), "/op funciona do IP autorizado");
            getServer().removeOp("Ana");
        });
    }
}
