package br.goldsuite.skywars;

import br.goldsuite.api.*;
import cn.nukkit.Player;
import cn.nukkit.block.Block;
import cn.nukkit.block.BlockTNT;
import cn.nukkit.blockentity.BlockEntity;
import cn.nukkit.blockentity.BlockEntityChest;
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
import cn.nukkit.event.inventory.InventoryPickupItemEvent;
import cn.nukkit.event.player.*;
import cn.nukkit.item.Item;
import cn.nukkit.level.Level;
import cn.nukkit.level.Location;
import cn.nukkit.level.sound.ClickSound;
import cn.nukkit.math.Vector3;
import cn.nukkit.plugin.PluginBase;
import cn.nukkit.scheduler.PluginTask;
import com.google.gson.Gson;
import java.util.*;

/**
 * SkyWars no estilo do Hypixel (refeito a partir do plugin de luizgeh, mantendo a logica: jaulas, contagem, baus com refil,
 * ultimo vivo vence, espectadores e mapa restaurado). Corrige as falhas do original: comandos de admin sem permissao,
 * nome de arena virando caminho de arquivo, espectador voando fora da partida e inventario perdido.
 */
public final class GoldSkyWars extends PluginBase implements Listener {
    static String PREFIX = "§b[§fSky§bWars]§f ";
    static final String STATS = "goldsw-stats";
    static final String KIT_ITEM = "§r§eKits §7(toque)", LEAVE_ITEM = "§r§cSair §7(toque)", AGAIN_ITEM = "§r§aJogar de novo §7(toque)";
    private static final Gson GSON = new Gson();
    private static final Set<String> ALLOWED = new HashSet<>(Arrays.asList("sw", "skywars", "tell", "msg", "w", "r", "login", "l", "fps"));

    enum Phase { ESPERANDO, CONTAGEM, JOGANDO, FIM, RESETANDO }

    final class Game {
        final String name;
        Phase phase = Phase.ESPERANDO;
        int seconds, elapsed;
        boolean forced;
        long noFallUntil;
        final Map<Player, Integer> players = new IdentityHashMap<>();
        final Set<Player> spectators = Collections.newSetFromMap(new IdentityHashMap<>());
        final Map<Player, Integer> kills = new IdentityHashMap<>();
        final Map<Player, Object[]> lastHit = new IdentityHashMap<>();
        final List<Vector3> cages = new ArrayList<>();
        Game(String name) { this.name = name; }
        SwState.Arena arena() { return data.read(s -> s.arenas.get(name)); }
        Level level() { SwState.Arena a = arena(); return a == null ? null : getServer().getLevelByName(a.world); }
        boolean waiting() { return phase == Phase.ESPERANDO || phase == Phase.CONTAGEM; }
        int capacity() { SwState.Arena a = arena(); return a == null ? 0 : a.spawns.size() * a.teamSize; }
        int teamsAlive() { return new HashSet<>(players.values()).size(); }
    }

    CoreService core;
    PersistentState<SwState> data;
    MapStore maps;
    final Map<String, Game> games = new HashMap<>();
    final Map<Player, Game> inGame = new IdentityHashMap<>();
    final Map<Player, SwState.Stats> stats = new IdentityHashMap<>();
    /** Bolas de neve que sao Perolas do Fim (pelo id da entidade). */
    final Set<Long> pearls = new HashSet<>();
    final Set<Player> flying = Collections.newSetFromMap(new IdentityHashMap<>());
    long minPlayers = 2, countdown = 20, gameSeconds = 900, refill1 = 180, refill2 = 360, killReward = 2000, winReward = 10000;
    String lobbyWorld = "";

    @Override public void onEnable() {
        Settings.prefix(this, PREFIX, v -> PREFIX = v);
        Settings.number(this, "minimo_jogadores", 2, "Jogadores para comecar a contagem", v -> minPlayers = Math.max(2, v));
        Settings.number(this, "contagem", 20, "Segundos de contagem nas jaulas (cai para 10 com a sala cheia)", v -> countdown = Math.max(5, v));
        Settings.number(this, "duracao", 900, "Duracao maxima da partida em segundos (depois e empate)", v -> gameSeconds = Math.max(120, v));
        Settings.number(this, "refil_1", 180, "Segundo da partida do primeiro refil dos baus", v -> refill1 = v);
        Settings.number(this, "refil_2", 360, "Segundo da partida do segundo refil dos baus", v -> refill2 = v);
        Settings.number(this, "premio_abate", 2000, "Dinheiro por abate (centavos)", v -> killReward = v);
        Settings.number(this, "premio_vitoria", 10000, "Dinheiro por vitoria (centavos)", v -> winReward = v);
        Settings.text(this, "mundo_lobby", "", "Mundo para onde os jogadores voltam (vazio = mundo padrao)", v -> lobbyWorld = v);
        for (Kits.Kit k : Kits.ALL.values()) { if (k.price > 0) { Settings.number(this, "kit." + k.id, k.price, "Preco do kit " + k.name + " (centavos)", v -> kitPrices.put(k.id, Math.max(0, v))); } }
        core = getServer().getServiceManager().load(CoreService.class);
        if (core == null) { throw new IllegalStateException("GoldCore precisa estar ativo antes do GoldSkyWars."); }
        maps = new MapStore(getServer(), getDataFolder().toPath());
        data = new PersistentState<>(core.getStore(), "goldskywars", SwState.class, SwState::new, SwState::validate);
        getServer().getPluginManager().registerEvents(this, this);
        data.ready().whenComplete((v, e) -> {
            if (e != null || !isEnabled()) { return; }
            for (Map.Entry<String, SwState.Arena> a : data.read(s -> new TreeMap<>(s.arenas)).entrySet()) {
                if (getServer().getLevelByName(a.getValue().world) == null && getServer().isLevelGenerated(a.getValue().world)) { getServer().loadLevel(a.getValue().world); }
                games.put(a.getKey(), new Game(a.getKey()));
            }
            getLogger().info(games.size() + " arena(s) de SkyWars pronta(s).");
            RankingService ranking = getServer().getServiceManager().load(RankingService.class);
            if (ranking != null) { ranking.register("skywars", this::topWins); }
        });
        for (Player p : getServer().getOnlinePlayers().values()) { loadStats(p); }
        getServer().getScheduler().scheduleRepeatingTask(new PluginTask<GoldSkyWars>(this) { @Override public void onRun(int tick) { second(); } }, 20);
    }

