package br.goldsuite.party;

import br.goldsuite.api.*;
import cn.nukkit.Player;
import cn.nukkit.block.Block;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.entity.Entity;
import cn.nukkit.entity.projectile.EntityProjectile;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.block.BlockBreakEvent;
import cn.nukkit.event.block.BlockPlaceEvent;
import cn.nukkit.event.entity.*;
import cn.nukkit.event.inventory.InventoryPickupArrowEvent;
import cn.nukkit.event.inventory.InventoryPickupItemEvent;
import cn.nukkit.event.player.*;
import cn.nukkit.item.Item;
import cn.nukkit.level.Level;
import cn.nukkit.level.generator.Flat;
import cn.nukkit.math.Vector3;
import cn.nukkit.plugin.PluginBase;
import java.util.*;

/**
 * Party: varias provas curtas sorteadas por partida, pontos por colocacao em cada uma e ranking final.
 * Varias salas rodam juntas, cada uma na sua vaga do mundo "party" com as proprias arenas. Um unico tick atende todas
 * as salas e uma fila de blocos com limite por tick evita picos ao montar ou limpar arenas.
 */
public final class GoldParty extends PluginBase implements Listener {
    static String PREFIX = "§d[§fParty§d]§f ";
    static final String WORLD = "party";

    Level world;
    Blocks blocks;
    private PersistentState<PartyState> data;
    final List<Room> rooms = new ArrayList<>();
    private final List<Room> closing = new ArrayList<>();
    final Map<Player, Room> byPlayer = new HashMap<>();
    private int nextId = 1;
    int minPlayers = 3, maxPlayers = 8, countdown = 30, perMatch = 5, maxRooms = 6;
    long prize1 = 30000, prize2 = 15000, prize3 = 7500;
    String lobbyWorld = "";
    final Set<String> disabled = new HashSet<>();

    @Override public void onEnable() {
        Settings.prefix(this, PREFIX, v -> PREFIX = v);
        Settings.number(this, "minimo_jogadores", 3, "Jogadores para comecar a contagem", v -> minPlayers = (int) Math.max(2, Math.min(8, v)));
        Settings.number(this, "maximo_jogadores", 8, "Jogadores por sala (ate 8)", v -> maxPlayers = (int) Math.max(2, Math.min(8, v)));
        Settings.number(this, "contagem", 30, "Segundos de contagem (cai para 10 com a sala cheia)", v -> countdown = (int) Math.max(5, Math.min(120, v)));
        Settings.number(this, "provas_por_partida", 5, "Quantas provas sao sorteadas por partida", v -> perMatch = (int) Math.max(1, Math.min(Minis.ALL.size(), v)));
        Settings.number(this, "maximo_salas", 6, "Salas jogando ao mesmo tempo", v -> maxRooms = (int) Math.max(1, Math.min(32, v)));
        Settings.number(this, "premio_1", 30000, "Premio do 1o lugar (centavos)", v -> prize1 = v);
        Settings.number(this, "premio_2", 15000, "Premio do 2o lugar (centavos)", v -> prize2 = v);
        Settings.number(this, "premio_3", 7500, "Premio do 3o lugar (centavos)", v -> prize3 = v);
        Settings.text(this, "provas_desligadas", "nenhuma", "Provas fora do sorteio, separadas por virgula (ex.: sumo,water_drop)", v -> { disabled.clear(); for (String s : v.toLowerCase(Locale.ROOT).split("[,\\s]+")) { if (!s.isEmpty()) { disabled.add(s); } } });
        Settings.text(this, "mundo_lobby", "", "Mundo para onde os jogadores voltam (vazio = mundo padrao)", v -> lobbyWorld = v);
        CoreService core = getServer().getServiceManager().load(CoreService.class);
        if (core == null) { throw new IllegalStateException("GoldCore precisa estar ativo antes do GoldParty."); }
        data = new PersistentState<>(core.getStore(), "goldparty", PartyState.class, PartyState::new, PartyState::validate);
        world = openWorld();
        blocks = new Blocks(world);
        Settings.number(this, "blocos_por_tick", 3000, "Limite de blocos montados por tick somando todas as salas", v -> blocks.perTick = (int) Math.max(200, Math.min(20000, v)));
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getScheduler().scheduleRepeatingTask(new cn.nukkit.scheduler.PluginTask<GoldParty>(this) { @Override public void onRun(int t) { tick(); } }, 1);
        data.ready().thenRun(() -> {
            RankingService ranking = getServer().getServiceManager().load(RankingService.class);
            if (ranking != null) { ranking.register("party", this::top); }
        });
    }

