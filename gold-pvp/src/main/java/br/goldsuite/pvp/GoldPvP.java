package br.goldsuite.pvp;

import br.goldsuite.api.*;
import cn.nukkit.Player;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.entity.Entity;
import cn.nukkit.entity.mob.EntityCreeper;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.entity.EntityDamageByEntityEvent;
import cn.nukkit.event.entity.EntityDamageEvent;
import cn.nukkit.event.entity.EntityExplodeEvent;
import cn.nukkit.event.entity.EntityLevelChangeEvent;
import cn.nukkit.event.player.*;
import cn.nukkit.item.Item;
import cn.nukkit.level.Level;
import cn.nukkit.level.Location;
import cn.nukkit.plugin.PluginBase;
import cn.nukkit.scheduler.PluginTask;
import com.google.gson.Gson;
import java.util.*;

/** Combat log, perfis detalhados, rankings (publicados para NPCs), ranked 1v1 com ELO e creepers sem destruicao. */
public final class GoldPvP extends PluginBase implements Listener {
    static final String PREFIX = "§4[§cPvP§4]§f ";
    static final String PROFILES = "goldpvp-perfil", STASH = "goldpvp-guardado";
    static final int MENU_ITEM = 340, MENU_SLOT = 8;
    static final String MENU_NAME = "§r§6Perfil e Ranked §7(toque)";
    private static final long TAG_MS = 15_000, COUNTDOWN_MS = 3_000, MATCH_MS = 180_000;
    private static final Set<String> ESCAPES = new HashSet<>(Arrays.asList("spawn", "lobby", "home", "casa", "warp", "warps", "tpa", "tpahere", "tpaccept", "tpaceitar", "back", "voltar", "ec", "bau", "enderchest", "hg", "fly", "voar"));
    private static final Gson GSON = new Gson();

    private CoreService core;
    private PersistentState<Boards> boards;
    private final Map<Player, Profile> profiles = new IdentityHashMap<>();
    private final Set<Player> dirty = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<Player, Long> taggedUntil = new IdentityHashMap<>();
    /** Fila do ranked: jogador -> instante de entrada. */
    private final LinkedHashMap<Player, Long> queue = new LinkedHashMap<>();
    private final Map<Player, Match> matches = new IdentityHashMap<>();
    private final Set<String> busyArenas = new HashSet<>();
    private final Fraud fraud = new Fraud();
    private int pruneTick;
    /** Perdedores que devolvem o inventario guardado ao renascer. */
    private final Set<Player> restoreOnRespawn = Collections.newSetFromMap(new IdentityHashMap<>());

    static final class Match {
        final Player a, b; final String arena; final long startAt = System.currentTimeMillis() + COUNTDOWN_MS; boolean ready, over;
        Match(Player a, Player b, String arena) { this.a = a; this.b = b; this.arena = arena; }
        Player other(Player p) { return p == a ? b : a; }
        /** Dano causado por a e por b no duelo (meios coracoes), para detectar quem entrega a partida. */
        final long[] dealt = new long[2];
        long dealt(Player p) { return dealt[p == a ? 0 : 1]; }
        boolean fighting() { return ready && !over && System.currentTimeMillis() >= startAt; }
    }

    @Override public void onEnable() {
        core = getServer().getServiceManager().load(CoreService.class);
        if (core == null) { throw new IllegalStateException("GoldCore precisa estar ativo antes do GoldPvP."); }
        boards = new PersistentState<>(core.getStore(), "goldpvp-ranking", Boards.class, Boards::new, Boards::validate);
        getServer().getPluginManager().registerEvents(this, this);
        RankingService ranking = getServer().getServiceManager().load(RankingService.class);
        if (ranking != null) {
            ranking.register("pvp", limit -> top(b -> b.kills, limit, v -> v + " abates"));
            ranking.register("ranked", this::topRanked);
        }
        for (Player p : getServer().getOnlinePlayers().values()) { load(p); }
        getServer().getScheduler().scheduleRepeatingTask(new PluginTask<GoldPvP>(this) { @Override public void onRun(int tick) { second(); } }, 20);
        getServer().getScheduler().scheduleRepeatingTask(new PluginTask<GoldPvP>(this) { @Override public void onRun(int tick) { flush(); } }, 20 * 30);
    }

    @Override public void onDisable() {
        for (Match m : new HashSet<>(matches.values())) { end(m, null); }
        flush();
        RankingService ranking = getServer().getServiceManager().load(RankingService.class);
        if (ranking != null) { ranking.unregister("pvp"); ranking.unregister("ranked"); }
        taggedUntil.clear(); queue.clear(); profiles.clear(); dirty.clear();
        if (boards != null) { boards.close(); }
    }

