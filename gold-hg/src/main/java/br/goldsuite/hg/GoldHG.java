package br.goldsuite.hg;

import br.goldsuite.api.*;
import br.goldsuite.api.TravelService.Transition;
import br.goldsuite.hg.Game.Phase;
import cn.nukkit.Player;
import cn.nukkit.block.Block;
import cn.nukkit.blockentity.BlockEntity;
import cn.nukkit.blockentity.BlockEntityChest;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.inventory.BaseInventory;
import cn.nukkit.inventory.ShapelessRecipe;
import cn.nukkit.item.Item;
import cn.nukkit.level.Level;
import cn.nukkit.level.Location;
import cn.nukkit.level.Position;
import cn.nukkit.level.sound.AnvilUseSound;
import cn.nukkit.level.sound.ClickSound;
import cn.nukkit.level.sound.GhastSound;
import cn.nukkit.math.Vector3;
import cn.nukkit.nbt.tag.CompoundTag;
import cn.nukkit.nbt.tag.ListTag;
import cn.nukkit.network.protocol.SetSpawnPositionPacket;
import cn.nukkit.plugin.PluginBase;
import cn.nukkit.scheduler.PluginTask;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

public final class GoldHG extends PluginBase {
    static String PREFIX = M.t("mensagem");
    static final int SELECTOR = 54;
    private static final int PER_PAGE = 21;
    private static final Set<String> ALLOWED_COMMANDS = new HashSet<>(Arrays.asList("hg", "hgkit", "hgkits", "sopa", "tell", "msg", "w", "r", "login", "l", "registrar"));

    PersistentState<HgState> data;
    MapStore maps;
    final Map<String, Game> games = new HashMap<>();
    /** Indices O(1) consultados pelos eventos; nunca percorrem listas. */
    final Map<Player, Game> inGame = new IdentityHashMap<>();
    final Map<Player, Kit> kits = new IdentityHashMap<>();
    /** Eliminados que devem nascer no lobby. */
    final Set<Player> returning = Collections.newSetFromMap(new IdentityHashMap<>());
    /** Quem entrou durante a invencibilidade e ainda escolhe o kit: jogador -> prazo. */
    private final Map<Player, Long> lateKit = new IdentityHashMap<>();
    private Abilities abilities;

    @Override public void onEnable() {
        M.B.attach(this);
        Settings.prefix(this, PREFIX, v -> PREFIX = v);
        CoreService core = getServer().getServiceManager().load(CoreService.class);
        if (core == null) { throw new IllegalStateException(M.t("goldcore_precisa_estar_ativo_antes_do")); }
        maps = new MapStore(getServer(), getDataFolder().toPath());
        data = new PersistentState<>(core.getStore(), "goldhg", HgState.class, HgState::new, HgState::validate);
        abilities = new Abilities(this);
        getServer().getPluginManager().registerEvents(abilities, this);
        registerSoupRecipes();
        data.ready().whenComplete((unused, error) -> {
            if (!isEnabled()) { return; }
            if (error != null) { getLogger().error(M.t("falha_ao_carregar_hg"), error); getServer().getPluginManager().disablePlugin(this); return; }
            if (rules().startRev < 1) {
                data.change(s -> { s.rules.minPlayers = 5; s.rules.waitSeconds = 50; s.rules.graceSeconds = 120; s.rules.startRev = 1; });
            }
            for (String arena : data.read(s -> new ArrayList<>(s.arenas.keySet()))) {
                if (getServer().getLevelByName(arena) == null && getServer().isLevelGenerated(arena)) { getServer().loadLevel(arena); }
                games.put(arena, new Game(arena, rules().waitSeconds));
            }
            getLogger().info(games.size() + " arena(s) de HG pronta(s).");
        });
        getServer().getScheduler().scheduleRepeatingTask(new PluginTask<GoldHG>(this) { @Override public void onRun(int tick) { second(); } }, 20);
    }

    @Override public void onDisable() {
        for (Game game : games.values()) { for (Player player : game.online()) { strip(player); } }
        games.clear(); inGame.clear(); kits.clear(); returning.clear(); lateKit.clear();
        if (abilities != null) { abilities.clear(); }
        if (data != null) { data.close(); }
    }

