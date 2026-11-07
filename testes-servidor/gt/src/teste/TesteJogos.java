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

/** Partidas simuladas de SkyWars e BedWars com jogadores falsos: configura, entra, comeca, luta, termina e reseta. */
public final class TesteJogos extends PluginBase {
    final List<Runnable> steps = new ArrayList<>();
    final List<String> errors = new ArrayList<>();
    Player admin, a, b, c;

    void step(int delayTicks, Runnable r) { steps.add(() -> getServer().getScheduler().scheduleDelayedTask(() -> { try { r.run(); } catch (Throwable t) { errors.add(t.toString()); getLogger().error("passo", t); } next(); }, delayTicks)); }
    void next() { if (!steps.isEmpty()) { steps.remove(0).run(); } else { getLogger().info(errors.isEmpty() ? "JOGOS_OK" : "JOGOS_FALHOU " + errors); getLogger().info("FIM_TESTE"); } }
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

    @Override public void onEnable() {
        getServer().getScheduler().scheduleDelayedTask(() -> {
            try {
                getServer().loadLevel("sw_ilhas"); getServer().loadLevel("bw_quatro");
                admin = fake("gerente", true); a = fake("ana", false); b = fake("bia", false); c = fake("caio", false);
            } catch (Exception e) { errors.add(e.toString()); }
            plan();
            next();
        }, 60);
    }

