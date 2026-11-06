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
public final class TesteRankup extends PluginBase {
    final List<Runnable> steps = new ArrayList<>();
    final List<String> errors = new ArrayList<>();
    final Map<String, List<String>> got = new ConcurrentHashMap<>();
    final List<String> requests = Collections.synchronizedList(new ArrayList<>());
    Player gerente, mod, adm, ana, bia, carlos;
    HttpServer groq;
    int port;

    void step(int delayTicks, Runnable r) { steps.add(() -> getServer().getScheduler().scheduleDelayedTask(() -> { try { r.run(); } catch (Throwable t) { errors.add(t.toString()); getLogger().error("passo", t); } next(); }, delayTicks)); }
    void next() { if (!steps.isEmpty()) { steps.remove(0).run(); } else { getLogger().info(errors.isEmpty() ? "RANKUP_OK" : "RANKUP_FALHOU " + errors); getLogger().info("FIM_TESTE");  } }
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

    @Override public void onEnable() {
        getServer().getScheduler().scheduleDelayedTask(() -> {
            try { gerente = fake("Gerente", true); ana = fake("Ana", false); bia = fake("Bia", false); } catch (Exception e) { errors.add(e.toString()); }
            plan(); next();
        }, 60);
    }

    void plan() {
        step(20, () -> {
            console("economia adicionar money Ana 200000");
            console("economia adicionar cash Ana 15");
            console("painel set GoldCrates misteriosa.muito_raro 1000");
        });
        step(10, () -> {
            br.goldsuite.api.PlaceholderService ph = getServer().getServiceManager().load(br.goldsuite.api.PlaceholderService.class);
            String hud = ph.apply(ana, "{money}|{cash}|{grupo}|{clan}|{kdr}|{online}|{x}");
            check(hud.startsWith("200k|15|PADRAO|Nenhum|0.00|") , "Placeholders de money, cash, grupo, clan e KDR (" + hud + ")");
            forget();
        });
        step(25, () -> {
            check(saw(ana, "$ 200k") && saw(ana, "Cash 15"), "Barra mostra dinheiro e cash");
            forget();
            type(ana, "/banco depositar 1500");
        });
        step(10, () -> {
            check(money(ana) == 19_850_000 && saw(ana, "depositou $1500.00"), "Deposito no banco tira da carteira");
            type(ana, "/banco");
            try { menuClick(ana, 25); } catch (Exception e) { errors.add("menu banco: " + e); }
        });
        step(10, () -> {
            check(money(ana) == 20_000_000, "Sacar tudo pelo menu do banco devolve a carteira (" + money(ana) + ")");
            type(gerente, "/caixa criar basica");
            type(gerente, "/caixa nome basica &6Basica");
        });
        step(10, () -> {
            gerente.getInventory().setItem(0, cn.nukkit.item.Item.get(264, 0, 3)); gerente.getInventory().setHeldItemIndex(0);
            type(gerente, "/caixa premiocomando basica 1 &2$1000 economia adicionar money {jogador} 1000");
        });
        step(10, () -> {
            type(gerente, "/caixa misteriosa Bia basica 2");
            type(gerente, "/caixa misteriosa Ana basica 1");
        });
        step(10, () -> {
            int boxes = 0; int slot = -1;
            for (Map.Entry<Integer, cn.nukkit.item.Item> e : bia.getInventory().getContents().entrySet()) { if (e.getValue().getId() == 54) { boxes += e.getValue().getCount(); slot = e.getKey(); } }
            check(boxes == 2, "Caixa misteriosa entregue como item");
            long before = money(bia);
            forget();
            try {
                bia.getInventory().setHotbarSlotIndex(0, slot); bia.getInventory().setHeldItemIndex(0);
                getServer().getPluginManager().callEvent(new cn.nukkit.event.player.PlayerItemHeldEvent(bia, bia.getInventory().getItem(slot), 0, slot));
                menuClick(bia, 11);
            } catch (Exception e) { errors.add("abrir caixa: " + e); }
            check(money(bia) == before + 100_000 && saw(bia, "Voce ganhou") && saw(bia, "MUITO RARO") && saw(ana, "ganhou um item"), "Abrir a caixa paga o premio de comando e anuncia a raridade");
            int left = 0; for (cn.nukkit.item.Item it : bia.getInventory().getContents().values()) { if (it.getId() == 54) { left += it.getCount(); } }
            check(left == 1, "Abrir consome so uma caixa");
            // Bau renomeado na mao (sem assinatura) nao abre nada.
            cn.nukkit.item.Item fake = cn.nukkit.item.Item.get(54, 0, 1); fake.setCustomName(bia.getInventory().getItem(slot).getCustomName());
            bia.getInventory().setItem(5, fake);
            long b2 = money(bia);
            getServer().getPluginManager().callEvent(new cn.nukkit.event.player.PlayerItemHeldEvent(bia, fake, 1, 5));
            check(money(bia) == b2, "Bau com o mesmo nome mas sem assinatura nao vira caixa");
        });
        step(10, () -> {
            forget();
            type(bia, "/config");
            try { menuClick(bia, 20); } catch (Exception e) { errors.add("menu config: " + e); }
        });
        step(10, () -> {
            forget();
            check(blocked(ana, "/tell Bia oi") && saw(ana, "mensagens privadas desativado") && !blocked(gerente, "/tell Bia oi"), "Preferencia: Bia desligou o tell (staff ainda manda)");
            check(!blocked(ana, "/tpa Bia"), "So o tell foi desligado");
            type(ana, "/luz");
            check(ana.hasEffect(cn.nukkit.potion.Effect.NIGHT_VISION) || !ana.hasPermission("goldessentials.luz"), "Lanterna (visao noturna) para quem tem permissao");
            type(gerente, "/luz");
            check(gerente.hasEffect(cn.nukkit.potion.Effect.NIGHT_VISION), "/luz liga a visao noturna");
            type(gerente, "/setloja");
        });
        step(20, () -> {
            check(saw(gerente, "Loja marcada"), "/setloja grava a loja");
            forget();
            type(bia, "/loja Gerente");
        });
        step(20, () -> {
            check(saw(bia, "Indo para a loja de gerente"), "/loja leva ate a loja do jogador");
            forget();
            type(gerente, "/aviso evento as 20h");
            check(saw(ana, "AVISO") && saw(ana, "evento as 20h"), "/aviso aparece para todos");
        });
    }
}