    HgState.Rules rules() { return data.read(s -> s.rules); }
    Level lobby() { return getServer().getDefaultLevel(); }

    /** Recraft classico: tigela com cacto ou com cacau tambem vira sopa. */
    private void registerSoupRecipes() {
        getServer().addRecipe(new ShapelessRecipe(Item.get(282, 0, 1)).addIngredient(Item.get(281, 0, 1)).addIngredient(Item.get(81, 0, 1)));
        getServer().addRecipe(new ShapelessRecipe(Item.get(282, 0, 1)).addIngredient(Item.get(281, 0, 1)).addIngredient(Item.get(351, 3, 1)));
    }

    // ---------- ciclo de 1 segundo ----------

    private void second() {
        if (games.isEmpty() || !data.isLoaded()) { return; }
        HgState.Rules rules = rules();
        for (Game game : games.values()) {
            if (game.phase == Phase.RESETANDO) { continue; }
            if (game.players.isEmpty()) { if (game.phase != Phase.AGUARDANDO) { game.reset(rules.waitSeconds); } continue; }
            Level level = getServer().getLevelByName(game.arena);
            if (level == null) { continue; }
            List<Player> players = game.online();
            switch (game.phase) {
                case AGUARDANDO:
                    if (players.size() >= rules.minPlayers) {
                        game.phase = Phase.CONTAGEM; game.seconds = rules.waitSeconds;
                        broadcast(game, M.t("minimo_de_jogadores_atingido_partida_comeca", rules.waitSeconds));
                    }
                    for (Player p : players) { p.sendTip(M.t("aguardando_jogadores_bau_da_hotbar_kits", players.size(), rules.minPlayers)); }
                    break;
                case CONTAGEM: {
                    // Faltou gente: a contagem para e recomeca do inicio quando o minimo voltar.
                    if (players.size() < rules.minPlayers && !game.forced) { game.phase = Phase.AGUARDANDO; game.seconds = rules.waitSeconds; broadcast(game, M.t("jogadores_insuficientes_contagem_cancelada")); break; }
                    // Sala cheia: nao faz sentido esperar mais.
                    if (game.players.size() >= spawns(game).size() && game.seconds > 10) { game.seconds = 10; broadcast(game, M.t("sala_cheia_comecando_em_10s")); }
                    game.seconds--;
                    for (Player p : players) { p.sendTip(M.t("iniciando_em_kit", Game.clock(game.seconds), players.size(), spawns(game).size(), kit(p).title)); }
                    int s = game.seconds;
                    if (s == 30 || s == 15 || (s <= 5 && s > 0)) { broadcast(game, M.t("partida_comeca_em_segundo", s, (s == 1 ? "" : "s"))); }
                    if (s <= 10 && s > 0) { for (Player p : players) { level.addSound(new ClickSound(p), p); } }
                    if (s <= 0) { start(game, level, players, rules); }
                    break;
                }
                case INVENCIVEL:
                case LUTA:
                    running(game, level, players, rules);
                    break;
                default:
            }
        }
    }

    private void start(Game game, Level level, List<Player> players, HgState.Rules rules) {
        game.phase = Phase.INVENCIVEL;
        game.seconds = rules.gameSeconds;
        fillChests(level, rules.chestItems);
        for (Player p : players) { equip(p, level); p.sendMessage(PREFIX + M.t("partida_comecou_pvp_em_ainda_da", Game.clock(rules.graceSeconds))); }
    }

    /** Inventario limpo, bussola e itens do kit; usado no inicio e por quem entra durante a invencibilidade. */
    private void equip(Player p, Level level) {
        lateKit.remove(p);
        Kit kit = kit(p);
        if (kit == Kit.SURPRESA) { kit = Kit.random(); kits.put(p, kit); p.sendMessage(PREFIX + M.t("seu_kit_surpresa", kit.title)); }
        p.getInventory().clearAll();
        p.setGamemode(Player.SURVIVAL);
        p.setHealth(p.getMaxHealth());
        p.getFoodData().setLevel(20);
        p.removeAllEffects();
        p.getInventory().addItem(Item.get(Kit.COMPASS, 0, 1));
        for (Item item : kit.items()) { p.getInventory().addItem(item); }
        abilities.onStart(p, kit);
        level.addSound(new AnvilUseSound(p), p);
    }

