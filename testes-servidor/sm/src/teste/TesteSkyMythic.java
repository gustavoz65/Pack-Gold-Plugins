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

/** SkyWars estilo Mythic: /swmap, baus, minerios por ilha, votacao, almas, vantagens, jaulas e progresso. */
public final class TesteSkyMythic extends PluginBase {
    final List<Runnable> steps = new ArrayList<>();
    final List<String> errors = new ArrayList<>();
    Player admin, a, b, c;

    void step(int delayTicks, Runnable r) { steps.add(() -> getServer().getScheduler().scheduleDelayedTask(() -> { try { r.run(); } catch (Throwable t) { errors.add(t.toString()); getLogger().error("passo", t); } next(); }, delayTicks)); }
    void next() { if (!steps.isEmpty()) { steps.remove(0).run(); } else { getLogger().info(errors.isEmpty() ? "SKYMYTHIC_OK" : "SKYMYTHIC_FALHOU " + errors); getLogger().info("FIM_TESTE"); } }
    void check(boolean ok, String msg) { if (!ok) { errors.add(msg); getLogger().error("FALHOU: " + msg); } else { getLogger().info("ok: " + msg); } }

    Player fake(String name, boolean op) throws Exception {
        cn.nukkit.network.SourceInterface si = (cn.nukkit.network.SourceInterface) Proxy.newProxyInstance(cn.nukkit.network.SourceInterface.class.getClassLoader(), new Class<?>[]{cn.nukkit.network.SourceInterface.class}, (proxy, m, args) -> {
            if (m.getReturnType() == Integer.class || m.getReturnType() == int.class) { return 0; }
            if (m.getReturnType() == boolean.class) { return false; }
            return null;
        });
        Player p = new Player(si, (long) name.hashCode(), "10.0.0." + Math.abs(name.hashCode() % 200), 19133) {
            @Override public boolean isOnline() { return true; }
            @Override public boolean isConnected() { return true; }
            @Override public void sendMessage(String m) { getLogger().info("[" + getName() + "] " + cn.nukkit.utils.TextFormat.clean(m)); }
            @Override public boolean hasPermission(String perm) { return op || !perm.contains("admin"); }
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
        p.spawned = true;
        getServer().getOnlinePlayers().put(UUID.randomUUID(), p);
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

    void kill(Player killer, Player victim) { victim.attack(new EntityDamageByEntityEvent(killer, victim, EntityDamageEvent.CAUSE_ENTITY_ATTACK, 500)); }


    Object menuClick(Player p, int slot) throws Exception {
        Object menus = getServer().getServiceManager().load(br.goldsuite.api.MenuService.class);
        Field f = menus.getClass().getDeclaredField("open"); f.setAccessible(true);
        Object menu = ((Map<?, ?>) f.get(menus)).get(p);
        if (menu == null) { throw new RuntimeException("nenhum menu aberto para " + p.getName()); }
        Field c = menu.getClass().getDeclaredField("onClick"); c.setAccessible(true);
        ((java.util.function.BiConsumer<Player, Integer>) c.get(menu)).accept(p, slot);
        return menu;
    }
    Plugin sw() { return getServer().getPluginManager().getPlugin("GoldSkyWars"); }
    Object progress() throws Exception { return field(sw(), "progress"); }
    Object stats(Player p) throws Exception { Method m = progress().getClass().getDeclaredMethod("view", Player.class); m.setAccessible(true); return m.invoke(progress(), p); }
    long num(Player p, String f) throws Exception { Object v = field(stats(p), f); return ((Number) v).longValue(); }
    void set(Player p, String f, Object value) throws Exception {
        Method m = progress().getClass().getDeclaredMethod("change", Player.class, java.util.function.Consumer.class); m.setAccessible(true);
        m.invoke(progress(), p, (java.util.function.Consumer<Object>) s -> { try { Field x = s.getClass().getDeclaredField(f); x.setAccessible(true); x.set(s, value); } catch (Exception e) { throw new RuntimeException(e); } });
    }
    Object arenaObj() throws Exception { Object g = game("GoldSkyWars", "ilhas"); Method m = g.getClass().getDeclaredMethod("arena"); m.setAccessible(true); return m.invoke(g); }
    int count(String world, int id, double cx, double cz) {
        Level lv = getServer().getLevelByName(world); int n = 0;
        for (int x = (int) cx - 14; x <= cx + 14; x++) { for (int z = (int) cz - 14; z <= cz + 14; z++) { for (int y = 40; y < 80; y++) { if (lv.getBlockIdAt(x, y, z) == id) { n++; } } } }
        return n;
    }
    void loadAround(String world, double cx, double cz) { Level lv = getServer().getLevelByName(world); for (int x = ((int) cx >> 4) - 3; x <= ((int) cx >> 4) + 3; x++) { for (int z = ((int) cz >> 4) - 3; z <= ((int) cz >> 4) + 3; z++) { lv.loadChunk(x, z); } } }
    void tap(Player p, Item item, Block b) { getServer().getPluginManager().callEvent(new cn.nukkit.event.player.PlayerInteractEvent(p, item, b, 1, cn.nukkit.event.player.PlayerInteractEvent.RIGHT_CLICK_BLOCK)); }
    Item stick(Player p) { for (Item i : p.getInventory().getContents().values()) { if (i.getId() == 280) { return i; } } return null; }

    @Override public void onEnable() {
        getServer().getScheduler().scheduleDelayedTask(() -> {
            try {
                getServer().loadLevel("sw_ilhas");
                admin = fake("gerente", true); a = fake("ana", false); b = fake("bia", false); c = fake("caio", false);
            } catch (Exception e) { errors.add(e.toString()); }
            plan();
            next();
        }, 60);
    }

    final double[][] cages = {{34.5, 68, 0.5}, {24.5, 68, 24.5}, {0.5, 68, 34.5}, {-23.5, 68, 24.5}};
    final int[] diamondsBefore = new int[4];

    void plan() {
        step(5, () -> { at(admin, "sw_ilhas", 0.5, 67, 0.5); cmd(admin, "GoldSkyWars", "sw criar ilhas solo"); });
        // As gravacoes do SQLite sao assincronas: espere a arena existir antes de editar.
        step(60, () -> { at(admin, "sw_ilhas", 0.5, 67, 0.5); cmd(admin, "GoldSkyWars", "swmap meio"); });
        for (double[] cg : cages) { step(3, () -> cmd(admin, "GoldSkyWars", "swmap jaula " + cg[0] + " " + cg[1] + " " + cg[2])); }
        step(3, () -> { cmd(admin, "GoldSkyWars", "swmap espectador 0.5 90 0.5"); cmd(admin, "GoldSkyWars", "swmap minimo 3"); cmd(admin, "GoldSkyWars", "swmap nome Ilhas Gold"); cmd(admin, "GoldSkyWars", "swmap autor gustavoz65"); cmd(a, "GoldSkyWars", "swmap minimo 2"); });
        step(60, () -> {
            try {
                Object ar = arenaObj();
                check(((double[]) field(ar, "spectator")).length == 3 && ((double[]) field(ar, "spectator"))[1] == 90, "swmap: ponto do espectador salvo");
                check(((Number) field(ar, "minimum")).intValue() == 3, "swmap: minimo salvo e jogador comum nao altera");
                check("Ilhas Gold".equals(field(ar, "display")) && "gustavoz65".equals(field(ar, "author")), "swmap: nome e autor");
                check(((List<?>) field(ar, "spawns")).size() == 4, "swmap: 4 jaulas");
            } catch (Exception e) { errors.add(e.toString()); }
            // Tipo de bau: o primeiro bau do mapa vira "meio".
            cmd(admin, "GoldSkyWars", "swmap bau meio");
            Level lv = getServer().getLevelByName("sw_ilhas");
            for (int[] at : new int[][] {{0, 0}}) { loadAround("sw_ilhas", at[0], at[1]); }
            for (double[] cg : cages) { loadAround("sw_ilhas", cg[0], cg[2]); }
            cn.nukkit.blockentity.BlockEntity chest = null;
            for (cn.nukkit.blockentity.BlockEntity be : lv.getBlockEntities().values()) { if (be instanceof cn.nukkit.blockentity.BlockEntityChest) { chest = be; break; } }
            check(chest != null, "mapa tem bau");
            if (chest != null) { tap(admin, Item.get(0), lv.getBlock(chest)); }
            cmd(admin, "GoldSkyWars", "swmap bau pronto");
        });
        // A marcacao do bau e gravada de forma assincrona: espere a gravacao terminar.
        step(40, () -> { try { check(((Map<?, ?>) field(arenaObj(), "chests")).size() == 1 && ((Map<?, ?>) field(arenaObj(), "chests")).values().iterator().next().equals(Boolean.TRUE), "swmap bau: bau marcado como meio"); } catch (Exception e) { errors.add(e.toString()); } });
        // ---------- minerios por ilha ----------
        step(3, () -> {
            for (int i = 0; i < 4; i++) { diamondsBefore[i] = count("sw_ilhas", 56, cages[i][0], cages[i][2]); }
            at(admin, "sw_ilhas", 0.5, 67, 0.5);
            cmd(admin, "GoldSkyWars", "sw ilhas");
            check(stick(admin) != null, "minerios: graveto entregue");
            Level dl = getServer().getLevelByName("sw_ilhas"); StringBuilder col = new StringBuilder(); for (int y = 55; y < 72; y++) { col.append(y).append('=').append(dl.getBlockIdAt(34, y, 0)).append(' '); } getLogger().info("DEBUG coluna " + col + " admin.level=" + admin.level.getFolderName());
            Item st = stick(admin); getLogger().info("DEBUG graveto tag=" + (st == null ? null : st.hasCompoundTag() + " " + st.getNamedTag()));
        });
        for (int i = 0; i < 4; i++) {
            final int k = i;
            step(12, () -> { Level lv = getServer().getLevelByName("sw_ilhas"); tap(admin, stick(admin), lv.getBlock(new cn.nukkit.math.Vector3(Math.floor(cages[k][0]), cages[k][1] - 4, Math.floor(cages[k][2])))); });
            step(20, () -> {});
        }
        step(5, () -> {
            try {
                Map<?, ?> sel = (Map<?, ?>) field(field(sw(), "ores"), "selections");
                Object s = sel.get(admin);
                check(s != null && ((List<?>) field(s, "islands")).size() == 4, "minerios: 4 ilhas marcadas (" + (s == null ? 0 : ((List<?>) field(s, "islands")).size()) + ")");
            } catch (Exception e) { errors.add(e.toString()); }
            // Repetir a mesma ilha nao duplica.
            Level lv = getServer().getLevelByName("sw_ilhas");
            tap(admin, stick(admin), lv.getBlock(new cn.nukkit.math.Vector3(34, 63, 0)));
        });
        step(20, () -> {
            try { Object s = ((Map<?, ?>) field(field(sw(), "ores"), "selections")).get(admin); check(((List<?>) field(s, "islands")).size() == 4, "minerios: ilha repetida recusada"); } catch (Exception e) { errors.add(e.toString()); }
            // Graveto no criativo nao quebra o bloco da ilha.
            Level lv = getServer().getLevelByName("sw_ilhas");
            BlockBreakEvent br = new BlockBreakEvent(admin, lv.getBlock(new cn.nukkit.math.Vector3(34, 63, 0)), stick(admin));
            getServer().getPluginManager().callEvent(br);
            check(br.isCancelled(), "minerios: graveto nao quebra bloco");
            cmd(admin, "GoldSkyWars", "gerar minerios");
        });
        step(60, () -> {});
        step(60, () -> {
            if (getServer().getLevelByName("sw_ilhas") == null) { getServer().loadLevel("sw_ilhas"); }
            for (double[] cg : cages) { loadAround("sw_ilhas", cg[0], cg[2]); }
            for (int i = 0; i < 4; i++) {
                int now = count("sw_ilhas", 56, cages[i][0], cages[i][2]);
                check(getServer().getLevelByName("sw_ilhas") != null && now >= 4 && now >= diamondsBefore[i], "minerios: ilha " + (i + 1) + " com diamantes (" + diamondsBefore[i] + " -> " + now + ")");
            }
            int iron = count("sw_ilhas", 15, cages[0][0], cages[0][2]), coal = count("sw_ilhas", 16, cages[0][0], cages[0][2]);
            check(iron >= 5 && coal >= 6, "minerios: ferro e carvao (" + iron + ", " + coal + ")");
        });
        // ---------- progresso antes da partida ----------
        step(5, () -> {
            try { Object maps = field(sw(), "maps"); Method hb = maps.getClass().getDeclaredMethod("hasBackup", String.class); hb.setAccessible(true); check((Boolean) hb.invoke(maps, "sw_ilhas"), "minerios: mapa salvo depois de gerar"); } catch (Exception e) { errors.add(e.toString()); }
            try {
                set(a, "souls", 25); set(a, "coins", 1000L); set(a, "cages", new TreeSet<>(Collections.singleton("gold")));
                set(b, "coins", 0L);
            } catch (Exception e) { errors.add(e.toString()); }
        });
        step(10, () -> {
            try {
                // Poco das almas: 10 almas por giro.
                Method spin = progress().getClass().getDeclaredMethod("spin", Player.class); spin.setAccessible(true); spin.invoke(progress(), a);
            } catch (Exception e) { errors.add(e.toString()); }
        });
        // O giro grava o perfil de forma assincrona.
        step(40, () -> {
            try {
                check(num(a, "souls") == 15 && num(a, "spins") == 1, "almas: giro gastou 10 almas (" + num(a, "souls") + ")");
                cmd(a, "GoldSkyWars", "sw vantagens");
            } catch (Exception e) { errors.add(e.toString()); }
        });
        step(5, () -> { try { menuClick(a, 10); } catch (Exception e) { errors.add(e.toString()); } });
        step(40, () -> {
            try {
                Map<?, ?> perks = (Map<?, ?>) field(stats(a), "perks");
                check(Integer.valueOf(1).equals(perks.get("speedboost")), "vantagens: velocidade comprada no menu");
                cmd(b, "GoldSkyWars", "sw vantagens");
            } catch (Exception e) { errors.add(e.toString()); }
        });
        step(5, () -> { try { menuClick(b, 10); } catch (Exception e) { errors.add(e.toString()); } });
        step(15, () -> {
            try {
                check(((Map<?, ?>) field(stats(b), "perks")).isEmpty() && num(b, "coins") == 0, "vantagens: sem moedas nao compra");
                cmd(a, "GoldSkyWars", "sw jaulas");
            } catch (Exception e) { errors.add(e.toString()); }
        });
        step(5, () -> { try { menuClick(a, 14); } catch (Exception e) { errors.add(e.toString()); } });
        step(15, () -> { try { check("gold".equals(field(stats(a), "cage")), "jaulas: jaula de ouro escolhida"); cmd(b, "GoldSkyWars", "sw jaulas"); } catch (Exception e) { errors.add(e.toString()); } });
        step(5, () -> { try { menuClick(b, 14); } catch (Exception e) { errors.add(e.toString()); } });
        step(15, () -> { try { check("glass".equals(field(stats(b), "cage")), "jaulas: bloqueada nao pode ser escolhida"); } catch (Exception e) { errors.add(e.toString()); } });
        // ---------- partida ----------
        step(5, () -> {
            at(admin, getServer().getDefaultLevel().getFolderName(), 0, 70, 0);
            Item lobby = Item.get(347, 0, 1); lobby.setCustomName("Perfil (toque)"); a.getInventory().setItem(3, lobby);
            for (Player p : new Player[] {a, b, c}) { cmd(p, "GoldSkyWars", "sw entrar solo"); }
        });
        step(5, () -> {
            try {
                check(a.level.getFolderName().equals("sw_ilhas"), "partida: entrou");
                check(a.level.getBlockIdAt(a.getFloorX(), a.getFloorY() - 1, a.getFloorZ()) == 41, "jaulas: jaula de ouro na partida (" + a.level.getBlockIdAt(a.getFloorX(), a.getFloorY() - 1, a.getFloorZ()) + ")");
                check(b.level.getBlockIdAt(b.getFloorX(), b.getFloorY() - 1, b.getFloorZ()) == 20, "jaulas: vidro para quem nao escolheu");
                boolean vote = false; for (Item i : a.getInventory().getContents().values()) { if (i.getId() == 339 && i.getCustomName().contains("Votar")) { vote = true; } }
                check(vote, "votacao: item de votar na hotbar");
                cmd(a, "GoldSkyWars", "sw votar");
            } catch (Exception e) { errors.add(e.toString()); }
        });
        // Menu de voto: 0-2 baus, 3-4 hora, 5-6 clima, 7-8 vida, 9-11 modificador.
        step(5, () -> { try { menuClick(a, 4); } catch (Exception e) { errors.add(e.toString()); } cmd(b, "GoldSkyWars", "sw votar"); });
        step(5, () -> { try { menuClick(b, 4); } catch (Exception e) { errors.add(e.toString()); } cmd(a, "GoldSkyWars", "sw votar"); });
        step(5, () -> { try { menuClick(a, 8); } catch (Exception e) { errors.add(e.toString()); } cmd(b, "GoldSkyWars", "sw votar"); });
        step(5, () -> { try { menuClick(b, 8); } catch (Exception e) { errors.add(e.toString()); } getServer().dispatchCommand(getServer().getConsoleSender(), "sw iniciar ilhas"); });
        step(160, () -> {
            try {
                check(phase("GoldSkyWars", "ilhas").equals("JOGANDO"), "partida: comecou");
                int t = a.level.getTime() % 24000;
                check(t >= 13000 && t < 23000, "votacao: noite venceu (" + t + ")");
                check(a.getMaxHealth() == 40, "votacao: vida dupla (" + a.getMaxHealth() + ")");
                check(a.hasEffect(cn.nukkit.potion.Effect.SPEED), "vantagens: velocidade aplicada no inicio");
            } catch (Exception e) { errors.add(e.toString()); }
            kill(a, b);
        });
        step(10, () -> {
            try {
                check(Math.abs(b.y - 90) < 1.5, "espectador: vai para o ponto marcado (y=" + b.y + ")");
                check(num(a, "kills") == 1 && num(a, "souls") == 16 && num(a, "xp") >= 10, "progresso: abate deu alma e XP (almas " + num(a, "souls") + ")");
            } catch (Exception e) { errors.add(e.toString()); }
            kill(a, c);
        });
        // O XP da vitoria tambem e gravado de forma assincrona.
        step(40, () -> { try { check(phase("GoldSkyWars", "ilhas").equals("FIM"), "partida: ultimo vivo venceu"); check(num(a, "wins") == 1 && num(a, "xp") >= 120, "progresso: vitoria deu XP (" + num(a, "xp") + ")"); } catch (Exception e) { errors.add(e.toString()); } });
        step(220, () -> {
            try {
                check(phase("GoldSkyWars", "ilhas").equals("ESPERANDO"), "reset: mapa restaurado");
                check(a.getMaxHealth() == 20, "reset: vida volta ao normal");
                boolean lobby = false; for (Item i : a.getInventory().getContents().values()) { if (i.getId() == 347) { lobby = true; } }
                check(lobby, "reset: itens do lobby devolvidos");
                check(count("sw_ilhas", 56, cages[0][0], cages[0][2]) >= 4, "reset: minerios gerados continuam no mapa restaurado");
            } catch (Exception e) { errors.add(e.toString()); }
        });
    }
}
