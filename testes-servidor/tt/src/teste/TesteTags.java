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
public final class TesteTags extends PluginBase {
    final List<Runnable> steps = new ArrayList<>();
    final List<String> errors = new ArrayList<>();
    final Map<String, List<String>> got = new ConcurrentHashMap<>();
    final List<String> requests = Collections.synchronizedList(new ArrayList<>());
    Player gerente, mod, adm, ana, bia, carlos;
    HttpServer groq;
    int port;

    void step(int delayTicks, Runnable r) { steps.add(() -> getServer().getScheduler().scheduleDelayedTask(() -> { try { r.run(); } catch (Throwable t) { errors.add(t.toString()); getLogger().error("passo", t); } next(); }, delayTicks)); }
    void next() { if (!steps.isEmpty()) { steps.remove(0).run(); } else { getLogger().info(errors.isEmpty() ? "TAGS_OK" : "TAGS_FALHOU " + errors); getLogger().info("FIM_TESTE");  } }
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

    br.goldsuite.api.PermissionService perms() { return getServer().getServiceManager().load(br.goldsuite.api.PermissionService.class); }
    boolean logHas(String s) { try { return new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("log.txt")), "UTF-8").contains(s); } catch (Exception e) { return false; } }

    @Override public void onEnable() {
        getServer().getScheduler().scheduleDelayedTask(() -> {
            try { gerente = fake("Gerente", true); ana = fake("Ana", false); bia = fake("Bia", false); } catch (Exception e) { errors.add(e.toString()); }
            plan(); next();
        }, 60);
    }

    void plan() {
        step(20, () -> { console("grupo criar vip"); console("grupo herdar vip padrao"); console("grupo tag vip &6[VIP]"); });
        step(20, () -> { console("grupo jogador Ana vip"); });
        step(30, () -> {
            forget();
            type(ana, "/tag");
            check(saw(ana, "Suas tags: [Jogador], [VIP]"), "VIP tem as tags Jogador e VIP");
            try { check("Suas tags".equals(menuTitle(ana)), "/tag abre o menu de tags"); } catch (Exception e) { errors.add(e.toString()); }
            type(ana, "/tag Jogador");
        });
        step(15, () -> {
            check(saw(ana, "Agora voce usa a tag [Jogador]"), "/tag Jogador escolhe a tag");
            forget();
            type(ana, "oi pessoal");
            check(saw(bia, "[Jogador] Ana: oi pessoal"), "Chat mostra a tag escolhida (Jogador) para o VIP");
            check("vip".equals(perms().getGroup("Ana")), "Trocar de tag nao tira o cargo VIP");
            type(ana, "/tag vip");
        });
        step(50, () -> {
            forget();
            type(ana, "voltei");
            check(saw(bia, "[VIP] Ana: voltei"), "/tag vip volta para a tag VIP");
            forget();
            type(bia, "ola");
            check(saw(ana, "[Jogador] Bia: ola"), "Jogador sem cargo aparece com [Jogador]");
            console("tag criar campeao &d[Campeao]");
            console("tag dar Bia campeao 7");
        });
        step(20, () -> {
            check(saw(bia, "Voce ganhou a tag [Campeao] por 7 dia(s)"), "Tag de evento entregue com aviso");
            forget();
            type(bia, "/tag Campeao");
        });
        step(15, () -> {
            forget();
            type(bia, "ganhei");
            check(saw(ana, "[Campeao] Bia: ganhei"), "Tag de evento escolhida aparece no chat");
            forget();
            type(bia, "/tag VIP");
            check(saw(bia, "Voce nao tem a tag VIP"), "Nao da para escolher tag que nao tem");
            type(ana, "/tag VIP");
            console("grupo jogador Ana padrao");
        });
        step(30, () -> {
            forget();
            type(ana, "sem vip");
            check(saw(bia, "[Jogador] Ana: sem vip"), "VIP vencido: tag VIP escolhida volta para a automatica");
            br.goldsuite.api.CoreService core = getServer().getServiceManager().load(br.goldsuite.api.CoreService.class);
            core.getStore().get("goldtags", "bia").whenComplete((v, e) -> { String saved = v == null ? "" : v.orElse(""); getLogger().info("GRAVADO=" + saved); });
            console("mundo lista");
        });
        step(60, () -> {
            forget();
            type(ana, "seu fdp vai tomar no cu");
            check(saw(bia, "Ana: seu *** *** ***** ** **"), "Filtro troca palavrao e frase por asteriscos no chat");
        });
        step(60, () -> {
            forget();
            type(ana, "/tell Bia vtnc p0rr4");
            check(saw(bia, "**** *****"), "Filtro vale no tell");
            console("chat palavra adicionar feio");
            console("chat palavra remover fdp");
        });
        step(60, () -> {
            forget();
            type(ana, "que feio fdp");
            check(saw(bia, "Ana: que **** fdp"), "/chat palavra adicionar e remover mudam o filtro na hora");
            console("chat palavra testar seu arrombado");
        });
        step(20, () -> {
            check(logHas("Fica assim: seu *********"), "/chat palavra testar mostra o resultado");
            check(logHas("Nao carregados:") && logHas("sw_ilhas"), "/mundo lista mostra os mapas nao carregados");
            check(logHas("GRAVADO=campeao|campeao="), "Escolha e tags gravadas no banco");
        });
    }
}
