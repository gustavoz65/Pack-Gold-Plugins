package br.goldsuite.plots;

import br.goldsuite.api.*;
import br.goldsuite.api.EconomyService.Currency;
import cn.nukkit.Player;
import cn.nukkit.block.Block;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.block.BlockBreakEvent;
import cn.nukkit.event.block.BlockPlaceEvent;
import cn.nukkit.event.player.PlayerBucketEmptyEvent;
import cn.nukkit.event.player.PlayerBucketFillEvent;
import cn.nukkit.event.player.PlayerInteractEvent;
import cn.nukkit.event.player.PlayerQuitEvent;
import cn.nukkit.item.Item;
import cn.nukkit.level.Level;
import cn.nukkit.level.generator.Generator;
import cn.nukkit.math.Vector3;
import cn.nukkit.plugin.PluginBase;
import cn.nukkit.scheduler.PluginTask;
import java.math.BigDecimal;
import java.util.*;

/** Terrenos infinitos: compra, membros, limite por permissao, protecao e limpeza aos poucos. */
public final class GoldPlots extends PluginBase implements Listener {
    static String PREFIX = M.t("prefixo");

    /** Um plot comprado (so os comprados sao guardados, um registro por id "x;z"). */
    public static final class Plot {
        public String owner = "";
        public List<String> members = new ArrayList<>();
        public long bought;
        static void validate(Plot p) {
            if (p == null || p.owner == null || p.members == null || p.members.size() > 16 || (!p.owner.isEmpty() && !p.owner.matches("[a-z0-9_]{3,16}"))) { throw new IllegalArgumentException(M.t("plot_invalido")); }
            for (String m : p.members) { if (m == null || !m.matches("[a-z0-9_]{3,16}")) { throw new IllegalArgumentException(M.t("plot_invalido")); } }
        }
    }

    /** Um mundo de terrenos: plots (/plot, plotworld do pack) ou terrenos (/terreno, mapa terrenos do pack). */
    final class PW {
        final String key, command;
        String world;
        long price = 1500_00;
        RecordStore<Plot> plots;
        /** Plots no meio de uma compra (id -> comprador): o segundo comprador recebe "ja tem dono". */
        final Map<String, String> pending = new HashMap<>();
        PW(String key, String command, String world) { this.key = key; this.command = command; this.world = world; }
        PlotGrid grid() { return key.equals("terrenos") ? TerrenoGenerator.grid : PlotGenerator.grid; }
        boolean is(Level level) { return level != null && level.getFolderName().equalsIgnoreCase(world); }
        String title() { return key.equals("terrenos") ? M.t("titulo_terrenos") : M.t("titulo_plots"); }
    }

    final PW plotsWorld = new PW("plots", "plot", "plots");
    final PW terrenosWorld = new PW("terrenos", "terreno", "terrenos");
    final List<PW> worlds = Arrays.asList(plotsWorld, terrenosWorld);
    int defaultLimit = 1, perTick = 2000;
    /** Limpezas em andamento (por tick). */
    private final ArrayDeque<int[]> clearJobs = new ArrayDeque<>();
    private final Map<int[], Level> clearLevels = new IdentityHashMap<>();
    private final Map<int[], PW> clearWorlds = new IdentityHashMap<>();

    @Override public void onLoad() {
        Generator.addGenerator(PlotGenerator.class, "goldplots", PlotGenerator.TYPE);
        Generator.addGenerator(TerrenoGenerator.class, "goldterrenos", TerrenoGenerator.TYPE);
    }

