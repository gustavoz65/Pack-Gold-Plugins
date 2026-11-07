package teste;

import cn.nukkit.Player;
import cn.nukkit.entity.Entity;
import cn.nukkit.event.player.*;
import cn.nukkit.item.Item;
import cn.nukkit.level.Level;
import cn.nukkit.network.protocol.TextPacket;
import cn.nukkit.plugin.PluginBase;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Itens de menu da hotbar: por padrao so agem ao tocar no chao (selecionar o slot nao faz nada), o segundo evento do
 * mesmo toque e ignorado, hotbar.ao_selecionar = 1 volta ao jeito antigo, seletor de lobbies (/lobbies) com contador,
 * livro do perfil do PvP, e os itens da zona voltam ao renascer e ao fazer login (e nao caem ao morrer).
 */
@SuppressWarnings("unchecked")
public final class TesteHotbar extends PluginBase {
    final List<Runnable> steps = new ArrayList<>();
    final List<String> errors = new ArrayList<>();
    final Map<String, List<String>> got = new ConcurrentHashMap<>();
    Player ana;

    void step(int delayTicks, Runnable r) { steps.add(() -> getServer().getScheduler().scheduleDelayedTask(() -> { try { r.run(); } catch (Throwable t) { errors.add(t.toString()); getLogger().error("passo", t); } next(); }, delayTicks)); }
    void next() { if (!steps.isEmpty()) { steps.remove(0).run(); } else { getLogger().info(errors.isEmpty() ? "HOTBAR_OK" : "HOTBAR_FALHOU " + errors); getLogger().info("FIM_TESTE"); } }
    void check(boolean ok, String msg) { if (!ok) { errors.add(msg); getLogger().error("FALHOU: " + msg); } else { getLogger().info("ok: " + msg); } }
    List<String> got(Player p) { return got.computeIfAbsent(p.getName(), k -> Collections.synchronizedList(new ArrayList<>())); }
    boolean saw(Player p, String part) { return count(p, part) > 0; }
    int count(Player p, String part) { int n = 0; synchronized (got(p)) { for (String s : got(p)) { if (s.contains(part)) { n++; } } } return n; }
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

    Object engine() { return getServer().getServiceManager().load(br.goldsuite.api.MenuService.class); }
    Object menuOf(Player p) throws Exception { Field f = engine().getClass().getDeclaredField("open"); f.setAccessible(true); return ((Map<?, ?>) f.get(engine())).get(p); }
    boolean menuOpen(Player p) { try { return menuOf(p) != null; } catch (Exception e) { errors.add(e.toString()); return false; } }
    void menuClick(Player p, int slot) throws Exception {
        Object menu = menuOf(p);
        if (menu == null) { throw new RuntimeException("nenhum menu aberto"); }
        Field c = menu.getClass().getDeclaredField("onClick"); c.setAccessible(true);
        ((java.util.function.BiConsumer<Player, Integer>) c.get(menu)).accept(p, slot);
    }
    void closeMenu(Player p) { ((br.goldsuite.api.MenuService) engine()).close(p); }

    /** Seleciona o slot como o cliente: troca o item na mao e chama o evento. */
    void select(Player p, int slot) {
        // O cliente real liga cada posicao da hotbar a um slot do inventario (MobEquipmentPacket); o falso nao.
        for (int i = 0; i < 9; i++) { p.getInventory().setHotbarSlotIndex(i, i); }
        p.getInventory().setHeldItemIndex(slot);
        getServer().getPluginManager().callEvent(new PlayerItemHeldEvent(p, p.getInventory().getItem(slot), slot, slot));
    }
    /** Toque no chao com o item da mao. */
    void tapGround(Player p) {
        cn.nukkit.block.Block b = p.level.getBlock(new cn.nukkit.math.Vector3(p.getFloorX(), p.getFloorY() - 1, p.getFloorZ()));
        getServer().getPluginManager().callEvent(new PlayerInteractEvent(p, p.getInventory().getItemInHand(), b, 1, PlayerInteractEvent.RIGHT_CLICK_BLOCK));
    }
    boolean hasZoneItems(Player p) { Item a = p.getInventory().getItem(0), b = p.getInventory().getItem(1); return a.getId() == 340 && b.getId() == 345; }

    @Override public void onEnable() {
        getServer().getScheduler().scheduleDelayedTask(() -> {
            try { getServer().generateLevel("lobby"); ana = fake("Ana", false); } catch (Exception e) { errors.add(e.toString()); getLogger().error("setup", e); }
            plan();
            next();
        }, 60);
    }