    @Override public void onDisable() {
        for (Player p : new ArrayList<>(byPlayer.keySet())) { toLobby(p); }
        byPlayer.clear(); rooms.clear();
        if (data != null) { data.close(); }
    }

    /** Mundo vazio do Party (criado na primeira vez), sempre de dia. */
    private Level openWorld() {
        if (!getServer().isLevelGenerated(WORLD)) {
            Map<String, Object> o = new HashMap<>();
            o.put("preset", "2;0;1;");
            getServer().generateLevel(WORLD, 1L, Flat.class, o);
        }
        if (getServer().getLevelByName(WORLD) == null) { getServer().loadLevel(WORLD); }
        Level lv = getServer().getLevelByName(WORLD);
        lv.setTime(6000);
        lv.stopTime();
        return lv;
    }

    private List<RankingService.Entry> top(int limit) {
        List<RankingService.Entry> out = new ArrayList<>();
        if (!data.isLoaded()) { return out; }
        for (Map.Entry<String, PartyState.Stats> e : data.read(s -> s.top(limit))) { out.add(new RankingService.Entry(e.getKey(), e.getValue().wins, e.getValue().wins + " vitorias")); }
        return out;
    }

    boolean isBuilt(String key) { return data.isLoaded() && data.read(s -> s.built.contains(key)); }
    void markBuilt(String key) { data.change(s -> s.built.add(key)); }

    // ---------- tick ----------

    private void tick() {
        blocks.tick();
        for (int i = 0; i < rooms.size(); i++) { rooms.get(i).tick(); }
        // A vaga so volta a ser usada depois que a limpeza da arena terminou.
        for (Iterator<Room> it = closing.iterator(); it.hasNext(); ) { Room r = it.next(); if (r.queue.empty()) { blocks.unregister(r.queue); it.remove(); } }
    }

    // ---------- salas ----------

    private int freeSlot() {
        Set<Integer> used = new HashSet<>();
        for (Room r : rooms) { used.add(r.slot); }
        for (Room r : closing) { used.add(r.slot); }
        for (int s = 0; s < maxRooms; s++) { if (!used.contains(s)) { return s; } }
        return -1;
    }

    void join(Player p) {
        if (byPlayer.containsKey(p)) { p.sendMessage(PREFIX + "Voce ja esta numa sala. /party sair"); return; }
        if (!data.isLoaded()) { p.sendMessage(PREFIX + "Carregando, tente em instantes."); return; }
        for (Item it : p.getInventory().getContents().values()) {
            if (it.getId() != 0 && !it.hasCustomName()) { p.sendMessage(PREFIX + "§cGuarde seus itens antes de entrar (so itens do lobby podem ficar)."); return; }
        }
        Room room = null;
        for (Room r : rooms) { if (r.waiting() && r.players.size() < maxPlayers) { room = r; break; } }
        if (room == null) {
            int slot = freeSlot();
            if (slot < 0) { p.sendMessage(PREFIX + "§cTodas as salas estao jogando. Tente em alguns minutos."); return; }
            room = new Room(this, nextId++, slot);
            rooms.add(room);
            blocks.register(room.queue);
            buildLobby(room);
        }
        byPlayer.put(p, room);
        room.players.add(p);
        p.setGamemode(Player.SURVIVAL);
        room.revive(p);
        p.teleport(room.lobby());
        room.say(PREFIX + room.color(p) + p.getName() + " §fentrou §7(" + room.players.size() + "/" + maxPlayers + ")");
    }

