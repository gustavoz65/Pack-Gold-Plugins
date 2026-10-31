package br.goldsuite.bedwars;

import br.goldsuite.api.*;
import cn.nukkit.Player;
import cn.nukkit.block.Block;
import cn.nukkit.block.BlockTNT;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.entity.Entity;
import cn.nukkit.entity.item.EntityItem;
import cn.nukkit.entity.projectile.EntityProjectile;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.block.BlockBreakEvent;
import cn.nukkit.event.block.BlockPlaceEvent;
import cn.nukkit.event.entity.*;
import cn.nukkit.event.inventory.InventoryPickupItemEvent;
import cn.nukkit.event.player.*;
import cn.nukkit.item.Item;
import cn.nukkit.item.enchantment.Enchantment;
import cn.nukkit.level.Explosion;
import cn.nukkit.level.Level;
import cn.nukkit.level.Location;
import cn.nukkit.level.Position;
import cn.nukkit.level.particle.FloatingTextParticle;
import cn.nukkit.level.sound.ClickSound;
import cn.nukkit.math.AxisAlignedBB;
import cn.nukkit.math.Vector3;
import cn.nukkit.plugin.PluginBase;
import cn.nukkit.potion.Effect;
import cn.nukkit.scheduler.PluginTask;
import com.google.gson.Gson;
import java.util.*;

/**
 * BedWars no padrao do MushMC/Hypixel: times com cama, respawn enquanto a cama existir (abate final quando nao),
 * forja da base, diamante e esmeralda no meio com niveis, loja, melhorias de time, linha do tempo de eventos e itens
 * especiais (bola de fogo, ovo-ponte, perola, TNT automatica). So se quebram blocos colocados na partida e as camas.
 */
public final class GoldBedWars extends PluginBase implements Listener {
    static String PREFIX = "§c[§fBed§cWars]§f ";
    static final String STATS = "goldbw-stats";
    static final String LEAVE = "§r§cSair §7(toque)", TEAMS = "§r§eEscolher time §7(toque)", FIREBALL = "§r§6Bola de Fogo", PEARL = "§r§5Perola do Fim", EGG = "§r§eOvo-Ponte";
    private static final Gson GSON = new Gson();
    private static final Set<String> ALLOWED = new HashSet<>(Arrays.asList("bw", "bedwars", "tell", "msg", "w", "r", "login", "l", "fps"));
    /** Blocos que a bola de fogo e a TNT podem destruir (so os colocados na partida). Vidro, pedra do end e obsidiana resistem. */
    private static final Set<Integer> SOFT = new HashSet<>(Arrays.asList(35, 159, 5, 65, 19));

    enum Phase { ESPERANDO, CONTAGEM, JOGANDO, FIM, RESETANDO }

    static final class Kit { int armor, pickaxe, axe; boolean shears; }

    final class Game {
        final String name;
        Phase phase = Phase.ESPERANDO;
        int seconds, elapsed;
        boolean forced;
        final Map<Player, String> team = new IdentityHashMap<>();
        final Map<Player, String> wanted = new IdentityHashMap<>();
        final Set<Player> finalDead = Collections.newSetFromMap(new IdentityHashMap<>());
        final Map<Player, Integer> respawn = new IdentityHashMap<>();
        final Set<String> beds = new HashSet<>();
        final Set<Long> placed = new HashSet<>();
        final Map<String, Map<String, Integer>> upgrades = new HashMap<>();
        final Map<Player, Kit> kits = new IdentityHashMap<>();
        final Map<Player, Object[]> lastHit = new IdentityHashMap<>();
        final Map<Player, int[]> score = new IdentityHashMap<>();
        final Map<String, Boolean> trapArmed = new HashMap<>();
        final Map<Object, Integer> timers = new HashMap<>();
        final List<FloatingTextParticle> labels = new ArrayList<>();
        int tick;
        Game(String name) { this.name = name; }
        BwState.Arena arena() { return data.read(s -> s.arenas.get(name)); }
        Level level() { BwState.Arena a = arena(); return a == null ? null : getServer().getLevelByName(a.world); }
        boolean waiting() { return phase == Phase.ESPERANDO || phase == Phase.CONTAGEM; }
        int capacity() { BwState.Arena a = arena(); return a == null ? 0 : a.teams.size() * a.teamSize; }
        boolean alive(Player p) { return team.containsKey(p) && !finalDead.contains(p); }
        int level(String t, String up) { return upgrades.computeIfAbsent(t, k -> new HashMap<>()).getOrDefault(up, 0); }
        Set<String> teamsAlive() { Set<String> out = new TreeSet<>(); for (Map.Entry<Player, String> e : team.entrySet()) { if (!finalDead.contains(e.getKey())) { out.add(e.getValue()); } } return out; }
    }

    CoreService core;
    PersistentState<BwState> data;
    MapStore maps;
    final Map<String, Game> games = new HashMap<>();
    final Map<Player, Game> inGame = new IdentityHashMap<>();
    final Map<Player, BwState.Stats> stats = new IdentityHashMap<>();
    final Set<Player> flying = Collections.newSetFromMap(new IdentityHashMap<>());
    final Map<Long, String> special = new HashMap<>();
    long minPlayers = 2, countdown = 20, killReward = 1000, finalReward = 2500, bedReward = 3000, winReward = 15000;
    String lobbyWorld = "";

    @Override public void onEnable() {
        Settings.prefix(this, PREFIX, v -> PREFIX = v);
        Settings.number(this, "minimo_jogadores", 2, "Jogadores para comecar a contagem", v -> minPlayers = Math.max(2, v));
        Settings.number(this, "contagem", 20, "Segundos de contagem (cai para 10 com a sala cheia)", v -> countdown = Math.max(5, v));
        Settings.number(this, "premio_abate", 1000, "Dinheiro por abate (centavos)", v -> killReward = v);
        Settings.number(this, "premio_abate_final", 2500, "Dinheiro por abate final (centavos)", v -> finalReward = v);
        Settings.number(this, "premio_cama", 3000, "Dinheiro por cama destruida (centavos)", v -> bedReward = v);
        Settings.number(this, "premio_vitoria", 15000, "Dinheiro por vitoria (centavos)", v -> winReward = v);
        Settings.text(this, "mundo_lobby", "", "Mundo para onde os jogadores voltam (vazio = mundo padrao)", v -> lobbyWorld = v);
        core = getServer().getServiceManager().load(CoreService.class);
        if (core == null) { throw new IllegalStateException("GoldCore precisa estar ativo antes do GoldBedWars."); }
        maps = new MapStore(getServer(), getDataFolder().toPath());
        data = new PersistentState<>(core.getStore(), "goldbedwars", BwState.class, BwState::new, BwState::validate);
        getServer().getPluginManager().registerEvents(this, this);
        data.ready().whenComplete((v, e) -> {
            if (e != null || !isEnabled()) { return; }
            for (Map.Entry<String, BwState.Arena> a : data.read(s -> new TreeMap<>(s.arenas)).entrySet()) {
                if (getServer().getLevelByName(a.getValue().world) == null && getServer().isLevelGenerated(a.getValue().world)) { getServer().loadLevel(a.getValue().world); }
                games.put(a.getKey(), new Game(a.getKey()));
            }
            getLogger().info(games.size() + " arena(s) de BedWars pronta(s).");
            RankingService ranking = getServer().getServiceManager().load(RankingService.class);
            if (ranking != null) { ranking.register("bedwars", this::topWins); }
        });
        for (Player p : getServer().getOnlinePlayers().values()) { loadStats(p); }
        getServer().getScheduler().scheduleRepeatingTask(new PluginTask<GoldBedWars>(this) { @Override public void onRun(int t) { second(); } }, 20);
        // Forja e geradores: um passo a cada 5 ticks, so arenas em jogo.
        getServer().getScheduler().scheduleRepeatingTask(new PluginTask<GoldBedWars>(this) { @Override public void onRun(int t) { generators(); } }, 5);
    }

    @Override public void onDisable() {
        for (Player p : new ArrayList<>(inGame.keySet())) { toLobby(p); }
        for (Player p : new ArrayList<>(stats.keySet())) { saveStats(p); }
        games.clear(); inGame.clear(); special.clear();
        if (data != null) { data.close(); }
    }

