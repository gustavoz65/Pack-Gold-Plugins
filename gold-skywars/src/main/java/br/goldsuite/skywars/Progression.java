package br.goldsuite.skywars;

import br.goldsuite.api.*;
import cn.nukkit.Player;
import cn.nukkit.item.Item;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Progressao inspirada no MythicSkyWars, persistida em uma transacao por perfil. */
final class Progression {
    static final String[] PERKS = {"fallreduction","firedamagereduction","shotdamagereduction","arrowrecovery","blazingarrows","bulldozer","endermastery","juggernaut","knowledge","miningexpertise","speedboost","resistanceboost","nourishment","savior"};
    static final String[] CAGES = {"glass","iron","leaves","ice","gold"};
    static final int[] CAGE_BLOCKS = {20,101,18,79,41};
    final RecordStore<SwState.Stats> records;
    private final GoldSkyWars plugin;
    private final Set<Player> sessions = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<String> busy = new HashSet<>();
    private final Random random = new Random();
    long soulCost = 10, duplicateCoins = 100, killXp = 10, winXp = 100, killCoins = 20, winCoins = 100;
    Progression(GoldSkyWars plugin) {
        this.plugin = plugin;
        records = new RecordStore<>(plugin.core.getStore(), GoldSkyWars.STATS, SwState.Stats.class, SwState.Stats::new, SwState::validateStats, false);
        Settings.number(plugin, "almas.custo", 10, "Almas por rodada do poco", v -> soulCost = Math.max(1, Math.min(1000, v)));
        Settings.number(plugin, "almas.repetido", 100, "Moedas SkyWars por premio repetido", v -> duplicateCoins = Math.max(0, Math.min(100000, v)));
        Settings.number(plugin, "xp.abate", 10, "XP SkyWars por abate", v -> killXp = Math.max(0, Math.min(10000, v)));
        Settings.number(plugin, "xp.vitoria", 100, "XP SkyWars por vitoria", v -> winXp = Math.max(0, Math.min(10000, v)));
        Settings.number(plugin, "moedas.abate", 20, "Moedas SkyWars por abate", v -> killCoins = Math.max(0, Math.min(10000, v)));
        Settings.number(plugin, "moedas.vitoria", 100, "Moedas SkyWars por vitoria", v -> winCoins = Math.max(0, Math.min(10000, v)));
        Settings.placeholder(plugin, "sw_almas", p -> loaded(p) ? String.valueOf(get(p).souls) : "0");
        Settings.placeholder(plugin, "sw_nivel", p -> loaded(p) ? String.valueOf(level(get(p).xp)) : "1");
        Settings.placeholder(plugin, "sw_moedas", p -> loaded(p) ? String.valueOf(get(p).coins) : "0");
    }
    private final Set<Player> ready = Collections.newSetFromMap(new IdentityHashMap<>());
    void load(Player p) {
        if (!sessions.add(p)) { return; }
        records.retain(GoldSkyWars.key(p)).whenComplete((v, e) -> {
            if (!sessions.contains(p) || !plugin.isEnabled()) { return; }
            if (e == null) { ready.add(p); } else { p.sendMessage(M.t("perfil_falha")); plugin.getLogger().error("Falha ao carregar perfil SkyWars", e); }
        });
    }
    void release(Player p) { ready.remove(p); if (sessions.remove(p)) { records.release(GoldSkyWars.key(p)); } }
    boolean loaded(Player p) { return ready.contains(p); }
    SwState.Stats get(Player p) { if (!loaded(p)) { throw new IllegalStateException(M.t("carregando")); } return records.snapshot(GoldSkyWars.key(p)); }
    CompletableFuture<SwState.Stats> change(Player p, Consumer<SwState.Stats> op) {
        if (!loaded(p)) { return PersistentState.failed(new IllegalStateException(M.t("carregando"))); }
        return records.change(GoldSkyWars.key(p), op);
    }
    void event(Player p, Consumer<SwState.Stats> op) {
        change(p, op).whenComplete((s, e) -> {
            if (e != null) { plugin.getLogger().error("Falha ao persistir progresso SkyWars", e); if (p.isOnline()) { p.sendMessage(M.t("perfil_falha")); } }
        });
    }
    static int level(long xp) {
        int level = 1;
        while (level < 10000) { long cost = levelCost(level); if (xp < cost) { return level; } xp -= cost; level++; }
        return level;
    }
    static long levelCost(int level) {
        if (level <= 3) { return level * 1000L; }
        if (level == 4) { return 3500; }
        if (level <= 10) { return 5000; }
        return Math.min(15000, 5000 + ((level - 1) / 10) * 1000L);
    }
    int perk(Player p, String id) { SwState.Stats s = get(p); return s.disabledPerks.contains(id) ? 0 : s.perks.getOrDefault(id, 0); }
    int cage(Player p) { int n = Arrays.asList(CAGES).indexOf(get(p).cage); return n < 0 ? 20 : CAGE_BLOCKS[n]; }
    private void transaction(Player p, Consumer<SwState.Stats> mutation, Consumer<SwState.Stats> done) {
        if (!loaded(p) || !busy.add(GoldSkyWars.key(p))) { p.sendMessage(M.t("perfil_ocupado")); return; }
        change(p, mutation).whenComplete((s, e) -> {
            busy.remove(GoldSkyWars.key(p));
            if (!plugin.isEnabled() || !p.isOnline()) { return; }
            if (e != null) { p.sendMessage(M.t("perfil_operacao_falhou", GoldSkyWars.root(e))); }
            else { done.accept(s); }
        });
    }
    void buyKit(Player p, Kits.Kit kit) {
        transaction(p, s -> {
            GoldSkyWars.Game g = plugin.inGame.get(p);
            if (g != null && !g.waiting()) { throw new IllegalArgumentException(M.t("perfil_antes_partida")); }
            long price = Math.max(0, plugin.price(kit) / 100);
            if (!s.kits.contains(kit.id) && price > 0) { spend(s, price); s.kits.add(kit.id); }
            s.kit = kit.id;
        }, s -> p.sendMessage(M.t("kit_selecionado", kit.name)));
    }
    static void spend(SwState.Stats s, long amount) {
        if (amount < 0 || s.coins < amount) { throw new IllegalArgumentException(M.t("perfil_sem_moedas")); } s.coins -= amount;
    }
    void menu(Player p) {
        SwState.Stats s = get(p);
        Map<Integer, Item> items = new HashMap<>();
        items.put(10, icon(368, M.t("almas_menu", s.souls, soulCost)));
        items.put(12, icon(278, M.t("vantagens_menu")));
        items.put(14, icon(20, M.t("jaulas_menu")));
        items.put(16, icon(399, M.t("prestigio_menu", level(s.xp), s.xp, s.coins)));
        plugin.menus().open(p, M.t("sky_opcoes"), items, (who, slot) -> {
            try { if (slot == 10) { well(who); } else if (slot == 12) { perks(who); } else if (slot == 14) { cages(who); } else if (slot == 16) { prestige(who); } }
            catch (RuntimeException e) { who.sendMessage(e.getMessage()); }
        });
    }
    static Item icon(int id, String name) { Item item = Item.get(id, 0, 1); item.setCustomName(name); return item; }
    void well(Player p) {
        if (plugin.inGame.containsKey(p)) { p.sendMessage(M.t("almas_no_lobby")); return; }
        Map<Integer, Item> items = new HashMap<>();
        items.put(13, icon(368, M.t("almas_confirmar", soulCost, get(p).souls)));
        plugin.menus().open(p, M.t("almas_titulo"), items, (who, slot) -> { if (slot == 13) { spin(who); } });
    }
    void spin(Player p) {
        if (plugin.inGame.containsKey(p)) { p.sendMessage(M.t("almas_no_lobby")); return; }
        final long cost = soulCost, duplicate = duplicateCoins;
        final String[] reward = {""};
        transaction(p, s -> { reward[0] = roll(s, random, (int)cost, duplicate); }, s -> {
            p.sendMessage(M.t("almas_premio", reward[0], s.souls));
            if (!DeviceService.low(p)) {
                EffectService fx = plugin.getServer().getServiceManager().load(EffectService.class);
                if (fx != null) { fx.follow(p, EffectService.Shape.CELEBRACAO, 20); }
            }
        });
    }
    /** Custo e premio pertencem ao mesmo registro: fechar menu/desconectar nao perde nem duplica premio. */
    static String roll(SwState.Stats s, Random random, int cost, long duplicate) {
        if (s.souls < cost) { throw new IllegalArgumentException(M.t("almas_insuficientes")); }
        s.souls -= cost; s.spins++;
        int pick = random.nextInt(100);
        if (pick < 50) { long coins = 50 + random.nextInt(101); s.coins += coins; return M.t("almas_moedas", coins); }
        if (pick < 65) { s.xp += 100; return M.t("almas_xp"); }
        boolean fresh; String prize;
        if (pick < 80) {
            List<String> kits = new ArrayList<>(Kits.ALL.keySet()); kits.remove(Kits.DEFAULT);
            String id = kits.get(random.nextInt(kits.size())); fresh = s.kits.add(id); prize = Kits.ALL.get(id).name;
        } else if (pick < 95) {
            String id = PERKS[random.nextInt(PERKS.length)]; fresh = !s.perks.containsKey(id);
            if (fresh) { s.perks.put(id, 1); } prize = id;
        } else {
            String id = CAGES[1 + random.nextInt(CAGES.length - 1)]; fresh = s.cages.add(id); prize = id;
        }
        if (!fresh) { s.coins += duplicate; return M.t("almas_repetido", prize, duplicate); }
        return M.t(pick >= 95 ? "almas_lendario" : "almas_raro", prize);
    }
    void perks(Player p) {
        SwState.Stats s = get(p);
        Map<Integer, Item> items = new HashMap<>();
        for (int i = 0; i < PERKS.length; i++) { String id = PERKS[i]; int level = s.perks.getOrDefault(id, 0); items.put(i, icon(278, M.t("vantagem_item", id, level, (level + 1) * 500))); }
        plugin.menus().open(p, M.t("vantagens_menu"), items, (who, slot) -> {
            if (slot >= 0 && slot < PERKS.length) {
                String id = PERKS[slot];
                transaction(who, next -> { int n = next.perks.getOrDefault(id, 0); if (n >= 4) { throw new IllegalArgumentException(M.t("vantagem_maximo")); } spend(next, (n + 1) * 500L); next.perks.put(id, n + 1); }, next -> perks(who));
            }
        });
    }
    void cages(Player p) {
        SwState.Stats s = get(p); Map<Integer, Item> items = new HashMap<>();
        for (int i = 0; i < CAGES.length; i++) { items.put(10 + i, icon(CAGE_BLOCKS[i], M.t("jaula_item", CAGES[i], i == 0 || s.cages.contains(CAGES[i]) ? M.t("jaula_possui") : M.t("jaula_bloqueada")))); }
        plugin.menus().open(p, M.t("jaulas_menu"), items, (who, slot) -> {
            int n = slot - 10; if (n < 0 || n >= CAGES.length) { return; }
            transaction(who, next -> {
                if (n != 0 && !next.cages.contains(CAGES[n])) { throw new IllegalArgumentException(M.t("jaula_bloqueada")); }
                next.cage = CAGES[n];
            }, next -> who.sendMessage(M.t("jaula_escolhida", next.cage)));
        });
    }
    void prestige(Player p) {
        SwState.Stats s = get(p); int[] levels = {1,10,25,50,75,100,150};
        Map<Integer, Item> items = new HashMap<>();
        for (int i = 0; i < levels.length; i++) { items.put(i + 10, icon(399, M.t("prestigio_item", i, levels[i]))); }
        plugin.menus().open(p, M.t("prestigio_titulo"), items, (who, slot) -> {
            int index = slot - 10; if (index < 0 || index >= levels.length) { return; }
            transaction(who, next -> { if (level(next.xp) < levels[index]) { throw new IllegalArgumentException(M.t("prestigio_nivel")); } next.prestige = index; }, next -> who.sendMessage(M.t("prestigio_escolhido", index)));
        });
    }
    void close() { for (Player p : new ArrayList<>(sessions)) { release(p); } records.close(); busy.clear(); }
}