    /** Plataforma de espera da vaga (vidro com borda). */
    private void buildLobby(Room r) {
        int x0 = r.ox() + 40, z0 = r.oz(12) + 40;
        for (int x = 0; x <= 16; x++) {
            for (int z = 0; z <= 16; z++) {
                boolean edge = x == 0 || z == 0 || x == 16 || z == 16;
                r.queue.add(x0 + x, 100, z0 + z, edge ? 155 : 20, 0);
                if (edge) { r.queue.add(x0 + x, 101, z0 + z, 102, 0); }
            }
        }
        // A plataforma fica pronta antes de qualquer outro bloco da fila: carrega ja.
        blocks.tick();
    }

    void leave(Player p, boolean quitting) {
        Room r = byPlayer.remove(p);
        if (r == null) { return; }
        r.players.remove(p);
        r.spectators.remove(p);
        r.frozen.remove(p);
        r.total.remove(p);
        if (!quitting) { toLobby(p); } else { p.setDataFlag(Entity.DATA_FLAGS, Entity.DATA_FLAG_INVISIBLE, false); p.getInventory().clearAll(); }
        r.say(PREFIX + "§7" + p.getName() + " saiu da sala.");
        if (r.players.isEmpty()) { close(r); }
    }

    void toLobby(Player p) {
        p.getInventory().clearAll();
        p.removeAllEffects();
        p.setDataFlag(Entity.DATA_FLAGS, Entity.DATA_FLAG_INVISIBLE, false);
        if (!p.isCreative()) { p.setAllowFlight(false); }
        p.setHealth(p.getMaxHealth());
        p.getFoodData().setLevel(20);
        p.extinguish();
        Level lobby = lobbyWorld.isEmpty() ? null : getServer().getLevelByName(lobbyWorld);
        p.teleport((lobby == null ? getServer().getDefaultLevel() : lobby).getSafeSpawn());
    }

    void opened(Room r) { }

    void close(Room r) {
        if (!rooms.remove(r)) { return; }
        for (Player p : new ArrayList<>(r.players)) { byPlayer.remove(p); toLobby(p); }
        r.players.clear();
        if (r.mini != null) { r.mini.cleanup(r.queue); }
        closing.add(r);
    }

    /** Fim da partida: premios, estatisticas e efeito no campeao. */
    void finished(Room r, List<Player> ranking) {
        EconomyService eco = getServer().getServiceManager().load(EconomyService.class);
        long[] prizes = {prize1, prize2, prize3};
        for (int i = 0; i < ranking.size(); i++) {
            Player p = ranking.get(i);
            String key = p.getName().toLowerCase(Locale.ROOT);
            int pts = r.total.getOrDefault(p, 0), first = r.firsts.getOrDefault(p, 0);
            boolean win = i == 0;
            data.change(s -> { PartyState.Stats st = s.of(key); st.games++; st.points += pts; st.firsts += first; if (win) { st.wins++; } });
            if (i < 3 && eco != null && prizes[i] > 0 && r.players.size() >= 2) { eco.credit(key, EconomyService.Currency.MONEY, prizes[i]); p.sendMessage(PREFIX + "§a+$" + prizes[i] / 100 + " pelo " + (i + 1) + "o lugar!"); }
        }
        if (!ranking.isEmpty()) {
            Player w = ranking.get(0);
            getServer().broadcastMessage(PREFIX + "§6" + w.getName() + " §fvenceu o Party com §e" + r.total.getOrDefault(w, 0) + " pontos§f!");
            EffectService fx = getServer().getServiceManager().load(EffectService.class);
            if (fx != null) { fx.follow(w, EffectService.Shape.CELEBRACAO, 80); }
        }
    }

    // ---------- eventos ----------