    private List<RankingService.Entry> topWins(int limit) {
        List<RankingService.Entry> out = new ArrayList<>();
        if (!data.isLoaded()) { return out; }
        List<Map.Entry<String, Integer>> list = data.read(s -> new ArrayList<>(s.topWins.entrySet()));
        list.sort((a, b) -> b.getValue() - a.getValue());
        for (int i = 0; i < list.size() && i < limit; i++) { out.add(new RankingService.Entry(list.get(i).getKey(), list.get(i).getValue(), list.get(i).getValue() + " vitorias")); }
        return out;
    }

    static String key(Player p) { return p.getName().toLowerCase(Locale.ROOT); }

    void loadStats(Player p) {
        core.getStore().get(STATS, key(p)).thenAccept(v -> {
            if (!p.isOnline()) { return; }
            BwState.Stats s = new BwState.Stats();
            if (v.isPresent()) { try { s = GSON.fromJson(v.get(), BwState.Stats.class); } catch (RuntimeException bad) { s = new BwState.Stats(); } }
            stats.put(p, s);
        });
    }
    void saveStats(Player p) { BwState.Stats s = stats.get(p); if (s != null) { core.getStore().put(STATS, key(p), GSON.toJson(s)); } }
    BwState.Stats stats(Player p) { return stats.computeIfAbsent(p, k -> new BwState.Stats()); }

    // ---------- entrar e sair ----------

    void join(Player p, int size) {
        if (inGame.containsKey(p)) { throw new IllegalArgumentException("Voce ja esta numa partida. Use /bw sair."); }
        for (Item i : p.getInventory().getContents().values()) { if (i.getId() != 0 && !(i.hasCustomName() && i.getCustomName().contains("(toque)"))) { throw new IllegalArgumentException("Esvazie o inventario antes de entrar no BedWars."); } }
        for (Item i : p.getInventory().getArmorContents()) { if (i.getId() != 0) { throw new IllegalArgumentException("Tire a armadura antes de entrar no BedWars."); } }
        Game best = null;
        for (Game g : games.values()) {
            BwState.Arena a = g.arena();
            if (a == null || a.teamSize != size || !g.waiting() || g.level() == null || g.team.size() >= g.capacity() || a.waiting.length != 4) { continue; }
            if (best == null || g.team.size() > best.team.size()) { best = g; }
        }
        if (best == null) { throw new IllegalArgumentException("Nenhuma sala aberta nesse modo agora."); }
        Game g = best;
        g.team.put(p, "");
        inGame.put(p, g);
        clean(p);
        double[] w = g.arena().waiting;
        p.teleport(new Location(w[0], w[1], w[2], w[3], 0, g.level()));
        p.getInventory().setItem(0, Items.named(Item.get(35, 0, 1), TEAMS));
        p.getInventory().setItem(8, Items.named(Item.get(355, 0, 1), LEAVE));
        p.getInventory().sendContents(p);
        broadcast(g, "§7" + p.getName() + " §eentrou (§f" + g.team.size() + "/" + g.capacity() + "§e)");
    }

    void clean(Player p) {
        p.getInventory().clearAll(); p.removeAllEffects(); p.extinguish();
        p.setHealth(p.getMaxHealth()); p.getFoodData().setLevel(20); p.setGamemode(Player.SURVIVAL);
        p.setDataFlag(Entity.DATA_FLAGS, Entity.DATA_FLAG_INVISIBLE, false);
        if (flying.remove(p) && !p.isCreative()) { p.setAllowFlight(false); }
    }

    void leave(Player p) {
        Game g = inGame.get(p);
        if (g == null) { return; }
        if (g.phase == Phase.JOGANDO && g.alive(p)) { die(g, p, lastHitter(g, p), true, "§7" + p.getName() + " §esaiu da partida."); }
        g.team.remove(p); g.wanted.remove(p); g.finalDead.remove(p); g.respawn.remove(p); g.kits.remove(p); g.score.remove(p);
        toLobby(p);
        if (g.phase == Phase.JOGANDO) { checkWin(g); }
    }

    void toLobby(Player p) {
        inGame.remove(p);
        clean(p);
        Level lobby = lobbyWorld.isEmpty() ? null : getServer().getLevelByName(lobbyWorld);
        if (lobby == null) { lobby = getServer().getDefaultLevel(); }
        p.teleport(lobby.getSafeSpawn());
    }

    // ---------- ciclo de 1 segundo ----------

    void second() {
        for (Game g : games.values()) {
            Level lv = g.level();
            if (lv == null || g.phase == Phase.RESETANDO) { continue; }
            int min = (int) Math.min(minPlayers, Math.max(2, g.capacity()));
            switch (g.phase) {
                case ESPERANDO:
                    if (g.team.size() >= min || (g.forced && !g.team.isEmpty())) { g.phase = Phase.CONTAGEM; g.seconds = g.forced ? 5 : (int) countdown; broadcast(g, "§aA partida comeca em §f" + g.seconds + "s§a!"); }
                    for (Player p : g.team.keySet()) { p.sendTip("§eAguardando jogadores §f" + g.team.size() + "/" + g.capacity()); }
                    break;
                case CONTAGEM:
                    if (g.team.size() < min && !g.forced) { g.phase = Phase.ESPERANDO; broadcast(g, "§cJogadores insuficientes, contagem cancelada."); break; }
                    if (g.team.size() >= g.capacity() && g.seconds > 10) { g.seconds = 10; }
                    g.seconds--;
                    for (Player p : g.team.keySet()) { p.sendTip("§aComeca em §f" + g.seconds + "s" + (g.wanted.containsKey(p) ? " §7| " + BwState.label(g.wanted.get(p)) : "")); if (g.seconds <= 5) { lv.addSound(new ClickSound(p), p); } }
                    if (g.seconds <= 0) { start(g); }
                    break;
                case JOGANDO: playing(g, lv); break;
                case FIM: if (--g.seconds <= 0) { reset(g); } break;
                default:
            }
        }
    }

    /** Distribui os times (respeita a escolha se couber, completa o resto equilibrado) e comeca. Times vazios perdem a cama. */
    /** Carrega todos os chunks da arena (bases, geradores e camas): forja e camas longe dos jogadores tambem funcionam. */
    void loadArena(Game g) {
        BwState.Arena a = g.arena();
        List<double[]> points = new ArrayList<>(a.diamonds); points.addAll(a.emeralds);
        for (BwState.Team t : a.teams.values()) { if (t.spawn.length > 2) { points.add(t.spawn); } }
        double minX = 1e9, maxX = -1e9, minZ = 1e9, maxZ = -1e9;
        for (double[] p : points) { minX = Math.min(minX, p[0]); maxX = Math.max(maxX, p[0]); minZ = Math.min(minZ, p[2]); maxZ = Math.max(maxZ, p[2]); }
        Level lv = g.level();
        for (int cx = ((int) Math.floor(minX) - 16) >> 4; cx <= ((int) Math.floor(maxX) + 16) >> 4; cx++) { for (int cz = ((int) Math.floor(minZ) - 16) >> 4; cz <= ((int) Math.floor(maxZ) + 16) >> 4; cz++) { lv.loadChunk(cx, cz, false); } }
    }

    void start(Game g) {
        loadArena(g);
        BwState.Arena a = g.arena();
        Map<String, List<Player>> members = new LinkedHashMap<>();
        for (String t : a.teams.keySet()) { members.put(t, new ArrayList<>()); }
        List<Player> rest = new ArrayList<>();
        for (Player p : g.team.keySet()) { String w = g.wanted.get(p); if (w != null && members.containsKey(w) && members.get(w).size() < a.teamSize) { members.get(w).add(p); } else { rest.add(p); } }
        Collections.shuffle(rest);
        for (Player p : rest) { String smallest = null; for (Map.Entry<String, List<Player>> e : members.entrySet()) { if (e.getValue().size() < a.teamSize && (smallest == null || e.getValue().size() < members.get(smallest).size())) { smallest = e.getKey(); } } members.get(smallest).add(p); }
        g.phase = Phase.JOGANDO; g.elapsed = 0; g.forced = false; g.beds.clear(); g.placed.clear(); g.upgrades.clear(); g.timers.clear(); g.trapArmed.clear();
        for (Map.Entry<String, List<Player>> e : members.entrySet()) {
            if (e.getValue().isEmpty()) { breakBedBlocks(g, e.getKey()); continue; }
            g.beds.add(e.getKey());
            for (Player p : e.getValue()) { g.team.put(p, e.getKey()); g.kits.put(p, new Kit()); g.score.put(p, new int[3]); spawn(g, p); p.sendMessage(PREFIX + "Voce esta no time " + BwState.label(e.getKey()) + "§f. Proteja sua cama!"); }
        }
        labels(g);
    }

