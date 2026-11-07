package br.goldsuite.spawners;

import br.goldsuite.api.*;
import br.goldsuite.spawners.Types.Type;
import cn.nukkit.Player;
import cn.nukkit.Server;
import cn.nukkit.block.Block;
import cn.nukkit.blockentity.BlockEntity;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.entity.Entity;
import cn.nukkit.entity.data.EntityMetadata;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.block.BlockBreakEvent;
import cn.nukkit.event.block.BlockPlaceEvent;
import cn.nukkit.event.player.PlayerQuitEvent;
import cn.nukkit.event.server.DataPacketReceiveEvent;
import cn.nukkit.item.Item;
import cn.nukkit.level.Level;
import cn.nukkit.math.Vector3;
import cn.nukkit.network.protocol.AddEntityPacket;
import cn.nukkit.network.protocol.EntityEventPacket;
import cn.nukkit.network.protocol.InteractPacket;
import cn.nukkit.network.protocol.ProtocolInfo;
import cn.nukkit.network.protocol.RemoveEntityPacket;
import cn.nukkit.network.protocol.SetEntityDataPacket;
import cn.nukkit.plugin.PluginBase;
import cn.nukkit.scheduler.PluginTask;
import java.util.*;

/**
 * Spawners do Rankup. Cada spawner colocado (so nos mundos do painel, ex.: plots) junta mobs numa pilha enquanto
 * ha jogador perto. A pilha e um mob "de pacote" (nao existe no mundo, sem IA, nao e salvo no chunk): cada toque
 * mata um e os drops vao direto para o inventario.
 */
public final class GoldSpawners extends PluginBase implements Listener {
    static String PREFIX = M.t("prefixo");
    static final int MAX_SPAWNERS = 4000;

    public static final class Spawner {
        public int type;
        public String owner = "";
        public int mobs;
    }

    public static final class SpawnerState {
        public Map<String, Spawner> spawners = new TreeMap<>();
        static void validate(SpawnerState s) {
            if (s == null || s.spawners == null || s.spawners.size() > MAX_SPAWNERS) { throw new IllegalArgumentException(M.t("dados_invalidos")); }
            for (Map.Entry<String, Spawner> e : s.spawners.entrySet()) {
                Spawner sp = e.getValue();
                if (Loc.parse(e.getKey()) == null || sp == null || Types.get(sp.type) == null || sp.owner == null || !sp.owner.matches("[a-z0-9_]{3,16}") || sp.mobs < 0 || sp.mobs > 100000) {
                    throw new IllegalArgumentException(M.t("dados_invalidos"));
                }
            }
        }
    }