    final Map<String, Long> kitPrices = new HashMap<>();
    long price(Kits.Kit k) { return kitPrices.getOrDefault(k.id, k.price); }

    @Override public void onDisable() {
        for (Game g : games.values()) { for (Player p : new ArrayList<>(inGame.keySet())) { if (inGame.get(p) == g) { toLobby(p); } } }
        for (Player p : new ArrayList<>(stats.keySet())) { saveStats(p); }
        games.clear(); inGame.clear(); pearls.clear();
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

    // ---------- estatisticas ----------

    static String key(Player p) { return p.getName().toLowerCase(Locale.ROOT); }

    void loadStats(Player p) {
        core.getStore().get(STATS, key(p)).thenAccept(v -> {
            if (!p.isOnline()) { return; }
            SwState.Stats s = new SwState.Stats();
            if (v.isPresent()) { try { s = GSON.fromJson(v.get(), SwState.Stats.class); if (s.kits == null) { s.kits = new TreeSet<>(); } if (!Kits.ALL.containsKey(s.kit)) { s.kit = Kits.DEFAULT; } } catch (RuntimeException bad) { s = new SwState.Stats(); } }
            stats.put(p, s);
        });
    }

    void saveStats(Player p) { SwState.Stats s = stats.get(p); if (s != null) { core.getStore().put(STATS, key(p), GSON.toJson(s)); } }

    SwState.Stats stats(Player p) { return stats.computeIfAbsent(p, k -> new SwState.Stats()); }

    // ---------- entrar e sair ----------

    static boolean menuItem(Item i) { return i.hasCustomName() && (i.getCustomName().equals(KIT_ITEM) || i.getCustomName().equals(LEAVE_ITEM) || i.getCustomName().equals(AGAIN_ITEM)); }

    void join(Player p, int teamSize) {
        if (inGame.containsKey(p)) { throw new IllegalArgumentException("Voce ja esta numa partida. Use /sw sair."); }
        for (Item i : p.getInventory().getContents().values()) {
            // Itens do lobby (livro do perfil etc.) podem ficar; itens de verdade nao, para ninguem perder nada.
            if (i.getId() != 0 && !(i.hasCustomName() && i.getCustomName().contains("(toque)"))) { throw new IllegalArgumentException("Esvazie o inventario antes de entrar no SkyWars."); }
        }
        for (Item i : p.getInventory().getArmorContents()) { if (i.getId() != 0) { throw new IllegalArgumentException("Tire a armadura antes de entrar no SkyWars."); } }
        Game best = null;
        for (Game g : games.values()) {
            SwState.Arena a = g.arena();
            if (a == null || a.teamSize != teamSize || !g.waiting() || g.level() == null || g.players.size() >= g.capacity()) { continue; }
            if (best == null || g.players.size() > best.players.size()) { best = g; }
        }
        if (best == null) { throw new IllegalArgumentException("Nenhuma sala " + (teamSize == 1 ? "solo" : "de dupla") + " aberta agora. Tente em instantes."); }
        Game g = best;
        SwState.Arena a = g.arena();
        int slot = -1;
        for (int i = 0; i < a.spawns.size() && slot < 0; i++) { int used = 0; for (int s : g.players.values()) { if (s == i) { used++; } } if (used < a.teamSize) { slot = i; } }
        if (slot < 0) { throw new IllegalArgumentException("Sala cheia."); }
        double[] sp = a.spawns.get(slot);
        g.players.put(p, slot);
        inGame.put(p, g);
        p.getInventory().clearAll();
        p.removeAllEffects();
        p.setHealth(p.getMaxHealth());
        p.getFoodData().setLevel(20);
        p.setGamemode(Player.SURVIVAL);
        cage(g, sp, true);
        p.teleport(new Location(sp[0], sp[1], sp[2], sp[3], 0, g.level()));
        p.getInventory().setItem(0, Items.named(Item.get(54, 0, 1), KIT_ITEM));
        p.getInventory().setItem(8, Items.named(Item.get(355, 0, 1), LEAVE_ITEM));
        p.getInventory().sendContents(p);
        broadcast(g, "§7" + p.getName() + " §eentrou (§f" + g.players.size() + "/" + g.capacity() + "§e)");
        p.sendMessage(PREFIX + "Kit: §e" + Kits.ALL.get(stats(p).kit).name + "§f. Toque no bau da hotbar para trocar.");
    }

    void leave(Player p) {
        Game g = inGame.get(p);
        if (g == null) { return; }
        if (g.phase == Phase.JOGANDO && g.players.containsKey(p)) { eliminate(g, p, lastHitter(g, p), false, "§7" + p.getName() + " §esaiu da partida."); }
        g.players.remove(p); g.spectators.remove(p); g.kills.remove(p); g.lastHit.remove(p);
        toLobby(p);
        if (g.waiting()) { broadcast(g, "§7" + p.getName() + " §esaiu (§f" + g.players.size() + "/" + g.capacity() + "§e)"); }
    }

    /** Volta ao lobby limpo: sem itens da partida, sem voo e sem invisibilidade de espectador. */
    void toLobby(Player p) {
        inGame.remove(p);
        p.getInventory().clearAll();
        p.removeAllEffects();
        p.setDataFlag(Entity.DATA_FLAGS, Entity.DATA_FLAG_INVISIBLE, false);
        if (flying.remove(p) && !p.isCreative()) { p.setAllowFlight(false); }
        p.setGamemode(Player.SURVIVAL);
        p.setHealth(p.getMaxHealth());
        p.getFoodData().setLevel(20);
        p.extinguish();
        Level lobby = lobbyWorld.isEmpty() ? null : getServer().getLevelByName(lobbyWorld);
        if (lobby == null) { lobby = getServer().getDefaultLevel(); }
        p.teleport(lobby.getSafeSpawn());
    }

    /** Jaula de vidro em volta do ponto: chao, quatro paredes de 3 de altura e teto. */
    void cage(Game g, double[] sp, boolean build) {
        Level lv = g.level();
        int x = (int) Math.floor(sp[0]), y = (int) Math.floor(sp[1]), z = (int) Math.floor(sp[2]);
        List<Vector3> blocks = new ArrayList<>();
        blocks.add(new Vector3(x, y - 1, z)); blocks.add(new Vector3(x, y + 3, z));
        for (int dy = 0; dy <= 2; dy++) { for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) { blocks.add(new Vector3(x + d[0], y + dy, z + d[1])); } }
        for (Vector3 v : blocks) {
            if (build && lv.getBlockIdAt(v.getFloorX(), v.getFloorY(), v.getFloorZ()) != 0) { continue; }
            lv.setBlock(v, Block.get(build ? 20 : 0), true, false);
            if (build) { g.cages.add(v); }
        }
    }