    void spawn(Game g, Player p) {
        String t = g.team.get(p);
        double[] s = g.arena().teams.get(t).spawn;
        clean(p);
        p.teleport(new Location(s[0], s[1], s[2], s[3], 0, g.level()));
        give(g, p);
    }

    /** Equipamento permanente: armadura do nivel comprado, espada de madeira e as ferramentas (uma abaixo do nivel ao morrer). */
    void give(Game g, Player p) {
        String t = g.team.get(p);
        Kit k = g.kits.get(p);
        int prot = g.level(t, "protecao");
        int wool = BwState.wool(t);
        Item[] armor = {Item.get(298), Item.get(299), Item.get(Shop.ARMOR[k.armor][0]), Item.get(Shop.ARMOR[k.armor][1])};
        for (int i = 0; i < 4; i++) { if (prot > 0) { armor[i].addEnchantment(ench(Enchantment.ID_PROTECTION_ALL, prot)); } p.getInventory().setArmorItem(i, armor[i]); }
        p.getInventory().addItem(sword(g, t, 268));
        if (k.pickaxe > 0) { p.getInventory().addItem(Item.get(Shop.PICKAXE[k.pickaxe - 1])); }
        if (k.axe > 0) { p.getInventory().addItem(Item.get(Shop.AXE[k.axe - 1])); }
        if (k.shears) { p.getInventory().addItem(Item.get(359)); }
        p.getInventory().sendContents(p); p.getInventory().sendArmorContents(p);
        p.setNameTag(BwState.color(t) + "[" + t.substring(0, 1).toUpperCase() + "] " + p.getName());
        p.sendTip(BwState.color(t) + "§lTime " + t + " §r§7| §fLa: " + wool);
    }

    static Enchantment ench(int id, int level) { Enchantment e = Enchantment.getEnchantment(id); e.setLevel(level); return e; }

    Item sword(Game g, String team, int id) { Item s = Item.get(id); if (g.level(team, "afiada") > 0) { s.addEnchantment(ench(Enchantment.ID_DAMAGE_ALL, 1)); } return s; }

    void playing(Game g, Level lv) {
        g.elapsed++;
        for (Object[] ev : Shop.EVENTS) {
            if (g.elapsed != (Integer) ev[0]) { continue; }
            String name = (String) ev[1];
            broadcast(g, "§6§l" + name + "!");
            if (name.startsWith("Camas")) { for (String t : new ArrayList<>(g.beds)) { breakBedBlocks(g, t); } g.beds.clear(); broadcast(g, "§cTodas as camas foram destruidas: agora toda morte e final!"); }
            if (name.startsWith("Fim")) { broadcast(g, "§7Tempo esgotado: empate."); finish(g, null); return; }
        }
        // Respawn em 5 s para quem tem cama.
        for (Iterator<Map.Entry<Player, Integer>> it = g.respawn.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Player, Integer> e = it.next();
            Player p = e.getKey();
            int left = e.getValue() - 1;
            if (left <= 0) { it.remove(); p.setDataFlag(Entity.DATA_FLAGS, Entity.DATA_FLAG_INVISIBLE, false); spawn(g, p); p.sendMessage(PREFIX + "§aVoce renasceu!"); }
            else { e.setValue(left); p.sendTip("§cRenascendo em §f" + left + "s"); }
        }
        double voidY = voidY(g);
        BwState.Arena a = g.arena();
        for (Player p : new ArrayList<>(g.team.keySet())) {
            if (!g.alive(p) || g.respawn.containsKey(p)) { continue; }
            if (p.y < voidY) { die(g, p, lastHitter(g, p), true, null); continue; }
            String t = g.team.get(p);
            double[] base = a.teams.get(t).spawn;
            int haste = g.level(t, "pressa");
            if (haste > 0) { p.addEffect(Effect.getEffect(Effect.HASTE).setAmplifier(haste - 1).setDuration(60).setVisible(false)); }
            if (g.level(t, "cura") > 0 && dist2(p, base) < 144) { p.addEffect(Effect.getEffect(Effect.REGENERATION).setDuration(60).setVisible(false)); }
            // Armadilha: o primeiro inimigo que entra na base de outro time fica cego e lento.
            for (Map.Entry<String, BwState.Team> other : a.teams.entrySet()) {
                if (other.getKey().equals(t) || !Boolean.TRUE.equals(g.trapArmed.get(other.getKey())) || dist2(p, other.getValue().spawn) > 100) { continue; }
                g.trapArmed.put(other.getKey(), false);
                g.upgrades.get(other.getKey()).put("armadilha", 0);
                p.addEffect(Effect.getEffect(Effect.BLINDNESS).setDuration(160)); p.addEffect(Effect.getEffect(Effect.SLOWNESS).setDuration(160));
                for (Map.Entry<Player, String> m : g.team.entrySet()) { if (m.getValue().equals(other.getKey())) { m.getKey().sendMessage(PREFIX + "§c§lALARME! §r§cAlguem ativou sua armadilha!"); } }
            }
            p.sendTip(sidebar(g, p));
        }
        for (Player p : g.finalDead) { p.sendTip(sidebar(g, p)); }
        updateLabels(g);
    }

    static double dist2(Player p, double[] c) { return c.length < 3 ? 1e9 : Math.pow(p.x - c[0], 2) + Math.pow(p.z - c[2], 2); }

    double voidY(Game g) { double min = 1000; for (BwState.Team t : g.arena().teams.values()) { if (t.spawn.length > 1) { min = Math.min(min, t.spawn[1]); } } return min - 30; }

    /** Linha de status: cada time com cama (+), so vivos (numero) ou eliminado (-), e o proximo evento. */
    String sidebar(Game g, Player viewer) {
        StringBuilder s = new StringBuilder();
        for (String t : g.arena().teams.keySet()) {
            int alive = 0; for (Map.Entry<Player, String> e : g.team.entrySet()) { if (e.getValue().equals(t) && !g.finalDead.contains(e.getKey())) { alive++; } }
            s.append(BwState.color(t)).append(Character.toUpperCase(t.charAt(0))).append(g.beds.contains(t) ? "§a+" : alive > 0 ? "§e" + alive : "§8-").append(' ');
        }
        for (Object[] ev : Shop.EVENTS) { int at = (Integer) ev[0]; if (at > g.elapsed) { s.append("§7| §f").append(ev[1]).append(" §7").append(clock(at - g.elapsed)); break; } }
        int[] sc = g.score.getOrDefault(viewer, new int[3]);
        return s.append(" §7| §fAbates §e").append(sc[0]).append(" §fFinais §e").append(sc[1]).toString();
    }

    static final String[] ROMAN = {"", "I", "II", "III"};

    static String clock(int s) { return String.format("%d:%02d", s / 60, s % 60); }

    // ---------- geradores ----------

    void generators() {
        for (Game g : games.values()) {
            if (g.phase != Phase.JOGANDO) { continue; }
            Level lv = g.level();
            if (lv == null) { continue; }
            g.tick += 5;
            BwState.Arena a = g.arena();
            for (Map.Entry<String, BwState.Team> t : a.teams.entrySet()) {
                if (t.getValue().forge.length != 3 || !hasMembers(g, t.getKey())) { continue; }
                int forge = g.level(t.getKey(), "forja");
                int[] rate = Shop.forge(forge);
                drop(g, lv, "f" + t.getKey(), t.getValue().forge, Shop.IRON, rate[0], 48);
                drop(g, lv, "g" + t.getKey(), t.getValue().forge, Shop.GOLD, rate[1], 12);
                if (forge >= 3) { drop(g, lv, "e" + t.getKey(), t.getValue().forge, Shop.EMERALD, 600, 2); }
            }
            int dTier = g.elapsed >= 1080 ? 2 : g.elapsed >= 360 ? 1 : 0, eTier = g.elapsed >= 1440 ? 2 : g.elapsed >= 720 ? 1 : 0;
            for (int i = 0; i < a.diamonds.size(); i++) { drop(g, lv, "d" + i, a.diamonds.get(i), Shop.DIAMOND, Shop.DIAMOND_TIERS[dTier] * 20, 4); }
            for (int i = 0; i < a.emeralds.size(); i++) { drop(g, lv, "m" + i, a.emeralds.get(i), Shop.EMERALD, Shop.EMERALD_TIERS[eTier] * 20, 2); }
        }
    }