    /** Posicao "mundo:x:y:z". */
    static final class Loc {
        final String world; final int x, y, z;
        Loc(String world, int x, int y, int z) { this.world = world; this.x = x; this.y = y; this.z = z; }
        static String key(String world, int x, int y, int z) { return world + ":" + x + ":" + y + ":" + z; }
        static Loc parse(String key) {
            String[] p = key == null ? new String[0] : key.split(":");
            if (p.length != 4 || !p[0].matches("[A-Za-z0-9_][A-Za-z0-9_.-]{0,31}")) { return null; }
            try { return new Loc(p[0], Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3])); } catch (NumberFormatException e) { return null; }
        }
    }

    /** Copia em memoria de um spawner: pilha atual, progresso e quem esta vendo o mob. */
    static final class Live {
        final String key; final Loc loc; final Type type; final String owner;
        int mobs, progress;
        final long eid = Entity.entityCount++;
        final Set<Player> viewers = new HashSet<>();
        String shown = "";
        Live(String key, Loc loc, Type type, String owner, int mobs) { this.key = key; this.loc = loc; this.type = type; this.owner = owner; this.mobs = mobs; }
    }

    private PersistentState<SpawnerState> data;
    private final Map<String, Live> live = new LinkedHashMap<>();
    private final Map<Long, Live> byEid = new HashMap<>();
    private final Set<String> dirty = new HashSet<>();
    private final Map<Player, Long> lastHit = new HashMap<>();
    private Set<String> worlds = new HashSet<>(Collections.singletonList("plots"));
    int interval = 20, max = 200, limit = 20, radius = 24, viewDistance = 32;
    private int seconds;

    @Override public void onEnable() {
        M.B.attach(this);
        Settings.prefix(this, PREFIX, v -> PREFIX = v);
        CoreService core = getServer().getServiceManager().load(CoreService.class);
        if (core == null) { throw new IllegalStateException(M.t("goldcore_precisa_estar_ativo")); }
        Settings.text(this, "mundos", "plots", "Mundos onde spawner funciona (separe por virgula)", v -> {
            Set<String> w = new HashSet<>();
            for (String s : v.split(",")) { if (!s.trim().isEmpty()) { w.add(s.trim().toLowerCase(Locale.ROOT)); } }
            worlds = w;
        });
        Settings.number(this, "intervalo", 20, "Segundos para nascer 1 mob (com jogador perto)", v -> interval = (int) Math.max(2, Math.min(3600, v)));
        Settings.number(this, "pilha_maxima", 200, "Mobs acumulados por spawner", v -> max = (int) Math.max(1, Math.min(100000, v)));
        Settings.number(this, "limite_por_jogador", 20, "Spawners por jogador (goldspawners.limite.<n> aumenta)", v -> limit = (int) Math.max(1, Math.min(MAX_SPAWNERS, v)));
        Settings.number(this, "raio", 24, "Distancia do jogador para o spawner trabalhar", v -> radius = (int) Math.max(4, Math.min(64, v)));
        data = new PersistentState<>(core.getStore(), "goldspawners", SpawnerState.class, SpawnerState::new, SpawnerState::validate);
        data.ready().thenRun(() -> getServer().getScheduler().scheduleTask(this::rebuild));
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getScheduler().scheduleRepeatingTask(new PluginTask<GoldSpawners>(this) { @Override public void onRun(int t) { second(); } }, 20);
    }

    @Override public void onDisable() {
        for (Live l : live.values()) { despawnAll(l); }
        flush();
        live.clear(); byEid.clear(); lastHit.clear();
        if (data != null) { data.close(); }
    }

    private void rebuild() {
        if (!isEnabled() || !data.isLoaded()) { return; }
        for (Live l : live.values()) { despawnAll(l); }
        live.clear(); byEid.clear();
        data.read(s -> { for (Map.Entry<String, Spawner> e : s.spawners.entrySet()) { add(e.getKey(), e.getValue()); } return null; });
    }

    private Live add(String key, Spawner sp) {
        Live l = new Live(key, Loc.parse(key), Types.get(sp.type), sp.owner, sp.mobs);
        live.put(key, l); byEid.put(l.eid, l);
        return l;
    }

    static String key(Player p) { return p.getName().toLowerCase(Locale.ROOT); }
    boolean allowedWorld(Level level) { return level != null && worlds.contains(level.getFolderName().toLowerCase(Locale.ROOT)); }

    /** Item do spawner: bloco 52 com o tipo no dano e o nome no item. */
    static Item item(Type type, int count) {
        Item it = Item.get(Block.MONSTER_SPAWNER, type.id, count);
        it.setCustomName(M.t("item_spawner", type.name()));
        return it;
    }

    static final int[] EXTRA_LIMITS = {200, 150, 100, 75, 50, 40, 30, 25};

    int limitOf(Player p) {
        for (int n : EXTRA_LIMITS) { if (n > limit && p.hasPermission("goldspawners.limite." + n)) { return n; } }
        return limit;
    }

    int countOf(String owner) { int n = 0; for (Live l : live.values()) { if (l.owner.equals(owner)) { n++; } } return n; }

    // ---------- colocar e quebrar ----------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void place(BlockPlaceEvent event) {
        Item item = event.getItem();
        if (item.getId() != Block.MONSTER_SPAWNER) { return; }
        Type type = Types.get(item.getDamage());
        Player p = event.getPlayer();
        Block b = event.getBlock();
        if (type == null) { return; }
        if (!data.isLoaded()) { event.setCancelled(); p.sendPopup(M.t("carregando")); return; }
        if (!allowedWorld(b.level)) { event.setCancelled(); p.sendMessage(PREFIX + M.t("so_nos_plots")); return; }
        if (live.size() >= MAX_SPAWNERS) { event.setCancelled(); p.sendMessage(PREFIX + M.t("limite_do_servidor")); return; }
        if (countOf(key(p)) >= limitOf(p)) { event.setCancelled(); p.sendMessage(PREFIX + M.t("limite_de_spawners", limitOf(p))); return; }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void placed(BlockPlaceEvent event) {
        Item item = event.getItem();
        Type type = item.getId() == Block.MONSTER_SPAWNER ? Types.get(item.getDamage()) : null;
        if (type == null || !data.isLoaded() || !allowedWorld(event.getBlock().level)) { return; }
        Block b = event.getBlock();
        Player p = event.getPlayer();
        String k = Loc.key(b.level.getFolderName(), b.getFloorX(), b.getFloorY(), b.getFloorZ());
        Spawner sp = new Spawner(); sp.type = type.id; sp.owner = key(p);
        add(k, sp);
        data.change(s -> s.spawners.put(k, sp)).whenComplete((v, e) -> {
            if (e != null) { Live l = live.remove(k); if (l != null) { despawnAll(l); byEid.remove(l.eid); } if (p.isOnline()) { p.sendMessage(PREFIX + M.t("erro_ao_salvar")); } return; }
            if (p.isOnline()) { p.sendMessage(PREFIX + M.t("spawner_colocado", type.name())); }
        });
        // O bloco do core nasce com um gerador de zumbis de verdade: tira, quem gera aqui e o plugin.
        getServer().getScheduler().scheduleDelayedTask(() -> {
            BlockEntity tile = b.level.getBlockEntity(b);
            if (tile != null && b.level.getBlockIdAt(b.getFloorX(), b.getFloorY(), b.getFloorZ()) == Block.MONSTER_SPAWNER) { tile.close(); }
        }, 1);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void breakBlock(BlockBreakEvent event) {
        Block b = event.getBlock();
        if (b.getId() != Block.MONSTER_SPAWNER) { return; }
        String k = Loc.key(b.level.getFolderName(), b.getFloorX(), b.getFloorY(), b.getFloorZ());
        Live l = live.get(k);
        if (l == null) { return; }
        Player p = event.getPlayer();
        if (!l.owner.equals(key(p)) && !p.hasPermission("goldspawners.admin")) { event.setCancelled(); p.sendPopup(M.t("spawner_de_outro")); return; }
        event.setDrops(new Item[0]);
        remove(k);
        Item it = item(l.type, 1);
        for (Item rest : p.getInventory().addItem(it)) { b.level.dropItem(b.add(0.5, 0.5, 0.5), rest); }
        p.sendMessage(PREFIX + M.t("spawner_removido", l.type.name()));
    }

    private void remove(String k) {
        Live l = live.remove(k);
        if (l == null) { return; }
        despawnAll(l); byEid.remove(l.eid); dirty.remove(k);
        data.change(s -> s.spawners.remove(k));
    }

    // ---------- relogio: nascer mobs e mostrar as pilhas ----------

    private void second() {
        if (!data.isLoaded()) { return; }
        double r2 = (double) radius * radius, v2 = (double) viewDistance * viewDistance;
        List<String> gone = new ArrayList<>();
        for (Live l : live.values()) {
            Level level = getServer().getLevelByName(l.loc.world);
            if (level == null || !level.isChunkLoaded(l.loc.x >> 4, l.loc.z >> 4)) { if (!l.viewers.isEmpty()) { despawnAll(l); } continue; }
            if (level.getBlockIdAt(l.loc.x, l.loc.y, l.loc.z) != Block.MONSTER_SPAWNER) { gone.add(l.key); continue; }
            boolean near = false;
            for (Player p : level.getPlayers().values()) {
                double d = p.distanceSquared(new Vector3(l.loc.x + 0.5, l.loc.y + 1, l.loc.z + 0.5));
                if (d <= r2) { near = true; }
                boolean show = l.mobs > 0 && d <= v2 && p.spawned && Boolean.TRUE.equals(p.usedChunks.get(Level.chunkHash(l.loc.x >> 4, l.loc.z >> 4)));
                if (show && !l.viewers.contains(p)) { spawnTo(l, p); } else if (!show && l.viewers.contains(p)) { despawnFrom(l, p); }
            }
            l.viewers.removeIf(p -> p.level != level || !p.isOnline());
            if (near && l.mobs < max && ++l.progress >= interval) {
                l.progress = 0; l.mobs++; dirty.add(l.key);
                if (l.mobs == 1) { for (Player p : level.getPlayers().values()) { if (p.distanceSquared(new Vector3(l.loc.x, l.loc.y, l.loc.z)) <= v2 && Boolean.TRUE.equals(p.usedChunks.get(Level.chunkHash(l.loc.x >> 4, l.loc.z >> 4)))) { spawnTo(l, p); } } }
                else { rename(l); }
            }
        }
        for (String k : gone) { remove(k); }
        if (++seconds % 60 == 0) { flush(); }
    }

    /** Grava as pilhas que mudaram (uma vez por minuto e ao desligar). */
    private void flush() {
        if (dirty.isEmpty() || data == null || !data.isLoaded()) { return; }
        Map<String, Integer> counts = new HashMap<>();
        for (String k : dirty) { Live l = live.get(k); if (l != null) { counts.put(k, l.mobs); } }
        dirty.clear();
        data.change(s -> { for (Map.Entry<String, Integer> e : counts.entrySet()) { Spawner sp = s.spawners.get(e.getKey()); if (sp != null) { sp.mobs = e.getValue(); } } });
    }

    private String label(Live l) { return M.t("nome_pilha", l.mobs, l.type.name()); }

    private void spawnTo(Live l, Player p) {
        l.viewers.add(p);
        l.shown = label(l);
        AddEntityPacket pk = new AddEntityPacket();
        pk.eid = l.eid; pk.type = l.type.network;
        pk.x = l.loc.x + 0.5f; pk.y = l.loc.y + 1f; pk.z = l.loc.z + 0.5f;
        pk.yaw = 0; pk.pitch = 0;
        pk.metadata = new EntityMetadata()
                .putByte(Entity.DATA_FLAGS, 0)
                .putShort(Entity.DATA_AIR, 300)
                .putString(Entity.DATA_NAMETAG, l.shown)
                .putBoolean(Entity.DATA_SHOW_NAMETAG, true)
                .putBoolean(Entity.DATA_SILENT, true)
                .putBoolean(Entity.DATA_NO_AI, true)
                .putLong(Entity.DATA_LEAD_HOLDER, -1)
                .putByte(Entity.DATA_LEAD, 0);
        p.dataPacket(pk);
    }

    private void despawnFrom(Live l, Player p) {
        if (!l.viewers.remove(p) || !p.isConnected()) { return; }
        RemoveEntityPacket pk = new RemoveEntityPacket();
        pk.eid = l.eid;
        p.dataPacket(pk);
    }

    private void despawnAll(Live l) { for (Player p : new ArrayList<>(l.viewers)) { despawnFrom(l, p); } }

    private void rename(Live l) {
        String name = label(l);
        if (name.equals(l.shown) || l.viewers.isEmpty()) { l.shown = name; return; }
        l.shown = name;
        SetEntityDataPacket pk = new SetEntityDataPacket();
        pk.eid = l.eid;
        pk.metadata = new EntityMetadata().putString(Entity.DATA_NAMETAG, name);
        Server.broadcastPacket(l.viewers, pk);
    }

    // ---------- matar: toque no mob ----------

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void packet(DataPacketReceiveEvent event) {
        if (event.getPacket().pid() != ProtocolInfo.INTERACT_PACKET) { return; }
        Live l = byEid.get(((InteractPacket) event.getPacket()).target);
        if (l == null) { return; }
        event.setCancelled();
        hit(event.getPlayer(), l);
    }

    /** Um toque = um mob a menos e os drops dele no inventario. Inventario cheio: nao mata (nada se perde). */
    void hit(Player p, Live l) {
        if (!p.spawned || !p.isAlive() || p.level == null || !p.level.getFolderName().equals(l.loc.world) || l.mobs <= 0) { return; }
        if (p.distanceSquared(new Vector3(l.loc.x + 0.5, l.loc.y + 1, l.loc.z + 0.5)) > 36) { return; }
        long now = System.currentTimeMillis();
        Long last = lastHit.get(p);
        if (last != null && now - last < 250) { return; }
        lastHit.put(p, now);
        List<Item> drops = new ArrayList<>();
        for (int[] d : l.type.drops) { drops.add(Item.get(d[0], d[1], d[2])); }
        for (Item d : drops) { if (!p.getInventory().canAddItem(d)) { p.sendPopup(M.t("inventario_cheio")); return; } }
        for (Item d : drops) { p.getInventory().addItem(d); }
        l.mobs--; dirty.add(l.key);
        EntityEventPacket hurt = new EntityEventPacket();
        hurt.eid = l.eid; hurt.event = l.mobs == 0 ? EntityEventPacket.DEATH_ANIMATION : EntityEventPacket.HURT_ANIMATION;
        Server.broadcastPacket(l.viewers, hurt);
        if (l.mobs == 0) { getServer().getScheduler().scheduleDelayedTask(() -> { if (l.mobs == 0) { despawnAll(l); } }, 15); }
        else { rename(l); }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void quit(PlayerQuitEvent event) {
        Player p = event.getPlayer();
        lastHit.remove(p);
        for (Live l : live.values()) { l.viewers.remove(p); }
    }

    // ---------- comandos ----------

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        try {
            String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("tipos")) {
                for (Type t : Types.ALL) {
                    StringBuilder d = new StringBuilder();
                    for (int[] x : t.drops) { d.append(d.length() == 0 ? "" : ", ").append(x[2]).append("x ").append(Item.get(x[0], x[1]).getName()); }
                    sender.sendMessage(M.t("linha_tipo", t.id, t.name(), d));
                }
                return true;
            }
            if (sub.equals("dar")) {
                if (!sender.hasPermission("goldspawners.admin")) { throw new IllegalArgumentException(M.t("sem_permissao")); }
                if (args.length < 3) { throw new IllegalArgumentException(M.t("uso")); }
                Player target = getServer().getPlayerExact(args[1]);
                if (target == null) { throw new IllegalArgumentException(M.t("jogador_offline")); }
                Type t = Types.parse(args[2]);
                int n = args.length > 3 ? Math.max(1, Math.min(64, Integer.parseInt(args[3]))) : 1;
                for (Item rest : target.getInventory().addItem(item(t, n))) { target.level.dropItem(target, rest); }
                sender.sendMessage(PREFIX + M.t("spawner_dado", n, t.name(), target.getName()));
                return true;
            }
            if (!(sender instanceof Player)) { sender.sendMessage(M.t("uso")); return true; }
            Player p = (Player) sender;
            String me = key(p);
            int count = 0, mobs = 0;
            Map<String, Integer> byType = new TreeMap<>();
            for (Live l : live.values()) { if (l.owner.equals(me)) { count++; mobs += l.mobs; byType.merge(l.type.name(), 1, Integer::sum); } }
            p.sendMessage(PREFIX + M.t("seus_spawners", count, limitOf(p), mobs));
            for (Map.Entry<String, Integer> e : byType.entrySet()) { p.sendMessage(M.t("linha_seu_tipo", e.getValue(), e.getKey())); }
            p.sendMessage(M.t("dica_spawners"));
            return true;
        } catch (RuntimeException e) { sender.sendMessage(PREFIX + "§c" + e.getMessage()); return true; }
    }
}