    // ---------- ciclo de 1 segundo ----------

    void second() {
        long now = System.currentTimeMillis();
        for (Game g : games.values()) {
            Level lv = g.level();
            if (lv == null || g.phase == Phase.RESETANDO) { continue; }
            switch (g.phase) {
                case ESPERANDO:
                    if (g.players.size() >= Math.min(minPlayers, Math.max(2, g.capacity())) || (g.forced && !g.players.isEmpty())) { g.phase = Phase.CONTAGEM; g.seconds = g.forced ? 5 : (int) countdown; broadcast(g, "§aA partida comeca em §f" + g.seconds + "s§a!"); }
                    for (Player p : g.players.keySet()) { p.sendTip("§eAguardando jogadores §f" + g.players.size() + "/" + g.capacity()); }
                    break;
                case CONTAGEM: {
                    if (g.players.size() < Math.min(minPlayers, Math.max(2, g.capacity())) && !g.forced) { g.phase = Phase.ESPERANDO; broadcast(g, "§cJogadores insuficientes, contagem cancelada."); break; }
                    if (g.players.size() >= g.capacity() && g.seconds > 10) { g.seconds = 10; }
                    g.seconds--;
                    for (Player p : g.players.keySet()) { p.sendTip("§aComeca em §f" + g.seconds + "s §7| §fKit: §e" + Kits.ALL.get(stats(p).kit).name); if (g.seconds <= 5) { lv.addSound(new ClickSound(p), p); } }
                    if (g.seconds <= 0) { start(g); }
                    break;
                }
                case JOGANDO: {
                    g.elapsed++;
                    if (g.elapsed == refill1 || g.elapsed == refill2) { loadArena(g); fillChests(g, true); broadcast(g, "§6Os baus foram reabastecidos!"); }
                    if (g.elapsed == gameSeconds - 60) { broadcast(g, "§cFalta 1 minuto! Sem vencedor, a partida termina empatada."); }
                    if (g.elapsed >= gameSeconds) { broadcast(g, "§7Tempo esgotado: ninguem venceu."); finish(g, Collections.emptyList()); break; }
                    double voidY = voidY(g);
                    for (Player p : new ArrayList<>(g.players.keySet())) {
                        if (p.y < voidY) { eliminate(g, p, lastHitter(g, p), true, null); continue; }
                        long next = Math.min(refill1 > g.elapsed ? refill1 : Long.MAX_VALUE, refill2 > g.elapsed ? refill2 : Long.MAX_VALUE);
                        p.sendTip("§bSkyWars §7| §fVivos: §a" + g.players.size() + " §7| §fAbates: §e" + g.kills.getOrDefault(p, 0) + " §7| " + (next == Long.MAX_VALUE ? "§fFim: §c" + clock(gameSeconds - g.elapsed) : "§fRefil: §6" + clock(next - g.elapsed)));
                    }
                    for (Player s : g.spectators) { s.sendTip("§7Espectador §8| §fVivos: §a" + g.players.size()); }
                    break;
                }
                case FIM:
                    if (--g.seconds <= 0) { reset(g); }
                    break;
                default:
            }
        }
    }