    void plan() {
        // ---------- SkyWars ----------
        step(5, () -> { at(admin, "sw_ilhas", 0.5, 67, 0.5); cmd(admin, "GoldSkyWars", "sw criar ilhas solo"); });
        double[][] cages = {{34.5, 68, 0.5}, {24.5, 68, 24.5}, {0.5, 68, 34.5}, {-23.5, 68, 24.5}};
        step(3, () -> { at(admin, "sw_ilhas", 0.5, 67, 0.5); cmd(admin, "GoldSkyWars", "sw meio"); });
        // Jaulas por coordenada (como no pontos.txt), com o admin parado no centro.
        for (double[] cg : cages) { step(3, () -> cmd(admin, "GoldSkyWars", "sw jaula " + cg[0] + " " + cg[1] + " " + cg[2])); }
        step(3, () -> cmd(admin, "GoldSkyWars", "sw salvar"));
        step(40, () -> { at(admin, getServer().getDefaultLevel().getFolderName(), 0, 70, 0); for (Player p : new Player[] {a, b, c}) { cmd(p, "GoldSkyWars", "sw entrar solo"); } });
        step(5, () -> {
            try {
                check(phase("GoldSkyWars", "ilhas").equals("ESPERANDO") || phase("GoldSkyWars", "ilhas").equals("CONTAGEM"), "SkyWars: sala aberta com 3 jogadores");
                check(a.level.getFolderName().equals("sw_ilhas") && a.level.getBlockIdAt(a.getFloorX(), a.getFloorY() - 1, a.getFloorZ()) == 20, "SkyWars: jogador dentro da jaula de vidro");
            } catch (Exception e) { errors.add(e.toString()); }
            getServer().dispatchCommand(getServer().getConsoleSender(), "sw iniciar ilhas");
        });
        step(160, () -> {
            try {
                check(phase("GoldSkyWars", "ilhas").equals("JOGANDO"), "SkyWars: partida comecou");
                check(a.level.getBlockIdAt(a.getFloorX(), a.getFloorY() - 1, a.getFloorZ()) != 20 || a.y < 68, "SkyWars: jaula removida");
                int filled = 0; for (cn.nukkit.blockentity.BlockEntity be : a.level.getBlockEntities().values()) { if (be instanceof cn.nukkit.blockentity.BlockEntityChest) { for (Item i : ((cn.nukkit.blockentity.BlockEntityChest) be).getInventory().getContents().values()) { if (i.getId() != 0) { filled++; break; } } } }
                check(filled >= 16, "SkyWars: baus preenchidos (" + filled + ")");
                check(a.getInventory().getContents().size() > 0, "SkyWars: kit entregue");
            } catch (Exception e) { errors.add(e.toString()); }
            kill(a, b);
        });
        step(5, () -> {
            try {
                Object g = game("GoldSkyWars", "ilhas");
                check(((Set<?>) field(g, "spectators")).contains(b) && b.getDataFlag(Entity.DATA_FLAGS, Entity.DATA_FLAG_INVISIBLE) && b.getAllowFlight(), "SkyWars: morto vira espectador invisivel voando");
                check(((Map<?, ?>) field(g, "kills")).get(a).equals(1), "SkyWars: abate contado para quem matou");
            } catch (Exception e) { errors.add(e.toString()); }
            kill(a, c);
        });
        step(5, () -> { try { check(phase("GoldSkyWars", "ilhas").equals("FIM"), "SkyWars: ultimo vivo venceu"); } catch (Exception e) { errors.add(e.toString()); } });
        step(320, () -> {
            try {
                check(!b.getAllowFlight() && !b.getDataFlag(Entity.DATA_FLAGS, Entity.DATA_FLAG_INVISIBLE), "SkyWars: espectador volta ao lobby sem voo nem invisibilidade (falha do original)");
                boolean onlyLobbyItems = true; for (Item i : b.getInventory().getContents().values()) { if (!(i.hasCustomName() && i.getCustomName().contains("(toque)"))) { onlyLobbyItems = false; } }
                check(b.level == getServer().getDefaultLevel() && onlyLobbyItems, "SkyWars: todos voltaram ao lobby sem itens da partida");
                check(phase("GoldSkyWars", "ilhas").equals("ESPERANDO"), "SkyWars: mapa restaurado e sala aberta de novo");
            } catch (Exception e) { errors.add(e.toString()); }
            // Falha do original: nome de arena virando caminho de arquivo.
            at(admin, "sw_ilhas", 0.5, 67, 0.5);
            cmd(admin, "GoldSkyWars", "sw criar ../../GoldCore/hack solo");
            cmd(a, "GoldSkyWars", "sw criar outra solo");
        });
        step(5, () -> { try { Object st = field(getServer().getPluginManager().getPlugin("GoldSkyWars"), "data"); check(((Map<?, ?>) game("GoldSkyWars", "outra") == null ? new HashMap<>() : new HashMap<>()).isEmpty() && game("GoldSkyWars", "outra") == null, "SkyWars: jogador comum nao cria arena"); } catch (Exception e) { errors.add(e.toString()); } });

        // ---------- BedWars ----------
        step(5, () -> { at(admin, "bw_quatro", 0.5, 95, 0.5); cmd(admin, "GoldBedWars", "bw criar quatro solo"); });
        step(3, () -> { at(admin, "bw_quatro", 0.5, 95, 0.5); cmd(admin, "GoldBedWars", "bw espera"); });
        Object[][] teams = {{"vermelho", 0.5, 65, -41.5, 0, 65, -46, 0.5, 65, -37.5}, {"azul", 42.5, 65, 0.5, 46, 65, 0, 38.5, 65, 0.5}, {"verde", 0.5, 65, 42.5, 0, 65, 46, 0.5, 65, 38.5}, {"amarelo", -41.5, 65, 0.5, -46, 65, 0, -37.5, 65, 0.5}};
        for (Object[] t : teams) {
            step(2, () -> {
                at(admin, "bw_quatro", (double) t[1], ((Integer) t[2]).doubleValue(), (double) t[3]); cmd(admin, "GoldBedWars", "bw time " + t[0] + " spawn");
                at(admin, "bw_quatro", 0.5, 95, 0.5);
                cmd(admin, "GoldBedWars", "bw time " + t[0] + " cama " + t[4] + " " + t[5] + " " + t[6]);
                cmd(admin, "GoldBedWars", "bw time " + t[0] + " forja " + t[7] + " " + t[8] + " " + t[9]);
            });
        }
        step(2, () -> { at(admin, "bw_quatro", 22.5, 65, 22.5); cmd(admin, "GoldBedWars", "bw diamante"); at(admin, "bw_quatro", 2.5, 68, 0.5); cmd(admin, "GoldBedWars", "bw esmeralda"); });
        step(3, () -> cmd(admin, "GoldBedWars", "bw salvar"));
        step(40, () -> { at(admin, getServer().getDefaultLevel().getFolderName(), 0, 70, 0); cmd(a, "GoldBedWars", "bw entrar solo"); cmd(b, "GoldBedWars", "bw entrar solo"); });
        step(5, () -> getServer().dispatchCommand(getServer().getConsoleSender(), "bw iniciar quatro"));
        step(160, () -> {
            try {
                check(phase("GoldBedWars", "quatro").equals("JOGANDO"), "BedWars: partida comecou");
                Object g = game("GoldBedWars", "quatro");
                Map<?, ?> team = (Map<?, ?>) field(g, "team");
                check(team.get(a) != null && team.get(b) != null && !team.get(a).equals(team.get(b)), "BedWars: jogadores em times diferentes");
                check(((Set<?>) field(g, "beds")).size() == 2, "BedWars: so os times com jogadores ficam com cama");
                check(a.getInventory().getArmorItem(0).getId() == 298 && a.getInventory().contains(Item.get(268)), "BedWars: armadura de couro e espada de madeira");
                Level lv = a.level; double[] forge = forgeOf(team.get(a).toString());
                int iron = 0; for (Entity e : lv.getNearbyEntities(new cn.nukkit.math.AxisAlignedBB(forge[0] - 2, forge[1] - 2, forge[2] - 2, forge[0] + 2, forge[1] + 3, forge[2] + 2))) { if (e instanceof cn.nukkit.entity.item.EntityItem) { iron++; } }
                check(iron > 0, "BedWars: forja soltando ferro");
                a.getInventory().addItem(Item.get(265, 0, 10));

            } catch (Exception e) { errors.add(e.toString()); }
            try {
                Class<?> shop = Class.forName("br.goldsuite.bedwars.Shop", true, getServer().getPluginManager().getPlugin("GoldBedWars").getClass().getClassLoader());
                Field cats = shop.getDeclaredField("CATEGORIES"); cats.setAccessible(true);
                Object wool = ((List<?>) ((Map<?, ?>) cats.get(null)).get("Blocos")).get(0);
                Method buy = getServer().getPluginManager().getPlugin("GoldBedWars").getClass().getDeclaredMethod("buy", Player.class, wool.getClass());
                buy.setAccessible(true);
                buy.invoke(getServer().getPluginManager().getPlugin("GoldBedWars"), a, wool);
                boolean has = false; for (Item i : a.getInventory().getContents().values()) { if (i.getId() == 35 && i.getCount() == 16) { has = true; } }
                check(has, "BedWars: compra de 16 de la na loja");
                // Quebrar a cama do outro time.
                Object g = game("GoldBedWars", "quatro");
                String tb = ((Map<?, ?>) field(g, "team")).get(b).toString();
                int[] bed = bedOf(tb);
                Block bedBlock = a.level.getBlock(new cn.nukkit.math.Vector3(bed[0], bed[1], bed[2]));
                check(bedBlock.getId() == 26, "BedWars: cama existe no mapa");
                getServer().getPluginManager().callEvent(new BlockBreakEvent(a, bedBlock, Item.get(0)));
                check(!((Set<?>) field(g, "beds")).contains(tb), "BedWars: cama inimiga destruida");
                // Bloco do mapa nao pode ser quebrado.
                BlockBreakEvent mapBlock = new BlockBreakEvent(a, a.level.getBlock(new cn.nukkit.math.Vector3(bed[0], bed[1] - 1, bed[2])), Item.get(0));
                getServer().getPluginManager().callEvent(mapBlock);
                check(mapBlock.isCancelled(), "BedWars: bloco do mapa protegido");
            } catch (Exception e) { errors.add(e.toString()); getLogger().error("bw", e); }
            kill(a, b);
        });
        step(5, () -> {
            try {
                Object g = game("GoldBedWars", "quatro");
                check(((Set<?>) field(g, "finalDead")).contains(b), "BedWars: sem cama, a morte e final");
                check(phase("GoldBedWars", "quatro").equals("FIM"), "BedWars: ultimo time venceu");
            } catch (Exception e) { errors.add(e.toString()); }
        });
        step(220, () -> { try { check(phase("GoldBedWars", "quatro").equals("ESPERANDO") && b.level == getServer().getDefaultLevel() && !b.getAllowFlight(), "BedWars: reset e todos no lobby sem voo"); } catch (Exception e) { errors.add(e.toString()); } });
    }

    double[] forgeOf(String team) throws Exception { Object a = arena(); Map<?, ?> teams = (Map<?, ?>) field(a, "teams"); return (double[]) field(teams.get(team), "forge"); }
    int[] bedOf(String team) throws Exception { Object a = arena(); Map<?, ?> teams = (Map<?, ?>) field(a, "teams"); return (int[]) field(teams.get(team), "bed"); }
    Object arena() throws Exception { Object g = game("GoldBedWars", "quatro"); Method m = g.getClass().getDeclaredMethod("arena"); m.setAccessible(true); return m.invoke(g); }
}
