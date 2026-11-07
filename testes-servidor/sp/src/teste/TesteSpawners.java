package teste;

import cn.nukkit.Player;
import cn.nukkit.block.Block;
import cn.nukkit.blockentity.BlockEntitySign;
import cn.nukkit.entity.Entity;
import cn.nukkit.event.block.BlockBreakEvent;
import cn.nukkit.event.block.BlockPlaceEvent;
import cn.nukkit.event.player.PlayerInteractEvent;
import cn.nukkit.item.Item;
import cn.nukkit.level.Level;
import cn.nukkit.level.Location;
import cn.nukkit.math.Vector3;
import cn.nukkit.network.protocol.InteractPacket;
import cn.nukkit.network.protocol.TextPacket;
import cn.nukkit.plugin.PluginBase;
import br.goldsuite.api.EconomyService;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Spawners (colocar, empilhar, matar pelo pacote real, quebrar), placas de compra/venda criadas pelo console, /loja, direcao no spawn e mundo preencher. */
@SuppressWarnings("unchecked")
public final class TesteSpawners extends PluginBase {
    final List<Runnable> steps = new ArrayList<>();
    final List<String> errors = new ArrayList<>();
    final Map<String, List<String>> got = new ConcurrentHashMap<>();
    Player ana, bia;
    Level lv;

    void step(int delayTicks, Runnable r) { steps.add(() -> getServer().getScheduler().scheduleDelayedTask(() -> { try { r.run(); } catch (Throwable t) { errors.add(t.toString()); getLogger().error("passo", t); } next(); }, delayTicks)); }
    void next() { if (!steps.isEmpty()) { steps.remove(0).run(); } else { getLogger().info(errors.isEmpty() ? "SPAWNERS_OK" : "SPAWNERS_FALHOU " + errors); getLogger().info("FIM_TESTE"); } }
    void check(boolean ok, String msg) { if (!ok) { errors.add(msg); getLogger().error("FALHOU: " + msg); } else { getLogger().info("ok: " + msg); } }
    List<String> got(Player p) { return got.computeIfAbsent(p.getName(), k -> Collections.synchronizedList(new ArrayList<>())); }
    boolean saw(Player p, String part) { synchronized (got(p)) { for (String s : got(p)) { if (s.contains(part)) { return true; } } } return false; }
    void console(String line) { getServer().dispatchCommand(getServer().getConsoleSender(), line); }
    long money(Player p) { return getServer().getServiceManager().load(EconomyService.class).balance(p.getName(), EconomyService.Currency.MONEY); }
    int count(Player p, int id, int meta) { int n = 0; for (Item it : p.getInventory().getContents().values()) { if (it.getId() == id && it.getDamage() == meta) { n += it.getCount(); } } return n; }
    Item find(Player p, int id) { for (Item it : p.getInventory().getContents().values()) { if (it.getId() == id) { return it; } } return null; }

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


    Object engine() { return getServer().getServiceManager().load(br.goldsuite.api.MenuService.class); }
    Object menuOf(Player p) throws Exception { Field f = engine().getClass().getDeclaredField("open"); f.setAccessible(true); return ((Map<?, ?>) f.get(engine())).get(p); }

    Object spawnersPlugin() { return getServer().getPluginManager().getPlugin("GoldSpawners"); }
    Map<String, Object> live() throws Exception { Field f = spawnersPlugin().getClass().getDeclaredField("live"); f.setAccessible(true); return (Map<String, Object>) f.get(spawnersPlugin()); }
    int field(Object o, String name) throws Exception { Field f = o.getClass().getDeclaredField(name); f.setAccessible(true); return ((Number) f.get(o)).intValue(); }

    void tap(Player p, int x, int y, int z) {
        getServer().getPluginManager().callEvent(new PlayerInteractEvent(p, p.getInventory().getItemInHand(), lv.getBlock(new Vector3(x, y, z)), 0, PlayerInteractEvent.RIGHT_CLICK_BLOCK));
    }

    @Override public void onEnable() {
        getServer().getScheduler().scheduleDelayedTask(() -> {
            try {
                lv = getServer().getDefaultLevel();
                ana = fake("Ana", false); bia = fake("Bia", false);
            } catch (Exception e) { errors.add(e.toString()); getLogger().error("setup", e); }
            plan();
            next();
        }, 80);
    }