    static String clock(long s) { return String.format("%d:%02d", s / 60, s % 60); }

    double voidY(Game g) {
        double min = 1000; for (double[] sp : g.arena().spawns) { min = Math.min(min, sp[1]); }
        return min - 40;
    }

    /** Carrega todos os chunks da arena (jaulas e meio, com folga): baus de ilhas longe dos jogadores tambem sao preenchidos. */
    void loadArena(Game g) {
        SwState.Arena a = g.arena();
        double minX = 1e9, maxX = -1e9, minZ = 1e9, maxZ = -1e9;
        List<double[]> points = new ArrayList<>(a.spawns); if (a.center.length == 3) { points.add(a.center); }
        for (double[] p : points) { minX = Math.min(minX, p[0]); maxX = Math.max(maxX, p[0]); minZ = Math.min(minZ, p[2]); maxZ = Math.max(maxZ, p[2]); }
        Level lv = g.level();
        for (int cx = ((int) Math.floor(minX) - 24) >> 4; cx <= ((int) Math.floor(maxX) + 24) >> 4; cx++) { for (int cz = ((int) Math.floor(minZ) - 24) >> 4; cz <= ((int) Math.floor(maxZ) + 24) >> 4; cz++) { lv.loadChunk(cx, cz, false); } }
    }

    void start(Game g) {
        loadArena(g);
        g.phase = Phase.JOGANDO; g.elapsed = 0; g.forced = false;
        g.noFallUntil = System.currentTimeMillis() + 5000;
        for (Vector3 v : g.cages) { g.level().setBlock(v, Block.get(0), true, false); }
        g.cages.clear();
        fillChests(g, false);
        for (Player p : g.players.keySet()) {
            p.getInventory().clearAll();
            Kits.Kit kit = Kits.ALL.get(stats(p).kit);
            for (Item it : Kits.items(kit)) {
                if (Items.armor(it.getId())) { p.getInventory().setArmorItem(Items.armorSlot(it.getId()), it); } else { p.getInventory().addItem(it); }
            }
            p.getInventory().sendContents(p); p.getInventory().sendArmorContents(p);
            p.setHealth(p.getMaxHealth()); p.getFoodData().setLevel(20);
            g.kills.put(p, 0);
            p.sendMessage(PREFIX + "§l§aLUTEM! §r§fUltimo " + (g.arena().teamSize > 1 ? "time" : "jogador") + " vivo vence.");
        }
    }

    /** Baus da ilha garantem arma e blocos; os do meio (perto do centro) sao mais fortes. No refil, so os vazios ganham itens novos. */
    void fillChests(Game g, boolean refill) {
        SwState.Arena a = g.arena();
        Random r = new Random();
        int filled = 0;
        for (BlockEntity be : new ArrayList<>(g.level().getBlockEntities().values())) {
            if (!(be instanceof BlockEntityChest)) { continue; }
            BlockEntityChest chest = (BlockEntityChest) be;
            boolean middle = a.center.length == 3 && Math.pow(be.x - a.center[0], 2) + Math.pow(be.z - a.center[2], 2) <= 144;
            if (refill) { boolean empty = true; for (Item i : chest.getInventory().getContents().values()) { if (i.getId() != 0) { empty = false; break; } } if (!empty) { continue; } }
            chest.getInventory().clearAll();
            List<Integer> slots = new ArrayList<>(); for (int i = 0; i < 27; i++) { slots.add(i); }
            Collections.shuffle(slots, r);
            int i = 0;
            for (Item item : Loot.roll(r, middle, a.insane)) { chest.getInventory().setItem(slots.get(i++), item); }
            filled++;
        }
        if (filled == 0 && !refill) { getLogger().warning("Arena " + g.name + " sem baus: coloque baus no mapa e use /sw salvar."); }
    }

    Player lastHitter(Game g, Player p) {
        Object[] h = g.lastHit.get(p);
        if (h == null || System.currentTimeMillis() - (Long) h[1] > 15_000) { return null; }
        Player k = (Player) h[0];
        return k.isOnline() && g.players.containsKey(k) ? k : null;
    }