    boolean hasMembers(Game g, String t) { for (Map.Entry<Player, String> e : g.team.entrySet()) { if (e.getValue().equals(t) && !g.finalDead.contains(e.getKey())) { return true; } } return false; }

    /** Solta o recurso quando o contador vence; com o limite de itens parados perto, nao solta (nada de pilhas de entidades). */
    void drop(Game g, Level lv, Object id, double[] at, int item, int interval, int cap) {
        int left = g.timers.getOrDefault(id, interval) - 5;
        if (left > 0) { g.timers.put(id, left); return; }
        g.timers.put(id, interval);
        AxisAlignedBB box = new AxisAlignedBB(at[0] - 1.5, at[1] - 1, at[2] - 1.5, at[0] + 1.5, at[1] + 2, at[2] + 1.5);
        int count = 0;
        for (Entity e : lv.getNearbyEntities(box)) { if (e instanceof EntityItem && ((EntityItem) e).getItem().getId() == item) { count += ((EntityItem) e).getItem().getCount(); } }
        if (count >= cap) { return; }
        lv.dropItem(new Vector3(at[0], at[1] + 0.3, at[2]), Item.get(item, 0, 1), new Vector3(0, 0, 0));
    }

    /** Hologramas sobre diamante e esmeralda: nivel e tempo para o proximo. */
    void labels(Game g) {
        g.labels.clear();
        BwState.Arena a = g.arena();
        for (double[] d : a.diamonds) { g.labels.add(new FloatingTextParticle(new Vector3(d[0], d[1] + 2.5, d[2]), "", "§b§lDiamante")); }
        for (double[] e : a.emeralds) { g.labels.add(new FloatingTextParticle(new Vector3(e[0], e[1] + 2.5, e[2]), "", "§a§lEsmeralda")); }
    }

    void updateLabels(Game g) {
        Level lv = g.level();
        BwState.Arena a = g.arena();
        int dTier = g.elapsed >= 1080 ? 3 : g.elapsed >= 360 ? 2 : 1, eTier = g.elapsed >= 1440 ? 3 : g.elapsed >= 720 ? 2 : 1;
        Player[] viewers = lv.getPlayers().values().toArray(new Player[0]);
        for (int i = 0; i < g.labels.size(); i++) {
            boolean diamond = i < a.diamonds.size();
            String key = diamond ? "d" + i : "m" + (i - a.diamonds.size());
            int left = (g.timers.getOrDefault(key, 0) + 19) / 20;
            FloatingTextParticle l = g.labels.get(i);
            l.setTitle(diamond ? "§b§lDiamante " + ROMAN[dTier] : "§a§lEsmeralda " + ROMAN[eTier]);
            l.setText("§eProximo em §f" + left + "s");
            if (viewers.length > 0) { lv.addParticle(l, viewers); }
        }
    }

    // ---------- morte, camas e fim ----------

    Player lastHitter(Game g, Player p) {
        Object[] h = g.lastHit.get(p);
        if (h == null || System.currentTimeMillis() - (Long) h[1] > 15_000) { return null; }
        Player k = (Player) h[0];
        return k.isOnline() && g.alive(k) ? k : null;
    }

    /** Morte: os recursos vao para quem matou; com cama renasce em 5 s (ferramentas caem um nivel), sem cama e abate final. */
    void die(Game g, Player p, Player killer, boolean voidDeath, String customMessage) {
        if (!g.alive(p) || g.respawn.containsKey(p)) { return; }
        String t = g.team.get(p);
        boolean isFinal = !g.beds.contains(t);
        int[] res = new int[4];
        int[] ids = {Shop.IRON, Shop.GOLD, Shop.DIAMOND, Shop.EMERALD};
        for (Item i : p.getInventory().getContents().values()) { for (int k = 0; k < 4; k++) { if (i.getId() == ids[k]) { res[k] += i.getCount(); } } }
        if (killer != null && killer != p) {
            for (int k = 0; k < 4; k++) { if (res[k] > 0) { killer.getInventory().addItem(Item.get(ids[k], 0, res[k])); } }
            int[] sc = g.score.computeIfAbsent(killer, x -> new int[3]);
            BwState.Stats ks = stats(killer);
            if (isFinal) { sc[1]++; ks.finals++; reward(killer, finalReward); } else { sc[0]++; ks.kills++; reward(killer, killReward); }
            saveStats(killer);
        }
        BwState.Stats ps = stats(p); ps.deaths++;
        String msg = customMessage != null ? customMessage : BwState.color(t) + p.getName() + " §7" + (killer != null && killer != p ? (voidDeath ? "foi jogado no vazio por " : "foi morto por ") + BwState.color(g.team.get(killer)) + killer.getName() : (voidDeath ? "caiu no vazio" : "morreu")) + (isFinal ? " §b§lABATE FINAL!" : "");
        broadcast(g, msg);
        Kit k = g.kits.get(p);
        if (k != null) { k.pickaxe = Math.max(k.pickaxe > 0 ? 1 : 0, k.pickaxe - 1); k.axe = Math.max(k.axe > 0 ? 1 : 0, k.axe - 1); }
        ghost(g, p);
        if (isFinal) {
            g.finalDead.add(p); ps.games++;
            p.sendMessage(PREFIX + "§cVoce foi eliminado (sem cama). Assistindo como espectador.");
            if (!hasMembers(g, t)) { broadcast(g, "§c§lTIME ELIMINADO! " + BwState.label(t) + " §cestá fora."); }
            checkWin(g);
        } else { g.respawn.put(p, 5); }
        saveStats(p);
    }

    /** Fantasma: invisivel, voando, sem itens, sobre a base do time. */
    void ghost(Game g, Player p) {
        p.getInventory().clearAll(); p.removeAllEffects(); p.extinguish();
        p.setHealth(p.getMaxHealth()); p.getFoodData().setLevel(20);
        p.setDataFlag(Entity.DATA_FLAGS, Entity.DATA_FLAG_INVISIBLE, true);
        if (!p.getAllowFlight()) { p.setAllowFlight(true); flying.add(p); }
        double[] s = g.arena().teams.get(g.team.get(p)).spawn;
        p.teleport(new Location(s[0], s[1] + 10, s[2], 0, 0, g.level()));
        p.getInventory().setItem(8, Items.named(Item.get(355, 0, 1), LEAVE));
        p.getInventory().sendContents(p);
    }

    void breakBedBlocks(Game g, String t) {
        int[] b = g.arena().teams.get(t).bed;
        if (b.length != 3) { return; }
        Level lv = g.level();
        for (int dx = -1; dx <= 1; dx++) { for (int dz = -1; dz <= 1; dz++) { if (lv.getBlockIdAt(b[0] + dx, b[1], b[2] + dz) == 26) { lv.setBlock(new Vector3(b[0] + dx, b[1], b[2] + dz), Block.get(0), true, false); } } }
    }

    String bedOwner(Game g, Block b) {
        for (Map.Entry<String, BwState.Team> e : g.arena().teams.entrySet()) {
            int[] p = e.getValue().bed;
            if (p.length == 3 && Math.abs(p[0] - b.getFloorX()) <= 1 && Math.abs(p[2] - b.getFloorZ()) <= 1 && Math.abs(p[1] - b.getFloorY()) <= 1) { return e.getKey(); }
        }
        return null;
    }

    void checkWin(Game g) {
        if (g.phase != Phase.JOGANDO) { return; }
        Set<String> alive = g.teamsAlive();
        if (alive.size() <= 1) { finish(g, alive.isEmpty() ? null : alive.iterator().next()); }
    }

