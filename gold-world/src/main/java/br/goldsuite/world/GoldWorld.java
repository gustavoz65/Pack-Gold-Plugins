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
    static String PREFIX = M.t("mundos");
    static final int STACK_RADIUS = 6, STACK_MAX = 50;
    private static final ZoneId BRASILIA = ZoneId.of("America/Sao_Paulo");
    private PersistentState<WorldState> data;
    /** Id da entidade -> quantas criaturas ela representa. So vive em memoria. */
    private final Map<Long, Integer> stacks = new HashMap<>();

    public static final class WorldState {
        public Set<String> realTime = new TreeSet<>();
        public Set<String> autoInv = new TreeSet<>();
        /** Mundos sempre de dia (meio-dia parado) e sempre sem chuva (lobbies). */
        public Set<String> alwaysDay = new TreeSet<>();
        public Set<String> clearWeather = new TreeSet<>();
        /** Mundo -> raio da borda em blocos a partir do spawn (mundo de terreno gerado, ex.: factions). */
        public Map<String, Integer> borders = new TreeMap<>();
        public boolean stackMobs = true;
        /** Mundo -> para onde o jogador olha ao chegar no spawn (0 = sul, 90 = oeste, 180 = norte, 270 = leste). */
        public Map<String, Integer> spawnYaw = new TreeMap<>();
        static void validate(WorldState s) {
            if (s != null && s.spawnYaw == null) { s.spawnYaw = new TreeMap<>(); }
            if (s != null) { for (Map.Entry<String, Integer> y : s.spawnYaw.entrySet()) { Destination.world(y.getKey()); if (y.getValue() == null || y.getValue() < 0 || y.getValue() >= 360) { throw new IllegalArgumentException(M.t("configuracao_de_mundos_invalida")); } } }
            if (s != null && s.alwaysDay == null) { s.alwaysDay = new TreeSet<>(); }
            if (s != null && s.clearWeather == null) { s.clearWeather = new TreeSet<>(); }
            if (s != null && s.borders == null) { s.borders = new TreeMap<>(); }
            if (s != null) { for (Map.Entry<String, Integer> b : s.borders.entrySet()) { Destination.world(b.getKey()); if (b.getValue() == null || b.getValue() < 50 || b.getValue() > 100000) { throw new IllegalArgumentException(M.t("configuracao_de_mundos_invalida")); } } }
            if (s == null || s.realTime == null || s.autoInv == null || s.realTime.size() > 64 || s.autoInv.size() > 64 || s.alwaysDay.size() > 64 || s.clearWeather.size() > 64) { throw new IllegalArgumentException(M.t("configuracao_de_mundos_invalida")); }
            for (String w : s.alwaysDay) { Destination.world(w); }
            for (String w : s.clearWeather) { Destination.world(w); }
            for (String w : s.realTime) { Destination.world(w); }
            for (String w : s.autoInv) { Destination.world(w); }
        }
    }

    /** O gerador precisa existir antes dos mundos carregarem (mapas "void" convertidos do PC). */
    @Override public void onLoad() { Generator.addGenerator(VoidGenerator.class, "void", VoidGenerator.TYPE); }

    @Override public void onEnable() {
        M.B.attach(this);
        Settings.prefix(this, PREFIX, v -> PREFIX = v);
        CoreService core = getServer().getServiceManager().load(CoreService.class);
        if (core == null) { throw new IllegalStateException(M.t("goldcore_precisa_estar_ativo_antes_do")); }
        data = new PersistentState<>(core.getStore(), "goldworld", WorldState.class, WorldState::new, WorldState::validate);
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getScheduler().scheduleRepeatingTask(new PluginTask<GoldWorld>(this) { @Override public void onRun(int tick) { realTime(); dayAndWeather(); } }, 20 * 60);
        data.ready().thenRun(() -> getServer().getScheduler().scheduleTask(this::dayAndWeather));
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

    // ---------- dia e sol fixos ----------

    private void dayAndWeather() {
        if (!data.isLoaded()) { return; }
        for (Level level : getServer().getLevels().values()) { apply(level); }
    }

    private void apply(Level level) {
        if (!data.isLoaded() || level == null) { return; }
        String name = level.getFolderName();
        if (data.read(s -> s.alwaysDay.contains(name))) { level.setTime(6000); level.stopTime(); }
        if (data.read(s -> s.clearWeather.contains(name)) && level.isRaining()) { level.setRaining(false); level.setThundering(false); }
    }

    /** Mundo que carrega depois (arena, lobby aberto pelo NPC) ja entra de dia e sem chuva. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void loaded(cn.nukkit.event.level.LevelLoadEvent event) { apply(event.getLevel()); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void rain(cn.nukkit.event.level.WeatherChangeEvent event) {
        if (event.toWeatherState() && data.isLoaded() && data.read(s -> s.clearWeather.contains(event.getLevel().getFolderName()))) { event.setCancelled(); }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void thunder(cn.nukkit.event.level.ThunderChangeEvent event) {
        if (event.toThunderState() && data.isLoaded() && data.read(s -> s.clearWeather.contains(event.getLevel().getFolderName()))) { event.setCancelled(); }
    }

    // ---------- direcao ao chegar ----------

    /** Quem chega no spawn de um mundo (NPC, seletor, /spawn, /mundo tp) olha para o lado configurado, ex.: para os NPCs. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void arrive(cn.nukkit.event.player.PlayerTeleportEvent event) {
        cn.nukkit.level.Location to = event.getTo();
        if (to == null || to.level == null || !data.isLoaded()) { return; }
        Integer yaw = data.read(s -> s.spawnYaw.isEmpty() ? null : s.spawnYaw.get(to.level.getFolderName()));
        if (yaw == null) { return; }
        cn.nukkit.math.Vector3 spawn = to.level.getSpawnLocation();
        if (Math.abs(to.x - (spawn.x + 0.5)) > 3 || Math.abs(to.z - (spawn.z + 0.5)) > 3) { return; }
        to.yaw = yaw; to.pitch = 0;
    }

    // ---------- borda do mundo ----------

    /** Fora da borda o passo e cancelado: o servidor nao precisa gerar terreno novo sem fim (cada chunk novo pesa). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void border(cn.nukkit.event.player.PlayerMoveEvent event) {
        if (!data.isLoaded()) { return; }
        cn.nukkit.level.Location to = event.getTo();
        Integer radius = data.read(s -> s.borders.isEmpty() ? null : s.borders.get(to.level.getFolderName()));
        if (radius == null) { return; }
        cn.nukkit.math.Vector3 spawn = to.level.getSpawnLocation();
        if (Math.abs(to.x - spawn.x) <= radius && Math.abs(to.z - spawn.z) <= radius) { return; }
        event.setCancelled();
        event.getPlayer().sendPopup(M.t("fim_do_mapa"));
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
            spawner.setCustomName(M.t("spawner", type));
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
                    Set<String> loaded = new HashSet<>();
                    for (Level l : getServer().getLevels().values()) { loaded.add(l.getFolderName().toLowerCase(Locale.ROOT)); b.append(b.length() == 0 ? "" : "\u00a77, ").append(M.t("mundo_carregado", l.getFolderName(), l.getPlayers().size())); }
                    sender.sendMessage(PREFIX + M.t("carregados", b.toString()));
                    // Mapas que estao na pasta worlds/ mas ninguem abriu ainda: aparecem para nao precisar saber o nome.
                    StringBuilder off = new StringBuilder();
                    java.io.File[] dirs = new java.io.File(getServer().getDataPath(), "worlds").listFiles(java.io.File::isDirectory);
                    if (dirs != null) {
                        Arrays.sort(dirs, Comparator.comparing(java.io.File::getName, String.CASE_INSENSITIVE_ORDER));
                        for (java.io.File d : dirs) {
                            if (loaded.contains(d.getName().toLowerCase(Locale.ROOT)) || !new java.io.File(d, "level.dat").isFile()) { continue; }
                            off.append(off.length() == 0 ? "" : "\u00a77, ").append(M.t("mundo_nao_carregado", d.getName()));
                        }
                    }
                    sender.sendMessage(PREFIX + M.t("nao_carregados", off.length() == 0 ? M.t("nenhum_mundo") : off.toString()));
                    sender.sendMessage(PREFIX + M.t("dica_mundo_tp"));
                    return true;
                }
                case "tp": {
                    if (!(sender instanceof Player)) { return true; }
                    String name = Destination.world(arg(args, 1)).target;
                    if (!sender.hasPermission("goldworld.tp." + name.toLowerCase(Locale.ROOT)) && !sender.hasPermission("goldworld.admin")) { throw new IllegalArgumentException(M.t("sem_permissao_para_este_mundo")); }
                    getServer().getServiceManager().load(TravelService.class).travel((Player) sender, Destination.world(name), Transition.PORTAL)
                            .whenComplete((ok, e) -> { if (e != null) { sender.sendMessage(PREFIX + "§c" + root(e)); } });
                    return true;
                }
                default: break;
            }
            if (!sender.hasPermission("goldworld.admin")) { throw new IllegalArgumentException(M.t("sem_permissao")); }
            switch (sub) {
                case "criar": {
                    String name = Destination.world(arg(args, 1)).target;
                    String type = args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : "normal";
                    Class<? extends Generator> generator = Generator.getGenerator(type.equals("plano") ? "flat" : type);
                    if (getServer().isLevelGenerated(name)) { throw new IllegalArgumentException(M.t("mundo_ja_existe")); }
                    sender.sendMessage(PREFIX + M.t("gerando", name));
                    sender.sendMessage(PREFIX + (getServer().generateLevel(name, new Random().nextLong(), generator) ? M.t("mundo_criado_use_mundo_tp", name, name) : M.t("nao_foi_possivel_criar")));
                    return true;
                }
                case "carregar": { String name = Destination.world(arg(args, 1)).target; sender.sendMessage(PREFIX + (getServer().loadLevel(name) ? M.t("carregado", name) : M.t("mundo_inexistente"))); return true; }
                case "descarregar": {
                    Level level = getServer().getLevelByName(Destination.world(arg(args, 1)).target);
                    if (level == null || level == getServer().getDefaultLevel()) { throw new IllegalArgumentException(M.t("mundo_nao_carregado_ou_principal")); }
                    for (Player p : new ArrayList<>(level.getPlayers().values())) { p.teleport(getServer().getDefaultLevel().getSafeSpawn()); }
                    sender.sendMessage(PREFIX + (getServer().unloadLevel(level) ? M.t("descarregado") : M.t("nao_foi_possivel_descarregar")));
                    return true;
                }
                case "borda": {
                    // /mundo borda <mundo> <raio> | /mundo borda <mundo> off
                    String name = Destination.world(arg(args, 1)).target;
                    String raw = arg(args, 2);
                    Integer radius = raw.equalsIgnoreCase("off") ? null : Integer.valueOf(raw);
                    if (radius != null && (radius < 50 || radius > 100000)) { throw new IllegalArgumentException(M.t("borda_de_50_a_100000")); }
                    data.change(s -> { if (radius == null) { s.borders.remove(name); } else { s.borders.put(name, radius); } })
                            .whenComplete((v, e) -> sender.sendMessage(PREFIX + (e != null ? "§c" + root(e) : radius == null ? M.t("borda_removida", name) : M.t("borda_definida", name, radius))));
                    return true;
                }
                case "direcao": {
                    // /mundo direcao <mundo> <0-359|off>: para onde o jogador olha ao chegar no spawn.
                    String name = Destination.world(arg(args, 1)).target;
                    String raw = arg(args, 2);
                    Integer yaw = raw.equalsIgnoreCase("off") ? null : Integer.valueOf(Math.floorMod(Integer.parseInt(raw), 360));
                    data.change(s -> { if (yaw == null) { s.spawnYaw.remove(name); } else { s.spawnYaw.put(name, yaw); } })
                            .whenComplete((v, e) -> sender.sendMessage(PREFIX + (e != null ? "§c" + root(e) : M.t("direcao_definida", name, yaw == null ? "off" : String.valueOf(yaw)))));
                    return true;
                }
                case "preencher": {
                    // /mundo preencher <mundo> <x1> <y1> <z1> <x2> <y2> <z2> <id[:meta]> (console; ate 100 mil blocos)
                    String name = Destination.world(arg(args, 1)).target;
                    int[] v = new int[6];
                    for (int i = 0; i < 6; i++) { v[i] = Integer.parseInt(arg(args, 2 + i)); }
                    String[] idm = arg(args, 8).split(":");
                    int id = Integer.parseInt(idm[0]), meta = idm.length > 1 ? Integer.parseInt(idm[1]) : 0;
                    if (id < 0 || id > 255 || meta < 0 || meta > 15) { throw new IllegalArgumentException(M.t("bloco_invalido")); }
                    int x1 = Math.min(v[0], v[3]), x2 = Math.max(v[0], v[3]), y1 = Math.max(0, Math.min(v[1], v[4])), y2 = Math.min(127, Math.max(v[1], v[4])), z1 = Math.min(v[2], v[5]), z2 = Math.max(v[2], v[5]);
                    long volume = (long) (x2 - x1 + 1) * (y2 - y1 + 1) * (z2 - z1 + 1);
                    if (volume > 100000) { throw new IllegalArgumentException(M.t("area_grande_demais")); }
                    Level level = getServer().getLevelByName(name);
                    if (level == null && getServer().loadLevel(name)) { level = getServer().getLevelByName(name); }
                    if (level == null) { throw new IllegalArgumentException(M.t("mundo_inexistente")); }
                    for (int cx = x1 >> 4; cx <= x2 >> 4; cx++) {
                        for (int cz = z1 >> 4; cz <= z2 >> 4; cz++) {
                            level.loadChunk(cx, cz, true);
                            cn.nukkit.level.format.FullChunk c = level.getChunk(cx, cz, true);
                            // Sem "gerado" o Nukkit grava o chunk vazio e a construcao some ao reiniciar.
                            if (!c.isGenerated() || !c.isPopulated()) { c.setGenerated(); c.setPopulated(); }
                        }
                    }
                    Block block = Block.get(id, meta);
                    for (int x = x1; x <= x2; x++) { for (int y = y1; y <= y2; y++) { for (int z = z1; z <= z2; z++) { level.setBlock(new cn.nukkit.math.Vector3(x, y, z), block.clone(), true, false); } } }
                    sender.sendMessage(PREFIX + M.t("preenchido", volume, name));
                    return true;
                }
                case "dia": case "sol": {
                    // Alterna: /mundo dia <mundo> (meio-dia parado), /mundo sol <mundo> (nunca chove).
                    String name = Destination.world(arg(args, 1)).target;
                    boolean day = sub.equals("dia");
                    boolean[] on = new boolean[1];
                    data.change(s -> { Set<String> set = day ? s.alwaysDay : s.clearWeather; on[0] = set.add(name); if (!on[0]) { set.remove(name); } })
                            .whenComplete((v, e) -> {
                                if (e == null) { Level l = getServer().getLevelByName(name); if (l != null) { if (day && !on[0]) { l.startTime(); } else { apply(l); } } }
                                sender.sendMessage(PREFIX + (e != null ? "§c" + root(e) : M.t(day ? (on[0] ? "sempre_dia_ligado" : "sempre_dia_desligado") : (on[0] ? "sempre_sol_ligado" : "sempre_sol_desligado"), name)));
                            });
                    return true;
                }
                case "horareal": case "autoinv": {
                    String name = Destination.world(arg(args, 1)).target;
                    boolean[] on = new boolean[1];
                    data.change(s -> { Set<String> set = sub.equals("horareal") ? s.realTime : s.autoInv; on[0] = set.add(name); if (!on[0]) { set.remove(name); } })
                            .whenComplete((v, e) -> {
                                if (e == null && sub.equals("horareal") && !on[0]) { Level l = getServer().getLevelByName(name); if (l != null) { l.startTime(); } }
                                sender.sendMessage(PREFIX + (e != null ? "§c" + root(e) : M.t("em", ((sub.equals("horareal") ? M.t("hora_real") : "AutoInv") + (on[0] ? " ligado" : " desligado")), name)));
                            });
                    if (sub.equals("horareal")) { realTime(); }
                    return true;
                }
                case "empilhar": {
                    boolean[] on = new boolean[1];
                    data.change(s -> { s.stackMobs = !s.stackMobs; on[0] = s.stackMobs; }).thenRun(() -> sender.sendMessage(PREFIX + M.t("mobs_empilhados", (on[0] ? M.t("ligado") : M.t("desligado")))));
                    return true;
                }
                default:
                    sender.sendMessage(PREFIX + M.t("mundo_lista_tp_mundo_criar_mundo"));
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
        p.sendMessage(PREFIX + (count == 0 ? M.t("nada_para_fundir") : M.t("itens_fundidos", count)));
    }

    private static String arg(String[] args, int i) { if (args.length <= i) { throw new IllegalArgumentException(M.t("faltam_argumentos_use_mundo")); } return args[i]; }
    static String root(Throwable e) { while (e.getCause() != null) { e = e.getCause(); } return e.getMessage(); }
}