    /** Morte: itens caem (menos no vazio), vira espectador invisivel e voando, e o abate vai para quem bateu por ultimo. */
    void eliminate(Game g, Player p, Player killer, boolean voidDeath, String customMessage) {
        if (!g.players.containsKey(p)) { return; }
        g.players.remove(p);
        if (!voidDeath) {
            for (Item i : p.getInventory().getContents().values()) { if (i.getId() != 0 && !menuItem(i)) { p.level.dropItem(p, i); } }
            for (Item i : p.getInventory().getArmorContents()) { if (i.getId() != 0) { p.level.dropItem(p, i); } }
        }
        SwState.Stats ps = stats(p); ps.deaths++; ps.games++; saveStats(p);
        String msg = customMessage;
        if (killer != null && killer != p) {
            int k = g.kills.merge(killer, 1, Integer::sum);
            SwState.Stats ks = stats(killer); ks.kills++; saveStats(killer);
            reward(killer, killReward);
            if (msg == null) { msg = "§c" + p.getName() + " §7" + (voidDeath ? "foi jogado no vazio por" : "foi morto por") + " §a" + killer.getName() + (k >= 2 ? " §6(" + k + " abates)" : ""); }
            killer.getLevel().addSound(new ClickSound(killer), killer);
        } else if (msg == null) { msg = "§c" + p.getName() + " §7" + (voidDeath ? "caiu no vazio." : "morreu."); }
        broadcast(g, msg);
        spectate(g, p);
        checkWin(g);
    }

    void spectate(Game g, Player p) {
        g.spectators.add(p);
        p.getInventory().clearAll();
        p.removeAllEffects(); p.extinguish();
        p.setHealth(p.getMaxHealth()); p.getFoodData().setLevel(20);
        p.setDataFlag(Entity.DATA_FLAGS, Entity.DATA_FLAG_INVISIBLE, true);
        if (!p.getAllowFlight()) { p.setAllowFlight(true); flying.add(p); }
        SwState.Arena a = g.arena();
        double[] c = a.center.length == 3 ? a.center : a.spawns.get(0);
        p.teleport(new Location(c[0], c[1] + 12, c[2], 0, 0, g.level()));
        p.getInventory().setItem(0, Items.named(Item.get(339, 0, 1), AGAIN_ITEM));
        p.getInventory().setItem(8, Items.named(Item.get(355, 0, 1), LEAVE_ITEM));
        p.getInventory().sendContents(p);
        p.sendMessage(PREFIX + "§cVoce foi eliminado! §fAssistindo como espectador. Papel: jogar de novo | Cama: sair.");
    }

    void checkWin(Game g) {
        if (g.phase != Phase.JOGANDO || g.teamsAlive() > 1) { return; }
        finish(g, new ArrayList<>(g.players.keySet()));
    }

    void finish(Game g, List<Player> winners) {
        g.phase = Phase.FIM; g.seconds = 8;
        if (winners.isEmpty()) { return; }
        StringBuilder names = new StringBuilder();
        EffectService fx = getServer().getServiceManager().load(EffectService.class);
        for (Player w : winners) {
            SwState.Stats s = stats(w); s.wins++; s.games++; saveStats(w);
            String n = key(w); int wins = s.wins;
            data.change(st -> SwState.offer(st.topWins, n, wins));
            reward(w, winReward);
            if (fx != null) { fx.follow(w, EffectService.Shape.CELEBRACAO, 80); }
            w.sendMessage(PREFIX + "§6§lVITORIA! §r§fVoce ganhou o SkyWars.");
            names.append(names.length() == 0 ? "" : " e ").append(w.getName());
        }
        getServer().broadcastMessage(PREFIX + "§e" + names + " §fvenceu o SkyWars em §b" + g.name + "§f!");
    }

    void reward(Player p, long cents) {
        EconomyService eco = getServer().getServiceManager().load(EconomyService.class);
        if (eco != null && cents > 0) { eco.credit(key(p), EconomyService.Currency.MONEY, cents); p.sendPopup("§a+$" + cents / 100); }
    }

    /** Fim: todos voltam ao lobby e o mapa e restaurado da copia em segundo plano. */
    void reset(Game g) {
        g.phase = Phase.RESETANDO;
        Level lv = g.level();
        for (Player p : new ArrayList<>(inGame.keySet())) { if (inGame.get(p) == g) { toLobby(p); } }
        if (lv != null) { for (Player p : new ArrayList<>(lv.getPlayers().values())) { toLobby(p); } }
        g.players.clear(); g.spectators.clear(); g.kills.clear(); g.lastHit.clear(); g.cages.clear();
        String world = g.arena().world;
        if (!maps.hasBackup(world)) { g.phase = Phase.ESPERANDO; getLogger().warning("Arena " + g.name + " sem copia do mapa: use /sw salvar."); return; }
        if (lv != null) { getServer().unloadLevel(lv, true); }
        maps.restore(world).whenComplete((v, e) -> {
            if (e != null) { getLogger().error("Falha ao restaurar " + world, e); }
            getServer().loadLevel(world);
            g.phase = Phase.ESPERANDO;
        });
    }

    void broadcast(Game g, String msg) {
        for (Player p : g.players.keySet()) { p.sendMessage(PREFIX + msg); }
        for (Player p : g.spectators) { p.sendMessage(PREFIX + msg); }
    }

    // ---------- menus ----------

    void kitMenu(Player p) {
        Game g = inGame.get(p);
        if (g == null || !g.waiting()) { p.sendMessage(PREFIX + "Os kits so podem ser trocados antes da partida."); return; }
        SwState.Stats s = stats(p);
        Map<Integer, Item> icons = new HashMap<>();
        List<Kits.Kit> list = new ArrayList<>(Kits.ALL.values());
        for (int i = 0; i < list.size() && i < 26; i++) {
            Kits.Kit k = list.get(i);
            boolean owned = k.price == 0 || price(k) == 0 || s.kits.contains(k.id), selected = s.kit.equals(k.id);
            String state = selected ? "§a§l[SELECIONADO]" : owned ? "§eToque para usar" : "§6Comprar: $" + price(k) / 100;
            icons.put(i, Items.named(Item.get(k.icon, k.iconMeta, 1), (selected ? "§a" : owned ? "§f" : "§7") + k.name + "\n§7" + k.description + "\n" + state));
        }
        menus().open(p, "Kits do SkyWars", icons, (who, slot) -> { if (slot < list.size()) { chooseKit(who, list.get(slot)); } });
    }