    void finish(Game g, String winner) {
        g.phase = Phase.FIM; g.seconds = 8;
        if (winner == null) { return; }
        EffectService fx = getServer().getServiceManager().load(EffectService.class);
        StringBuilder names = new StringBuilder();
        for (Map.Entry<Player, String> e : g.team.entrySet()) {
            if (!e.getValue().equals(winner)) { continue; }
            Player w = e.getKey();
            BwState.Stats s = stats(w); s.wins++; s.games++; saveStats(w);
            String n = key(w); int wins = s.wins;
            data.change(st -> BwState.offer(st.topWins, n, wins));
            reward(w, winReward);
            if (fx != null && w.isOnline()) { fx.follow(w, EffectService.Shape.CELEBRACAO, 80); }
            w.sendMessage(PREFIX + "§6§lVITORIA!");
            names.append(names.length() == 0 ? "" : ", ").append(w.getName());
        }
        getServer().broadcastMessage(PREFIX + "Time " + BwState.label(winner) + " §f(" + names + ") venceu o BedWars em §c" + g.name + "§f!");
    }

    void reward(Player p, long cents) {
        EconomyService eco = getServer().getServiceManager().load(EconomyService.class);
        if (eco != null && cents > 0) { eco.credit(key(p), EconomyService.Currency.MONEY, cents); }
    }

    void reset(Game g) {
        g.phase = Phase.RESETANDO;
        Level lv = g.level();
        for (FloatingTextParticle l : g.labels) { l.setInvisible(true); if (lv != null) { Player[] v = lv.getPlayers().values().toArray(new Player[0]); if (v.length > 0) { lv.addParticle(l, v); } } }
        for (Player p : new ArrayList<>(inGame.keySet())) { if (inGame.get(p) == g) { toLobby(p); } }
        if (lv != null) { for (Player p : new ArrayList<>(lv.getPlayers().values())) { toLobby(p); } }
        g.team.clear(); g.wanted.clear(); g.finalDead.clear(); g.respawn.clear(); g.kits.clear(); g.score.clear(); g.lastHit.clear(); g.labels.clear(); g.placed.clear();
        String world = g.arena().world;
        if (!maps.hasBackup(world)) { g.phase = Phase.ESPERANDO; getLogger().warning("Arena " + g.name + " sem copia do mapa: use /bw salvar."); return; }
        if (lv != null) { getServer().unloadLevel(lv, true); }
        maps.restore(world).whenComplete((v, e) -> { if (e != null) { getLogger().error("Falha ao restaurar " + world, e); } getServer().loadLevel(world); g.phase = Phase.ESPERANDO; });
    }

    void broadcast(Game g, String msg) { for (Player p : g.team.keySet()) { p.sendMessage(PREFIX + msg); } }

    // ---------- loja e melhorias ----------

    MenuService menus() { return getServer().getServiceManager().load(MenuService.class); }

    boolean nearBase(Game g, Player p) { return dist2(p, g.arena().teams.get(g.team.get(p)).spawn) <= 400; }

    void shop(Player p, String category) {
        Game g = inGame.get(p);
        if (g == null || g.phase != Phase.JOGANDO || !g.alive(p) || g.respawn.containsKey(p)) { p.sendMessage(PREFIX + "A loja abre so durante a partida."); return; }
        if (!nearBase(g, p)) { p.sendMessage(PREFIX + "§cA loja fica na sua base."); return; }
        Map<Integer, Item> icons = new HashMap<>();
        List<String> cats = new ArrayList<>(Shop.CATEGORIES.keySet());
        for (int i = 0; i < cats.size(); i++) { String[] ic = Shop.CATEGORY_ICON.get(cats.get(i)).split(":"); icons.put(i, Items.named(Item.get(Integer.parseInt(ic[0]), Integer.parseInt(ic[1]), 1), (cats.get(i).equals(category) ? "§a§l" : "§e") + cats.get(i))); }
        List<Shop.Offer> offers = category == null ? Collections.emptyList() : Shop.CATEGORIES.get(category);
        Kit k = g.kits.get(p);
        String t = g.team.get(p);
        for (int i = 0; i < offers.size(); i++) {
            Shop.Offer o = offers.get(i);
            int[] cost = cost(o, k);
            String[] ic = o.icon.split(":");
            int meta = o.give.startsWith("wool") || o.give.startsWith("clay") ? BwState.wool(t) : Integer.parseInt(ic[1]);
            String label = cost == null ? "§aNivel maximo" : (has(p, cost[0], cost[1]) ? "§a" : "§c") + Shop.currency(cost[0], cost[1]);
            icons.put(9 + i, Items.named(Item.get(Integer.parseInt(ic[0]), meta, 1), "§f" + o.name + "\n" + label));
        }
        menus().open(p, category == null ? "Loja" : "Loja: " + category, icons, (who, slot) -> {
            if (slot < cats.size()) { shop(who, cats.get(slot)); return; }
            if (slot >= 9 && slot - 9 < offers.size()) { buy(who, offers.get(slot - 9)); shop(who, category); }
        });
    }

    /** Custo atual: ferramentas e armadura sobem de nivel; null = ja no maximo. */
    int[] cost(Shop.Offer o, Kit k) {
        if (o.give.equals("pickaxe")) { return k.pickaxe >= 4 ? null : Shop.TOOL_COST[k.pickaxe]; }
        if (o.give.equals("axe")) { return k.axe >= 4 ? null : Shop.TOOL_COST[k.axe]; }
        if (o.give.startsWith("armor:") && k.armor >= Integer.parseInt(o.give.substring(6))) { return null; }
        if (o.give.equals("shears") && k.shears) { return null; }
        return new int[] {o.currency, o.price};
    }

    static boolean has(Player p, int id, int amount) {
        int n = 0; for (Item i : p.getInventory().getContents().values()) { if (i.getId() == id) { n += i.getCount(); } }
        return n >= amount;
    }

    static void take(Player p, int id, int amount) {
        for (Map.Entry<Integer, Item> e : new HashMap<>(p.getInventory().getContents()).entrySet()) {
            if (amount <= 0) { break; }
            Item i = e.getValue();
            if (i.getId() != id) { continue; }
            int use = Math.min(amount, i.getCount());
            amount -= use;
            i.setCount(i.getCount() - use);
            p.getInventory().setItem(e.getKey(), i.getCount() <= 0 ? Item.get(0) : i);
        }
    }

    void buy(Player p, Shop.Offer o) {
        Game g = inGame.get(p);
        Kit k = g.kits.get(p);
        String t = g.team.get(p);
        int[] cost = cost(o, k);
        if (cost == null) { p.sendMessage(PREFIX + "Voce ja tem o melhor nivel."); return; }
        if (!has(p, cost[0], cost[1])) { p.sendMessage(PREFIX + "§cFalta " + Shop.currency(cost[0], cost[1]) + "."); return; }
        take(p, cost[0], cost[1]);
        String give = o.give;
        if (give.startsWith("wool:") || give.startsWith("clay:")) { p.getInventory().addItem(Item.get(give.startsWith("wool") ? 35 : 159, BwState.wool(t), Integer.parseInt(give.substring(5)))); }
        else if (give.startsWith("sword:")) {
            for (Map.Entry<Integer, Item> e : new HashMap<>(p.getInventory().getContents()).entrySet()) { if (e.getValue().getId() == 268) { p.getInventory().setItem(e.getKey(), Item.get(0)); } }
            p.getInventory().addItem(sword(g, t, Integer.parseInt(give.substring(6))));
        }
        else if (give.startsWith("armor:")) { k.armor = Integer.parseInt(give.substring(6)); refreshArmor(g, p); }
        else if (give.equals("pickaxe") || give.equals("axe")) {
            int[] line = give.equals("pickaxe") ? Shop.PICKAXE : Shop.AXE;
            int lvl = give.equals("pickaxe") ? ++k.pickaxe : ++k.axe;
            for (Map.Entry<Integer, Item> e : new HashMap<>(p.getInventory().getContents()).entrySet()) { for (int id : line) { if (e.getValue().getId() == id) { p.getInventory().setItem(e.getKey(), Item.get(0)); } } }
            p.getInventory().addItem(Item.get(line[lvl - 1]));
        }
        else if (give.equals("shears")) { k.shears = true; p.getInventory().addItem(Item.get(359)); }
        else if (give.startsWith("effect:")) { String[] e = give.split(":"); p.addEffect(Effect.getEffect(Integer.parseInt(e[1])).setAmplifier(Integer.parseInt(e[2])).setDuration(Integer.parseInt(e[3]) * 20)); }
        else if (give.equals("fireball")) { p.getInventory().addItem(Items.named(Item.get(332, 0, 1), FIREBALL)); }
        else if (give.equals("pearl")) { p.getInventory().addItem(Items.named(Item.get(332, 0, 1), PEARL)); }
        else if (give.equals("egg")) { p.getInventory().addItem(Items.named(Item.get(344, 0, 1), EGG)); }
        else { p.getInventory().addItem(Items.parse(give)); }
        p.getInventory().sendContents(p);
        p.getLevel().addSound(new ClickSound(p), p);
        p.sendMessage(PREFIX + "§aComprou: " + o.name);
    }