    @Override public void onEnable() {
        M.B.attach(this);
        Settings.prefix(this, PREFIX, v -> PREFIX = v);
        CoreService core = getServer().getServiceManager().load(CoreService.class);
        if (core == null) { throw new IllegalStateException(M.t("goldcore_precisa_estar_ativo")); }
        Settings.text(this, "mundo", "plots", "Mundo de plots (/plot; crie com: mundo criar plots goldplots)", v -> plotsWorld.world = v.trim());
        Settings.number(this, "preco", 1500, "Preco do plot em money", v -> plotsWorld.price = Math.max(0, v) * 100);
        Settings.text(this, "terrenos.mundo", "terrenos", "Mundo de terrenos (/terreno; gerador goldterrenos)", v -> terrenosWorld.world = v.trim());
        Settings.number(this, "terrenos.preco", 1500, "Preco do terreno em money", v -> terrenosWorld.price = Math.max(0, v) * 100);
        Settings.number(this, "limite_padrao", 1, "Plots (e terrenos) de quem nao tem goldplots.limite.<n>", v -> defaultLimit = (int) Math.max(0, Math.min(50, v)));
        Settings.number(this, "limpar.blocos_por_tick", 2000, "Blocos repostos por tick ao limpar um plot", v -> perTick = (int) Math.max(100, Math.min(20000, v)));
        int[] g = new int[4];
        Settings.number(this, "grade.tamanho", 42, "Lado do plot (NAO mude depois de criar o mundo)", v -> g[0] = (int) v);
        Settings.number(this, "grade.rua", 11, "Largura da rua (NAO mude depois de criar o mundo)", v -> g[1] = (int) v);
        Settings.number(this, "grade.altura", 64, "Altura do chao (NAO mude depois de criar o mundo)", v -> g[2] = (int) v);
        Settings.number(this, "grade.deslocamento", 5, "Deslocamento da grade (5 = casa com o plotworld do pack; NAO mude depois)", v -> g[3] = (int) v);
        try { PlotGenerator.grid = new PlotGrid(g[0], g[1], g[2], Math.floorMod(g[3], g[0] + 2 + g[1])); } catch (IllegalArgumentException e) { getLogger().warning(M.t("grade_invalida_usando_padrao")); }
        plotsWorld.plots = new RecordStore<>(core.getStore(), "goldplots", Plot.class, Plot::new, Plot::validate, true);
        terrenosWorld.plots = new RecordStore<>(core.getStore(), "goldterrenos", Plot.class, Plot::new, Plot::validate, true);
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getScheduler().scheduleRepeatingTask(new PluginTask<GoldPlots>(this) { @Override public void onRun(int t) { clearTick(); } }, 1);
    }

    @Override public void onDisable() {
        clearJobs.clear(); clearLevels.clear(); clearWorlds.clear();
        for (PW w : worlds) { w.pending.clear(); if (w.plots != null) { w.plots.close(); } }
    }

    PW worldOf(Level level) { for (PW w : worlds) { if (w.is(level)) { return w; } } return null; }
    static String key(Player p) { return p.getName().toLowerCase(Locale.ROOT); }
    static String money(long cents) { return "$ " + BigDecimal.valueOf(cents, 2).toPlainString(); }


    /** Dono do plot ("" = livre). */
    String owner(PW w, String id) {
        if (id == null || !w.plots.isLoaded()) { return ""; }
        return w.plots.keys().contains(id) ? w.plots.peek(id).owner : "";
    }

    boolean canBuild(Player p, PW w, int x, int z) {
        if (p.hasPermission("goldplots.admin")) { return true; }
        String id = w.grid().id(x, z);
        if (id == null || !w.plots.isLoaded() || !w.plots.keys().contains(id)) { return false; }
        Plot pl = w.plots.peek(id);
        String k = key(p);
        return pl.owner.equals(k) || pl.members.contains(k);
    }

    List<String> owned(PW w, String player) {
        List<String> out = new ArrayList<>();
        if (!w.plots.isLoaded()) { return out; }
        for (String id : w.plots.keys()) { if (w.plots.peek(id).owner.equals(player)) { out.add(id); } }
        out.sort(Comparator.comparingLong(id -> w.plots.peek(id).bought));
        return out;
    }

    int limit(Player p) {
        int best = defaultLimit;
        for (int n = 50; n > best; n--) { if (p.hasPermission("goldplots.limite." + n)) { best = n; break; } }
        return best;
    }

    // ---------- protecao ----------