    @EventHandler(priority = EventPriority.HIGHEST)
    public void damage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player)) { return; }
        Player p = (Player) e.getEntity();
        Room r = byPlayer.get(p);
        if (r == null) { return; }
        Mini m = r.mini;
        if (r.phase != Room.Phase.JOGANDO || m == null || !r.alive(p)) { e.setCancelled(); return; }
        int cause = e.getCause();
        if (cause == EntityDamageEvent.CAUSE_VOID) { e.setCancelled(); return; }
        Player attacker = null;
        if (e instanceof EntityDamageByEntityEvent) {
            Entity d = ((EntityDamageByEntityEvent) e).getDamager();
            if (d instanceof EntityProjectile && ((EntityProjectile) d).shootingEntity instanceof Player) { d = ((EntityProjectile) d).shootingEntity; }
            if (!(d instanceof Player) || d == p || byPlayer.get(d) != r || !r.alive((Player) d) || !m.pvp()) { e.setCancelled(); return; }
            attacker = (Player) d;
            m.lastHit.put(p, attacker); m.lastHitAt.put(p, System.currentTimeMillis());
            e.setCancelled(false);
            if (m.hit(p, attacker, e)) { return; }
            if (m.knockbackOnly()) { e.setDamage(0); return; }
        } else if (cause == EntityDamageEvent.CAUSE_FALL && m.fallDamage()) {
            e.setCancelled(false);
        } else { e.setCancelled(); return; }
        if (e.getFinalDamage() >= p.getHealth()) {
            e.setCancelled();
            m.lethal(p, attacker != null ? attacker : m.recentAttacker(p));
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void interact(PlayerInteractEvent e) {
        Room r = byPlayer.get(e.getPlayer());
        if (r == null || e.getAction() == PlayerInteractEvent.PHYSICAL) { return; }
        if (r.phase != Room.Phase.JOGANDO || r.mini == null || !r.alive(e.getPlayer())) { e.setCancelled(); return; }
        r.mini.interact(e.getPlayer(), e);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void place(BlockPlaceEvent e) {
        Room r = byPlayer.get(e.getPlayer());
        if (r == null) { return; }
        if (r.phase == Room.Phase.JOGANDO && r.mini instanceof Minis.PvpMlg && r.alive(e.getPlayer())) {
            Block b = e.getBlock();
            e.setCancelled(false);
            ((Minis.PvpMlg) r.mini).placed((int) b.x, (int) b.y, (int) b.z);
            return;
        }
        e.setCancelled();
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void breakBlock(BlockBreakEvent e) { if (byPlayer.containsKey(e.getPlayer())) { e.setCancelled(); } }

    @EventHandler(priority = EventPriority.HIGH)
    public void bucket(PlayerBucketEmptyEvent e) {
        Room r = byPlayer.get(e.getPlayer());
        if (r == null) { return; }
        e.setCancelled();
        if (r.phase == Room.Phase.JOGANDO && r.mini instanceof Minis.PvpMlg && r.alive(e.getPlayer())) {
            Block side = e.getBlockClicked().getSide(e.getBlockFace());
            if (side.getId() == 0) { ((Minis.PvpMlg) r.mini).water((int) side.x, (int) side.y, (int) side.z); }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void drop(PlayerDropItemEvent e) { if (byPlayer.containsKey(e.getPlayer())) { e.setCancelled(); } }

    @EventHandler(priority = EventPriority.HIGH)
    public void pickup(InventoryPickupItemEvent e) { if (e.getInventory().getHolder() instanceof Player && byPlayer.containsKey(e.getInventory().getHolder())) { e.setCancelled(); } }

    @EventHandler(priority = EventPriority.HIGH)
    public void pickupArrow(InventoryPickupArrowEvent e) { if (e.getInventory().getHolder() instanceof Player && byPlayer.containsKey(e.getInventory().getHolder())) { e.setCancelled(); } }

    @EventHandler(priority = EventPriority.HIGH)
    public void hunger(PlayerFoodLevelChangeEvent e) { if (byPlayer.containsKey(e.getPlayer())) { e.setCancelled(); } }

    /** Flecha que acertou some na hora: menos entidades no mundo. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void projectileHit(ProjectileHitEvent e) {
        Entity en = e.getEntity();
        if (en.level == world) { getServer().getScheduler().scheduleDelayedTask(() -> { if (!en.closed) { en.close(); } }, 1); }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void command(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        if (!byPlayer.containsKey(p) || p.hasPermission("goldparty.admin")) { return; }
        String m = e.getMessage().toLowerCase(Locale.ROOT);
        if (m.startsWith("/party") || m.startsWith("/suporte") || m.startsWith("/finalizar") || m.startsWith("/tell") || m.startsWith("/msg") || m.startsWith("/r ")) { return; }
        e.setCancelled();
        p.sendMessage(PREFIX + "§cDurante o Party so /party sair funciona.");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void quit(PlayerQuitEvent e) { leave(e.getPlayer(), true); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void death(PlayerDeathEvent e) { leave(e.getEntity(), true); }

    /** Saiu do mundo do Party por outro meio (teleporte da staff, por exemplo): sai da sala. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void changeWorld(EntityLevelChangeEvent e) {
        if (e.getEntity() instanceof Player && e.getTarget() != world && byPlayer.containsKey(e.getEntity())) { leave((Player) e.getEntity(), true); }
    }

    // ---------- comandos ----------

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Player p = sender instanceof Player ? (Player) sender : null;
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "entrar";
        switch (sub) {
            case "entrar": if (p != null) { join(p); } return true;
            case "sair": if (p != null) { if (byPlayer.containsKey(p)) { leave(p, false); p.sendMessage(PREFIX + "Voce saiu do Party."); } else { p.sendMessage(PREFIX + "Voce nao esta numa sala."); } } return true;
            case "top": {
                int i = 1;
                sender.sendMessage(PREFIX + "§6Top vitorias:");
                for (RankingService.Entry e : top(10)) { sender.sendMessage("§e" + (i++) + ". §f" + e.player + " §7- " + e.label); }
                return true;
            }
            case "stats": {
                String name = (args.length > 1 ? args[1] : sender.getName()).toLowerCase(Locale.ROOT);
                PartyState.Stats st = data.read(s -> s.stats.get(name));
                sender.sendMessage(st == null ? PREFIX + "Sem partidas." : PREFIX + name + ": §e" + st.wins + " §fvitorias, " + st.games + " partidas, " + st.points + " pontos, " + st.firsts + " provas em 1o");
                return true;
            }
            case "salas": {
                sender.sendMessage(PREFIX + rooms.size() + " sala(s), limite " + maxRooms + ":");
                for (Room r : rooms) { sender.sendMessage("§e#" + r.id + " §7vaga " + r.slot + " §f" + r.phase + " §7" + r.players.size() + " jogador(es)" + (r.mini != null ? " - " + r.mini.name() : "")); }
                return true;
            }
            case "iniciar": {
                if (!sender.hasPermission("goldparty.admin")) { sender.sendMessage("§cSem permissao."); return true; }
                Room r = p != null ? byPlayer.get(p) : null;
                if (r == null || !r.waiting()) { sender.sendMessage(PREFIX + "Entre numa sala em espera primeiro."); return true; }
                r.forced = true;
                sender.sendMessage(PREFIX + "Comecando em 5 s (mesmo sem o minimo).");
                return true;
            }
            case "reconstruir": {
                if (!sender.hasPermission("goldparty.admin")) { sender.sendMessage("§cSem permissao."); return true; }
                data.change(s -> s.built.clear()).whenComplete((v, e) -> sender.sendMessage(PREFIX + "As arenas serao montadas de novo no proximo uso."));
                return true;
            }
            default:
                sender.sendMessage(PREFIX + "/party entrar, /party sair, /party top, /party stats [nick], /party salas" + (sender.hasPermission("goldparty.admin") ? ", /party iniciar, /party reconstruir" : ""));
                return true;
        }
    }
}