    void refreshArmor(Game g, Player p) {
        Kit k = g.kits.get(p);
        int prot = g.level(g.team.get(p), "protecao");
        Item legs = Item.get(Shop.ARMOR[k.armor][0]), boots = Item.get(Shop.ARMOR[k.armor][1]);
        Item[] all = {p.getInventory().getArmorItem(0), p.getInventory().getArmorItem(1), legs, boots};
        for (int i = 0; i < 4; i++) { Item it = all[i]; if (it.getId() == 0) { continue; } if (prot > 0) { it.addEnchantment(ench(Enchantment.ID_PROTECTION_ALL, prot)); } p.getInventory().setArmorItem(i, it); }
        p.getInventory().sendArmorContents(p);
    }

    void upgradesMenu(Player p) {
        Game g = inGame.get(p);
        if (g == null || g.phase != Phase.JOGANDO || !g.alive(p) || g.respawn.containsKey(p)) { return; }
        if (!nearBase(g, p)) { p.sendMessage(PREFIX + "§cAs melhorias ficam na sua base."); return; }
        String t = g.team.get(p);
        Map<Integer, Item> icons = new HashMap<>();
        int[] iconIds = {267, 307, 257, 61, 373, 30};
        for (int i = 0; i < Shop.UPGRADES.length; i++) {
            Object[] u = Shop.UPGRADES[i];
            int[] costs = (int[]) u[2];
            int lvl = g.level(t, (String) u[0]);
            String state = lvl >= costs.length ? "§aCompleto" : (has(p, Shop.DIAMOND, costs[lvl]) ? "§a" : "§c") + "Nivel " + (lvl + 1) + ": " + Shop.currency(Shop.DIAMOND, costs[lvl]);
            icons.put(10 + i, Items.named(Item.get(iconIds[i], i == 4 ? 21 : 0, 1), "§f" + u[1] + "\n" + state));
        }
        menus().open(p, "Melhorias do time", icons, (who, slot) -> {
            int i = slot - 10;
            if (i < 0 || i >= Shop.UPGRADES.length) { return; }
            Object[] u = Shop.UPGRADES[i];
            int[] costs = (int[]) u[2];
            int lvl = g.level(t, (String) u[0]);
            if (lvl >= costs.length) { who.sendMessage(PREFIX + "Essa melhoria ja esta completa."); return; }
            if (!has(who, Shop.DIAMOND, costs[lvl])) { who.sendMessage(PREFIX + "§cFalta " + Shop.currency(Shop.DIAMOND, costs[lvl]) + "."); return; }
            take(who, Shop.DIAMOND, costs[lvl]);
            g.upgrades.computeIfAbsent(t, k -> new HashMap<>()).put((String) u[0], lvl + 1);
            if (u[0].equals("armadilha")) { g.trapArmed.put(t, true); }
            for (Map.Entry<Player, String> m : g.team.entrySet()) {
                if (!m.getValue().equals(t)) { continue; }
                m.getKey().sendMessage(PREFIX + "§b" + who.getName() + " comprou " + u[1] + " nivel " + (lvl + 1) + "!");
                if (u[0].equals("protecao")) { refreshArmor(g, m.getKey()); }
                if (u[0].equals("afiada")) { for (Map.Entry<Integer, Item> e : new HashMap<>(m.getKey().getInventory().getContents()).entrySet()) { int id = e.getValue().getId(); if (id == 268 || id == 272 || id == 267 || id == 276) { m.getKey().getInventory().setItem(e.getKey(), sword(g, t, id)); } } }
            }
            upgradesMenu(who);
        });
    }

    void teamMenu(Player p) {
        Game g = inGame.get(p);
        if (g == null || !g.waiting()) { return; }
        BwState.Arena a = g.arena();
        List<String> teams = new ArrayList<>(a.teams.keySet());
        Map<Integer, Item> icons = new HashMap<>();
        for (int i = 0; i < teams.size(); i++) {
            String t = teams.get(i);
            long n = g.wanted.values().stream().filter(t::equals).count();
            icons.put(10 + i, Items.named(Item.get(35, BwState.wool(t), 1), BwState.label(t) + "\n§7" + n + "/" + a.teamSize + (n >= a.teamSize ? " §c(cheio)" : "")));
        }
        menus().open(p, "Escolha seu time", icons, (who, slot) -> {
            int i = slot - 10;
            if (i < 0 || i >= teams.size()) { return; }
            String t = teams.get(i);
            if (g.wanted.values().stream().filter(t::equals).count() >= a.teamSize) { who.sendMessage(PREFIX + "§cEsse time esta cheio."); return; }
            g.wanted.put(who, t);
            who.sendMessage(PREFIX + "Voce escolheu o time " + BwState.label(t) + "§f.");
        });
    }

    // ---------- eventos ----------

    Game gameAt(Level lv) {
        if (lv == null) { return null; }
        for (Game g : games.values()) { BwState.Arena a = g.arena(); if (a != null && a.world.equals(lv.getFolderName())) { return g; } }
        return null;
    }

    boolean frozen(Player p) {
        Game g = inGame.get(p);
        if (g != null) { return g.phase != Phase.JOGANDO || !g.alive(p) || g.respawn.containsKey(p); }
        return gameAt(p.level) != null && !p.hasPermission("goldbedwars.admin");
    }