    private boolean denied(Player p, Block b) { PW w = b == null ? null : worldOf(b.level); return w != null && !canBuild(p, w, b.getFloorX(), b.getFloorZ()); }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void breakBlock(BlockBreakEvent e) { if (denied(e.getPlayer(), e.getBlock())) { e.setCancelled(); e.getPlayer().sendPopup(M.t("plot_de_outro")); } }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void place(BlockPlaceEvent e) { if (denied(e.getPlayer(), e.getBlock())) { e.setCancelled(); e.getPlayer().sendPopup(M.t("plot_de_outro")); } }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void interact(PlayerInteractEvent e) {
        Block b = e.getBlock();
        if (e.getAction() != PlayerInteractEvent.RIGHT_CLICK_BLOCK || b == null || b.level == null || b.getId() == 0) { return; }
        if (denied(e.getPlayer(), b)) { e.setCancelled(); }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void bucket(PlayerBucketEmptyEvent e) { if (denied(e.getPlayer(), e.getBlockClicked())) { e.setCancelled(); } }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void bucketFill(PlayerBucketFillEvent e) { if (denied(e.getPlayer(), e.getBlockClicked())) { e.setCancelled(); } }

    @EventHandler(priority = EventPriority.MONITOR)
    public void quit(PlayerQuitEvent e) { String k = key(e.getPlayer()); for (PW w : worlds) { w.pending.values().removeIf(k::equals); } }

    // ---------- compra ----------

    /** Reserva, cobra e grava; se a gravacao falhar, devolve. Dois compradores do mesmo plot: so um leva. */
    void buy(Player p, PW w, String id, boolean thenHome) {
        if (!w.plots.isLoaded()) { p.sendMessage(PREFIX + M.t("carregando")); return; }
        if (id == null) { p.sendMessage(PREFIX + M.t("fique_dentro_de_um_plot")); return; }
        if (!owner(w, id).isEmpty() || w.pending.containsKey(id)) { p.sendMessage(PREFIX + M.t("plot_ja_tem_dono")); return; }
        String k = key(p);
        int mine = owned(w, k).size() + (int) w.pending.values().stream().filter(k::equals).count();
        int limit = limit(p);
        if (mine >= limit) { p.sendMessage(PREFIX + M.t("limite_de_plots", limit)); return; }
        EconomyService eco = getServer().getServiceManager().load(EconomyService.class);
        if (eco == null) { return; }
        long cost = w.price;
        if (eco.balance(p.getName(), Currency.MONEY) < cost) { p.sendMessage(PREFIX + M.t("dinheiro_insuficiente", money(cost))); return; }
        w.pending.put(id, k);
        String name = p.getName();
        eco.debit(name, Currency.MONEY, cost).whenComplete((v, error) -> {
            if (error != null) { w.pending.remove(id); if (p.isOnline()) { p.sendMessage(PREFIX + M.t("dinheiro_insuficiente", money(cost))); } return; }
            w.plots.change(id, pl -> { if (!pl.owner.isEmpty()) { throw new IllegalStateException(M.t("plot_ja_tem_dono")); } pl.owner = k; pl.members.clear(); pl.bought = System.currentTimeMillis(); })
                    .whenComplete((pl, fail) -> {
                        w.pending.remove(id);
                        if (fail != null) { eco.credit(name, Currency.MONEY, cost); if (p.isOnline()) { p.sendMessage(PREFIX + M.t("compra_falhou_devolvido")); } return; }
                        if (!p.isOnline()) { return; }
                        p.sendMessage(PREFIX + M.t("plot_comprado", id, money(cost)));
                        if (thenHome) { home(p, w, id); }
                    });
        });
    }

    /** Plot livre mais perto do centro (aneis em volta de 0;0). */
    String nearestFree(PW w) {
        for (int r = 0; r <= 400; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) { continue; }
                    String id = PlotGrid.key(dx, dz);
                    if (owner(w, id).isEmpty() && !w.pending.containsKey(id)) { return id; }
                }
            }
        }
        return null;
    }

    private boolean load(Player p, PW w) {
        if (getServer().getLevelByName(w.world) == null && !getServer().loadLevel(w.world)) { p.sendMessage(PREFIX + M.t("mundo_de_plots_nao_existe", w.world)); return false; }
        return true;
    }

    /** Leva para a frente do plot (na rua, olhando para ele). */
    void home(Player p, PW w, String id) {
        int[] ij = PlotGrid.parse(id);
        PlotGrid g = w.grid();
        double x = g.min(ij[0]) + g.size / 2.0, z = g.min(ij[1]) - 2 + 0.5;
        if (!load(p, w)) { return; }
        TravelService travel = getServer().getServiceManager().load(TravelService.class);
        if (travel != null) { travel.travel(p, Destination.position(w.world, x, g.height + 1, z, 0, 0), TravelService.Transition.PORTAL); }
        else { p.teleport(new cn.nukkit.level.Location(x, g.height + 1, z, 0, 0, getServer().getLevelByName(w.world))); }
    }

    /** Plot onde o jogador esta (null fora do mundo ou na rua). */
    String here(Player p, PW w) { return w.is(p.level) ? w.grid().id(p.getFloorX(), p.getFloorZ()) : null; }

    /** Plot que o jogador administra: o que ele esta pisando (se for dele) ou o primeiro que tem. */
    String managed(Player p, PW w) {
        String h = here(p, w), k = key(p);
        if (h != null && (owner(w, h).equals(k) || p.hasPermission("goldplots.admin") && !owner(w, h).isEmpty())) { return h; }
        List<String> mine = owned(w, k);
        return mine.isEmpty() ? null : mine.get(0);
    }

    void members(Player p, PW w, String id, String who, boolean add) {
        String k = key(p);
        if (id == null) { p.sendMessage(PREFIX + M.t("voce_nao_tem_plot")); return; }
        if (!owner(w, id).equals(k) && !p.hasPermission("goldplots.admin")) { p.sendMessage(PREFIX + M.t("plot_de_outro")); return; }
        String m = who.toLowerCase(Locale.ROOT);
        if (!m.matches("[a-z0-9_]{3,16}") || m.equals(owner(w, id))) { p.sendMessage(PREFIX + M.t("jogador_invalido")); return; }
        w.plots.change(id, pl -> { if (add) { if (!pl.members.contains(m)) { if (pl.members.size() >= 16) { throw new IllegalArgumentException(M.t("limite_de_membros")); } pl.members.add(m); } } else { pl.members.remove(m); } })
                .whenComplete((pl, e) -> { if (p.isOnline()) { p.sendMessage(PREFIX + (e != null ? "§c" + root(e) : M.t(add ? "membro_adicionado" : "membro_removido", m, id))); } });
    }

    // ---------- limpar ----------

    void clear(Player p, PW w, String id) {
        String k = key(p);
        if (id == null) { p.sendMessage(PREFIX + M.t("voce_nao_tem_plot")); return; }
        if (!owner(w, id).equals(k) && !p.hasPermission("goldplots.admin")) { p.sendMessage(PREFIX + M.t("plot_de_outro")); return; }
        Level level = getServer().getLevelByName(w.world);
        if (level == null) { p.sendMessage(PREFIX + M.t("mundo_de_plots_nao_existe", w.world)); return; }
        int[] ij = PlotGrid.parse(id);
        for (int[] j : clearJobs) { if (clearWorlds.get(j) == w && j[0] == ij[0] && j[1] == ij[1]) { p.sendMessage(PREFIX + M.t("plot_ja_limpando")); return; } }
        PlotGrid g = w.grid();
        // Quem esta no plot vai para a frente dele antes de mexer nos blocos.
        for (Player o : level.getPlayers().values()) { if (id.equals(g.id(o.getFloorX(), o.getFloorZ()))) { home(o, w, id); } }
        int[] job = {ij[0], ij[1], g.min(ij[0]), g.min(ij[1]), 0};
        clearJobs.add(job);
        clearLevels.put(job, level);
        clearWorlds.put(job, w);
        p.sendMessage(PREFIX + M.t("limpando_plot", id));
    }

    /** Repoe o plot como o gerador fez, coluna por coluna, ate perTick blocos por tick. */
    private void clearTick() {
        int budget = perTick;
        while (budget > 0 && !clearJobs.isEmpty()) {
            int[] j = clearJobs.peek();
            Level level = clearLevels.get(j);
            PW w = clearWorlds.get(j);
            if (level == null || w == null || getServer().getLevelByName(level.getFolderName()) == null) { clearJobs.poll(); clearLevels.remove(j); clearWorlds.remove(j); continue; }
            PlotGrid g = w.grid();
            int x0 = g.min(j[0]), z0 = g.min(j[1]);
            while (budget > 0 && j[4] < g.size * g.size) {
                int x = x0 + j[4] / g.size, z = z0 + j[4] % g.size;
                if (!level.isChunkLoaded(x >> 4, z >> 4)) { level.loadChunk(x >> 4, z >> 4, true); }
                for (int y = 0; y < 128; y++) {
                    int want = g.block(PlotGrid.Kind.PLOT, y);
                    if (level.getBlockIdAt(x, y, z) != want || level.getBlockDataAt(x, y, z) != 0) { level.setBlock(new Vector3(x, y, z), Block.get(want), true, false); }
                }
                budget -= 128;
                j[4]++;
            }
            if (j[4] >= g.size * g.size) {
                clearJobs.poll(); clearLevels.remove(j); clearWorlds.remove(j);
                String id = PlotGrid.key(j[0], j[1]);
                Player owner = getServer().getPlayerExact(owner(w, id));
                if (owner != null) { owner.sendMessage(PREFIX + M.t("plot_limpo", id)); }
            }
        }
    }

    boolean clearing(PW w, String id) { for (int[] j : clearJobs) { if (clearWorlds.get(j) == w && PlotGrid.key(j[0], j[1]).equals(id)) { return true; } } return false; }

    // ---------- menus ----------

    private MenuService menus() { return getServer().getServiceManager().load(MenuService.class); }
    private static Item icon(int id, int meta, String name) { Item i = Item.get(id, meta, 1); i.setCustomName(name); return i; }

    void menu(Player p, PW w) {
        if (!w.plots.isLoaded()) { p.sendMessage(PREFIX + M.t("carregando")); return; }
        String h = here(p, w), k = key(p);
        List<String> mine = owned(w, k);
        Map<Integer, Item> icons = new HashMap<>();
        String hereText = h == null ? M.t("menu_fora_de_plot") : owner(w, h).isEmpty() ? M.t("menu_comprar_este", h, money(w.price)) : M.t("menu_plot_de", h, owner(w, h));
        icons.put(10, icon(2, 0, hereText));
        icons.put(11, icon(345, 0, M.t("menu_auto", money(w.price))));
        icons.put(12, icon(355, 0, M.t("menu_meus_plots", mine.size(), limit(p))));
        icons.put(13, icon(339, 0, M.t("menu_info")));
        icons.put(14, icon(397, 3, M.t("menu_membros")));
        icons.put(15, icon(325, 0, M.t("menu_limpar")));
        icons.put(16, icon(381, 0, M.t("menu_ir_para_plots")));
        menus().open(p, w.title(), icons, (who, slot) -> {
            switch (slot) {
                case 10: { String now = here(who, w); if (now != null && owner(w, now).isEmpty()) { buy(who, w, now, false); } else { info(who, w, now); } break; }
                case 11: buy(who, w, nearestFree(w), true); break;
                case 12: homesMenu(who, w); break;
                case 13: info(who, w, here(who, w) != null ? here(who, w) : managed(who, w)); break;
                case 14: membersMenu(who, w); break;
                case 15: clearMenu(who, w); break;
                case 16: goWorld(who, w); break;
                default:
            }
        });
    }

    /** Vai para o mundo: para o primeiro plot do jogador, ou para o meio do mapa se ainda nao tem. */
    void go(Player p, PW w) {
        List<String> mine = owned(w, key(p));
        if (!mine.isEmpty()) { home(p, w, mine.get(0)); } else { goWorld(p, w); p.sendMessage(PREFIX + M.t("chegou_sem_plot", w.command)); }
    }

    void goWorld(Player p, PW w) {
        if (!load(p, w)) { return; }
        TravelService travel = getServer().getServiceManager().load(TravelService.class);
        PlotGrid g = w.grid();
        // No meio da rua do cruzamento 0;0, olhando para o primeiro plot.
        double c = g.road / 2.0 - g.offset;
        if (travel != null) { travel.travel(p, Destination.position(w.world, c, g.height + 1, c, 0, 0), TravelService.Transition.PORTAL); }
    }

    void homesMenu(Player p, PW w) {
        List<String> mine = owned(w, key(p));
        if (mine.isEmpty()) { p.sendMessage(PREFIX + M.t("voce_nao_tem_plot")); return; }
        Map<Integer, Item> icons = new HashMap<>();
        for (int i = 0; i < mine.size() && i < 18; i++) { icons.put(i, icon(2, 0, M.t("menu_plot_home", i + 1, mine.get(i)))); }
        icons.put(22, icon(262, 0, M.t("botao_voltar")));
        menus().open(p, M.t("titulo_meus_plots"), icons, (who, slot) -> {
            if (slot == 22) { menu(who, w); return; }
            if (slot >= 0 && slot < mine.size()) { home(who, w, mine.get(slot)); }
        });
    }

    void membersMenu(Player p, PW w) {
        String id = managed(p, w);
        if (id == null) { p.sendMessage(PREFIX + M.t("voce_nao_tem_plot")); return; }
        List<String> current = new ArrayList<>(w.plots.peek(id).members);
        List<Player> online = new ArrayList<>();
        for (Player o : getServer().getOnlinePlayers().values()) { if (o != p && !current.contains(key(o)) && !key(o).equals(owner(w, id))) { online.add(o); } }
        Map<Integer, Item> icons = new HashMap<>();
        for (int i = 0; i < current.size() && i < 9; i++) { icons.put(i, icon(397, 3, M.t("menu_membro_remover", current.get(i)))); }
        for (int i = 0; i < online.size() && i < 9; i++) { icons.put(9 + i, icon(397, 0, M.t("menu_membro_adicionar", online.get(i).getName()))); }
        icons.put(22, icon(262, 0, M.t("botao_voltar")));
        menus().open(p, M.t("titulo_membros", id), icons, (who, slot) -> {
            if (slot == 22) { menu(who, w); return; }
            if (slot >= 0 && slot < 9 && slot < current.size()) { members(who, w, id, current.get(slot), false); }
            if (slot >= 9 && slot < 18 && slot - 9 < online.size()) { members(who, w, id, online.get(slot - 9).getName(), true); }
        });
    }

    void clearMenu(Player p, PW w) {
        String id = managed(p, w);
        if (id == null) { p.sendMessage(PREFIX + M.t("voce_nao_tem_plot")); return; }
        Map<Integer, Item> icons = new HashMap<>();
        icons.put(11, icon(35, 5, M.t("botao_confirmar_limpar", id)));
        icons.put(15, icon(35, 14, M.t("botao_cancelar")));
        menus().open(p, M.t("titulo_limpar"), icons, (who, slot) -> { if (slot == 11) { clear(who, w, id); } });
    }

    void info(Player p, PW w, String id) {
        if (id == null) { p.sendMessage(PREFIX + M.t("fique_dentro_de_um_plot")); return; }
        String o = owner(w, id);
        if (o.isEmpty()) { p.sendMessage(PREFIX + M.t("info_livre", id, money(w.price))); return; }
        Plot pl = w.plots.peek(id);
        p.sendMessage(PREFIX + M.t("info_dono", id, o, pl.members.isEmpty() ? "-" : String.join(", ", pl.members)));
    }

    // ---------- comandos ----------

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) { sender.sendMessage(M.t("use_dentro_do_jogo")); return true; }
        Player p = (Player) sender;
        PW w = command.getName().equals("terreno") ? terrenosWorld : plotsWorld;
        AuthService auth = getServer().getServiceManager().load(AuthService.class);
        if (auth != null && !auth.isAuthenticated(p)) { p.sendMessage(M.t("autentique_se_primeiro")); return true; }
        if (!w.plots.isLoaded()) { p.sendMessage(PREFIX + M.t("carregando")); return true; }
        // /plot sozinho: fora do mapa leva ate ele (ao seu plot, se tiver); ja no mapa abre o menu.
        String sub = args.length == 0 ? (w.is(p.level) ? "menu" : "ir") : args[0].toLowerCase(Locale.ROOT);
        try {
            switch (sub) {
                case "comprar": buy(p, w, here(p, w), false); return true;
                case "auto": buy(p, w, nearestFree(w), true); return true;
                case "home": case "casa": {
                    List<String> mine = owned(w, key(p));
                    if (mine.isEmpty()) { p.sendMessage(PREFIX + M.t("voce_nao_tem_plot")); return true; }
                    int n = args.length > 1 ? Integer.parseInt(args[1]) : 1;
                    if (n < 1 || n > mine.size()) { p.sendMessage(PREFIX + M.t("voce_tem_plots", mine.size())); return true; }
                    home(p, w, mine.get(n - 1));
                    return true;
                }
                case "info": info(p, w, here(p, w)); return true;
                case "add": case "adicionar": if (args.length < 2) { p.sendMessage(PREFIX + M.t("uso_plot")); return true; } members(p, w, managed(p, w), args[1], true); return true;
                case "remover": if (args.length < 2) { p.sendMessage(PREFIX + M.t("uso_plot")); return true; } members(p, w, managed(p, w), args[1], false); return true;
                case "limpar": clearMenu(p, w); return true;
                case "ir": go(p, w); return true;
                case "spawn": goWorld(p, w); return true;
                default: menu(p, w); return true;
            }
        } catch (NumberFormatException e) { p.sendMessage(PREFIX + M.t("numero_invalido")); return true; }
        catch (RuntimeException e) { p.sendMessage(PREFIX + "§c" + root(e)); return true; }
    }

    static String root(Throwable e) { while (e.getCause() != null) { e = e.getCause(); } return e.getMessage(); }
}