    void chooseKit(Player p, Kits.Kit k) {
        SwState.Stats s = stats(p);
        if (k.price == 0 || price(k) == 0 || s.kits.contains(k.id)) { s.kit = k.id; saveStats(p); p.sendMessage(PREFIX + "Kit §e" + k.name + " §fselecionado."); return; }
        EconomyService eco = getServer().getServiceManager().load(EconomyService.class);
        long cost = price(k);
        if (eco == null || eco.balance(key(p), EconomyService.Currency.MONEY) < cost) { p.sendMessage(PREFIX + "§cDinheiro insuficiente: o kit " + k.name + " custa $" + cost / 100 + "."); return; }
        eco.debit(key(p), EconomyService.Currency.MONEY, cost).whenComplete((v, e) -> {
            if (e != null) { p.sendMessage(PREFIX + "§cCompra nao concluida."); return; }
            s.kits.add(k.id); s.kit = k.id; saveStats(p);
            p.sendMessage(PREFIX + "§aVoce comprou e selecionou o kit " + k.name + "!");
        });
    }

    MenuService menus() { return getServer().getServiceManager().load(MenuService.class); }

    // ---------- eventos ----------

    Game gameAt(Level lv) {
        if (lv == null) { return null; }
        for (Game g : games.values()) { SwState.Arena a = g.arena(); if (a != null && a.world.equals(lv.getFolderName())) { return g; } }
        return null;
    }

