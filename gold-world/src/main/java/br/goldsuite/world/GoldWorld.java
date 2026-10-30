package br.goldsuite.world;

import br.goldsuite.api.*;
import br.goldsuite.api.TravelService.Transition;
import cn.nukkit.Player;
import cn.nukkit.block.Block;
import cn.nukkit.blockentity.BlockEntity;
import cn.nukkit.blockentity.BlockEntityMobSpawner;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.entity.Entity;
import cn.nukkit.entity.EntityLiving;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.block.BlockBreakEvent;
import cn.nukkit.event.block.BlockPlaceEvent;
import cn.nukkit.event.entity.CreatureSpawnEvent;
import cn.nukkit.event.entity.EntityDeathEvent;
import cn.nukkit.inventory.FurnaceRecipe;
import cn.nukkit.item.Item;
import cn.nukkit.item.enchantment.Enchantment;
import cn.nukkit.level.Level;
import cn.nukkit.level.MobSpawning;
import cn.nukkit.level.Position;
import cn.nukkit.level.generator.Generator;
import cn.nukkit.math.AxisAlignedBB;
import cn.nukkit.nbt.tag.CompoundTag;
import cn.nukkit.plugin.PluginBase;
import cn.nukkit.scheduler.PluginTask;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;

/** Mundos (ManyWorlds), hora real (RealTime), /fundir (MegaFornalha), SilkSpawners, StackingMobs e AutoInv. */
public final class GoldWorld extends PluginBase implements Listener {
    static final String PREFIX = "§2[§aMundos§2]§f ";
    static final int STACK_RADIUS = 6, STACK_MAX = 50;
    private static final ZoneId BRASILIA = ZoneId.of("America/Sao_Paulo");
    private PersistentState<WorldState> data;
    /** Id da entidade -> quantas criaturas ela representa. So vive em memoria. */
    private final Map<Long, Integer> stacks = new HashMap<>();

    public static final class WorldState {
        public Set<String> realTime = new TreeSet<>();
        public Set<String> autoInv = new TreeSet<>();
        public boolean stackMobs = true;
        static void validate(WorldState s) {
            if (s == null || s.realTime == null || s.autoInv == null || s.realTime.size() > 64 || s.autoInv.size() > 64) { throw new IllegalArgumentException("Configuracao de mundos invalida."); }
            for (String w : s.realTime) { Destination.world(w); }
            for (String w : s.autoInv) { Destination.world(w); }
        }
    }

    @Override public void onEnable() {
        CoreService core = getServer().getServiceManager().load(CoreService.class);
        if (core == null) { throw new IllegalStateException("GoldCore precisa estar ativo antes do GoldWorld."); }
        data = new PersistentState<>(core.getStore(), "goldworld", WorldState.class, WorldState::new, WorldState::validate);
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getScheduler().scheduleRepeatingTask(new PluginTask<GoldWorld>(this) { @Override public void onRun(int tick) { realTime(); } }, 20 * 60);
    }

    @Override public void onDisable() { stacks.clear(); if (data != null) { data.close(); } }

    // ---------- hora real ----------

    /** 06:00 de Brasilia = amanhecer (tick 0); 24 h reais = 24000 ticks. */
    static int ticksFor(ZonedDateTime now) {
        int minutes = now.getHour() * 60 + now.getMinute();
        return (int) (((minutes - 360 + 1440) % 1440) * 24000L / 1440);
    }

    private void realTime() {
        if (!data.isLoaded()) { return; }
        Set<String> worlds = data.read(s -> s.realTime);
        if (worlds.isEmpty()) { return; }
        int ticks = ticksFor(ZonedDateTime.now(BRASILIA));
        for (String name : worlds) {
            Level level = getServer().getLevelByName(name);
            if (level != null) { level.setTime(ticks); level.stopTime(); }
        }
    }

    // ---------- mobs empilhados ----------