    void plan() {
        step(20, () -> {
            console("zona criar hub");
            console("zona mundo hub lobby");
            console("zona item hub 1 340 &eLobbies lobbies");
            console("zona item hub 2 345 &bWarps warp");
            console("zona seletor 1 2 &aHub|{mundo:lobby}_jogando spawn");
            console("zona seletor 2 46 &cWarps warp");
            console("setwarp casa lobby 1 70 1");
            console("ranked lobby lobby");
        });
        step(40, () -> { ana.teleport(getServer().getLevelByName("lobby").getSafeSpawn()); });
        // As boas-vindas do GoldStaff abrem 3 s depois de entrar: espere e feche antes de testar a hotbar.
        step(80, () -> { check(menuOpen(ana), "Boas-vindas abertas depois de entrar"); closeMenu(ana); });
        step(20, () -> {
            check(hasZoneItems(ana), "Itens da zona entregues ao entrar no lobby");
            check(ana.getInventory().getItem(8).getId() == 340, "Livro do perfil no lobby de PvP");
            forget();
            select(ana, 1);
            check(!saw(ana, "Warps:"), "Selecionar o slot nao executa o item (padrao: tocar no chao)");
            select(ana, 0);
            check(!menuOpen(ana), "Selecionar o livro do seletor nao abre o menu");
            select(ana, 1);
            tapGround(ana);
            tapGround(ana);
            check(count(ana, "Warps:") == 1, "Tocar no chao executa uma vez; o segundo evento do mesmo toque e ignorado (" + count(ana, "Warps:") + ")");
        });
        step(15, () -> {
            tapGround(ana);
            check(count(ana, "Warps:") == 2, "Outro toque depois de 500 ms executa de novo");
            select(ana, 0);
        });
        step(15, () -> { forget(); tapGround(ana); });
        step(10, () -> {
            check(menuOpen(ana), "Tocar no chao com o livro abre o seletor de lobbies");
            check(!saw(ana, "Warps:"), "Tocar com o livro nao executa outro item");
            if (!menuOpen(ana)) { return; }
            try {
                Item icon = ((cn.nukkit.inventory.Inventory) menuOf(ana)).getItem(0);
                check(icon.getId() == 2 && icon.getCustomName().contains("Hub") && icon.getCustomName().contains("1 jogando"), "Icone do seletor com contador do mundo (" + icon.getCustomName() + ")");
                forget();
                menuClick(ana, 1);
            } catch (Exception e) { errors.add(e.toString()); }
            check(saw(ana, "Warps:"), "Icone do seletor executa o comando dele");
        });
        step(10, () -> {
            closeMenu(ana);
            console("painel set GoldCore hotbar.ao_selecionar 1");
        });
        step(20, () -> {
            forget();
            select(ana, 2);
            select(ana, 1);
            check(saw(ana, "Warps:"), "hotbar.ao_selecionar = 1: selecionar o slot executa (jeito antigo)");
            console("painel set GoldCore hotbar.ao_selecionar 0");
        });
        step(20, () -> {
            forget();
            select(ana, 2);
            select(ana, 1);
            check(!saw(ana, "Warps:"), "De volta a 0: selecionar nao executa");
            closeMenu(ana);
            select(ana, 8);
            forget();
            tapGround(ana);
        });
        step(20, () -> {
            check(menuOpen(ana), "Livro do perfil abre ao tocar no chao (" + got(ana) + ")");
            closeMenu(ana);
            // Morte: os itens da zona nao caem no chao e voltam ao renascer.
            Item[] drops = ana.getInventory().getContents().values().toArray(new Item[0]);
            PlayerDeathEvent death = new PlayerDeathEvent(ana, drops, "", 0);
            getServer().getPluginManager().callEvent(death);
            boolean dropped = false; for (Item i : death.getDrops()) { if (i.getId() == 345 && i.hasCustomName()) { dropped = true; } }
            check(!dropped, "Itens da zona nao caem ao morrer");
            ana.getInventory().clearAll();
            getServer().getPluginManager().callEvent(new PlayerRespawnEvent(ana, getServer().getLevelByName("lobby").getSafeSpawn()));
        });
        step(20, () -> {
            check(hasZoneItems(ana), "Itens da zona voltam ao renascer no mesmo mundo");
            ana.getInventory().clearAll();
            getServer().getPluginManager().callEvent(new br.goldsuite.api.event.PlayerAuthEvent(ana, br.goldsuite.api.event.PlayerAuthEvent.Method.LOGIN));
        });
        step(25, () -> {
            check(hasZoneItems(ana), "Itens da zona voltam depois do login");
            ana.getInventory().clearAll();
            getServer().getPluginManager().callEvent(new PlayerJoinEvent(ana, ""));
        });
        step(30, () -> {
            check(hasZoneItems(ana), "Itens da zona voltam ao entrar no servidor (relog)");
            console("mundoprotecao lobby fome off");
            console("mundoprotecao lobby movimento off");
            console("mundoprotecao lobby dano off");
        });
        step(30, () -> {
            // Lobby protegido: sem fome, sem puxao de movimento e sem dano de queda.
            PlayerFoodLevelChangeEvent fome = new PlayerFoodLevelChangeEvent(ana, ana.getFoodData().getLevel() - 1, 0);
            getServer().getPluginManager().callEvent(fome);
            check(fome.isCancelled(), "fome off: a fome nao cai no lobby");
            PlayerFoodLevelChangeEvent comer = new PlayerFoodLevelChangeEvent(ana, 20, 5);
            ana.getFoodData().setLevel(10);
            getServer().getPluginManager().callEvent(comer);
            check(!comer.isCancelled(), "fome off: comer continua subindo a fome");
            PlayerInvalidMoveEvent mov = new PlayerInvalidMoveEvent(ana, true);
            getServer().getPluginManager().callEvent(mov);
            check(mov.isCancelled(), "movimento off: o servidor nao puxa o jogador de volta");
            cn.nukkit.event.entity.EntityDamageEvent queda = new cn.nukkit.event.entity.EntityDamageEvent(ana, cn.nukkit.event.entity.EntityDamageEvent.CAUSE_FALL, 4);
            getServer().getPluginManager().callEvent(queda);
            check(queda.isCancelled(), "dano off no mundo: sem dano de queda fora de areas");
        });
        step(50, () -> { check(ana.getFoodData().getLevel() == 20, "fome off: quem chega com fome fica com a barra cheia (" + ana.getFoodData().getLevel() + ")"); });
    }
}