    void plan() {
        step(20, () -> {
            console("painel set GoldSpawners mundos " + lv.getFolderName());
            console("painel set GoldSpawners intervalo 2");
            console("economia adicionar money Ana 1000");
            console("mundo preencher " + lv.getFolderName() + " 0 69 0 12 69 12 98");
            console("comprar placa comprar " + lv.getFolderName() + " 5 70 2 chao:0 52:5 1 100 &eSpawner Zumbi");
            console("comprar categoria blocos 1 &eBlocos");
            console("comprar placa vender " + lv.getFolderName() + " 7 70 2 sul 367 1 3,50");
        });
        step(40, () -> {
            check(lv.getBlockIdAt(6, 69, 6) == 98 && lv.getBlockIdAt(0, 69, 12) == 98, "mundo preencher colocou o chao");
            check(lv.getChunk(0, 0).isGenerated(), "chunk preenchido marcado como gerado");
            check(lv.getBlockIdAt(5, 70, 2) == Block.SIGN_POST && lv.getBlockIdAt(7, 70, 2) == Block.WALL_SIGN, "placas colocadas pelo console");
            cn.nukkit.blockentity.BlockEntity t = lv.getBlockEntity(new Vector3(5, 70, 2));
            check(t instanceof BlockEntitySign && Arrays.toString(((BlockEntitySign) t).getText()).contains("Spawner Zumbi"), "placa com o nome do spawner " + (t instanceof BlockEntitySign ? Arrays.toString(((BlockEntitySign) t).getText()) : t));
            check(money(ana) == 100000, "Ana com $1000");
            ana.teleport(new Location(5.5, 70, 4.5, 0, 0, lv));
            tap(ana, 5, 70, 2); tap(ana, 5, 70, 2);
        });
        step(20, () -> {
            Item sp = find(ana, 52);
            check(sp != null && sp.getDamage() == 5 && sp.getCustomName().contains("Spawner Zumbi") && money(ana) == 100000 - 10000, "comprou o spawner de zumbi na placa (" + (sp == null ? null : sp.getDamage() + " " + sp.getCustomName()) + ", " + money(ana) + ")");
            ana.getInventory().clearAll();
            ana.getInventory().setItemInHand(sp);
            Block at = Block.get(Block.MONSTER_SPAWNER, 5); at.x = 3; at.y = 70; at.z = 6; at.level = lv;
            BlockPlaceEvent ev = new BlockPlaceEvent(ana, at, lv.getBlock(new Vector3(3, 70, 6)), lv.getBlock(new Vector3(3, 69, 6)), sp);
            getServer().getPluginManager().callEvent(ev);
            check(!ev.isCancelled(), "spawner pode ser colocado no mundo liberado");
            if (!ev.isCancelled()) { lv.setBlock(new Vector3(3, 70, 6), Block.get(Block.MONSTER_SPAWNER, 5), true, true); ana.getInventory().setItemInHand(Item.get(0)); }
        });
        step(140, () -> {
            try {
                Object l = live().get(lv.getFolderName() + ":3:70:6");
                check(l != null, "spawner registrado");
                check(l != null && field(l, "mobs") >= 2, "mobs nasceram com a Ana perto (" + (l == null ? -1 : field(l, "mobs")) + ")");
                check(lv.getBlockEntity(new Vector3(3, 70, 6)) == null, "gerador de zumbis do core removido do bloco");
                check(lv.getEntities().length == 0 || Arrays.stream(lv.getEntities()).noneMatch(e -> e.getNetworkId() == 32), "nenhum zumbi de verdade no mundo");
                int before = field(l, "mobs");
                Field eidF = l.getClass().getDeclaredField("eid"); eidF.setAccessible(true);
                InteractPacket pk = new InteractPacket(); pk.action = InteractPacket.ACTION_LEFT_CLICK; pk.target = (long) eidF.get(l);
                ana.teleport(new Location(4.5, 70, 7.5, 0, 0, lv));
                ana.handleDataPacket(pk);
                check(field(l, "mobs") == before - 1 && count(ana, 367, 0) == 2, "toque no mob matou um e deu 2 carnes podres (" + field(l, "mobs") + ", " + count(ana, 367, 0) + ")");
                ana.handleDataPacket(pk);
                check(field(l, "mobs") == before - 1, "toque repetido em menos de 250 ms nao conta");
            } catch (Exception e) { errors.add(e.toString()); }
        });
        step(10, () -> {
            try {
                Object l = live().get(lv.getFolderName() + ":3:70:6");
                Field eidF = l.getClass().getDeclaredField("eid"); eidF.setAccessible(true);
                InteractPacket pk = new InteractPacket(); pk.action = InteractPacket.ACTION_LEFT_CLICK; pk.target = (long) eidF.get(l);
                ana.handleDataPacket(pk);
                check(count(ana, 367, 0) == 4, "segundo mob morto: 4 carnes podres");
            } catch (Exception e) { errors.add(e.toString()); }
            ana.getInventory().setItemInHand(Item.get(0));
            tap(ana, 7, 70, 2); tap(ana, 7, 70, 2);
        });
        step(20, () -> {
            check(count(ana, 367, 0) == 0 && money(ana) == 100000 - 10000 + 1400, "placa de venda vendeu as 4 carnes por $14 (" + count(ana, 367, 0) + ", " + money(ana) + ")");
            BlockBreakEvent b = new BlockBreakEvent(bia, lv.getBlock(new Vector3(3, 70, 6)), Item.get(278));
            getServer().getPluginManager().callEvent(b);
            check(b.isCancelled(), "Bia nao quebra o spawner da Ana");
            BlockBreakEvent a = new BlockBreakEvent(ana, lv.getBlock(new Vector3(3, 70, 6)), Item.get(278));
            getServer().getPluginManager().callEvent(a);
            check(!a.isCancelled() && a.getDrops().length == 0 && count(ana, 52, 5) == 1, "Ana quebra e o spawner volta ao inventario (sem drop duplicado) " + a.isCancelled() + " " + a.getDrops().length + " " + count(ana, 52, 5));
            try { check(!live().containsKey(lv.getFolderName() + ":3:70:6"), "registro apagado ao quebrar"); } catch (Exception e) { errors.add(e.toString()); }
            console("painel set GoldSpawners mundos plots");
        });
        step(20, () -> {
            Item sp = find(ana, 52);
            ana.getInventory().setItemInHand(sp);
            Block at = Block.get(Block.MONSTER_SPAWNER, 5); at.x = 9; at.y = 70; at.z = 9; at.level = lv;
            BlockPlaceEvent ev = new BlockPlaceEvent(ana, at, lv.getBlock(new Vector3(9, 70, 9)), lv.getBlock(new Vector3(9, 69, 9)), sp);
            getServer().getPluginManager().callEvent(ev);
            check(ev.isCancelled(), "fora do mundo de plots o spawner nao e colocado");
            console("mundo direcao " + lv.getFolderName() + " 180");
        });
        step(20, () -> {
            Vector3 s = lv.getSpawnLocation();
            ana.teleport(new Location(s.x + 0.5, s.y, s.z + 0.5, 0, 0, lv));
            check(Math.abs(ana.yaw - 180) < 0.01, "chegou no spawn olhando para 180 (" + ana.yaw + ")");
            ana.teleport(new Location(s.x + 20, s.y, s.z + 20, 0, 0, lv));
            check(Math.abs(ana.yaw) < 0.01, "longe do spawn a direcao nao muda");
            type(ana, "/loja");
        });
        step(10, () -> {
            try { check(menuOf(ana) != null, "/loja abre a loja do servidor " + got(ana)); } catch (Exception e) { errors.add(e.toString()); }
            console("mundo criar destino void");
            console("mundo portal " + lv.getFolderName() + " 40 destino");
        });
        step(40, () -> {
            Location from = new Location(5, 45, 5, 0, 0, lv), to = new Location(5, 39, 5, 0, 0, lv);
            ana.teleport(from);
            getServer().getPluginManager().callEvent(new cn.nukkit.event.player.PlayerMoveEvent(ana, from, to));
        });
        step(60, () -> { check(ana.level.getFolderName().equals("destino"), "portal de queda leva ao mundo destino (" + ana.level.getFolderName() + ")"); });
    }
}