    /** Um mob igual por perto absorve o novo: menos entidades, menos IA e menos pacotes. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void spawn(CreatureSpawnEvent event) {
        if (!data.isLoaded() || !data.read(s -> s.stackMobs)) { return; }
        Position pos = event.getPosition();
        if (pos == null || pos.level == null) { return; }
        AxisAlignedBB area = new AxisAlignedBB(pos.x - STACK_RADIUS, pos.y - 3, pos.z - STACK_RADIUS, pos.x + STACK_RADIUS, pos.y + 3, pos.z + STACK_RADIUS);
        for (Entity other : pos.level.getNearbyEntities(area)) {
            if (!(other instanceof EntityLiving) || other instanceof Player || other.closed || other.getNetworkId() != event.getNetworkId()) { continue; }
            int count = stacks.getOrDefault(other.getId(), 1);
            if (count >= STACK_MAX) { continue; }
            stacks.put(other.getId(), count + 1);
            other.setNameTag("§ex" + (count + 1));
            other.setNameTagVisible(true);
            event.setCancelled();
            return;
        }
    }

    /** Ao morrer, a pilha renasce com um a menos; os drops caem normalmente. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void death(EntityDeathEvent event) {
        Entity dead = event.getEntity();
        Integer count = stacks.remove(dead.getId());
        if (count == null || count <= 1 || dead.level == null) { return; }
        Entity next = Entity.createEntity(dead.getNetworkId(), dead.level.getChunk(dead.getFloorX() >> 4, dead.getFloorZ() >> 4, true), MobSpawning.createNBT(dead.x, dead.y, dead.z));
        if (next == null) { return; }
        if (count - 1 > 1) { stacks.put(next.getId(), count - 1); next.setNameTag("§ex" + (count - 1)); next.setNameTagVisible(true); }
        next.spawnToAll();
    }

    // ---------- spawners e drops ----------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void breakBlock(BlockBreakEvent event) {
        Block b = event.getBlock();
        Player p = event.getPlayer();
        if (b.getId() == Block.MONSTER_SPAWNER && p.getInventory().getItemInHand().getEnchantment(Enchantment.ID_SILK_TOUCH) != null) {
            BlockEntity tile = b.level.getBlockEntity(b);
            int type = tile instanceof BlockEntityMobSpawner ? ((BlockEntityMobSpawner) tile).getEntityId() : 32;
            Item spawner = Item.get(Block.MONSTER_SPAWNER, 0, 1);
            spawner.setCustomBlockData(new CompoundTag().putInt("EntityId", type));
            spawner.setCustomName("§r§eSpawner (" + type + ")");
            event.setDrops(new Item[] {spawner});
        }
        if (!data.isLoaded() || !data.read(s -> s.autoInv.contains(b.level.getFolderName())) || p.isCreative()) { return; }
        Item[] drops = event.getDrops();
        event.setDrops(new Item[0]);
        for (Item drop : drops) { for (Item rest : p.getInventory().addItem(drop)) { b.level.dropItem(b.add(0.5, 0.5, 0.5), rest); } }
    }

    /** Spawner colocado volta com o tipo de mob guardado no item. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void place(BlockPlaceEvent event) {
        Item item = event.getItem();
        if (item.getId() != Block.MONSTER_SPAWNER || !item.hasCustomBlockData() || !item.getCustomBlockData().contains("EntityId")) { return; }
        int type = item.getCustomBlockData().getInt("EntityId");
        Block b = event.getBlock();
        getServer().getScheduler().scheduleDelayedTask(new PluginTask<GoldWorld>(this) {
            @Override public void onRun(int tick) {
                BlockEntity tile = b.level.getBlockEntity(b);
                if (tile instanceof BlockEntityMobSpawner) { ((BlockEntityMobSpawner) tile).setEntityId(type); }
            }
        }, 1);
    }

    // ---------- comandos ----------

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        try {
            if (command.getName().equals("fundir")) { smelt(sender); return true; }
            String sub = args.length == 0 ? "lista" : args[0].toLowerCase(Locale.ROOT);
            switch (sub) {
                case "lista": {
                    StringBuilder b = new StringBuilder();
                    for (Level l : getServer().getLevels().values()) { b.append(l.getFolderName()).append(" (").append(l.getPlayers().size()).append(") "); }
                    sender.sendMessage(PREFIX + "Carregados: " + b.toString().trim());
                    return true;
                }
                case "tp": {
                    if (!(sender instanceof Player)) { return true; }
                    String name = Destination.world(arg(args, 1)).target;
                    if (!sender.hasPermission("goldworld.tp." + name.toLowerCase(Locale.ROOT)) && !sender.hasPermission("goldworld.admin")) { throw new IllegalArgumentException("Sem permissao para este mundo."); }
                    getServer().getServiceManager().load(TravelService.class).travel((Player) sender, Destination.world(name), Transition.PORTAL)
                            .whenComplete((ok, e) -> { if (e != null) { sender.sendMessage(PREFIX + "§c" + root(e)); } });
                    return true;
                }
                default: break;
            }
            if (!sender.hasPermission("goldworld.admin")) { throw new IllegalArgumentException("Sem permissao."); }
            switch (sub) {
                case "criar": {
                    String name = Destination.world(arg(args, 1)).target;
                    String type = args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : "normal";
                    Class<? extends Generator> generator = Generator.getGenerator(type.equals("plano") ? "flat" : type);
                    if (getServer().isLevelGenerated(name)) { throw new IllegalArgumentException("O mundo ja existe."); }
                    sender.sendMessage(PREFIX + "Gerando " + name + "...");
                    sender.sendMessage(PREFIX + (getServer().generateLevel(name, new Random().nextLong(), generator) ? "Mundo " + name + " criado. Use /mundo tp " + name : "§cNao foi possivel criar."));
                    return true;
                }
                case "carregar": { String name = Destination.world(arg(args, 1)).target; sender.sendMessage(PREFIX + (getServer().loadLevel(name) ? name + " carregado." : "§cMundo inexistente.")); return true; }
                case "descarregar": {
                    Level level = getServer().getLevelByName(Destination.world(arg(args, 1)).target);
                    if (level == null || level == getServer().getDefaultLevel()) { throw new IllegalArgumentException("Mundo nao carregado ou e o principal."); }
                    for (Player p : new ArrayList<>(level.getPlayers().values())) { p.teleport(getServer().getDefaultLevel().getSafeSpawn()); }
                    sender.sendMessage(PREFIX + (getServer().unloadLevel(level) ? "Descarregado." : "§cNao foi possivel descarregar."));
                    return true;
                }
                case "horareal": case "autoinv": {
                    String name = Destination.world(arg(args, 1)).target;
                    boolean[] on = new boolean[1];
                    data.change(s -> { Set<String> set = sub.equals("horareal") ? s.realTime : s.autoInv; on[0] = set.add(name); if (!on[0]) { set.remove(name); } })
                            .whenComplete((v, e) -> {
                                if (e == null && sub.equals("horareal") && !on[0]) { Level l = getServer().getLevelByName(name); if (l != null) { l.startTime(); } }
                                sender.sendMessage(PREFIX + (e != null ? "§c" + root(e) : (sub.equals("horareal") ? "Hora real" : "AutoInv") + (on[0] ? " ligado" : " desligado") + " em " + name + "."));
                            });
                    if (sub.equals("horareal")) { realTime(); }
                    return true;
                }
                case "empilhar": {
                    boolean[] on = new boolean[1];
                    data.change(s -> { s.stackMobs = !s.stackMobs; on[0] = s.stackMobs; }).thenRun(() -> sender.sendMessage(PREFIX + "Mobs empilhados " + (on[0] ? "ligado." : "desligado.")));
                    return true;
                }
                default:
                    sender.sendMessage(PREFIX + "/mundo lista, tp <mundo>, criar <mundo> [normal|plano], carregar, descarregar, horareal <mundo>, autoinv <mundo>, empilhar; /fundir");
                    return true;
            }
        } catch (RuntimeException e) { sender.sendMessage(PREFIX + "§c" + root(e)); return true; }
    }

    /** Funde na hora o que tem receita de fornalha (minerios, comida, areia...). */
    private void smelt(CommandSender sender) {
        if (!(sender instanceof Player)) { return; }
        Player p = (Player) sender;
        int count = 0;
        for (int slot = 0; slot < 36; slot++) {
            Item it = p.getInventory().getItem(slot);
            if (it.getId() == 0 || it.hasCompoundTag()) { continue; }
            FurnaceRecipe recipe = getServer().getCraftingManager().matchFurnaceRecipe(it);
            if (recipe == null) { continue; }
            Item result = recipe.getResult().clone();
            result.setCount(Math.min(result.getMaxStackSize(), it.getCount() * result.getCount()));
            p.getInventory().setItem(slot, result);
            count += it.getCount();
        }
        p.sendMessage(PREFIX + (count == 0 ? "Nada para fundir." : count + " itens fundidos."));
    }

    private static String arg(String[] args, int i) { if (args.length <= i) { throw new IllegalArgumentException("Faltam argumentos. Use /mundo."); } return args[i]; }
    static String root(Throwable e) { while (e.getCause() != null) { e = e.getCause(); } return e.getMessage(); }
}