    private void running(Game game, Level level, List<Player> players, HgState.Rules rules) {
        int elapsed = rules.gameSeconds - game.seconds;
        if (!lateKit.isEmpty()) {
            long now = System.currentTimeMillis();
            for (Player p : players) { Long until = lateKit.get(p); if (until != null && (now >= until || game.phase != Phase.INVENCIVEL)) { equip(p, level); p.sendMessage(PREFIX + M.t("kit_entregue", kit(p).title)); } }
        }
        if (game.phase == Phase.INVENCIVEL) {
            int left = rules.graceSeconds - elapsed;
            if (left <= 0) {
                game.phase = Phase.LUTA;
                for (Player p : players) { level.addSound(new GhastSound(p), p); p.sendMessage(PREFIX + M.t("pvp_foi_ativado")); }
            } else if (left == 120 || left == 60 || left == 30 || left == 15 || left <= 5) {
                broadcast(game, M.t("pvp_sera_ativado_em_segundos", left));
            }
        }
        game.seconds--;
        int s = game.seconds;
        if (rules.feastAt > 0 && !game.feastDone) {
            int until = s - rules.feastAt;
            if (until == 300 || until == 60 || until == 30 || until == 10) {
                Vector3 c = feastCenter(game, level);
                broadcast(game, M.t("feast_em_em", Game.clock(until), c.getFloorX(), c.getFloorY(), c.getFloorZ()));
            }
            if (until <= 0) { game.feastDone = true; buildFeast(level, feastCenter(game, level), rules.feastItems); broadcast(game, M.t("feast_apareceu_no_centro")); }
        }
        if (!game.refillDone && rules.refillAt > 0 && s <= rules.refillAt) { game.refillDone = true; fillChests(level, rules.chestItems); broadcast(game, M.t("os_baus_foram_reabastecidos")); }
        if (!game.finaleDone && s <= rules.finaleAt) { game.finaleDone = true; finale(game, level, players); }
        else if (s > 0 && s % 60 == 0) { broadcast(game, M.t("minuto_restantes", (s / 60))); }
        boolean compass = s % 2 == 0;
        for (Player p : players) {
            String status = M.t("vivos", players.size(), (game.phase == Phase.INVENCIVEL ? M.t("pvp_em", Game.clock(Math.max(0, rules.graceSeconds - elapsed - 1))) : M.t("fim_em", Game.clock(s))));
            if (compass && p.getInventory().getItemInHand().getId() == Kit.COMPASS) { status += compass(p, players); }
            p.sendTip(status);
            abilities.everySecond(p, kit(p));
        }
        if (players.size() <= 1) { end(game, players.isEmpty() ? null : players.get(0)); }
        else if (s <= 0) { broadcast(game, M.t("ninguem_venceu_desta_vez")); end(game, null); }
    }

    /** Aponta a bussola para o inimigo mais proximo (pacote so para o dono). */
    private String compass(Player p, List<Player> players) {
        Player nearest = null;
        double best = Double.MAX_VALUE;
        for (Player other : players) {
            if (other == p || other.level != p.level) { continue; }
            double d = other.distanceSquared(p);
            if (d < best) { best = d; nearest = other; }
        }
        if (nearest == null) { return ""; }
        SetSpawnPositionPacket packet = new SetSpawnPositionPacket();
        packet.x = nearest.getFloorX(); packet.y = nearest.getFloorY(); packet.z = nearest.getFloorZ();
        p.dataPacket(packet);
        return M.t("alvo", nearest.getName(), (int) Math.sqrt(best));
    }

    private void finale(Game game, Level level, List<Player> players) {
        Vector3 center = feastCenter(game, level);
        broadcast(game, M.t("batalha_final"));
        for (int i = 0; i < players.size(); i++) {
            double angle = 2 * Math.PI * i / players.size();
            double x = center.x + Math.cos(angle) * 6, z = center.z + Math.sin(angle) * 6;
            float yaw = (float) Math.toDegrees(Math.atan2(-(center.x - x), center.z - z));
            players.get(i).teleport(new Location(x, center.y, z, yaw, 0, level));
        }
    }