    private List<RankingService.Entry> top(java.util.function.Function<Boards, Map<String, Integer>> board, int limit, java.util.function.IntFunction<String> label) {
        if (!boards.isLoaded()) { return Collections.emptyList(); }
        List<RankingService.Entry> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : boards.read(b -> Boards.sorted(board.apply(b)))) {
            if (out.size() >= limit) { break; }
            out.add(new RankingService.Entry(e.getKey(), e.getValue(), label.apply(e.getValue())));
        }
        return out;
    }

    /** Top por pontos de rank; os 10 primeiros Imortais aparecem como Divino. */
    private List<RankingService.Entry> topRanked(int limit) {
        if (!boards.isLoaded()) { return Collections.emptyList(); }
        List<RankingService.Entry> out = new ArrayList<>();
        List<Map.Entry<String, Integer>> sorted = boards.read(b -> Boards.sorted(b.ladder));
        for (int i = 0; i < sorted.size() && out.size() < limit; i++) {
            int v = sorted.get(i).getValue();
            boolean divine = v >= Ranks.IMMORTAL && i < Ranks.DIVINE_SLOTS;
            out.add(new RankingService.Entry(sorted.get(i).getKey(), v, "§" + Ranks.COLORS[Ranks.tier(v, divine)] + Ranks.name(v, divine) + " §7" + Ranks.points(v) + " PR"));
        }
        return out;
    }

    /** Divino: Imortal entre os 10 com mais pontos do servidor. */
    private boolean divine(String name, int ladder) {
        if (ladder < Ranks.IMMORTAL || !boards.isLoaded()) { return false; }
        int pos = boards.read(b -> Boards.position(b.ladder, name));
        return pos > 0 && pos <= Ranks.DIVINE_SLOTS;
    }

    private String rankText(String name, Profile pr) { return Ranks.display(pr.ladder, divine(name, pr.ladder), pr.games()); }

    // ---------- perfis ----------

    private void load(Player p) {
        String name = key(p);
        core.getStore().get(PROFILES, name).whenComplete((value, error) -> {
            if (!isEnabled() || !p.isOnline()) { return; }
            Profile profile = new Profile();
            if (error == null && value.isPresent()) {
                try { profile = GSON.fromJson(value.get(), Profile.class); profile.validate(); }
                catch (RuntimeException invalid) { getLogger().warning("Perfil invalido de " + name + "; recomecando."); profile = new Profile(); }
            }
            profiles.put(p, profile);
            giveMenuItem(p);
            rank(p);
        });
        // Guardado de um duelo interrompido (queda do servidor ou saida no meio): devolve agora.
        core.getStore().get(STASH, name).whenComplete((value, error) -> {
            if (error == null && value.isPresent() && p.isOnline() && !matches.containsKey(p)) { restore(p, value.get(), true); }
        });
    }

    private Profile profile(Player p) { return profiles.get(p); }

    private void touch(Player p) { if (profiles.containsKey(p)) { dirty.add(p); } }

    private void flush() {
        for (Player p : new ArrayList<>(dirty)) { save(p); }
        dirty.clear();
    }

    private void save(Player p) {
        Profile profile = profiles.get(p);
        if (profile != null) { core.getStore().put(PROFILES, key(p), GSON.toJson(profile)); }
    }

    private void rank(Player p) {
        Profile pr = profile(p);
        if (pr == null) { return; }
        String name = key(p);
        int kills = pr.kills, ladder = pr.ladder;
        boards.change(b -> { if (kills > 0) { Boards.offer(b.kills, name, kills); } if (ladder >= 0) { Boards.offer(b.ladder, name, ladder); } })
                .thenRun(() -> { if (p.isOnline() && divine(name, ladder)) { reward(p, "pingente_divino", "trilha_divina", "cor_divina"); } });
    }

    // ---------- combate aberto ----------

    private boolean tagged(Player p) { Long until = taggedUntil.get(p); return until != null && until > System.currentTimeMillis(); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void guardMatch(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player)) { return; }
        Player victim = (Player) event.getEntity();
        Player attacker = event instanceof EntityDamageByEntityEvent && ((EntityDamageByEntityEvent) event).getDamager() instanceof Player ? (Player) ((EntityDamageByEntityEvent) event).getDamager() : null;
        Match mv = matches.get(victim), ma = attacker == null ? null : matches.get(attacker);
        if (mv == null && ma == null) { return; }
        // Durante o duelo so o oponente acerta, e so depois da contagem.
        if (mv == null || (attacker != null && ma != mv) || !mv.fighting()) { event.setCancelled(); }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void damage(EntityDamageEvent event) {
        if (!(event instanceof EntityDamageByEntityEvent) || !(event.getEntity() instanceof Player)) { return; }
        Entity damager = ((EntityDamageByEntityEvent) event).getDamager();
        if (!(damager instanceof Player) || damager == event.getEntity()) { return; }
        Player victim = (Player) event.getEntity(), attacker = (Player) damager;
        long half = Math.round(event.getFinalDamage());
        Profile pv = profile(victim), pa = profile(attacker);
        if (pv != null) { pv.damageTaken += half; touch(victim); }
        if (pa != null) { pa.damageDealt += half; touch(attacker); }
        Match duel = matches.get(victim);
        if (duel != null) { if (duel == matches.get(attacker)) { duel.dealt[duel.a == attacker ? 0 : 1] += half; } return; }
        long until = System.currentTimeMillis() + TAG_MS;
        for (Player p : new Player[] {victim, attacker}) {
            if (!tagged(p)) { p.sendPopup("§cEm combate! Nao saia por 15 s."); }
            taggedUntil.put(p, until);
            queue.remove(p);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void command(PlayerCommandPreprocessEvent event) {
        Player p = event.getPlayer();
        String m = event.getMessage();
        if (!m.startsWith("/") || p.hasPermission("goldpvp.admin")) { return; }
        int space = m.indexOf(' ');
        String name = (space < 0 ? m.substring(1) : m.substring(1, space)).toLowerCase(Locale.ROOT);
        if (name.contains(":")) { name = name.substring(name.indexOf(':') + 1); }
        if (matches.containsKey(p) && !name.equals("ranked") && !name.equals("tell") && !name.equals("r")) {
            event.setCancelled(); p.sendMessage(PREFIX + "§cDurante o duelo so /ranked sair (desistir)."); return;
        }
        if (tagged(p) && ESCAPES.contains(name)) {
            event.setCancelled();
            p.sendMessage(PREFIX + "§cVoce esta em combate por mais " + ((taggedUntil.get(p) - System.currentTimeMillis()) / 1000 + 1) + " s.");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void death(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        taggedUntil.remove(victim);
        Match match = matches.get(victim);
        if (match != null) {
            // Itens do kit nao caem no chao; o inventario original volta ao renascer.
            event.setDrops(new Item[0]);
            event.setDeathMessage("");
            restoreOnRespawn.add(victim);
            end(match, match.other(victim));
            return;
        }
        event.setDrops(Arrays.stream(event.getDrops()).filter(i -> !isMenuItem(i)).toArray(Item[]::new));
        EntityDamageEvent cause = victim.getLastDamageCause();
        if (!(cause instanceof EntityDamageByEntityEvent) || !(((EntityDamageByEntityEvent) cause).getDamager() instanceof Player)) { return; }
        Player killer = (Player) ((EntityDamageByEntityEvent) cause).getDamager();
        if (killer == victim) { return; }
        // Abate entre contas do mesmo IP ou a mesma dupla repetindo nao conta; repetir vira advertencia.
        boolean sameIp = Fraud.sameIp(ip(killer), ip(victim));
        boolean counts = fraud.killCounts(key(killer), key(victim), System.currentTimeMillis());
        if (sameIp || !counts) {
            killer.sendMessage(PREFIX + "§cEste abate nao conta" + (sameIp ? " (mesmo IP da vitima)." : " (abates repetidos na mesma pessoa)."));
            if (!counts) { flag(killer, "abates repetidos em " + victim.getName()); flag(victim, "morreu varias vezes para " + killer.getName()); }
            return;
        }
        Profile pk = profile(killer), pv = profile(victim);
        if (pk != null) { pk.kill(); touch(killer); rank(killer); if (pk.streak >= 5 && pk.streak % 5 == 0) { getServer().broadcastMessage(PREFIX + "§6" + killer.getName() + " esta com " + pk.streak + " abates seguidos!"); } }
        if (pv != null) { pv.death(); touch(victim); }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void respawn(PlayerRespawnEvent event) {
        Player p = event.getPlayer();
        if (restoreOnRespawn.remove(p)) {
            core.getStore().get(STASH, key(p)).whenComplete((value, error) -> {
                if (error == null && value.isPresent() && p.isOnline()) {
                    getServer().getScheduler().scheduleDelayedTask(new PluginTask<GoldPvP>(this) { @Override public void onRun(int tick) { restore(p, value.get(), true); } }, 2);
                }
            });
        } else {
            getServer().getScheduler().scheduleDelayedTask(new PluginTask<GoldPvP>(this) { @Override public void onRun(int tick) { giveMenuItem(p); } }, 2);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR) public void join(PlayerJoinEvent event) { load(event.getPlayer()); }

    /** Sair em combate larga tudo no chao; sair no duelo e derrota (o inventario guardado volta ao entrar). */
    @EventHandler(priority = EventPriority.LOWEST)
    public void quit(PlayerQuitEvent event) {
        Player p = event.getPlayer();
        queue.remove(p);
        restoreOnRespawn.remove(p);
        lastHeld.remove(p);
        Match match = matches.get(p);
        // Sair durante a contagem cancela sem ELO: impede farmar rating com uma segunda conta.
        if (match != null) {
            boolean fighting = match.fighting();
            end(match, fighting ? match.other(p) : null);
            if (!fighting && match.other(p).isOnline()) { match.other(p).sendMessage(PREFIX + "Oponente saiu antes do inicio. Duelo cancelado."); }
        }
        boolean wasTagged = tagged(p);
        taggedUntil.remove(p);
        if (match == null && wasTagged && p.isAlive() && !p.hasPermission("goldpvp.admin")) {
            for (Item item : new ArrayList<>(p.getInventory().getContents().values())) { if (item.getId() != 0 && !isMenuItem(item)) { p.level.dropItem(p, item); } }
            for (Item armor : p.getInventory().getArmorContents()) { if (armor.getId() != 0) { p.level.dropItem(p, armor); } }
            p.getInventory().clearAll();
            Profile pr = profile(p);
            if (pr != null) { pr.death(); dirty.add(p); }
            getServer().broadcastMessage(PREFIX + "§e" + p.getName() + "§f saiu em combate e perdeu os itens!");
        }
        save(p);
        dirty.remove(p);
        profiles.remove(p);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void explode(EntityExplodeEvent event) { if (event.getEntity() instanceof EntityCreeper) { event.setBlockList(new ArrayList<>()); } }

    // ---------- item do menu na hotbar ----------

    static boolean isMenuItem(Item item) { return item.getId() == MENU_ITEM && item.hasCustomName() && item.getCustomName().equals(MENU_NAME); }

    private boolean inLobby(Player p) {
        if (!boards.isLoaded() || p.level == null) { return false; }
        Set<String> worlds = boards.read(b -> b.lobbyWorlds);
        return worlds.isEmpty() ? p.level == getServer().getDefaultLevel() : worlds.contains(p.level.getFolderName());
    }

    private void giveMenuItem(Player p) {
        if (!p.isOnline() || matches.containsKey(p) || !inLobby(p)) { return; }
        for (Item it : p.getInventory().getContents().values()) { if (isMenuItem(it)) { return; } }
        if (p.getInventory().getItem(MENU_SLOT).getId() != 0) { return; }
        Item book = Item.get(MENU_ITEM, 0, 1);
        book.setCustomName(MENU_NAME);
        p.getInventory().setItem(MENU_SLOT, book);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void changeWorld(EntityLevelChangeEvent event) {
        if (!(event.getEntity() instanceof Player)) { return; }
        Player p = (Player) event.getEntity();
        getServer().getScheduler().scheduleDelayedTask(new PluginTask<GoldPvP>(this) { @Override public void onRun(int tick) { giveMenuItem(p); } }, 5);
    }

    /** Ultimo slot da hotbar usado antes do livro, para devolver a selecao. */
    private final Map<Player, Integer> lastHeld = new IdentityHashMap<>();

    @EventHandler(priority = EventPriority.LOW)
    public void interact(PlayerInteractEvent event) {
        if (event.getAction() == PlayerInteractEvent.PHYSICAL || !isMenuItem(event.getPlayer().getInventory().getItemInHand())) { return; }
        event.setCancelled();
        menu(event.getPlayer(), event.getPlayer());
    }

    /**
     * No 0.15 tocar no ar com um livro nao chega ao servidor: o livro so ficava selecionado.
     * Agora selecionar o livro ja abre o perfil, e a hotbar volta para o slot anterior; tocar de novo reabre.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void held(PlayerItemHeldEvent event) {
        Player p = event.getPlayer();
        if (!isMenuItem(event.getItem())) { lastHeld.put(p, p.getInventory().getHeldItemIndex()); return; }
        Integer before = lastHeld.get(p);
        int back = before == null || before == p.getInventory().getHeldItemIndex() ? (p.getInventory().getHeldItemIndex() == 0 ? 1 : 0) : before;
        menu(p, p);
        getServer().getScheduler().scheduleDelayedTask(new PluginTask<GoldPvP>(this) {
            @Override public void onRun(int tick) {
                if (!p.isOnline() || !isMenuItem(p.getInventory().getItemInHand())) { return; }
                p.getInventory().setHeldItemIndex(back);
                p.getInventory().sendHeldItem(p);
            }
        }, 2);
    }

    /** Elo antes do nome no chat para quem ativou no perfil. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void chat(PlayerChatEvent event) {
        Profile pr = profile(event.getPlayer());
        if (pr == null || !pr.showElo || pr.ladder < 0) { return; }
        String tag = Ranks.chatTag(pr.ladder, divine(key(event.getPlayer()), pr.ladder));
        event.setFormat(event.getFormat().replace("{%0}", tag + "§r {%0}"));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void drop(PlayerDropItemEvent event) { if (isMenuItem(event.getItem())) { event.setCancelled(); } }

    /** Perfil detalhado em bau: estatisticas, elo, posicao, botoes do ranked, lista de elos e cosmeticos. */
    private void menu(Player viewer, Player target) {
        Profile pr = profile(target);
        if (pr == null) { viewer.sendMessage(PREFIX + "Perfil ainda carregando."); return; }
        String name = key(target);
        int killPos = boards.read(b -> Boards.position(b.kills, name)), rankPos = boards.read(b -> Boards.position(b.ladder, name));
        boolean divine = divine(name, pr.ladder);
        String rank = Ranks.display(pr.ladder, divine, pr.games());
        Map<Integer, Item> icons = new HashMap<>();
        icons.put(4, icon(397, 3, "§e" + target.getName() + "\n" + rank + "\n§7Jogando desde " + new java.text.SimpleDateFormat("dd/MM/yyyy").format(new Date(pr.firstSeen))));
        icons.put(10, icon(276, 0, "§aAbates: §f" + pr.kills + "\n§7Posicao: " + (killPos == 0 ? "-" : "#" + killPos)));
        icons.put(11, icon(352, 0, "§cMortes: §f" + pr.deaths));
        icons.put(12, icon(266, 0, "§6KDR: §f" + pr.kdr()));
        icons.put(13, icon(377, 0, "§eSequencia: §f" + pr.streak + "\n§7Melhor: " + pr.bestStreak));
        int tierIcon = pr.ladder < 0 ? 388 : Ranks.ICONS[Ranks.tier(pr.ladder, divine)];
        icons.put(14, icon(tierIcon, 0, "§bElo: " + rank + "\n§7Pico: " + (pr.peakLadder < 0 ? "-" : Ranks.name(pr.peakLadder, false)) + "\n§7Posicao: " + (rankPos == 0 ? "-" : "#" + rankPos)));
        icons.put(15, icon(267, 0, "§dDuelos: §a" + pr.rankedWins + "V §c" + pr.rankedLosses + "D §7" + pr.rankedDraws + "E\n§fVitorias: " + pr.winRate()));
        icons.put(16, icon(331, 0, "§4Dano causado: §f" + pr.damageDealt / 2 + " coracoes\n§4Dano recebido: §f" + pr.damageTaken / 2 + " coracoes"));
        boolean own = viewer == target;
        if (own) {
            boolean waiting = queue.containsKey(viewer);
            icons.put(19, icon(35, waiting ? 14 : 5, waiting ? "§cSair da fila do ranked" : "§aJogar ranked 1v1\n§7Pareamento pelo MMR"));
            icons.put(20, icon(351, pr.showElo ? 10 : 8, "§fElo no chat: " + (pr.showElo ? "§aligado" : "§cdesligado") + "\n§7Toque para " + (pr.showElo ? "esconder" : "mostrar")));
            if (getServer().getServiceManager().load(CosmeticService.class) != null) { icons.put(25, icon(377, 0, "§dCosmeticos\n§7Pingentes, trilhas e efeitos")); }
        }
        icons.put(21, icon(323, 0, "§6Lista de elos\n§7Como funciona Imortal e Divino"));
        icons.put(23, icon(339, 0, "§eTop 10 ranked"));
        icons.put(24, icon(339, 0, "§eTop 10 abates"));
        getServer().getServiceManager().load(MenuService.class).open(viewer, "Perfil de " + target.getName(), icons, (who, slot) -> {
            if (slot == 19 && own) { try { toggleQueue(who); } catch (RuntimeException e) { who.sendMessage(PREFIX + "§c" + e.getMessage()); } }
            else if (slot == 20 && own) { pr.showElo = !pr.showElo; dirty.add(who); who.sendMessage(PREFIX + "Elo no chat " + (pr.showElo ? "§aligado" : "§cdesligado") + "§f."); menu(who, target); }
            else if (slot == 21) { elosMenu(who, target); }
            else if (slot == 23) { topMenu(who, target, "ranked"); }
            else if (slot == 24) { topMenu(who, target, "pvp"); }
            else if (slot == 25 && own) { CosmeticService c = getServer().getServiceManager().load(CosmeticService.class); if (c != null) { c.openMenu(who); } }
        });
    }

    private void topMenu(Player p, Player back, String board) {
        RankingService ranking = getServer().getServiceManager().load(RankingService.class);
        List<RankingService.Entry> top = ranking == null ? Collections.emptyList() : ranking.top(board, 10);
        Map<Integer, Item> icons = new HashMap<>();
        for (int i = 0; i < top.size(); i++) { icons.put(i < 5 ? 2 + i : 6 + i, icon(i == 0 ? 266 : i < 3 ? 265 : 336, 0, "§e#" + (i + 1) + " §f" + top.get(i).player + "\n§7" + top.get(i).label)); }
        icons.put(22, icon(262, 0, "§fVoltar ao perfil"));
        getServer().getServiceManager().load(MenuService.class).open(p, board.equals("ranked") ? "Top ranked" : "Top abates", icons, (who, slot) -> menu(who, back.isOnline() ? back : who));
    }

    /** Todos os elos, do Ferro ao Divino, com os pontos de cada um; o do jogador aparece marcado. */
    private void elosMenu(Player p, Player back) {
        Profile pr = profile(back.isOnline() ? back : p);
        int mine = pr == null || pr.ladder < 0 ? -1 : Ranks.tier(pr.ladder, divine(key(back), pr.ladder));
        Map<Integer, Item> icons = new HashMap<>();
        for (int t = 0; t < Ranks.NAMES.length; t++) {
            String color = "§" + Ranks.COLORS[t] + (t == 8 ? "§l" : "");
            String info;
            if (t < 7) { info = "§7Divisoes 1, 2 e 3\n§7100 PR por divisao (" + t * 300 + " a " + (t * 300 + 299) + " pontos)"; }
            else if (t == 7) { info = "§7A partir de " + Ranks.IMMORTAL + " pontos\n§7Sem teto: os PR acumulam\n§7Imortal 2 com " + Ranks.IMMORTAL_2 + " PR, Imortal 3 com " + Ranks.IMMORTAL_3 + " PR"; }
            else { info = "§7So os " + Ranks.DIVINE_SLOTS + " Imortais com mais PR\n§7do servidor. Quem passar um\n§7Divino toma a vaga dele."; }
            icons.put(t < 7 ? 1 + t : t == 7 ? 12 : 14, icon(Ranks.ICONS[t], 0, color + Ranks.NAMES[t] + (t == mine ? " §a<- voce" : "") + "\n" + info));
        }
        icons.put(18, icon(388, 0, "§fComo funciona\n§7" + Ranks.PLACEMENTS + " classificatorias definem o elo inicial\n§7(no maximo Ascendente 1).\n§7Vitoria +PR, derrota -PR; o MMR oculto\n§7decide quanto cada partida vale."));
        icons.put(22, icon(262, 0, "§fVoltar ao perfil"));
        getServer().getServiceManager().load(MenuService.class).open(p, "Elos do ranked", icons, (who, slot) -> menu(who, back.isOnline() ? back : who));
    }

    private static Item icon(int id, int meta, String name) { Item item = Item.get(id, meta, 1); item.setCustomName(name); return item; }

    // ---------- ranked ----------

    private void toggleQueue(Player p) {
        if (queue.remove(p) != null) { p.sendMessage(PREFIX + "Voce saiu da fila."); return; }
        if (matches.containsKey(p)) { throw new IllegalArgumentException("Voce ja esta num duelo."); }
        if (tagged(p)) { throw new IllegalArgumentException("Saia do combate antes."); }
        if (profile(p) == null) { throw new IllegalArgumentException("Perfil ainda carregando."); }
        long ban = profile(p).rankedBanUntil - System.currentTimeMillis();
        if (ban > 0) { throw new IllegalArgumentException("Ranked bloqueado por fraude por mais " + (ban / 3_600_000 + 1) + " h."); }
        if (boards.read(b -> b.arenas.isEmpty())) { throw new IllegalArgumentException("Nenhuma arena de ranked configurada."); }
        queue.put(p, System.currentTimeMillis());
        p.sendMessage(PREFIX + "Na fila do ranked (" + rankText(key(p), profile(p)) + "§f). Procurando oponente...");
    }

    /** A cada segundo: contagem dos duelos, tempo limite e pareamento da fila. */
    private void second() {
        long now = System.currentTimeMillis();
        if (++pruneTick >= 60) { pruneTick = 0; fraud.prune(now); }
        for (Match m : new HashSet<>(matches.values())) {
            if (!m.ready || m.over) { continue; }
            long left = m.startAt - now;
            if (left > 0) { for (Player p : new Player[] {m.a, m.b}) { p.sendTip("§eDuelo comeca em §f" + (left / 1000 + 1)); } }
            else if (now - m.startAt > MATCH_MS) { for (Player p : new Player[] {m.a, m.b}) { p.sendMessage(PREFIX + "Tempo esgotado: empate."); } end(m, null); }
        }
        if (queue.size() < 2 || !boards.isLoaded()) { return; }
        List<Player> waiting = new ArrayList<>(queue.keySet());
        Set<Player> taken = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Player a : waiting) {
            if (taken.contains(a) || !a.isOnline()) { continue; }
            // A janela de rating aceita cresce com a espera: ninguem fica preso na fila.
            long waited = (now - queue.get(a)) / 1000;
            int window = (int) Math.min(800, 100 + waited * 25);
            Player best = null;
            int bestGap = Integer.MAX_VALUE;
            for (Player b : waiting) {
                if (b == a || taken.contains(b) || !b.isOnline() || profile(a) == null || profile(b) == null) { continue; }
                // Nunca pareia contas do mesmo IP nem a dupla que ja se enfrentou demais agora.
                if (Fraud.sameIp(ip(a), ip(b)) || !fraud.canPair(key(a), key(b), now)) { continue; }
                int gap = Math.abs(profile(a).rating - profile(b).rating);
                if (gap <= window && gap < bestGap) { best = b; bestGap = gap; }
            }
            if (best == null) { continue; }
            String arena = freeArena();
            if (arena == null) { return; }
            taken.add(a); taken.add(best);
            queue.remove(a); queue.remove(best);
            start(a, best, arena);
        }
    }

    private String freeArena() {
        for (String name : boards.read(b -> new ArrayList<>(b.arenas.keySet()))) { if (!busyArenas.contains(name)) { return name; } }
        return null;
    }

    /** Guarda os dois inventarios no banco; so depois disso entrega o kit e leva para a arena. */
    private void start(Player a, Player b, String arena) {
        Match m = new Match(a, b, arena);
        busyArenas.add(arena);
        matches.put(a, m); matches.put(b, m);
        stash(a).thenCombine(stash(b), (x, y) -> null).whenComplete((v, error) -> {
            if (m.over) { return; }
            if (error != null || !a.isOnline() || !b.isOnline()) { getLogger().warning("Duelo cancelado: " + (error == null ? "jogador saiu" : error.getMessage())); end(m, null); return; }
            Boards.Arena spots = boards.read(s -> s.arenas.get(arena));
            if (spots == null || getServer().getLevelByName(spots.a.target) == null || getServer().getLevelByName(spots.b.target) == null) { getLogger().warning("Duelo cancelado: arena " + arena + " indisponivel."); end(m, null); return; }
            List<ItemData> kit = boards.read(s -> s.kit);
            prepare(a, spots.a, kit); prepare(b, spots.b, kit);
            m.ready = true;
            Profile pa = profile(a), pb = profile(b);
            a.sendMessage(PREFIX + "§6Duelo contra " + b.getName() + " " + rankText(key(b), pb));
            b.sendMessage(PREFIX + "§6Duelo contra " + a.getName() + " " + rankText(key(a), pa));
        });
    }

    private java.util.concurrent.CompletableFuture<Void> stash(Player p) {
        Map<String, Object> saved = new HashMap<>();
        saved.put("itens", ItemData.snapshot(p.getInventory().getContents(), 36));
        List<ItemData> armor = new ArrayList<>();
        for (Item piece : p.getInventory().getArmorContents()) { armor.add(piece.getId() == 0 ? null : ItemData.of(piece)); }
        saved.put("armadura", armor);
        saved.put("local", Destination.position(p.level.getFolderName(), p.x, p.y, p.z, (float) p.yaw, (float) p.pitch));
        saved.put("modo", p.getGamemode());
        return core.getStore().put(STASH, key(p), GSON.toJson(saved));
    }

    private void prepare(Player p, Destination spot, List<ItemData> kit) {
        p.getInventory().clearAll();
        p.removeAllEffects();
        p.setGamemode(Player.SURVIVAL);
        p.setHealth(p.getMaxHealth());
        p.getFoodData().setLevel(20);
        List<ItemData> items = kit.isEmpty() ? defaultKit() : kit;
        int armorSlot = 0;
        for (ItemData d : items) {
            Item item = d.toItem();
            if (item.isArmor() && armorSlot < 4) { p.getInventory().setArmorItem(armorSlot++, item); } else { p.getInventory().addItem(item); }
        }
        p.teleport(new Location(spot.x, spot.y, spot.z, spot.yaw, spot.pitch, getServer().getLevelByName(spot.target)));
    }

    private static List<ItemData> defaultKit() {
        List<ItemData> kit = new ArrayList<>();
        for (int[] i : new int[][] {{306, 1}, {307, 1}, {308, 1}, {309, 1}, {276, 1}, {282, 1}, {282, 1}, {282, 1}, {282, 1}, {282, 1}, {282, 1}, {282, 1}, {282, 1}}) {
            ItemData d = new ItemData(); d.id = i[0]; d.count = i[1]; kit.add(d);
        }
        return kit;
    }

    /** Fim do duelo: ELO, devolucao dos inventarios e arena livre. winner null = empate/cancelado. */
    private void end(Match m, Player winner) {
        if (m.over) { return; }
        m.over = true;
        matches.remove(m.a); matches.remove(m.b);
        busyArenas.remove(m.arena);
        Profile pa = profile(m.a), pb = profile(m.b);
        boolean started = m.ready && System.currentTimeMillis() >= m.startAt;
        long duration = System.currentTimeMillis() - m.startAt;
        if (started) { fraud.recordMatch(key(m.a), key(m.b), System.currentTimeMillis()); }
        // Partida entregue (derrota rapida sem reacao) ou empate sem ninguem lutar: anulada, sem pontos.
        boolean fed = started && winner != null && Fraud.feeding(duration, m.dealt(m.other(winner)));
        boolean idle = started && winner == null && m.dealt[0] < Fraud.FEED_DAMAGE && m.dealt[1] < Fraud.FEED_DAMAGE;
        if (fed || idle) {
            started = false;
            for (Player p : new Player[] {m.a, m.b}) {
                if (p.isOnline()) { p.sendMessage(PREFIX + "§cPartida anulada: suspeita de fraude (" + (fed ? "derrota entregue" : "ninguem lutou") + "). Nao vale pontos."); }
                // Quem perdeu sem reagir sempre leva advertencia; o vencedor so quando a dupla repete (combinado).
                boolean repeat = !fraud.canPair(key(m.a), key(m.b), System.currentTimeMillis());
                if (idle || p != winner || repeat) { flag(p, fed ? "partida entregue contra " + m.other(p).getName() : "duelo parado contra " + m.other(p).getName()); }
            }
        }
        if (started && pa != null && pb != null) {
            if (winner == null) {
                pa.rankedDraws++; pb.rankedDraws++;
                for (Player p : new Player[] {m.a, m.b}) { if (p.isOnline()) { p.sendMessage(PREFIX + "§7Empate."); } applyRank(p, profile(p), 0); }
            } else {
                Player loser = m.other(winner);
                Profile pw = profile(winner), pl = profile(loser);
                int delta = Profile.eloDelta(pw.rating, pl.rating);
                pw.rating += delta; pw.peakRating = Math.max(pw.peakRating, pw.rating); pw.rankedWins++;
                pl.rating = Math.max(0, pl.rating - delta); pl.rankedLosses++;
                winner.sendMessage(PREFIX + "§aVitoria!");
                if (loser.isOnline()) { loser.sendMessage(PREFIX + "§cDerrota."); }
                applyRank(winner, pw, 1);
                applyRank(loser, pl, -1);
                EffectService effects = getServer().getServiceManager().load(EffectService.class);
                if (effects != null) { effects.follow(winner, EffectService.Shape.CELEBRACAO, 40); }
            }
            for (Player p : new Player[] {m.a, m.b}) { dirty.add(p); rank(p); }
        }
        // Quem continua vivo e online recebe o inventario agora; o perdedor morto recebe ao renascer.
        for (Player p : new Player[] {m.a, m.b}) {
            if (!p.isOnline() || restoreOnRespawn.contains(p)) { continue; }
            core.getStore().get(STASH, key(p)).whenComplete((value, error) -> { if (error == null && value.isPresent() && p.isOnline()) { restore(p, value.get(), true); } });
        }
    }

    static String ip(Player p) { return p.getAddress(); }

    /** Advertencia por fraude: aviso, staff alertada; 3 em 24 h expulsa, 4 bloqueia o ranked por 24 h. */
    private void flag(Player p, String reason) {
        String name = key(p);
        int count = fraud.strike(name, System.currentTimeMillis());
        getLogger().warning("Fraude PvP: " + name + " - " + reason + " (" + count + "/" + Fraud.KICK_AT + ")");
        for (Player staff : getServer().getOnlinePlayers().values()) {
            if (staff.hasPermission("goldpvp.admin")) { staff.sendMessage(PREFIX + "§e[Anti-fraude] §f" + p.getName() + ": " + reason + " §7(" + count + "/" + Fraud.KICK_AT + ")"); }
        }
        if (count >= Fraud.BAN_AT) {
            Profile pr = profile(p);
            if (pr != null) { pr.rankedBanUntil = System.currentTimeMillis() + Fraud.RANKED_BAN_MS; dirty.add(p); }
        }
        if (!p.isOnline()) { return; }
        if (count >= Fraud.KICK_AT) {
            Player target = p;
            // Expulsa no tick seguinte para nao derrubar o jogador no meio do evento atual.
            getServer().getScheduler().scheduleDelayedTask(new PluginTask<GoldPvP>(this) {
                @Override public void onRun(int tick) { if (target.isOnline()) { target.kick("Fraude no PvP detectada (" + reason + ")." + (count >= Fraud.BAN_AT ? " Ranked bloqueado por 24 h." : " Na proxima o ranked sera bloqueado."), false); } }
            }, 1);
        } else {
            p.sendMessage(PREFIX + "§c§lAdvertencia " + count + "/" + Fraud.KICK_AT + ": §r§c" + reason + ". Repetir expulsa do servidor.");
        }
    }

    /** Classificatorias, PR ganho ou perdido, subida/queda de elo e recompensas cosmeticas. */
    private void applyRank(Player p, Profile pr, int result) {
        String name = key(p);
        int oldTier = pr.ladder < 0 ? -1 : Ranks.tier(pr.ladder, false);
        if (pr.ladder < 0) {
            if (pr.games() < Ranks.PLACEMENTS) { if (p.isOnline()) { p.sendMessage(PREFIX + "§7Classificatoria " + pr.games() + "/" + Ranks.PLACEMENTS + "."); } return; }
            pr.ladder = Ranks.placement(pr.rating);
            if (p.isOnline()) { p.sendMessage(PREFIX + "§6Classificatorias concluidas! §fSeu elo: " + Ranks.display(pr.ladder, false, pr.games())); }
        } else {
            int change = result > 0 ? Ranks.winGain(pr.rating, pr.ladder) : result < 0 ? -Ranks.lossCost(pr.rating, pr.ladder) : Ranks.drawChange(pr.rating, pr.ladder);
            pr.ladder = Math.max(0, pr.ladder + change);
            if (p.isOnline()) { p.sendMessage(PREFIX + (change >= 0 ? "§a+" : "§c") + change + " PR §f-> " + Ranks.display(pr.ladder, false, pr.games())); }
        }
        pr.peakLadder = Math.max(pr.peakLadder, pr.ladder);
        int newTier = Ranks.tier(pr.ladder, false);
        if (oldTier >= 0 && newTier != oldTier && p.isOnline()) {
            String tier = "§" + Ranks.COLORS[newTier] + Ranks.NAMES[newTier];
            if (newTier > oldTier) {
                p.sendMessage(PREFIX + "§6Voce subiu para " + tier + "§6!");
                if (newTier >= 6) { getServer().broadcastMessage(PREFIX + "§e" + p.getName() + " §falcancou " + tier + "§f!"); }
            } else { p.sendMessage(PREFIX + "§cVoce caiu para " + tier + "§c."); }
        }
        // Pingentes de cada elo ja alcancado (pico) e de vitorias.
        List<String> ids = new ArrayList<>();
        for (int t = 0; t <= Ranks.tier(pr.peakLadder, false) && t < 8; t++) { ids.add("pingente_" + Ranks.NAMES[t].toLowerCase(Locale.ROOT)); }
        if (pr.peakLadder >= Ranks.IMMORTAL) { ids.add("abate_imortal"); }
        for (int wins : new int[] {10, 50, 100}) { if (pr.rankedWins >= wins) { ids.add("pingente_vitorias_" + wins); } }
        if (p.isOnline()) { reward(p, ids.toArray(new String[0])); }
    }

    /** Libera cosmeticos pelo GoldCosmetics (se instalado) e avisa os que forem novos. */
    private void reward(Player p, String... ids) {
        CosmeticService cosmetics = getServer().getServiceManager().load(CosmeticService.class);
        if (cosmetics == null) { return; }
        for (String id : ids) {
            if (!cosmetics.exists(id)) { continue; }
            cosmetics.unlock(key(p), id).thenAccept(fresh -> {
                if (!fresh || !p.isOnline()) { return; }
                p.sendMessage(PREFIX + "§dNovo cosmetico desbloqueado: §f" + id.replace('_', ' ') + "§d. Veja em /cosmeticos.");
                if (id.equals("pingente_divino")) { getServer().broadcastMessage(PREFIX + "§6§l" + p.getName() + " entrou para os Divinos!"); }
            });
        }
    }

    /** Limpa o kit, devolve itens, armadura, modo de jogo e local; so entao apaga o guardado. */
    @SuppressWarnings("unchecked")
    private void restore(Player p, String json, boolean teleport) {
        if (matches.containsKey(p)) { return; }
        Map<String, Object> raw = GSON.fromJson(json, Map.class);
        Map<Integer, ItemData> items = new HashMap<>();
        Map<String, Object> itemMap = (Map<String, Object>) raw.get("itens");
        if (itemMap != null) { for (Map.Entry<String, Object> e : itemMap.entrySet()) { items.put(Integer.parseInt(e.getKey()), GSON.fromJson(GSON.toJson(e.getValue()), ItemData.class)); } }
        List<Object> armor = (List<Object>) raw.get("armadura");
        Destination local = GSON.fromJson(GSON.toJson(raw.get("local")), Destination.class);
        int mode = raw.get("modo") instanceof Number ? ((Number) raw.get("modo")).intValue() : Player.SURVIVAL;
        p.getInventory().clearAll();
        for (Map.Entry<Integer, ItemData> e : items.entrySet()) { e.getValue().validate(); p.getInventory().setItem(e.getKey(), e.getValue().toItem()); }
        if (armor != null) { for (int i = 0; i < Math.min(4, armor.size()); i++) { if (armor.get(i) != null) { p.getInventory().setArmorItem(i, GSON.fromJson(GSON.toJson(armor.get(i)), ItemData.class).toItem()); } } }
        p.setGamemode(mode);
        p.setHealth(p.getMaxHealth());
        Level level = local == null ? null : getServer().getLevelByName(local.target);
        if (teleport && level != null) { p.teleport(new Location(local.x, local.y, local.z, local.yaw, local.pitch, level)); }
        core.getStore().delete(STASH, key(p));
        p.sendMessage(PREFIX + "Seu inventario foi devolvido.");
        giveMenuItem(p);
    }

    // ---------- comandos ----------

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        try {
            switch (command.getName()) {
                case "topkills": printTop(sender, "pvp"); return true;
                case "topranked": printTop(sender, "ranked"); return true;
                case "elos": {
                    if (sender instanceof Player) { elosMenu((Player) sender, (Player) sender); return true; }
                    for (int t = 0; t < Ranks.NAMES.length; t++) { sender.sendMessage("§" + Ranks.COLORS[t] + Ranks.NAMES[t]); }
                    return true;
                }
                case "elochat": {
                    if (!(sender instanceof Player)) { return true; }
                    Profile pr = profile((Player) sender);
                    if (pr == null) { return true; }
                    pr.showElo = !pr.showElo; dirty.add((Player) sender);
                    sender.sendMessage(PREFIX + "Elo no chat " + (pr.showElo ? "§aligado" : "§cdesligado") + "§f.");
                    return true;
                }
                case "perfil": {
                    Player target = args.length > 0 ? getServer().getPlayerExact(args[0]) : (sender instanceof Player ? (Player) sender : null);
                    if (target != null && sender instanceof Player) { menu((Player) sender, target); return true; }
                    if (args.length == 0) { return false; }
                    String name = args[0].toLowerCase(Locale.ROOT);
                    core.getStore().get(PROFILES, name).whenComplete((value, error) -> {
                        if (error != null || !value.isPresent()) { sender.sendMessage(PREFIX + "Perfil nao encontrado."); return; }
                        Profile pr = GSON.fromJson(value.get(), Profile.class);
                        sender.sendMessage(PREFIX + "§6" + name + " §f- " + rankText(name, pr) + " §7(pico " + (pr.peakLadder < 0 ? "-" : Ranks.name(pr.peakLadder, false)) + ")");
                        sender.sendMessage("§fAbates " + pr.kills + " | Mortes " + pr.deaths + " | KDR " + pr.kdr() + " | Melhor sequencia " + pr.bestStreak);
                        sender.sendMessage("§fRanked " + pr.rankedWins + "V " + pr.rankedLosses + "D " + pr.rankedDraws + "E (" + pr.winRate() + ")");
                    });
                    return true;
                }
                default: return ranked(sender, args);
            }
        } catch (RuntimeException e) { sender.sendMessage(PREFIX + "§c" + e.getMessage()); return true; }
    }

    private void printTop(CommandSender sender, String board) {
        sender.sendMessage(PREFIX + (board.equals("ranked") ? "§6Top ranked:" : "§6Top abates:"));
        int i = 1;
        for (RankingService.Entry e : getServer().getServiceManager().load(RankingService.class).top(board, 10)) { sender.sendMessage("§e" + (i++) + ". §f" + e.player + " §7- " + e.label); }
    }

    private boolean ranked(CommandSender sender, String[] args) {
        String sub = args.length == 0 ? "menu" : args[0].toLowerCase(Locale.ROOT);
        Player p = sender instanceof Player ? (Player) sender : null;
        switch (sub) {
            case "menu": if (p != null) { menu(p, p); } return true;
            case "fila": if (p != null) { toggleQueue(p); } return true;
            case "sair": {
                if (p == null) { return true; }
                Match m = matches.get(p);
                if (m != null) { p.sendMessage(PREFIX + "Voce desistiu."); end(m, m.other(p)); } else if (queue.remove(p) != null) { p.sendMessage(PREFIX + "Voce saiu da fila."); }
                return true;
            }
            default: break;
        }
        if (!sender.hasPermission("goldpvp.admin")) { sender.sendMessage(PREFIX + "/ranked, /ranked fila, /ranked sair, /perfil [jogador], /topranked, /topkills"); return true; }
        switch (sub) {
            case "arena": {
                if (p == null || args.length < 3) { sender.sendMessage(PREFIX + "Uso: /ranked arena <nome> <a|b|remover>"); return true; }
                String name = args[1].toLowerCase(Locale.ROOT);
                if (!name.matches("[a-z0-9_-]{1,20}")) { throw new IllegalArgumentException("Nome invalido."); }
                String what = args[2].toLowerCase(Locale.ROOT);
                if (busyArenas.contains(name)) { throw new IllegalArgumentException("Arena em uso; espere o duelo acabar."); }
                Destination here = Destination.position(p.level.getFolderName(), p.x, p.y, p.z, (float) p.yaw, (float) p.pitch);
                boards.change(b -> {
                    if (what.equals("remover")) { b.arenas.remove(name); return; }
                    Boards.Arena a = b.arenas.computeIfAbsent(name, k -> new Boards.Arena());
                    if (what.equals("a")) { a.a = here; } else if (what.equals("b")) { a.b = here; } else { throw new IllegalArgumentException("Use a, b ou remover."); }
                    if (a.a == null) { a.a = here; }
                    if (a.b == null) { a.b = here; }
                }).whenComplete((v, e) -> sender.sendMessage(PREFIX + (e != null ? "§c" + e.getMessage() : "Arena " + name + " atualizada (" + what + ").")));
                return true;
            }
            case "kit": {
                if (p == null) { return true; }
                List<ItemData> kit = new ArrayList<>(ItemData.snapshot(p.getInventory().getContents(), 36).values());
                for (Item armor : p.getInventory().getArmorContents()) { if (armor.getId() != 0) { kit.add(ItemData.of(armor)); } }
                boards.change(b -> { b.kit.clear(); b.kit.addAll(kit); }).whenComplete((v, e) -> sender.sendMessage(PREFIX + (e != null ? "§c" + e.getMessage() : "Kit do ranked salvo (" + kit.size() + " itens).")));
                return true;
            }
            case "lobby": {
                if (args.length < 2) { sender.sendMessage(PREFIX + "Uso: /ranked lobby <mundo> (alterna onde o item do perfil aparece)"); return true; }
                String world = Destination.world(args[1]).target;
                boards.change(b -> { if (!b.lobbyWorlds.remove(world)) { b.lobbyWorlds.add(world); } }).whenComplete((v, e) -> sender.sendMessage(PREFIX + "Mundos com o item: " + boards.read(b -> b.lobbyWorlds)));
                return true;
            }
            default:
                sender.sendMessage(PREFIX + "Admin: /ranked arena <nome> a|b|remover, /ranked kit, /ranked lobby <mundo>");
                return true;
        }
    }

    static String key(Player p) { return p.getName().toLowerCase(Locale.ROOT); }
}