    @EventHandler(priority = EventPriority.MONITOR) public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        loadStats(p);
        if (gameAt(p.level) != null) { getServer().getScheduler().scheduleDelayedTask(new PluginTask<GoldBedWars>(this) { @Override public void onRun(int t) { if (p.isOnline() && !inGame.containsKey(p)) { toLobby(p); } } }, 10); }
    }

    @EventHandler(priority = EventPriority.MONITOR) public void onQuit(PlayerQuitEvent e) { leave(e.getPlayer()); saveStats(e.getPlayer()); stats.remove(e.getPlayer()); flying.remove(e.getPlayer()); }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player)) { return; }
        Player p = (Player) e.getEntity();
        Game g = inGame.get(p);
        Player attacker = e instanceof EntityDamageByEntityEvent && ((EntityDamageByEntityEvent) e).getDamager() instanceof Player ? (Player) ((EntityDamageByEntityEvent) e).getDamager() : null;
        if (attacker != null && frozen(attacker)) { e.setCancelled(); return; }
        if (g == null) { return; }
        if (frozen(p)) {
            e.setCancelled();
            if (e.getCause() == EntityDamageEvent.CAUSE_VOID && g.waiting()) { double[] w = g.arena().waiting; p.teleport(new Vector3(w[0], w[1], w[2])); }
            return;
        }
        if (attacker != null && g.team.get(attacker) != null && g.team.get(attacker).equals(g.team.get(p))) { e.setCancelled(); return; }
        if (e.isCancelled()) { return; }
        if (attacker != null && attacker != p) { g.lastHit.put(p, new Object[] {attacker, System.currentTimeMillis()}); }
        if (e.getCause() == EntityDamageEvent.CAUSE_VOID || e.getFinalDamage() >= p.getHealth()) {
            e.setCancelled();
            die(g, p, attacker != null && attacker != p ? attacker : lastHitter(g, p), e.getCause() == EntityDamageEvent.CAUSE_VOID, null);
        }
    }

    static long pack(Block b) { return ((long) b.getFloorX() & 0x3FFFFFF) << 38 | ((long) b.getFloorZ() & 0x3FFFFFF) << 12 | (b.getFloorY() & 0xFFF); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        if (frozen(p)) { e.setCancelled(); return; }
        Game g = inGame.get(p);
        if (g == null) { return; }
        Block b = e.getBlock();
        if (b.getId() == 26) {
            e.setCancelled();
            String owner = bedOwner(g, b);
            if (owner == null || !g.beds.contains(owner)) { return; }
            if (owner.equals(g.team.get(p))) { p.sendMessage(PREFIX + "§cVoce nao pode quebrar a propria cama!"); return; }
            g.beds.remove(owner);
            breakBedBlocks(g, owner);
            int[] sc = g.score.computeIfAbsent(p, x -> new int[3]); sc[2]++;
            BwState.Stats s = stats(p); s.beds++; saveStats(p);
            reward(p, bedReward);
            broadcast(g, "§f§lCAMA DESTRUIDA! §r" + BwState.label(owner) + " §7perdeu a cama para " + BwState.color(g.team.get(p)) + p.getName());
            for (Map.Entry<Player, String> m : g.team.entrySet()) { if (m.getValue().equals(owner)) { m.getKey().sendTip("§c§lSUA CAMA FOI DESTRUIDA!\n§7Voce nao vai mais renascer."); } }
            return;
        }
        // Regra classica: so se quebra o que foi colocado na partida.
        if (!g.placed.remove(pack(b))) { e.setCancelled(); p.sendPopup("§cSo da para quebrar blocos colocados pelos jogadores"); }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Player p = e.getPlayer();
        if (frozen(p)) { e.setCancelled(); return; }
        Game g = inGame.get(p);
        if (g == null) { return; }
        Block b = e.getBlock();
        for (BwState.Team t : g.arena().teams.values()) { if (t.spawn.length > 2 && Math.abs(t.spawn[0] - b.x - 0.5) < 2 && Math.abs(t.spawn[2] - b.z - 0.5) < 2 && Math.abs(t.spawn[1] - b.y) < 3) { e.setCancelled(); p.sendPopup("§cNao bloqueie o ponto de renascer"); return; } }
        g.placed.add(pack(b));
        if (b.getId() == Block.TNT) {
            Level lv = b.level; Vector3 at = new Vector3(b.x, b.y, b.z);
            getServer().getScheduler().scheduleDelayedTask(new PluginTask<GoldBedWars>(this) { @Override public void onRun(int t) { Block now = lv.getBlock(at); if (now instanceof BlockTNT) { ((BlockTNT) now).prime(); } } }, 1);
        }
    }

    /** Explosoes no BedWars so derrubam blocos macios colocados pelos jogadores (o mapa e as camas ficam). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        Game g = gameAt(e.getPosition() == null ? null : e.getPosition().level);
        if (g == null) { return; }
        List<Block> keep = new ArrayList<>();
        for (Block b : e.getBlockList()) { if (SOFT.contains(b.getId()) && g.placed.remove(pack(b))) { keep.add(b); } }
        e.setBlockList(keep);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void onPickup(InventoryPickupItemEvent e) { if (e.getInventory().getHolder() instanceof Player && frozen((Player) e.getInventory().getHolder())) { e.setCancelled(); } }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void onDrop(PlayerDropItemEvent e) { if (frozen(e.getPlayer()) || (e.getItem().hasCustomName() && e.getItem().getCustomName().contains("(toque)"))) { e.setCancelled(); } }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        if (!inGame.containsKey(e.getPlayer()) || e.getPlayer().hasPermission("goldbedwars.admin")) { return; }
        String cmd = e.getMessage().substring(1).split(" ")[0].toLowerCase(Locale.ROOT);
        if (!ALLOWED.contains(cmd)) { e.setCancelled(); e.getPlayer().sendMessage(PREFIX + "§cNa partida so funcionam /bw, /tell e /fps."); }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        if (!inGame.containsKey(p)) { return; }
        if (useMenuItem(p, p.getInventory().getItemInHand())) { e.setCancelled(); return; }
        if (frozen(p)) { e.setCancelled(); }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHeld(PlayerItemHeldEvent e) {
        Player p = e.getPlayer();
        Item item = e.getItem();
        if (!inGame.containsKey(p) || !item.hasCustomName() || !item.getCustomName().contains("(toque)")) { return; }
        getServer().getScheduler().scheduleDelayedTask(new PluginTask<GoldBedWars>(this) { @Override public void onRun(int t) { if (p.isOnline()) { p.getInventory().setHeldItemIndex(4); p.getInventory().sendHeldItem(p); useMenuItem(p, item); } } }, 2);
    }

    boolean useMenuItem(Player p, Item i) {
        if (!i.hasCustomName()) { return false; }
        if (i.getCustomName().equals(LEAVE)) { leave(p); p.sendMessage(PREFIX + "Voce saiu da partida."); return true; }
        if (i.getCustomName().equals(TEAMS)) { teamMenu(p); return true; }
        return false;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLaunch(ProjectileLaunchEvent e) {
        EntityProjectile proj = e.getEntity();
        if (!(proj.shootingEntity instanceof Player) || !inGame.containsKey(proj.shootingEntity)) { return; }
        Player p = (Player) proj.shootingEntity;
        Item hand = p.getInventory().getItemInHand();
        if (!hand.hasCustomName()) { return; }
        String n = hand.getCustomName();
        if (n.equals(FIREBALL) || n.equals(PEARL)) { special.put(proj.getId(), n); }
        if (n.equals(EGG)) { bridge(p, proj); }
    }

    /** Ovo-Ponte: enquanto voa (ate 2 s), deixa uma ponte de la do time logo abaixo da trajetoria. */
    void bridge(Player p, EntityProjectile egg) {
        Game g = inGame.get(p);
        int wool = BwState.wool(g.team.get(p));
        final int[] ticks = {0};
        PluginTask<GoldBedWars> task = new PluginTask<GoldBedWars>(this) {
            @Override public void onRun(int t) {
                if (egg.closed || ++ticks[0] > 40 || g.phase != Phase.JOGANDO) { getHandler().cancel(); return; }
                Level lv = egg.level;
                int x = egg.getFloorX(), y = egg.getFloorY() - 2, z = egg.getFloorZ();
                for (int dx = 0; dx <= 1; dx++) { for (int dz = 0; dz <= 1; dz++) {
                    if (lv.getBlockIdAt(x + dx, y, z + dz) == 0 && egg.distanceSquared(p) > 9) { Block b = Block.get(35, wool); lv.setBlock(new Vector3(x + dx, y, z + dz), b, true, true); g.placed.add(((long) (x + dx) & 0x3FFFFFF) << 38 | ((long) (z + dz) & 0x3FFFFFF) << 12 | (y & 0xFFF)); }
                } }
            }
        };
        getServer().getScheduler().scheduleRepeatingTask(task, 1);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onHit(ProjectileHitEvent e) {
        Entity proj = e.getEntity();
        String kind = special.remove(proj.getId());
        if (kind == null || !(proj instanceof EntityProjectile) || !(((EntityProjectile) proj).shootingEntity instanceof Player)) { return; }
        Player p = (Player) ((EntityProjectile) proj).shootingEntity;
        if (!p.isOnline() || frozen(p) || p.level != proj.level) { return; }
        if (kind.equals(PEARL)) { p.teleport(new Vector3(proj.x, proj.y + 0.5, proj.z)); p.attack(new EntityDamageEvent(p, EntityDamageEvent.CAUSE_FALL, 5)); return; }
        Explosion ex = new Explosion(new Position(proj.x, proj.y, proj.z, proj.level), 2.2, proj);
        ex.explodeA(); ex.explodeB();
    }

    // ---------- comandos ----------

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!data.isLoaded()) { sender.sendMessage(PREFIX + "Carregando..."); return true; }
        String sub = args.length == 0 ? "ajuda" : args[0].toLowerCase(Locale.ROOT);
        Player p = sender instanceof Player ? (Player) sender : null;
        try {
            switch (sub) {
                case "entrar": if (p != null) { String m = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "solo"; join(p, m.startsWith("q") ? 4 : m.startsWith("d") ? 2 : 1); } return true;
                case "sair": if (p != null) { if (inGame.containsKey(p)) { leave(p); p.sendMessage(PREFIX + "Voce saiu da partida."); } else { p.sendMessage(PREFIX + "Voce nao esta numa partida."); } } return true;
                case "loja": if (p != null) { shop(p, args.length > 1 ? cap(args[1]) : "Blocos"); } return true;
                case "melhorias": if (p != null) { upgradesMenu(p); } return true;
                case "time": if (p != null && args.length == 1) { teamMenu(p); return true; } break;
                case "stats": {
                    Player t = args.length > 1 ? getServer().getPlayerExact(args[1]) : p;
                    if (t == null) { sender.sendMessage(PREFIX + "Jogador offline."); return true; }
                    BwState.Stats s = stats(t);
                    sender.sendMessage(PREFIX + "§e" + t.getName() + "§f: §a" + s.wins + " vitorias§f, " + s.kills + " abates, §b" + s.finals + " finais§f, " + s.beds + " camas, " + s.deaths + " mortes, " + s.games + " partidas");
                    return true;
                }
                case "top": { int i = 1; sender.sendMessage(PREFIX + "§6Top vitorias:"); for (RankingService.Entry e : topWins(10)) { sender.sendMessage("§e" + (i++) + ". §f" + e.player + " §7- " + e.label); } return true; }
                default: break;
            }
            if (!sender.hasPermission("goldbedwars.admin")) { sender.sendMessage(PREFIX + "/bw entrar [solo|dupla|quarteto], /bw sair, /bw loja, /bw melhorias, /bw stats, /bw top"); return true; }
            switch (sub) {
                case "criar": {
                    if (p == null) { return true; }
                    String name = BwState.name(arg(args, 1));
                    String m = args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : "solo";
                    int size = m.startsWith("q") ? 4 : m.startsWith("d") ? 2 : 1;
                    String world = p.level.getFolderName();
                    if (world.equals(getServer().getDefaultLevel().getFolderName())) { throw new IllegalArgumentException("Use um mundo proprio para a arena."); }
                    data.change(s -> { if (s.arenas.containsKey(name)) { throw new IllegalArgumentException("Arena ja existe."); } BwState.Arena a = new BwState.Arena(); a.world = world; a.teamSize = size; s.arenas.put(name, a); })
                            .whenComplete((v, e) -> { if (e == null) { games.put(name, new Game(name)); } p.sendMessage(PREFIX + (e != null ? "§c" + root(e) : "Arena " + name + " criada. Marque: /bw espera, /bw time <cor> spawn|cama|forja, /bw diamante, /bw esmeralda, /bw salvar.")); });
                    return true;
                }
                case "espera": case "time": case "diamante": case "esmeralda": case "desfazer": case "salvar": {
                    if (p == null) { return true; }
                    Game g = gameAt(p.level);
                    if (g == null) { throw new IllegalArgumentException("Entre no mundo de uma arena (veja /bw lista)."); }
                    if (!g.waiting() || !g.team.isEmpty()) { throw new IllegalArgumentException("A arena tem jogadores; espere esvaziar."); }
                    edit(p, g, sub, args);
                    return true;
                }
                case "lista": { for (Game g : games.values()) { BwState.Arena a = g.arena(); sender.sendMessage("§e" + g.name + " §f- " + a.world + ", " + a.teams.size() + " times de " + a.teamSize + ", " + a.diamonds.size() + " diamantes, " + a.emeralds.size() + " esmeraldas, " + g.phase + (maps.hasBackup(a.world) ? "" : " §cSEM COPIA")); } return true; }
                case "iniciar": { Game g = games.get(BwState.name(arg(args, 1))); if (g == null || !g.waiting()) { throw new IllegalArgumentException("Arena inexistente ou em jogo."); } g.forced = true; sender.sendMessage(PREFIX + "Comecando em 5 s."); return true; }
                case "remover": { String n = BwState.name(arg(args, 1)); Game g = games.get(n); if (g != null && !g.waiting()) { throw new IllegalArgumentException("Espere a partida acabar."); } data.change(s -> s.arenas.remove(n)).whenComplete((v, e) -> { games.remove(n); sender.sendMessage(PREFIX + "Arena removida."); }); return true; }
                default:
                    sender.sendMessage(PREFIX + "Admin: /bw criar <nome> [solo|dupla|quarteto], espera, time <cor> spawn|cama|forja, diamante, esmeralda (todos aceitam x y z no fim), desfazer <diamante|esmeralda>, salvar, lista, iniciar, remover");
                    sender.sendMessage(PREFIX + "Cores: " + String.join(", ", BwState.COLORS.keySet()) + ". Lojas: NPC com /npc comando bw loja e /npc comando bw melhorias.");
                    return true;
            }
        } catch (RuntimeException e) { sender.sendMessage(PREFIX + "§c" + e.getMessage()); return true; }
    }

    /** Ponto do jogador ou, se vierem tres numeros a partir de {@code from}, essas coordenadas (para colar o pontos.txt). */
    static double[] spot(Player p, String[] args, int from) {
        if (args.length >= from + 3) {
            double[] v;
            try { v = new double[] {Double.parseDouble(args[from]), Double.parseDouble(args[from + 1]), Double.parseDouble(args[from + 2]), p.yaw}; } catch (NumberFormatException ignored) { v = null; }
            if (v == null || !(Math.abs(v[0]) < 3e7 && Math.abs(v[2]) < 3e7 && v[1] >= 0 && v[1] < 256)) { throw new IllegalArgumentException("Coordenadas invalidas: use x y z (y de 0 a 255)."); }
            return v;
        }
        return new double[] {Math.floor(p.x) + 0.5, Math.floor(p.y), Math.floor(p.z) + 0.5, p.yaw};
    }

    void edit(Player p, Game g, String sub, String[] args) {
        double[] here = spot(p, args, sub.equals("time") ? 3 : 1);
        double[] here3 = {here[0], here[1], here[2]};
        switch (sub) {
            case "espera": change(p, s -> s.arenas.get(g.name).waiting = here, "Sala de espera marcada."); break;
            case "diamante": change(p, s -> s.arenas.get(g.name).diamonds.add(here3), "Gerador de diamante marcado."); break;
            case "esmeralda": change(p, s -> s.arenas.get(g.name).emeralds.add(here3), "Gerador de esmeralda marcado."); break;
            case "desfazer": { boolean d = arg(args, 1).startsWith("d"); change(p, s -> { List<double[]> l = d ? s.arenas.get(g.name).diamonds : s.arenas.get(g.name).emeralds; if (!l.isEmpty()) { l.remove(l.size() - 1); } }, "Ultimo gerador removido."); break; }
            case "time": {
                String color = arg(args, 1).toLowerCase(Locale.ROOT), what = arg(args, 2).toLowerCase(Locale.ROOT);
                if (!BwState.COLORS.containsKey(color)) { throw new IllegalArgumentException("Cores: " + String.join(", ", BwState.COLORS.keySet())); }
                int[] bed = null;
                if (what.equals("cama")) {
                    for (int dx = -3; dx <= 3 && bed == null; dx++) { for (int dy = -2; dy <= 1 && bed == null; dy++) { for (int dz = -3; dz <= 3 && bed == null; dz++) { int bx = (int) Math.floor(here[0]) + dx, by = (int) Math.floor(here[1]) + dy, bz = (int) Math.floor(here[2]) + dz; if (p.level.getBlockIdAt(bx, by, bz) == 26) { bed = new int[] {bx, by, bz}; } } } }
                    if (bed == null) { throw new IllegalArgumentException("Fique ao lado da cama do time (ate 3 blocos)."); }
                }
                int[] bedPos = bed;
                change(p, s -> {
                    BwState.Team t = s.arenas.get(g.name).teams.computeIfAbsent(color, k -> new BwState.Team());
                    switch (what) { case "spawn": t.spawn = here; break; case "forja": t.forge = here3; break; case "cama": t.bed = bedPos; break; default: throw new IllegalArgumentException("Use spawn, cama ou forja."); }
                }, "Time " + color + ": " + what + " marcado.");
                break;
            }
            default: {
                Level lv = p.level;
                lv.save(true);
                maps.backup(lv.getFolderName()).whenComplete((v, e) -> p.sendMessage(PREFIX + (e != null ? "§cFalha ao salvar a copia." : "Copia do mapa salva.")));
            }
        }
    }

    void change(Player p, java.util.function.Consumer<BwState> m, String ok) { data.change(m).whenComplete((v, e) -> p.sendMessage(PREFIX + (e != null ? "§c" + root(e) : ok))); }

    static String cap(String s) { for (String c : Shop.CATEGORIES.keySet()) { if (c.equalsIgnoreCase(s)) { return c; } } return "Blocos"; }
    static String arg(String[] a, int i) { if (a.length <= i) { throw new IllegalArgumentException("Faltam argumentos. Use /bw."); } return a[i]; }
    static String root(Throwable e) { while (e.getCause() != null) { e = e.getCause(); } return e.getMessage(); }
}