    @EventHandler(priority = EventPriority.MONITOR) public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        loadStats(p);
        // Voltou depois de cair no meio da partida: sai limpo para o lobby (sem levar itens da arena).
        if (gameAt(p.level) != null) { getServer().getScheduler().scheduleDelayedTask(new PluginTask<GoldSkyWars>(this) { @Override public void onRun(int t) { if (p.isOnline() && !inGame.containsKey(p)) { toLobby(p); } } }, 10); }
    }

    @EventHandler(priority = EventPriority.MONITOR) public void onQuit(PlayerQuitEvent e) { leave(e.getPlayer()); saveStats(e.getPlayer()); stats.remove(e.getPlayer()); flying.remove(e.getPlayer()); }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player)) { return; }
        Player p = (Player) e.getEntity();
        Game g = inGame.get(p);
        Player attacker = e instanceof EntityDamageByEntityEvent && ((EntityDamageByEntityEvent) e).getDamager() instanceof Player ? (Player) ((EntityDamageByEntityEvent) e).getDamager() : null;
        if (attacker != null && inGame.containsKey(attacker) && inGame.get(attacker).spectators.contains(attacker)) { e.setCancelled(); return; }
        if (g == null) { return; }
        if (g.phase != Phase.JOGANDO || g.spectators.contains(p)) {
            e.setCancelled();
            if (e.getCause() == EntityDamageEvent.CAUSE_VOID && g.waiting()) { double[] sp = g.arena().spawns.get(g.players.getOrDefault(p, 0)); p.teleport(new Vector3(sp[0], sp[1], sp[2])); }
            return;
        }
        if (attacker != null && g.arena().teamSize > 1 && Objects.equals(g.players.get(attacker), g.players.get(p))) { e.setCancelled(); return; }
        if (e.getCause() == EntityDamageEvent.CAUSE_FALL && System.currentTimeMillis() < g.noFallUntil) { e.setCancelled(); return; }
        if (e.isCancelled()) { return; }
        if (attacker != null && attacker != p) { g.lastHit.put(p, new Object[] {attacker, System.currentTimeMillis()}); }
        if (e.getCause() == EntityDamageEvent.CAUSE_VOID || e.getFinalDamage() >= p.getHealth()) {
            e.setCancelled();
            eliminate(g, p, attacker != null && attacker != p ? attacker : lastHitter(g, p), e.getCause() == EntityDamageEvent.CAUSE_VOID, null);
        }
    }

    boolean frozen(Player p) {
        Game g = inGame.get(p);
        if (g != null) { return g.phase != Phase.JOGANDO || g.spectators.contains(p); }
        Game at = gameAt(p.level);
        return at != null && !p.hasPermission("goldskywars.admin");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void onBreak(BlockBreakEvent e) { if (frozen(e.getPlayer())) { e.setCancelled(); } }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (frozen(e.getPlayer())) { e.setCancelled(); return; }
        Block b = e.getBlock();
        // TNT do Canhoneiro (e qualquer TNT na partida) acende sozinha.
        if (inGame.containsKey(e.getPlayer()) && b.getId() == Block.TNT) {
            Level lv = b.level; Vector3 at = new Vector3(b.x, b.y, b.z);
            getServer().getScheduler().scheduleDelayedTask(new PluginTask<GoldSkyWars>(this) { @Override public void onRun(int t) { Block now = lv.getBlock(at); if (now instanceof BlockTNT) { ((BlockTNT) now).prime(); } } }, 1);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void onPickup(InventoryPickupItemEvent e) { if (e.getInventory().getHolder() instanceof Player && frozen((Player) e.getInventory().getHolder())) { e.setCancelled(); } }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void onDrop(PlayerDropItemEvent e) { if (frozen(e.getPlayer()) || menuItem(e.getItem())) { e.setCancelled(); } }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        if (!inGame.containsKey(e.getPlayer()) || e.getPlayer().hasPermission("goldskywars.admin")) { return; }
        String cmd = e.getMessage().substring(1).split(" ")[0].toLowerCase(Locale.ROOT);
        if (!ALLOWED.contains(cmd)) { e.setCancelled(); e.getPlayer().sendMessage(PREFIX + "§cNa partida so funcionam /sw, /tell e /fps."); }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        Game g = inGame.get(p);
        if (g == null) { return; }
        if (useMenuItem(p, p.getInventory().getItemInHand())) { e.setCancelled(); return; }
        if (frozen(p)) { e.setCancelled(); }
    }

    /** No 0.15 tocar no ar com bau/cama nao chega ao servidor: selecionar o item ja age, e a hotbar volta ao slot 4. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHeld(PlayerItemHeldEvent e) {
        Player p = e.getPlayer();
        if (!inGame.containsKey(p) || !menuItem(e.getItem())) { return; }
        Item item = e.getItem();
        getServer().getScheduler().scheduleDelayedTask(new PluginTask<GoldSkyWars>(this) { @Override public void onRun(int t) {
            if (!p.isOnline()) { return; }
            p.getInventory().setHeldItemIndex(4); p.getInventory().sendHeldItem(p);
            useMenuItem(p, item);
        } }, 2);
    }

    boolean useMenuItem(Player p, Item item) {
        if (!item.hasCustomName()) { return false; }
        switch (item.getCustomName()) {
            case KIT_ITEM: kitMenu(p); return true;
            case LEAVE_ITEM: leave(p); p.sendMessage(PREFIX + "Voce saiu da partida."); return true;
            case AGAIN_ITEM: { Game g = inGame.get(p); int size = g == null ? 1 : g.arena().teamSize; leave(p); try { join(p, size); } catch (RuntimeException ex) { p.sendMessage(PREFIX + "§c" + ex.getMessage()); } return true; }
            default: return false;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLaunch(ProjectileLaunchEvent e) {
        EntityProjectile proj = e.getEntity();
        if (proj.shootingEntity instanceof Player) {
            Item hand = ((Player) proj.shootingEntity).getInventory().getItemInHand();
            if (hand.getId() == 332 && hand.hasCustomName() && hand.getCustomName().equals(Kits.PEARL)) { pearls.add(proj.getId()); }
        }
    }

    /** Perola do Fim: teleporta quem jogou para onde a bola de neve caiu (com 2,5 coracoes de dano, como a perola). */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onHit(ProjectileHitEvent e) {
        Entity proj = e.getEntity();
        if (!pearls.remove(proj.getId()) || !(proj instanceof EntityProjectile) || !(((EntityProjectile) proj).shootingEntity instanceof Player)) { return; }
        Player p = (Player) ((EntityProjectile) proj).shootingEntity;
        if (!p.isOnline() || p.level != proj.level || frozen(p)) { return; }
        p.teleport(new Vector3(proj.x, proj.y + 0.5, proj.z));
        p.attack(new EntityDamageEvent(p, EntityDamageEvent.CAUSE_FALL, 5));
    }

    // ---------- comandos ----------

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!data.isLoaded()) { sender.sendMessage(PREFIX + "Carregando..."); return true; }
        String sub = args.length == 0 ? "ajuda" : args[0].toLowerCase(Locale.ROOT);
        Player p = sender instanceof Player ? (Player) sender : null;
        try {
            switch (sub) {
                case "entrar": if (p != null) { join(p, args.length > 1 && args[1].equalsIgnoreCase("dupla") ? 2 : 1); } return true;
                case "sair": if (p != null) { if (inGame.containsKey(p)) { leave(p); p.sendMessage(PREFIX + "Voce saiu da partida."); } else { p.sendMessage(PREFIX + "Voce nao esta numa partida."); } } return true;
                case "kits": if (p != null) { kitMenu(p); } return true;
                case "stats": {
                    Player t = args.length > 1 ? getServer().getPlayerExact(args[1]) : p;
                    if (t == null) { sender.sendMessage(PREFIX + "Jogador offline."); return true; }
                    SwState.Stats s = stats(t);
                    sender.sendMessage(PREFIX + "§e" + t.getName() + "§f: §a" + s.wins + " vitorias§f, §c" + s.kills + " abates§f, " + s.deaths + " mortes, " + s.games + " partidas, kit §e" + Kits.ALL.get(s.kit).name);
                    return true;
                }
                case "top": { int i = 1; sender.sendMessage(PREFIX + "§6Top vitorias:"); for (RankingService.Entry e : topWins(10)) { sender.sendMessage("§e" + (i++) + ". §f" + e.player + " §7- " + e.label); } return true; }
                default: break;
            }
            // Tudo abaixo e de administracao: o plugin original deixava qualquer jogador criar arenas e escrever arquivos.
            if (!sender.hasPermission("goldskywars.admin")) { sender.sendMessage(PREFIX + "/sw entrar [solo|dupla], /sw sair, /sw kits, /sw stats, /sw top"); return true; }
            switch (sub) {
                case "criar": {
                    if (p == null) { return true; }
                    String name = SwState.name(arg(args, 1));
                    int size = args.length > 2 && args[2].equalsIgnoreCase("dupla") ? 2 : 1;
                    String world = p.level.getFolderName();
                    if (world.equals(getServer().getDefaultLevel().getFolderName())) { throw new IllegalArgumentException("Use um mundo proprio para a arena (/mundo criar)."); }
                    data.change(s -> { if (s.arenas.containsKey(name)) { throw new IllegalArgumentException("Arena ja existe."); } SwState.Arena a = new SwState.Arena(); a.world = world; a.teamSize = size; s.arenas.put(name, a); })
                            .whenComplete((v, e) -> { if (e == null) { games.put(name, new Game(name)); } p.sendMessage(PREFIX + (e != null ? "§c" + root(e) : "Arena " + name + " criada em " + world + ". Fique em cada jaula e use /sw jaula; no centro, /sw meio; depois /sw salvar.")); });
                    return true;
                }
                case "jaula": case "desfazer": case "meio": case "insano": case "salvar": {
                    if (p == null) { return true; }
                    Game g = gameAt(p.level);
                    if (g == null) { throw new IllegalArgumentException("Entre no mundo de uma arena (veja /sw lista)."); }
                    edit(p, g, sub, args);
                    return true;
                }
                case "lista": { for (Game g : games.values()) { SwState.Arena a = g.arena(); sender.sendMessage("§e" + g.name + " §f- " + a.world + ", " + (a.teamSize == 1 ? "solo" : "dupla") + (a.insane ? " insano" : "") + ", " + a.spawns.size() + " jaulas, " + g.phase + " (" + g.players.size() + "/" + g.capacity() + ")" + (maps.hasBackup(a.world) ? "" : " §cSEM COPIA")); } return true; }
                case "iniciar": { Game g = games.get(SwState.name(arg(args, 1))); if (g == null || !g.waiting()) { throw new IllegalArgumentException("Arena inexistente ou em jogo."); } g.forced = true; sender.sendMessage(PREFIX + "Comecando em 5 s."); return true; }
                case "remover": { String n = SwState.name(arg(args, 1)); Game g = games.get(n); if (g != null && !g.waiting()) { throw new IllegalArgumentException("Espere a partida acabar."); } data.change(s -> s.arenas.remove(n)).whenComplete((v, e) -> { games.remove(n); sender.sendMessage(PREFIX + "Arena removida."); }); return true; }
                default:
                    sender.sendMessage(PREFIX + "Admin: /sw criar <nome> [solo|dupla], jaula [x y z], desfazer, meio [x y z], insano, salvar, lista, iniciar <arena>, remover <arena>");
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
        if (!g.waiting() || !g.players.isEmpty()) { throw new IllegalArgumentException("A arena tem jogadores; espere esvaziar."); }
        double[] here = spot(p, args, 1);
        double[] c = g.arena().center;
        if (args.length >= 4 && c != null) { here[3] = Math.toDegrees(Math.atan2(-(c[0] - here[0]), c[2] - here[2])); }
        switch (sub) {
            case "jaula": data.change(s -> { SwState.Arena a = s.arenas.get(g.name); if (a.spawns.size() >= 24) { throw new IllegalArgumentException("Maximo de 24 jaulas."); } a.spawns.add(here); }).whenComplete((v, e) -> p.sendMessage(PREFIX + (e != null ? "§c" + root(e) : "Jaula " + g.arena().spawns.size() + " marcada (a jaula de vidro aparece aqui na partida)."))); break;
            case "desfazer": data.change(s -> { List<double[]> l = s.arenas.get(g.name).spawns; if (!l.isEmpty()) { l.remove(l.size() - 1); } }).whenComplete((v, e) -> p.sendMessage(PREFIX + "Ultima jaula removida.")); break;
            case "meio": data.change(s -> s.arenas.get(g.name).center = new double[] {here[0], here[1], here[2]}).whenComplete((v, e) -> p.sendMessage(PREFIX + "Centro marcado: baus a ate 12 blocos sao do meio.")); break;
            case "insano": data.change(s -> s.arenas.get(g.name).insane = !s.arenas.get(g.name).insane).whenComplete((v, e) -> p.sendMessage(PREFIX + "Modo insano " + (g.arena().insane ? "§aligado" : "§cdesligado") + "§f.")); break;
            default: {
                Level lv = p.level;
                lv.save(true);
                maps.backup(lv.getFolderName()).whenComplete((v, e) -> p.sendMessage(PREFIX + (e != null ? "§cFalha ao salvar a copia: " + e.getMessage() : "Copia do mapa salva: ele volta assim depois de cada partida.")));
            }
        }
    }

    static String arg(String[] a, int i) { if (a.length <= i) { throw new IllegalArgumentException("Faltam argumentos. Use /sw."); } return a[i]; }
    static String root(Throwable e) { while (e.getCause() != null) { e = e.getCause(); } return e.getMessage(); }
}
