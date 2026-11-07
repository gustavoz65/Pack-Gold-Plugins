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
    static String PREFIX = M.t("sky_wars");
    static final String STATS = "goldsw-stats";
    static final String KIT_ITEM = M.t("kits_toque"), LEAVE_ITEM = M.t("sair_toque"), AGAIN_ITEM = M.t("jogar_de_novo_toque"), VOTE_ITEM = M.t("votar_toque");
    private static final Gson GSON = new Gson();
    private static final Set<String> ALLOWED = new HashSet<>(Arrays.asList("sw", "skywars", "tell", "msg", "w", "r", "login", "l", "fps"));

    enum Phase { ESPERANDO, CONTAGEM, JOGANDO, FIM, RESETANDO }

    final class Game {
        final String name;
        Phase phase = Phase.ESPERANDO;
        int seconds, elapsed;
        boolean forced, editing;
        String lootMode = "normal";
        final MatchOptions options = new MatchOptions();
        final Set<Long> placedOres = new HashSet<>();
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
    final LobbyQueue queue = new LobbyQueue(this);
    Progression progress;
    Perks perks;
    OreEditor ores;
    final Map<Player, Map<Integer, Item>> lobbyItems = new IdentityHashMap<>();
    /** Bolas de neve que sao Perolas do Fim (pelo id da entidade). */
    final Set<Long> pearls = new HashSet<>();
    final Set<Player> flying = Collections.newSetFromMap(new IdentityHashMap<>());
    long minPlayers = 2, countdown = 20, gameSeconds = 900, refill1 = 180, refill2 = 360, killReward = 2000, winReward = 10000;
    String lobbyWorld = "";

    @Override public void onEnable() {
        M.B.attach(this);
        Settings.prefix(this, PREFIX, v -> PREFIX = v);
        Settings.number(this, "fila.minimo", 2, "Fila do lobby (NPC): jogadores para comecar a contagem", v -> queue.minimum = (int) Math.max(1, Math.min(48, v)));
        Settings.number(this, "fila.contagem", 10, "Fila do lobby (NPC): segundos de contagem antes de ir para o mapa", v -> queue.countdown = (int) Math.max(3, Math.min(120, v)));
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
        if (core == null) { throw new IllegalStateException(M.t("goldcore_precisa_estar_ativo_antes_do")); }
        progress = new Progression(this);
        perks = new Perks(this); ores = new OreEditor(this);
        getServer().getPluginManager().registerEvents(perks, this);
        getServer().getPluginManager().registerEvents(ores, this);
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
        getServer().getScheduler().scheduleRepeatingTask(new PluginTask<GoldSkyWars>(this) { @Override public void onRun(int tick) { second(); queue.second(); } }, 20);
        // Queda no vazio conferida 4x por segundo: a vitoria sai assim que o ultimo adversario cai.
        getServer().getScheduler().scheduleRepeatingTask(new PluginTask<GoldSkyWars>(this) { @Override public void onRun(int tick) { voidCheck(); } }, 5);
    }

    final Map<String, Long> kitPrices = new HashMap<>();
    long price(Kits.Kit k) { return kitPrices.getOrDefault(k.id, k.price); }

    @Override public void onDisable() {
        for (Game g : games.values()) { for (Player p : new ArrayList<>(inGame.keySet())) { if (inGame.get(p) == g) { toLobby(p); } } }
        if (progress != null) { progress.close(); }
        if (ores != null) { ores.close(); }
        lobbyItems.clear(); chestMode.clear();
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

    void loadStats(Player p) { progress.load(p); }
    /** Leitura sem copia: nao altere (use progress.change). */
    SwState.Stats stats(Player p) { return progress.view(p); }

    // ---------- entrar e sair ----------

    static boolean menuItem(Item i) { return i.hasCustomName() && (i.getCustomName().equals(KIT_ITEM) || i.getCustomName().equals(LEAVE_ITEM) || i.getCustomName().equals(AGAIN_ITEM) || i.getCustomName().equals(VOTE_ITEM)); }

    /** Pode entrar numa partida agora? (login, fora de partida, inventario e armadura vazios) */
    void checkCanJoin(Player p) {
        if (!progress.loaded(p)) { throw new IllegalArgumentException(M.t("carregando")); }
        AuthService auth = getServer().getServiceManager().load(AuthService.class);
        if (auth != null && !auth.isAuthenticated(p)) { throw new IllegalArgumentException(M.t("sky_login")); }
        if (inGame.containsKey(p)) { throw new IllegalArgumentException(M.t("voce_ja_esta_numa_partida_use")); }
        for (Item i : p.getInventory().getContents().values()) {
            // Itens do lobby (livro do perfil, livro dos lobbies...) podem ficar; itens de verdade nao, para ninguem perder nada.
            if (i.getId() != 0 && !lobbyItem(i)) { throw new IllegalArgumentException(M.t("esvazie_inventario_antes_de_entrar_no")); }
        }
        for (Item i : p.getInventory().getArmorContents()) { if (i.getId() != 0) { throw new IllegalArgumentException(M.t("tire_armadura_antes_de_entrar_no")); } }
    }

    /** Item de menu do lobby (nome com "(toque)" ou item de zona do GoldComandos): nao conta como item de verdade. */
    private static boolean lobbyItem(Item i) {
        if (!i.hasCustomName()) { return false; }
        String n = i.getCustomName();
        return n.contains("(toque)") || i.getId() == 340 || i.getId() == 345;
    }

    /** Sala aceitando jogadores deste tamanho de time. */
    boolean open(Game g, int teamSize) {
        SwState.Arena a = g.arena();
        return a != null && a.teamSize == teamSize && a.enabled && !g.editing && a.spawns.size() >= 2 && maps.hasBackup(a.world) && g.waiting() && g.level() != null && g.players.size() < g.capacity();
    }

    void join(Player p, int teamSize) { join(p, teamSize, null); }

    /** Entra na sala indicada (fila do lobby) ou, sem ela, na sala aberta com mais jogadores. */
    void join(Player p, int teamSize, Game target) {
        checkCanJoin(p);
        Game best = null;
        if (target != null) { if (open(target, teamSize)) { best = target; } }
        else { for (Game g : games.values()) { if (open(g, teamSize) && (best == null || g.players.size() > best.players.size())) { best = g; } } }
        if (best == null) { throw new IllegalArgumentException(M.t("nenhuma_sala_aberta_agora_tente_em", (teamSize == 1 ? M.t("solo") : M.t("de_dupla")))); }
        queue.forget(p);
        Game g = best;
        SwState.Arena a = g.arena();
        int slot = -1;
        for (int i = 0; i < a.spawns.size() && slot < 0; i++) { int used = 0; for (int s : g.players.values()) { if (s == i) { used++; } } if (used < a.teamSize) { slot = i; } }
        if (slot < 0) { throw new IllegalArgumentException(M.t("sala_cheia")); }
        double[] sp = a.spawns.get(slot);
        lobbyItems.put(p, new HashMap<>(p.getInventory().getContents()));
        g.players.put(p, slot);
        inGame.put(p, g);
        try { enter(p, g, a, sp); }
        catch (RuntimeException e) {
            // Nunca deixar o jogador pela metade (na sala sem itens): desfaz e devolve o lobby.
            getLogger().error("Falha ao entrar na arena " + g.name, e);
            g.players.remove(p); g.options.votes.remove(p); toLobby(p);
            throw new IllegalArgumentException(M.t("sala_falhou"));
        }
    }

    private void enter(Player p, Game g, SwState.Arena a, double[] sp) {
        p.getInventory().clearAll(); for (int hb = 0; hb < 9; hb++) { p.getInventory().setHotbarSlotIndex(hb, hb); }
        p.removeAllEffects();
        p.setHealth(p.getMaxHealth());
        p.getFoodData().setLevel(20);
        p.setGamemode(Player.SURVIVAL);
        cage(g, sp, true);
        p.teleport(new Location(sp[0], sp[1], sp[2], facingCenter(a, sp), 0, g.level()));
        p.getInventory().setItem(0, Items.named(Item.get(54, 0, 1), KIT_ITEM));
        p.getInventory().setItem(1, Items.named(Item.get(339, 0, 1), VOTE_ITEM));
        p.getInventory().setItem(8, Items.named(Item.get(355, 0, 1), LEAVE_ITEM));
        p.getInventory().sendContents(p);
        if (!a.display.isEmpty()) { p.sendMessage(PREFIX + M.t("mapa_info", a.display, a.author.isEmpty() ? "-" : a.author)); }
        broadcast(g, M.t("entrou", p.getName(), g.players.size(), g.capacity()));
        p.sendMessage(PREFIX + M.t("kit_toque_no_bau_da_hotbar", Kits.ALL.get(stats(p).kit).name));
    }

    void leave(Player p) {
        Game g = inGame.get(p);
        if (g == null) { return; }
        if (g.phase == Phase.JOGANDO && g.players.containsKey(p)) { eliminate(g, p, lastHitter(g, p), false, M.t("saiu_da_partida", p.getName())); }
        g.players.remove(p); g.spectators.remove(p); g.kills.remove(p); g.lastHit.remove(p); g.options.votes.remove(p);
        toLobby(p);
        if (g.waiting()) { broadcast(g, M.t("saiu", p.getName(), g.players.size(), g.capacity())); }
    }

    /** Volta ao lobby limpo: sem itens da partida, sem voo e sem invisibilidade de espectador. */
    void toLobby(Player p) {
        inGame.remove(p);
        p.getInventory().clearAll(); for (int hb = 0; hb < 9; hb++) { p.getInventory().setHotbarSlotIndex(hb, hb); }
        Map<Integer, Item> original = lobbyItems.remove(p);
        if (original != null) { p.getInventory().setContents(original); }
        p.setMaxHealth(20);
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
        // Depois do /sw salvar o mapa e recarregado sem chunks: carrega o da jaula (e vizinhos da borda) antes de mexer.
        for (int cx = (x - 1) >> 4; cx <= (x + 1) >> 4; cx++) { for (int cz = (z - 1) >> 4; cz <= (z + 1) >> 4; cz++) { lv.loadChunk(cx, cz); } }
        List<Vector3> blocks = new ArrayList<>();
        blocks.add(new Vector3(x, y - 1, z)); blocks.add(new Vector3(x, y + 3, z));
        for (int dy = 0; dy <= 2; dy++) { for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) { blocks.add(new Vector3(x + d[0], y + dy, z + d[1])); } }
        for (Vector3 v : blocks) {
            if (build && lv.getBlockIdAt(v.getFloorX(), v.getFloorY(), v.getFloorZ()) != 0) { continue; }
            lv.setBlock(v, Block.get(build ? cageBlock(g, sp) : 0), true, false);
            if (build) { g.cages.add(v); }
        }
    }

    private int cageBlock(Game g, double[] sp) {
        int slot = g.arena().spawns.indexOf(sp);
        for (Map.Entry<Player, Integer> entry : g.players.entrySet()) { if (entry.getValue() == slot) { return progress.cage(entry.getKey()); } }
        return 20;
    }

    // ---------- ciclo de 1 segundo ----------

    void second() {
        long now = System.currentTimeMillis();
        for (Game g : games.values()) {
            Level lv = g.level();
            if (lv == null || g.editing || g.phase == Phase.RESETANDO) { continue; }
            switch (g.phase) {
                case ESPERANDO:
                    if (g.players.size() >= Math.min(Math.max(minPlayers, g.arena().minimum), Math.max(2, g.capacity())) || (g.forced && !g.players.isEmpty())) { g.phase = Phase.CONTAGEM; g.seconds = g.forced ? 5 : (int) countdown; broadcast(g, M.t("partida_comeca_em", g.seconds)); }
                    for (Player p : g.players.keySet()) { p.sendTip(M.t("aguardando_jogadores", g.players.size(), g.capacity())); }
                    break;
                case CONTAGEM: {
                    if (g.players.size() < Math.min(Math.max(minPlayers, g.arena().minimum), Math.max(2, g.capacity())) && !g.forced) { g.phase = Phase.ESPERANDO; broadcast(g, M.t("jogadores_insuficientes_contagem_cancelada")); break; }
                    if (g.players.size() >= g.capacity() && g.seconds > 10) { g.seconds = 10; }
                    g.seconds--;
                    for (Player p : g.players.keySet()) { p.sendTip(M.t("comeca_em_kit", g.seconds, Kits.ALL.get(stats(p).kit).name)); if (g.seconds <= 5) { lv.addSound(new ClickSound(p), p); } }
                    if (g.seconds <= 0) { start(g); }
                    break;
                }
                case JOGANDO: {
                    g.elapsed++;
                    if (g.elapsed == refill1 || g.elapsed == refill2) { loadArena(g); fillChests(g, true); broadcast(g, M.t("os_baus_foram_reabastecidos")); }
                    if (g.elapsed == gameSeconds - 60) { broadcast(g, M.t("falta_1_minuto_sem_vencedor_partida")); }
                    if (g.elapsed >= gameSeconds) { broadcast(g, M.t("tempo_esgotado_ninguem_venceu")); finish(g, Collections.emptyList()); break; }
                    for (Player p : new ArrayList<>(g.players.keySet())) {
                        long next = Math.min(refill1 > g.elapsed ? refill1 : Long.MAX_VALUE, refill2 > g.elapsed ? refill2 : Long.MAX_VALUE);
                        p.sendTip(M.t("skywars_vivos_abates", g.players.size(), g.kills.getOrDefault(p, 0), (next == Long.MAX_VALUE ? M.t("fim", clock(gameSeconds - g.elapsed)) : M.t("refil", clock(next - g.elapsed)))));
                    }
                    for (Player s : g.spectators) { s.sendTip(M.t("espectador_vivos", g.players.size())); }
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

    /** Abaixo disso o jogador ja caiu: 25 blocos sob a jaula mais baixa (as ilhas nao descem tanto). */
    double voidY(Game g) {
        double min = 1000; for (double[] sp : g.arena().spawns) { min = Math.min(min, sp[1]); }
        return min - 25;
    }

    void voidCheck() {
        for (Game g : games.values()) {
            if (g.phase != Phase.JOGANDO || g.players.isEmpty()) { continue; }
            double voidY = voidY(g);
            for (Player p : new ArrayList<>(g.players.keySet())) { if (g.phase == Phase.JOGANDO && p.y < voidY) { eliminate(g, p, lastHitter(g, p), true, null); } }
        }
    }

    /** Direcao da jaula: olhando para o meio do mapa (centro, ou a media das jaulas). */
    static float facingCenter(SwState.Arena a, double[] sp) {
        double cx, cz;
        if (a.center.length == 3) { cx = a.center[0]; cz = a.center[2]; }
        else { cx = 0; cz = 0; for (double[] s : a.spawns) { cx += s[0]; cz += s[2]; } cx /= a.spawns.size(); cz /= a.spawns.size(); }
        double dx = cx - sp[0], dz = cz - sp[2];
        if (dx * dx + dz * dz < 1) { return (float) sp[3]; }
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
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
        g.options.apply(this, g);
        fillChests(g, false);
        for (Player p : g.players.keySet()) {
            p.getInventory().clearAll(); for (int hb = 0; hb < 9; hb++) { p.getInventory().setHotbarSlotIndex(hb, hb); }
            Kits.Kit kit = Kits.ALL.get(stats(p).kit);
            for (Item it : Kits.items(kit)) {
                if (Items.armor(it.getId())) { p.getInventory().setArmorItem(Items.armorSlot(it.getId()), it); } else { p.getInventory().addItem(it); }
            }
            p.getInventory().sendContents(p); p.getInventory().sendArmorContents(p);
            p.setHealth(p.getMaxHealth()); p.getFoodData().setLevel(20);
            g.kills.put(p, 0);
            perks.start(p);
            p.sendMessage(PREFIX + M.t("lutem_ultimo_vivo_vence", (g.arena().teamSize > 1 ? M.t("time") : M.t("jogador"))));
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
            String chestKey = be.getFloorX() + "," + be.getFloorY() + "," + be.getFloorZ();
            boolean middle = a.chests.containsKey(chestKey) ? a.chests.get(chestKey) : a.center.length == 3 && Math.pow(be.x - a.center[0], 2) + Math.pow(be.z - a.center[2], 2) <= 144;
            if (refill) { boolean empty = true; for (Item i : chest.getInventory().getContents().values()) { if (i.getId() != 0) { empty = false; break; } } if (!empty) { continue; } }
            chest.getInventory().clearAll();
            List<Integer> slots = new ArrayList<>(); for (int i = 0; i < 27; i++) { slots.add(i); }
            Collections.shuffle(slots, r);
            int i = 0;
            for (Item item : Loot.roll(r, middle && !g.lootMode.equals("basico"), g.lootMode.equals("insano"))) { chest.getInventory().setItem(slots.get(i++), item); }
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
        progress.event(p, ps -> { ps.deaths++; ps.games++; });
        String msg = customMessage;
        if (killer != null && killer != p) {
            int k = g.kills.merge(killer, 1, Integer::sum);
            progress.event(killer, ks -> { ks.kills++; ks.souls = Math.min(1000000, ks.souls + 1); ks.xp += progress.killXp; ks.coins += progress.killCoins; });
            perks.kill(killer);
            reward(killer, killReward);
            if (msg == null) { msg = "§c" + p.getName() + " §7" + (voidDeath ? M.t("foi_jogado_no_vazio_por") : M.t("foi_morto_por")) + " §a" + killer.getName() + (k >= 2 ? " §6(" + k + " abates)" : ""); }
            killer.getLevel().addSound(new ClickSound(killer), killer);
        } else if (msg == null) { msg = "§c" + p.getName() + " §7" + (voidDeath ? M.t("caiu_no_vazio") : "morreu."); }
        broadcast(g, msg);
        spectate(g, p);
        checkWin(g);
    }

    void spectate(Game g, Player p) {
        g.spectators.add(p);
        p.getInventory().clearAll(); for (int hb = 0; hb < 9; hb++) { p.getInventory().setHotbarSlotIndex(hb, hb); }
        p.removeAllEffects(); p.extinguish();
        p.setHealth(p.getMaxHealth()); p.getFoodData().setLevel(20);
        p.setDataFlag(Entity.DATA_FLAGS, Entity.DATA_FLAG_INVISIBLE, true);
        if (!p.getAllowFlight()) { p.setAllowFlight(true); flying.add(p); }
        SwState.Arena a = g.arena();
        double[] c = a.spectator.length == 3 ? a.spectator : a.center.length == 3 ? a.center : a.spawns.get(0);
        p.teleport(new Location(c[0], c[1] + (a.spectator.length == 3 ? 0 : 12), c[2], 0, 0, g.level()));
        p.getInventory().setItem(0, Items.named(Item.get(339, 0, 1), AGAIN_ITEM));
        p.getInventory().setItem(8, Items.named(Item.get(355, 0, 1), LEAVE_ITEM));
        p.getInventory().sendContents(p);
        p.sendMessage(PREFIX + M.t("voce_foi_eliminado_assistindo_como_espectador"));
    }

    void checkWin(Game g) {
        if (g.phase != Phase.JOGANDO || g.teamsAlive() > 1) { return; }
        finish(g, new ArrayList<>(g.players.keySet()));
    }

    void finish(Game g, List<Player> winners) {
        g.phase = Phase.FIM; g.seconds = 12;
        // Todos (vencedor e espectadores) escolhem: papel = jogar de novo, cama = voltar ao lobby. Sem escolha, voltam em 12 s.
        for (Player p : new ArrayList<>(g.players.keySet())) {
            p.getInventory().clearAll(); for (int hb = 0; hb < 9; hb++) { p.getInventory().setHotbarSlotIndex(hb, hb); }
            p.getInventory().setItem(0, Items.named(Item.get(339, 0, 1), AGAIN_ITEM));
            p.getInventory().setItem(8, Items.named(Item.get(355, 0, 1), LEAVE_ITEM));
            p.getInventory().setHeldItemIndex(4);
            p.getInventory().sendContents(p);
            p.sendMessage(PREFIX + M.t("fim_escolha_jogar_ou_sair"));
        }
        if (winners.isEmpty()) { return; }
        StringBuilder names = new StringBuilder();
        EffectService fx = getServer().getServiceManager().load(EffectService.class);
        for (Player w : winners) {
            String n = key(w);
            progress.change(w, s -> { s.wins++; s.games++; s.xp += progress.winXp; s.coins += progress.winCoins; }).thenAccept(s -> {
                if (isEnabled() && data.isLoaded()) { data.change(st -> SwState.offer(st.topWins, n, s.wins)); }
            }).exceptionally(e -> { getLogger().error("Falha ao salvar vitoria", e); return null; });
            reward(w, winReward);
            if (fx != null && !DeviceService.low(w)) { fx.follow(w, EffectService.Shape.CELEBRACAO, 80); }
            w.sendMessage(PREFIX + M.t("vitoria_voce_ganhou_skywars"));
            names.append(names.length() == 0 ? "" : " e ").append(w.getName());
        }
        getServer().broadcastMessage(PREFIX + M.t("venceu_skywars_em", names, g.name));
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
        g.players.clear(); g.spectators.clear(); g.kills.clear(); g.lastHit.clear(); g.cages.clear(); g.options.votes.clear(); g.placedOres.clear();
        String world = g.arena().world;
        if (!maps.hasBackup(world)) { getLogger().warning("Arena " + g.name + " sem copia do mapa: use /sw salvar."); return; }
        if (lv != null && !getServer().unloadLevel(lv, true)) { getLogger().error("Falha ao descarregar arena " + g.name); return; }
        maps.restore(world).whenComplete((v, e) -> {
            if (!isEnabled()) { return; }
            if (e != null) { getLogger().error(M.t("falha_ao_restaurar", world), e); return; }
            if (getServer().loadLevel(world)) { g.phase = Phase.ESPERANDO; }
        });
    }

    void broadcast(Game g, String msg) {
        for (Player p : g.players.keySet()) { p.sendMessage(PREFIX + msg); }
        for (Player p : g.spectators) { p.sendMessage(PREFIX + msg); }
    }

    // ---------- menus ----------

    void kitMenu(Player p) {
        Game g = inGame.get(p);
        if (g == null || !g.waiting()) { p.sendMessage(PREFIX + M.t("os_kits_so_podem_ser_trocados")); return; }
        SwState.Stats s = stats(p);
        Map<Integer, Item> icons = new HashMap<>();
        List<Kits.Kit> list = new ArrayList<>(Kits.ALL.values());
        for (int i = 0; i < list.size() && i < 26; i++) {
            Kits.Kit k = list.get(i);
            boolean owned = k.price == 0 || price(k) == 0 || s.kits.contains(k.id), selected = s.kit.equals(k.id);
            String state = selected ? M.t("selecionado") : owned ? M.t("toque_para_usar") : M.t("comprar", price(k) / 100);
            icons.put(i, Items.named(Item.get(k.icon, k.iconMeta, 1), (selected ? "§a" : owned ? "§f" : "§7") + k.name + "\n§7" + k.description + "\n" + state));
        }
        menus().open(p, M.t("kits_do_skywars"), icons, (who, slot) -> { if (slot < list.size()) { chooseKit(who, list.get(slot)); } });
    }

    void chooseKit(Player p, Kits.Kit k) { progress.buyKit(p, k); }

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

    @EventHandler(priority = EventPriority.MONITOR) public void onQuit(PlayerQuitEvent e) { queue.forget(e.getPlayer()); chestMode.remove(e.getPlayer()); leave(e.getPlayer()); progress.release(e.getPlayer()); flying.remove(e.getPlayer()); }

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
        if (!ALLOWED.contains(cmd)) { e.setCancelled(); e.getPlayer().sendMessage(PREFIX + M.t("na_partida_so_funcionam_sw_tell")); }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        Game g = inGame.get(p);
        if (g == null) { return; }
        Item hand = p.getInventory().getItemInHand();
        if (menuItem(hand)) { e.setCancelled(); if (HotbarAction.tap(p)) { useMenuItem(p, hand); } return; }
        if (frozen(p)) { e.setCancelled(); }
    }

    /**
     * Por padrao os itens da partida agem ao tocar no chao com eles (onInteract). Selecionar o slot so age com
     * hotbar.ao_selecionar = 1 no painel do GoldCore ou para o espectador, que voa e nao tem chao para tocar
     * ("Jogar de novo" e "Sair"). A hotbar volta ao slot 4.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHeld(PlayerItemHeldEvent e) {
        Player p = e.getPlayer();
        Game game = inGame.get(p);
        if (game == null || !menuItem(e.getItem())) { return; }
        if (!HotbarAction.onSelect() && !game.spectators.contains(p) && !flying.contains(p)) { return; }
        Item item = e.getItem();
        getServer().getScheduler().scheduleDelayedTask(new PluginTask<GoldSkyWars>(this) { @Override public void onRun(int t) {
            if (!p.isOnline()) { return; }
            p.getInventory().setHeldItemIndex(4); p.getInventory().sendHeldItem(p);
            useMenuItem(p, item);
        } }, 2);
    }

    boolean useMenuItem(Player p, Item item) {
        if (!item.hasCustomName()) { return false; }
        String name = item.getCustomName();
        if (name.equals(KIT_ITEM)) { kitMenu(p); return true; }
        if (name.equals(VOTE_ITEM)) { try { MatchOptions.menu(this, p); } catch (RuntimeException ex) { p.sendMessage(PREFIX + ex.getMessage()); } return true; }
        if (name.equals(LEAVE_ITEM)) { leave(p); p.sendMessage(PREFIX + M.t("voce_saiu_da_partida")); return true; }
        if (name.equals(AGAIN_ITEM)) { { Game g = inGame.get(p); int size = g == null ? 1 : g.arena().teamSize; leave(p); try { join(p, size); } catch (RuntimeException ex) { p.sendMessage(PREFIX + "§c" + ex.getMessage()); } return true; } }
        return false;
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
        p.attack(new EntityDamageEvent(p, EntityDamageEvent.CAUSE_FALL, Math.max(1, 5 - progress.perk(p, "endermastery"))));
    }

    // ---------- comandos ----------

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!data.isLoaded()) { sender.sendMessage(PREFIX + M.t("carregando")); return true; }
        if (command.getName().equals("gerar")) {
            if (sender instanceof Player && args.length == 1 && args[0].equalsIgnoreCase("minerios")) { try { ores.generate((Player)sender); } catch (RuntimeException e) { sender.sendMessage(e.getMessage()); } }
            else { sender.sendMessage(M.t("minerios_instrucoes")); } return true;
        }
        if (command.getName().equals("swmap")) { return mapCommand(sender, args); }
        String sub = args.length == 0 ? "opcoes" : args[0].toLowerCase(Locale.ROOT);
        Player p = sender instanceof Player ? (Player) sender : null;
        try {
            switch (sub) {
                case "opcoes": case "options": if (p != null) { progress.menu(p); } return true;
                case "almas": case "soulwell": if (p != null) { progress.well(p); } return true;
                case "vantagens": case "perks": if (p != null) { progress.perks(p); } return true;
                case "jaulas": case "glass": if (p != null) { progress.cages(p); } return true;
                case "prestigio": case "prestige": if (p != null) { progress.prestige(p); } return true;
                case "votar": if (p != null) { MatchOptions.menu(this, p); } return true;
                case "ilhas": if (p != null) { if (args.length > 1 && args[1].equalsIgnoreCase("limpar")) { ores.clear(p); } else { ores.tool(p); } } return true;
                case "join": case "entrar": if (p != null) { join(p, args.length > 1 && args[1].equalsIgnoreCase("dupla") ? 2 : 1); } return true;
                case "fila": case "jogar": if (p != null) { queue.toggle(p, args.length > 1 && args[1].equalsIgnoreCase("dupla") ? 2 : 1); } return true;
                case "quit": case "leave": case "sair": if (p != null) { if (inGame.containsKey(p)) { leave(p); p.sendMessage(PREFIX + M.t("voce_saiu_da_partida")); } else { p.sendMessage(PREFIX + M.t("voce_nao_esta_numa_partida")); } } return true;
                case "kits": if (p != null) { kitMenu(p); } return true;
                case "stats": {
                    Player t = args.length > 1 ? getServer().getPlayerExact(args[1]) : p;
                    if (t == null) { sender.sendMessage(PREFIX + M.t("jogador_offline")); return true; }
                    SwState.Stats s = stats(t);
                    sender.sendMessage(PREFIX + M.t("vitorias_abates_mortes_partidas_kit", t.getName(), s.wins, s.kills, s.deaths, s.games, Kits.ALL.get(s.kit).name));
                    return true;
                }
                case "top": { int i = 1; sender.sendMessage(PREFIX + M.t("top_vitorias")); for (RankingService.Entry e : topWins(10)) { sender.sendMessage("§e" + (i++) + ". §f" + e.player + " §7- " + e.label); } return true; }
                default: break;
            }
            // Tudo abaixo e de administracao: o plugin original deixava qualquer jogador criar arenas e escrever arquivos.
            if (!sender.hasPermission("goldskywars.admin")) { sender.sendMessage(PREFIX + M.t("sw_entrar_solo_dupla_sw_sair")); return true; }
            switch (sub) {
                case "criar": {
                    String name = SwState.name(arg(args, 1));
                    int size = args.length > 2 && args[2].equalsIgnoreCase("dupla") ? 2 : 1;
                    // No console o mundo vem no comando: /sw criar <nome> <solo|dupla> <mundo>
                    String world = p != null ? p.level.getFolderName() : arg(args, 3);
                    if (p == null && getServer().getLevelByName(world) == null && !getServer().isLevelGenerated(world)) { throw new IllegalArgumentException(M.t("mundo_inexistente_sw", world)); }
                    if (world.equals(getServer().getDefaultLevel().getFolderName())) { throw new IllegalArgumentException(M.t("use_um_mundo_proprio_para_arena")); }
                    if (p == null) { consoleArena = name; }
                    data.change(s -> { if (s.arenas.containsKey(name)) { throw new IllegalArgumentException(M.t("arena_ja_existe")); } SwState.Arena a = new SwState.Arena(); a.world = world; a.teamSize = size; s.arenas.put(name, a); })
                            .whenComplete((v, e) -> {
                                if (e == null) { games.put(name, new Game(name)); if (p == null) { consoleArena = name; } }
                                sender.sendMessage(PREFIX + (e != null ? "§c" + root(e) : M.t(p == null ? "arena_criada_console" : "arena_criada_em_fique_em_cada", name, world)));
                            });
                    return true;
                }
                case "jaula": case "desfazer": case "meio": case "insano": case "salvar": {
                    Game g = p != null ? gameAt(p.level) : consoleGame();
                    if (g == null) { throw new IllegalArgumentException(M.t("entre_no_mundo_de_uma_arena")); }
                    edit(sender, p, g, sub, args);
                    return true;
                }
                case "lista": { for (Game g : games.values()) { SwState.Arena a = g.arena(); sender.sendMessage(M.t("jaulas", g.name, a.world, (a.teamSize == 1 ? M.t("solo") : M.t("dupla")), (a.insane ? M.t("insano") : ""), a.spawns.size(), g.phase, g.players.size(), g.capacity(), (maps.hasBackup(a.world) ? "" : M.t("sem_copia")))); } return true; }
                case "iniciar": { Game g = games.get(SwState.name(arg(args, 1))); if (g == null || !g.waiting() || g.editing || !maps.hasBackup(g.arena().world)) { throw new IllegalArgumentException(M.t("arena_inexistente_ou_em_jogo")); } g.forced = true; if (g.phase == Phase.CONTAGEM && g.seconds > 5) { g.seconds = 5; } sender.sendMessage(PREFIX + M.t("comecando_em_5")); return true; }
                case "remover": { String n = SwState.name(arg(args, 1)); Game g = games.get(n); if (g != null && (!g.waiting() || g.editing || !g.players.isEmpty())) { throw new IllegalArgumentException(M.t("espere_partida_acabar")); } if (g != null) { g.editing = true; } data.change(s -> s.arenas.remove(n)).whenComplete((v, e) -> { if (g != null) { g.editing = false; } if (e != null) { sender.sendMessage(M.t("perfil_operacao_falhou", root(e))); return; } games.remove(n); sender.sendMessage(PREFIX + M.t("arena_removida")); }); return true; }
                default:
                    sender.sendMessage(PREFIX + M.t("admin_sw_criar_nome_solo_dupla"));
                    return true;
            }
        } catch (RuntimeException e) { sender.sendMessage(PREFIX + "§c" + e.getMessage()); return true; }
    }

    /** Ponto do jogador ou, se vierem tres numeros a partir de {@code from}, essas coordenadas (para colar o pontos.txt). */
    static double[] spot(Player p, String[] args, int from) {
        if (args.length >= from + 3) {
            double[] v;
            try { v = new double[] {Double.parseDouble(args[from]), Double.parseDouble(args[from + 1]), Double.parseDouble(args[from + 2]), p.yaw}; } catch (NumberFormatException ignored) { v = null; }
            if (v == null || !(Math.abs(v[0]) < 3e7 && Math.abs(v[2]) < 3e7 && v[1] >= 0 && v[1] < 256)) { throw new IllegalArgumentException(M.t("coordenadas_invalidas_use_de_0_255")); }
            return v;
        }
        return new double[] {Math.floor(p.x) + 0.5, Math.floor(p.y), Math.floor(p.z) + 0.5, p.yaw};
    }

    /** Arena escolhida pelo console com /swmap arena <nome> (ou a ultima criada pelo console). */
    String consoleArena;

    Game consoleGame() {
        Game g = consoleArena == null ? null : games.get(consoleArena);
        if (g == null) { throw new IllegalArgumentException(M.t("console_escolha_arena")); }
        return g;
    }

    /** Ponto do jogador ou coordenadas; o console precisa das coordenadas. */
    static double[] here(Player p, String[] args, int from) {
        if (p != null) { return spot(p, args, from); }
        if (args.length < from + 3) { throw new IllegalArgumentException(M.t("console_coordenadas")); }
        double[] v;
        try { v = new double[] {Double.parseDouble(args[from]), Double.parseDouble(args[from + 1]), Double.parseDouble(args[from + 2]), args.length > from + 3 ? Double.parseDouble(args[from + 3]) : 0}; } catch (NumberFormatException ignored) { v = null; }
        if (v == null || !(Math.abs(v[0]) < 3e7 && Math.abs(v[2]) < 3e7 && v[1] >= 0 && v[1] < 256 && Double.isFinite(v[3]))) { throw new IllegalArgumentException(M.t("coordenadas_invalidas_use_de_0_255")); }
        return v;
    }

    void edit(CommandSender sender, Player p, Game g, String sub, String[] args) {
        if (!g.waiting() || !g.players.isEmpty() || g.editing) { throw new IllegalArgumentException(M.t("arena_tem_jogadores_espere_esvaziar")); }
        boolean needsSpot = sub.equals("jaula") || sub.equals("meio");
        double[] here = needsSpot || p != null ? here(p, args, 1) : null;
        double[] c = g.arena().center;
        if (needsSpot && args.length >= 4 && c != null && c.length == 3) { here[3] = Math.toDegrees(Math.atan2(-(c[0] - here[0]), c[2] - here[2])); }
        switch (sub) {
            case "jaula": data.change(s -> { SwState.Arena a = s.arenas.get(g.name); if (a.spawns.size() >= 24) { throw new IllegalArgumentException(M.t("maximo_de_24_jaulas")); } a.spawns.add(here); }).whenComplete((v, e) -> sender.sendMessage(PREFIX + (e != null ? "§c" + root(e) : M.t("jaula_marcada_jaula_de_vidro_aparece", g.arena().spawns.size())))); break;
            case "desfazer": data.change(s -> { List<double[]> l = s.arenas.get(g.name).spawns; if (!l.isEmpty()) { l.remove(l.size() - 1); } }).whenComplete((v, e) -> sender.sendMessage(PREFIX + M.t("ultima_jaula_removida"))); break;
            case "meio": data.change(s -> s.arenas.get(g.name).center = new double[] {here[0], here[1], here[2]}).whenComplete((v, e) -> sender.sendMessage(PREFIX + M.t("centro_marcado_baus_ate_12_blocos"))); break;
            case "insano": data.change(s -> s.arenas.get(g.name).insane = !s.arenas.get(g.name).insane).whenComplete((v, e) -> sender.sendMessage(PREFIX + M.t("modo_insano", (g.arena().insane ? M.t("ligado") : M.t("desligado"))))); break;
            default: {
                String arenaWorld = g.arena().world;
                if (p == null && getServer().getLevelByName(arenaWorld) == null) { getServer().loadLevel(arenaWorld); }
                Level lv = p != null ? p.level : getServer().getLevelByName(arenaWorld);
                if (lv == null) { throw new IllegalArgumentException(M.t("mundo_inexistente_sw", arenaWorld)); }
                if (lv == getServer().getDefaultLevel()) { throw new IllegalArgumentException(M.t("use_um_mundo_proprio_para_arena")); }
                String world = lv.getFolderName();
                double[] back = p == null ? null : new double[] {p.x, p.y, p.z, p.yaw, p.pitch};
                g.editing = true;
                if (p != null) { chestMode.remove(p); }
                for (Player visitor : new ArrayList<>(lv.getPlayers().values())) { visitor.teleport(getServer().getDefaultLevel().getSafeSpawn()); }
                lv.save(true);
                if (!getServer().unloadLevel(lv, true)) { g.editing = false; throw new IllegalStateException(M.t("mapa_descarregar_falhou")); }
                maps.backup(world).whenComplete((v, e) -> {
                    if (!isEnabled()) { return; }
                    boolean loaded = getServer().loadLevel(world); g.editing = false;
                    if (!loaded) { g.phase = Phase.RESETANDO; }
                    Level again = getServer().getLevelByName(world);
                    if (p != null && loaded && again != null && p.isOnline() && !inGame.containsKey(p)) { p.teleport(new Location(back[0], back[1], back[2], back[3], back[4], again)); }
                    sender.sendMessage(e != null ? M.t("falha_ao_salvar_copia", root(e)) : M.t("copia_do_mapa_salva_ele_volta"));
                });
            }
        }
    }

    /** Admin no modo "tipo de bau": cada bau tocado recebe o tipo (true = meio, false = ilha, null = automatico pelo centro). */
    final Map<Player, Optional<Boolean>> chestMode = new IdentityHashMap<>();

    /** /swmap: edicao do mapa em que o admin esta (jaulas, espectador, baus, time, minimo, nome). */
    boolean mapCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("goldskywars.admin")) { sender.sendMessage(PREFIX + M.t("sw_entrar_solo_dupla_sw_sair")); return true; }
        Player p = sender instanceof Player ? (Player) sender : null;
        String sub = args.length == 0 ? "info" : args[0].toLowerCase(Locale.ROOT);
        try {
            // Console: /swmap arena <nome> escolhe a arena; depois os mesmos comandos, com coordenadas onde o jogador usaria a posicao.
            if (p == null && sub.equals("arena")) {
                String n = SwState.name(arg(args, 1));
                if (!games.containsKey(n)) { throw new IllegalArgumentException(M.t("arena_inexistente_ou_em_jogo")); }
                consoleArena = n;
                sender.sendMessage(PREFIX + M.t("console_arena_escolhida", n));
                return true;
            }
            Game g = p != null ? gameAt(p.level) : consoleGame();
            if (g == null) { throw new IllegalArgumentException(M.t("entre_no_mundo_de_uma_arena")); }
            SwState.Arena a = g.arena();
            switch (sub) {
                case "info": {
                    sender.sendMessage(PREFIX + M.t("swmap_info", g.name, a.world, a.display.isEmpty() ? "-" : a.display, a.author.isEmpty() ? "-" : a.author, a.teamSize, a.minimum, a.spawns.size(), a.spectator.length == 3 ? M.t("sim") : M.t("nao"), a.chests.size(), a.enabled ? M.t("ligado") : M.t("desligado"), maps.hasBackup(a.world) ? M.t("sim") : M.t("nao")));
                    sender.sendMessage(M.t("swmap_ajuda"));
                    if (p == null) { sender.sendMessage(M.t("swmap_ajuda_console")); }
                    return true;
                }
                case "jaula": case "spawn": edit(sender, p, g, "jaula", args); return true;
                case "desfazer": case "meio": case "salvar": edit(sender, p, g, sub, args); return true;
                case "espectador": {
                    editable(g);
                    double[] here = here(p, args, 1);
                    data.change(s -> s.arenas.get(g.name).spectator = new double[] {here[0], here[1], here[2]})
                            .whenComplete((v, e) -> sender.sendMessage(PREFIX + (e != null ? M.t("perfil_operacao_falhou", root(e)) : M.t("swmap_espectador"))));
                    return true;
                }
                case "time": case "minimo": {
                    editable(g);
                    int n;
                    try { n = Integer.parseInt(arg(args, 1)); } catch (NumberFormatException bad) { throw new IllegalArgumentException(M.t("swmap_numero")); }
                    boolean team = sub.equals("time");
                    if (team ? n < 1 || n > 4 : n < 2 || n > 96) { throw new IllegalArgumentException(M.t("swmap_numero")); }
                    data.change(s -> { SwState.Arena x = s.arenas.get(g.name); if (team) { x.teamSize = n; } else { x.minimum = n; } })
                            .whenComplete((v, e) -> sender.sendMessage(PREFIX + (e != null ? M.t("perfil_operacao_falhou", root(e)) : M.t(team ? "swmap_time" : "swmap_minimo", n))));
                    return true;
                }
                case "ativar": case "desativar": {
                    boolean on = sub.equals("ativar");
                    if (!on) { editable(g); }
                    data.change(s -> s.arenas.get(g.name).enabled = on)
                            .whenComplete((v, e) -> sender.sendMessage(PREFIX + (e != null ? M.t("perfil_operacao_falhou", root(e)) : M.t("swmap_estado", on ? M.t("ligado") : M.t("desligado")))));
                    return true;
                }
                case "nome": case "autor": {
                    StringBuilder text = new StringBuilder();
                    for (int i = 1; i < args.length; i++) { text.append(i > 1 ? " " : "").append(args[i]); }
                    String value = cn.nukkit.utils.TextFormat.clean(text.toString()).trim();
                    if (value.length() > 32) { throw new IllegalArgumentException(M.t("swmap_texto_longo")); }
                    boolean name = sub.equals("nome");
                    data.change(s -> { SwState.Arena x = s.arenas.get(g.name); if (name) { x.display = value; } else { x.author = value; } })
                            .whenComplete((v, e) -> sender.sendMessage(PREFIX + (e != null ? M.t("perfil_operacao_falhou", root(e)) : M.t("swmap_texto", value.isEmpty() ? "-" : value))));
                    return true;
                }
                case "bau": {
                    String type = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
                    if (p == null) {
                        // Console: /swmap bau meio|ilha|auto <x> <y> <z> marca um bau pela posicao.
                        if (!type.equals("meio") && !type.equals("ilha") && !type.equals("auto")) { throw new IllegalArgumentException(M.t("swmap_bau_uso")); }
                        double[] at = here(null, args, 2);
                        String key = (int) Math.floor(at[0]) + "," + (int) Math.floor(at[1]) + "," + (int) Math.floor(at[2]);
                        editable(g);
                        Boolean mode = type.equals("auto") ? null : type.equals("meio");
                        data.change(s -> { Map<String, Boolean> chests = s.arenas.get(g.name).chests; if (mode != null) { if (!chests.containsKey(key) && chests.size() >= 256) { throw new IllegalArgumentException(M.t("swmap_bau_limite")); } chests.put(key, mode); } else { chests.remove(key); } })
                                .whenComplete((v, x) -> sender.sendMessage(PREFIX + (x != null ? M.t("perfil_operacao_falhou", root(x)) : M.t("swmap_bau_marcado", key, mode == null ? M.t("swmap_bau_auto") : mode ? M.t("swmap_bau_meio") : M.t("swmap_bau_ilha")))));
                        return true;
                    }
                    switch (type) {
                        case "meio": chestMode.put(p, Optional.of(true)); break;
                        case "ilha": chestMode.put(p, Optional.of(false)); break;
                        case "auto": chestMode.put(p, Optional.empty()); break;
                        case "pronto": chestMode.remove(p); p.sendMessage(PREFIX + M.t("swmap_bau_fim")); return true;
                        default: throw new IllegalArgumentException(M.t("swmap_bau_uso"));
                    }
                    p.sendMessage(PREFIX + M.t("swmap_bau_modo", type));
                    return true;
                }
                default: sender.sendMessage(M.t("swmap_ajuda")); return true;
            }
        } catch (RuntimeException e) { sender.sendMessage(PREFIX + e.getMessage()); return true; }
    }

    private void editable(Game g) { if (!g.waiting() || !g.players.isEmpty() || g.editing) { throw new IllegalArgumentException(M.t("arena_tem_jogadores_espere_esvaziar")); } }

    /** Modo tipo de bau: tocar num bau grava o tipo dele no mapa (sem quebrar e sem abrir). */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onChestTool(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        Optional<Boolean> mode = chestMode.get(p);
        if (mode == null || e.getBlock() == null || e.getBlock().getId() != Block.CHEST) { return; }
        e.setCancelled();
        Game g = gameAt(p.level);
        if (g == null || !p.hasPermission("goldskywars.admin")) { chestMode.remove(p); return; }
        String key = e.getBlock().getFloorX() + "," + e.getBlock().getFloorY() + "," + e.getBlock().getFloorZ();
        try {
            editable(g);
            data.change(s -> { Map<String, Boolean> chests = s.arenas.get(g.name).chests; if (mode.isPresent()) { if (!chests.containsKey(key) && chests.size() >= 256) { throw new IllegalArgumentException(M.t("swmap_bau_limite")); } chests.put(key, mode.get()); } else { chests.remove(key); } })
                    .whenComplete((v, x) -> p.sendMessage(PREFIX + (x != null ? M.t("perfil_operacao_falhou", root(x)) : M.t("swmap_bau_marcado", key, !mode.isPresent() ? M.t("swmap_bau_auto") : mode.get() ? M.t("swmap_bau_meio") : M.t("swmap_bau_ilha")))));
        } catch (RuntimeException x) { p.sendMessage(PREFIX + x.getMessage()); }
    }

    static String arg(String[] a, int i) { if (a.length <= i) { throw new IllegalArgumentException(M.t("faltam_argumentos_use_sw")); } return a[i]; }
    static String root(Throwable e) { while (e.getCause() != null) { e = e.getCause(); } return e.getMessage(); }
}