    Vector3 feastCenter(Game game, Level level) {
        double[] finale = data.read(s -> s.arenas.get(game.arena).finale);
        if (finale.length == 3) { return new Vector3(finale[0], finale[1], finale[2]); }
        return level.getSafeSpawn();
    }

    /** Plataforma de grama com 8 baus em volta do centro; o mapa e restaurado no fim. */
    private void buildFeast(Level level, Vector3 center, List<int[]> items) {
        int cx = center.getFloorX(), cy = center.getFloorY(), cz = center.getFloorZ();
        for (int dx = -6; dx <= 6; dx++) {
            for (int dz = -6; dz <= 6; dz++) {
                if (dx * dx + dz * dz > 36) { continue; }
                level.setBlock(new Vector3(cx + dx, cy - 1, cz + dz), Block.get(Block.GRASS));
                for (int dy = 0; dy < 3; dy++) { level.setBlock(new Vector3(cx + dx, cy + dy, cz + dz), Block.get(Block.AIR)); }
            }
        }
        level.setBlock(new Vector3(cx, cy, cz), Block.get(Block.ENCHANTING_TABLE));
        for (int i = 0; i < 8; i++) {
            double angle = Math.PI * i / 4;
            int x = cx + (int) Math.round(Math.cos(angle) * 3), z = cz + (int) Math.round(Math.sin(angle) * 3);
            Vector3 pos = new Vector3(x, cy, z);
            level.setBlock(pos, Block.get(Block.CHEST));
            CompoundTag nbt = new CompoundTag("").putList(new ListTag<>("Items")).putString("id", BlockEntity.CHEST).putInt("x", x).putInt("y", cy).putInt("z", z);
            BlockEntityChest chest = new BlockEntityChest(level.getChunk(x >> 4, z >> 4), nbt);
            fill(chest.getRealInventory(), items, 2);
        }
    }

    void fillChests(Level level, List<int[]> items) {
        for (BlockEntity tile : new ArrayList<>(level.getBlockEntities().values())) {
            if (tile instanceof BlockEntityChest) { fill(((BlockEntityChest) tile).getRealInventory(), items, 3); }
        }
    }

    /** Cada slot recebe um item sorteado com chance 1/oneIn. */
    private static void fill(BaseInventory inventory, List<int[]> items, int oneIn) {
        inventory.clearAll();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (random.nextInt(oneIn) != 0) { continue; }
            int[] item = items.get(random.nextInt(items.size()));
            inventory.setItem(slot, Item.get(item[0], item[1], item[2]));
        }
    }

    // ---------- entrar, sair, eliminar ----------

    void join(Player player, String wanted) {
        if (inGame.containsKey(player)) { throw new IllegalArgumentException(M.t("voce_ja_esta_numa_partida_use")); }
        for (Item item : player.getInventory().getContents().values()) { if (item.getId() != 0) { throw new IllegalArgumentException(M.t("esvazie_inventario_antes_de_entrar_no")); } }
        for (Item item : player.getInventory().getArmorContents()) { if (item.getId() != 0) { throw new IllegalArgumentException(M.t("tire_armadura_antes_de_entrar_no")); } }
        Game game = null;
        if (wanted != null) {
            game = games.get(wanted);
            if (game == null) { throw new IllegalArgumentException(M.t("arena_inexistente", wanted)); }
        } else {
            // Prefere a sala mais cheia que ainda aceita gente, para as partidas comecarem logo.
            for (Game g : games.values()) {
                if (g.open() && spawns(g).size() > g.players.size() && (game == null || g.players.size() > game.players.size())) { game = g; }
            }
            if (game == null) { throw new IllegalArgumentException(M.t("nenhuma_sala_aberta_agora")); }
        }
        if (!game.open()) { throw new IllegalArgumentException(M.t("pvp_ja_foi_liberado_nessa_sala")); }
        List<double[]> spawns = spawns(game);
        int slot = game.freeSpawn(spawns.size());
        if (slot < 0) { throw new IllegalArgumentException(M.t("sala_cheia")); }
        double[] spawn = spawns.get(slot);
        Game target = game;
        target.players.put(player, slot);
        inGame.put(player, target);
        kits.putIfAbsent(player, Kit.NENHUM);
        TravelService travel = getServer().getServiceManager().load(TravelService.class);
        travel.travel(player, Destination.position(target.arena, spawn[0], spawn[1], spawn[2], (float) spawn[3], 0), Transition.ESPIRAL).whenComplete((ok, error) -> {
            if (!isEnabled() || inGame.get(player) != target) { return; }
            if (error != null || !Boolean.TRUE.equals(ok)) { target.players.remove(player); inGame.remove(player); if (player.isOnline()) { player.sendMessage(PREFIX + M.t("nao_foi_possivel_entrar")); } return; }
            player.setGamemode(Player.SURVIVAL);
            player.setHealth(player.getMaxHealth());
            player.getInventory().setItem(0, selector());
            broadcast(target, M.t("entrou", player.getName(), target.players.size(), spawns.size()));
            if (target.phase == Phase.INVENCIVEL) {
                // Chegou com a partida rolando: 15 s para escolher o kit, depois recebe o escolhido.
                lateKit.put(player, System.currentTimeMillis() + 15_000);
                player.sendMessage(PREFIX + M.t("partida_ja_comecou_mas_pvp_ainda"));
                openKits(player, 0);
            }
        });
    }

    void leave(Player player) {
        Game game = inGame.get(player);
        if (game == null) { throw new IllegalArgumentException(M.t("voce_nao_esta_numa_partida")); }
        if (game.waiting()) { game.players.remove(player); inGame.remove(player); lateKit.remove(player); strip(player); toLobby(player); broadcast(game, M.t("saiu_da_sala", player.getName())); }
        else if (game.phase == Phase.INVENCIVEL) { lateKit.remove(player); eliminate(player, game, M.t("saiu_antes_do_pvp", player.getName())); strip(player); toLobby(player); }
        else { eliminate(player, game, M.t("desistiu", player.getName())); strip(player); toLobby(player); }
    }

    /** Remove da partida e avisa a sala; quem venceu e decidido em seguida. */
    void eliminate(Player player, Game game, String message) {
        game.players.remove(player);
        inGame.remove(player);
        lateKit.remove(player);
        abilities.forget(player);
        broadcast(game, M.t("restantes", message, game.players.size()));
        if (!game.waiting() && game.players.size() <= 1) { end(game, game.players.isEmpty() ? null : game.players.keySet().iterator().next()); }
    }

    private void end(Game game, Player winner) {
        if (game.phase == Phase.RESETANDO) { return; }
        game.phase = Phase.RESETANDO;
        if (winner != null) {
            getServer().broadcastMessage(PREFIX + M.t("venceu_hg_em", winner.getName(), game.arena));
            EconomyService economy = getServer().getServiceManager().load(EconomyService.class);
            long reward = rules().rewardCents;
            if (economy != null && reward > 0) { economy.credit(winner.getName(), EconomyService.Currency.MONEY, reward).whenComplete((v, e) -> { if (e == null && winner.isOnline()) { winner.sendMessage(PREFIX + M.t("premio_de_vitoria_depositado")); } }); }
            EffectService effects = getServer().getServiceManager().load(EffectService.class);
            if (effects != null) { effects.follow(winner, EffectService.Shape.CELEBRACAO, 60); }
        }
        for (Player p : new ArrayList<>(game.players.keySet())) { inGame.remove(p); abilities.forget(p); if (p.isOnline()) { strip(p); toLobby(p); } }
        game.players.clear();
        // Espera as viagens terminarem antes de descarregar o mundo.
        getServer().getScheduler().scheduleDelayedTask(new PluginTask<GoldHG>(this) { @Override public void onRun(int tick) { resetMap(game); } }, 60);
    }

    private void resetMap(Game game) {
        if (!isEnabled()) { return; }
        Level level = getServer().getLevelByName(game.arena);
        if (level != null) {
            for (Player p : new ArrayList<>(level.getPlayers().values())) { p.teleport(lobby().getSafeSpawn()); }
            if (level == lobby() || !getServer().unloadLevel(level, true)) { getLogger().warning("Nao foi possivel descarregar " + game.arena + "; mapa nao restaurado."); game.reset(rules().waitSeconds); return; }
        }
        if (!maps.hasBackup(game.arena)) { getServer().loadLevel(game.arena); game.reset(rules().waitSeconds); return; }
        maps.restore(game.arena).whenComplete((v, error) -> {
            if (error != null) { getLogger().error(M.t("falha_ao_restaurar", game.arena), error); }
            if (!isEnabled()) { return; }
            getServer().loadLevel(game.arena);
            game.reset(rules().waitSeconds);
        });
    }

    /** Limpa tudo o que a partida deu ao jogador. */
    void strip(Player player) {
        player.getInventory().clearAll();
        player.removeAllEffects();
        player.setOnFire(0);
        if (player.getAdventureSettings().canFly() && player.getGamemode() == Player.SURVIVAL) { player.setAllowFlight(false); }
        player.setHealth(player.getMaxHealth());
        player.getFoodData().setLevel(20);
    }

    void toLobby(Player player) {
        TravelService travel = getServer().getServiceManager().load(TravelService.class);
        travel.travel(player, Destination.world(lobby().getFolderName()), Transition.ESCURECER).whenComplete((ok, error) -> {
            if ((error != null || !Boolean.TRUE.equals(ok)) && player.isOnline()) { player.teleport(lobby().getSafeSpawn()); }
        });
    }

    void broadcast(Game game, String message) { for (Player p : game.players.keySet()) { if (p.isOnline()) { p.sendMessage(PREFIX + message); } } }

    Kit kit(Player player) { Kit kit = kits.get(player); return kit == null ? Kit.NENHUM : kit; }

    List<double[]> spawns(Game game) { return data.read(s -> s.arenas.get(game.arena).spawns); }

    boolean isArena(Level level) { return level != null && games.containsKey(level.getFolderName()); }

    boolean choosingLate(Player player) { return lateKit.containsKey(player); }

    static Item selector() { Item item = Item.get(SELECTOR, 0, 1); item.setCustomName(M.t("kits_toque")); return item; }

    void openKits(Player player, int page) {
        MenuService menus = getServer().getServiceManager().load(MenuService.class);
        Kit[] all = Kit.values();
        int pages = (all.length + PER_PAGE - 1) / PER_PAGE;
        int current = Math.max(0, Math.min(page, pages - 1));
        Map<Integer, Item> icons = new HashMap<>();
        Kit chosen = kit(player);
        for (int i = 0; i < PER_PAGE && current * PER_PAGE + i < all.length; i++) { Kit kit = all[current * PER_PAGE + i]; icons.put(i, kit.icon(kit == chosen)); }
        if (current > 0) { icons.put(21, named(Item.get(262, 0, 1), M.t("pagina_anterior", current, pages))); }
        if (current < pages - 1) { icons.put(26, named(Item.get(262, 0, 1), M.t("proxima_pagina", current + 2, pages))); }
        menus.open(player, M.t("kits", (current + 1), pages), icons, (p, slot) -> {
            if (slot == 21 && current > 0) { openKits(p, current - 1); return; }
            if (slot == 26 && current < pages - 1) { openKits(p, current + 1); return; }
            int index = current * PER_PAGE + slot;
            if (slot >= PER_PAGE || index >= all.length) { return; }
            choose(p, all[index]);
        });
    }

    void choose(Player player, Kit kit) {
        Game game = inGame.get(player);
        boolean late = lateKit.containsKey(player);
        if (game != null && !game.waiting() && !late) { player.sendMessage(PREFIX + M.t("partida_ja_comecou_kit_nao_pode")); return; }
        kits.put(player, kit);
        player.sendMessage(PREFIX + M.t("kit_escolhido", kit.title, kit.description));
        if (late) { Level level = getServer().getLevelByName(game.arena); if (level != null) { equip(player, level); } }
    }

    private static Item named(Item item, String name) { item.setCustomName(name); return item; }

    boolean commandAllowed(String message) {
        String command = message.startsWith("/") ? message.substring(1) : message;
        int space = command.indexOf(' ');
        return ALLOWED_COMMANDS.contains((space < 0 ? command : command.substring(0, space)).toLowerCase(Locale.ROOT));
    }

    // ---------- comandos ----------

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        try {
            switch (command.getName()) {
                case "hgkits": if (!(sender instanceof Player)) { return true; } openKits((Player) sender, 0); return true;
                case "hgkit":
                    if (!(sender instanceof Player)) { return true; }
                    if (args.length == 0) { openKits((Player) sender, 0); } else { choose((Player) sender, Kit.parse(String.join(" ", args))); }
                    return true;
                case "sopa": return soupCommand(sender, args);
                default: return hgCommand(sender, args);
            }
        } catch (RuntimeException error) { sender.sendMessage(PREFIX + "§c" + root(error)); return true; }
    }

    private boolean soupCommand(CommandSender sender, String[] args) {
        if (args.length >= 2 && args[0].equalsIgnoreCase("mundo") && sender.hasPermission("goldhg.admin")) {
            String world = HgState.arenaName(args[1]);
            result(sender, data.change(s -> { if (!s.soupWorlds.remove(world)) { s.soupWorlds.add(world); } }), M.t("sopa_instantanea_alternada_em", world));
            return true;
        }
        if (!(sender instanceof Player)) { return true; }
        String name = HgState.player(sender.getName());
        boolean[] now = new boolean[1];
        result(sender, data.change(s -> { now[0] = !s.soupOnSelect.remove(name); if (now[0]) { s.soupOnSelect.add(name); } }),
                null, () -> sender.sendMessage(PREFIX + (now[0] ? M.t("sopa_ao_selecionar_ligada_toque_no") : M.t("sopa_ao_selecionar_desligada_toque_na"))));
        return true;
    }

    private boolean hgCommand(CommandSender sender, String[] args) {
        String sub = args.length == 0 ? "ajuda" : args[0].toLowerCase(Locale.ROOT);
        Player player = sender instanceof Player ? (Player) sender : null;
        AuthService auth = getServer().getServiceManager().load(AuthService.class);
        if (player != null && auth != null && !auth.isAuthenticated(player)) { sender.sendMessage(M.t("autentique_se_primeiro")); return true; }
        switch (sub) {
            case "entrar": if (player != null) { join(player, args.length > 1 ? HgState.arenaName(args[1]) : null); } return true;
            case "sair": if (player != null) { leave(player); } return true;
            case "lista":
                if (games.isEmpty()) { sender.sendMessage(PREFIX + M.t("nenhuma_arena_criada")); }
                for (Game g : games.values()) { sender.sendMessage(PREFIX + g.arena + ": " + g.phase.name().toLowerCase(Locale.ROOT) + " " + g.players.size() + "/" + spawns(g).size()); }
                return true;
            default:
        }
        if (!sender.hasPermission("goldhg.admin")) { help(sender); return true; }
        switch (sub) {
            case "criar": {
                if (args.length < 2) { sender.sendMessage(M.t("uso_hg_criar_mundo")); return true; }
                String name = HgState.arenaName(args[1]);
                if (!getServer().isLevelGenerated(name)) { throw new IllegalArgumentException(M.t("mundo_inexistente_em_worlds", name)); }
                if (getServer().getLevelByName(name) == null) { getServer().loadLevel(name); }
                Level level = getServer().getLevelByName(name);
                if (level == null || level == lobby()) { throw new IllegalArgumentException(M.t("use_um_mundo_separado_do_lobby")); }
                level.save(true);
                sender.sendMessage(PREFIX + M.t("salvando_copia_do_mapa"));
                result(sender, maps.backup(name).thenCompose(v -> data.change(s -> s.arenas.putIfAbsent(name, new HgState.Arena()))), null, () -> {
                    games.putIfAbsent(name, new Game(name, rules().waitSeconds));
                    sender.sendMessage(PREFIX + M.t("arena_criada_va_ao_mapa_fique", name));
                    if (player != null) { player.teleport(level.getSafeSpawn()); }
                });
                return true;
            }
            case "spawn": case "desfazer": case "final": {
                if (player == null) { return true; }
                String name = player.level.getFolderName();
                if (!games.containsKey(name)) { throw new IllegalArgumentException(M.t("voce_precisa_estar_dentro_de_uma")); }
                double[] here = {player.x, player.y, player.z, player.yaw};
                if (sub.equals("spawn")) {
                    result(sender, data.change(s -> { List<double[]> list = s.require(name).spawns; if (list.size() >= HgState.MAX_SPAWNS) { throw new IllegalArgumentException(M.t("limite_de_24_spawns")); } list.add(here); }),
                            null, () -> sender.sendMessage(PREFIX + M.t("spawn_salvo", spawns(games.get(name)).size())));
                } else if (sub.equals("desfazer")) {
                    result(sender, data.change(s -> { List<double[]> list = s.require(name).spawns; if (list.isEmpty()) { throw new IllegalArgumentException(M.t("nenhum_spawn")); } list.remove(list.size() - 1); }), M.t("ultimo_spawn_removido"));
                } else {
                    result(sender, data.change(s -> s.require(name).finale = new double[] {here[0], here[1], here[2]}), M.t("centro_do_feast_da_batalha_final"));
                }
                return true;
            }
            case "salvar": {
                Game g = arenaArg(args);
                if (!g.players.isEmpty()) { throw new IllegalArgumentException(M.t("espere_sala_esvaziar")); }
                getServer().getLevelByName(g.arena).save(true);
                result(sender, maps.backup(g.arena), M.t("copia_do_mapa_atualizada"));
                return true;
            }
            case "iniciar": { Game g = arenaArg(args); if (g.waiting() && !g.players.isEmpty()) { g.forced = true; g.phase = Phase.CONTAGEM; g.seconds = Math.min(g.seconds, 10); } sender.sendMessage(PREFIX + M.t("comecando_em_10")); return true; }
            case "regras": {
                if (args.length < 3) { HgState.Rules r = rules(); sender.sendMessage(PREFIX + M.t("minimo_jogadores_contagem_pvp_apos_mude", r.minPlayers, r.waitSeconds, r.graceSeconds)); return true; }
                String what = args[1].toLowerCase(Locale.ROOT);
                int value = Integer.parseInt(args[2]);
                result(sender, data.change(s -> {
                    switch (what) {
                        case "minimo": s.rules.minPlayers = value; break;
                        case "espera": s.rules.waitSeconds = value; break;
                        case "pvp": s.rules.graceSeconds = value; break;
                        default: throw new IllegalArgumentException(M.t("use_minimo_espera_ou_pvp"));
                    }
                }), "Regra " + what + " = " + value + ".");
                return true;
            }
            case "batalhafinal": { Game g = arenaArg(args); if (!g.waiting() && g.phase != Phase.RESETANDO) { g.seconds = Math.min(g.seconds, rules().finaleAt + 10); } sender.sendMessage(PREFIX + M.t("batalha_final_em_10")); return true; }
            case "remover": {
                Game g = arenaArg(args);
                if (!g.players.isEmpty()) { throw new IllegalArgumentException(M.t("espere_sala_esvaziar")); }
                result(sender, data.change(s -> s.arenas.remove(g.arena)), null, () -> { games.remove(g.arena); sender.sendMessage(PREFIX + M.t("arena_removida_copia_do_mapa_continua")); });
                return true;
            }
            default: help(sender); return true;
        }
    }

    private Game arenaArg(String[] args) {
        if (args.length < 2) { if (games.size() == 1) { return games.values().iterator().next(); } throw new IllegalArgumentException(M.t("informe_arena")); }
        Game g = games.get(HgState.arenaName(args[1]));
        if (g == null) { throw new IllegalArgumentException(M.t("arena_inexistente_2")); }
        return g;
    }

    private void help(CommandSender sender) {
        sender.sendMessage(PREFIX + M.t("hg_entrar_arena_hg_sair_hg"));
        if (sender.hasPermission("goldhg.admin")) { sender.sendMessage(PREFIX + M.t("admin_hg_criar_mundo_spawn_desfazer")); }
    }

    private void result(CommandSender sender, CompletableFuture<?> operation, String success) { result(sender, operation, success, null); }
    private void result(CommandSender sender, CompletableFuture<?> operation, String success, Runnable then) {
        operation.whenComplete((v, error) -> {
            if (!isEnabled()) { return; }
            if (error != null) { sender.sendMessage(PREFIX + "§c" + root(error)); return; }
            if (success != null) { sender.sendMessage(PREFIX + success); }
            if (then != null) { then.run(); }
        });
    }

    static String root(Throwable error) { while (error.getCause() != null) { error = error.getCause(); } return error.getMessage(); }
}
