package br.goldsuite.rankup;

import br.goldsuite.api.*;
import br.goldsuite.api.EconomyService.Currency;
import br.goldsuite.rankup.RankState.Mine;
import br.goldsuite.rankup.RankState.Rank;
import cn.nukkit.Player;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.player.PlayerJoinEvent;
import cn.nukkit.event.player.PlayerQuitEvent;
import cn.nukkit.item.Item;
import cn.nukkit.level.Level;
import cn.nukkit.permission.PermissionAttachment;
import cn.nukkit.plugin.PluginBase;
import cn.nukkit.scheduler.PluginTask;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Rankup: escada de ranks pagos com money, permissoes por rank e minas que se regeneram. */
public final class GoldRankup extends PluginBase implements Listener {
    static String PREFIX = M.t("prefixo");

    /** Progresso de um jogador (um registro por jogador, so os online na memoria). */
    public static final class Progress {
        public String rank = "";
        /** Reservado para o prestigio (depois da alpha). */
        public int prestige;
        public long since;
        static void validate(Progress p) {
            if (p == null || p.rank == null || p.rank.length() > 24 || p.prestige < 0) { throw new IllegalArgumentException(M.t("progresso_invalido")); }
        }
    }

    PersistentState<RankState> data;
    RecordStore<Progress> progress;
    Mines mines;
    private final Map<Player, PermissionAttachment> attachments = new IdentityHashMap<>();
    /** Quem esta no meio de um rankup (cobranca + gravacao): o segundo toque nao cobra de novo. */
    private final Set<String> busy = new HashSet<>();
    private boolean announce = true;
    private Set<String> tagWorlds = new HashSet<>();

    @Override public void onEnable() {
        M.B.attach(this);
        Settings.prefix(this, PREFIX, v -> PREFIX = v);
        CoreService core = getServer().getServiceManager().load(CoreService.class);
        if (core == null) { throw new IllegalStateException(M.t("goldcore_precisa_estar_ativo")); }
        data = new PersistentState<>(core.getStore(), "goldrankup", RankState.class, RankState::defaults, RankState::validate);
        progress = new RecordStore<>(core.getStore(), "goldrankup-jogador", Progress.class, Progress::new, Progress::validate, false);
        mines = new Mines(this);
        Settings.number(this, "minas.blocos_por_tick", 2000, "Blocos repostos por tick somando todas as minas", v -> mines.perTick = (int) Math.max(100, Math.min(20000, v)));
        Settings.number(this, "anuncio", 1, "1 = anuncia no chat quando alguem sobe de rank", v -> announce = v != 0);
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(mines, this);
        getServer().getScheduler().scheduleRepeatingTask(new PluginTask<GoldRankup>(this) { @Override public void onRun(int t) { mines.tick(); } }, 1);
        getServer().getScheduler().scheduleRepeatingTask(new PluginTask<GoldRankup>(this) { @Override public void onRun(int t) { mines.second(); } }, 20);
        Settings.text(this, "mundos_tag", "RankupLobby,minas,plots", "Mundos onde o {rank} aparece (chat e barra); fora deles fica vazio", v -> { Set<String> w = new HashSet<>(); for (String x : v.split(",")) { if (!x.trim().isEmpty()) { w.add(x.trim().toLowerCase(Locale.ROOT)); } } tagWorlds = w; });
        Settings.placeholder(this, "rank", p -> { if (!tagWorlds.isEmpty() && !tagWorlds.contains(p.level.getFolderName().toLowerCase(Locale.ROOT))) { return ""; } Rank r = current(p); return r == null ? "" : r.tag.replace('&', '§') + " "; });
        Settings.placeholder(this, "rank_proximo", p -> { Rank r = next(p); return r == null ? M.t("rank_maximo") : r.tag.replace('&', '§'); });
        Settings.placeholder(this, "rank_preco", p -> { Rank r = next(p); return r == null ? "-" : PlaceholderService.compact(r.price / 100); });
        Settings.placeholder(this, "rank_progresso", p -> progressPercent(p) + "%");
        data.ready().thenRun(() -> getServer().getScheduler().scheduleTask(() -> {
            for (Rank r : data.read(s -> new ArrayList<>(s.ranks))) {
                Settings.number(this, "preco." + r.id, r.price / 100, "Preco em money para chegar no rank " + r.name, v -> setPrice(r.id, v));
            }
            for (Player p : getServer().getOnlinePlayers().values()) { load(p); }
        }));
    }

    @Override public void onDisable() {
        for (Map.Entry<Player, PermissionAttachment> e : attachments.entrySet()) { try { e.getKey().removeAttachment(e.getValue()); } catch (RuntimeException ignored) { } }
        attachments.clear(); busy.clear();
        if (data != null) { data.close(); }
        if (progress != null) { progress.close(); }
    }

    /** Preco do painel (em reais): so grava se mudou, para nao reescrever o documento a cada carga. */
    private void setPrice(String id, long reais) {
        long cents = Math.max(0, Math.min(1_000_000_000L, reais)) * 100;
        if (!data.isLoaded() || data.read(s -> { int i = s.index(id); return i < 0 || s.ranks.get(i).price == cents; })) { return; }
        data.change(s -> { int i = s.index(id); if (i >= 0) { s.ranks.get(i).price = cents; } });
    }

    // ---------- jogadores ----------

    static String key(Player p) { return p.getName().toLowerCase(Locale.ROOT); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void join(PlayerJoinEvent e) { load(e.getPlayer()); }

    private void load(Player p) {
        if (!progress.isLoaded()) { return; }
        String k = key(p);
        progress.retain(k).whenComplete((v, error) -> {
            if (!isEnabled()) { return; }
            if (!p.isOnline()) { progress.release(k); return; }
            if (error != null) { getLogger().warning(M.t("falha_ao_carregar_rank", p.getName())); return; }
            applyPermissions(p);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void quit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        PermissionAttachment a = attachments.remove(p);
        if (a != null) { try { p.removeAttachment(a); } catch (RuntimeException ignored) { } }
        busy.remove(key(p));
        if (progress.isLoaded()) { progress.release(key(p)); }
    }

    boolean loaded(Player p) {
        if (!progress.isLoaded() || !data.isLoaded()) { return false; }
        try { progress.peek(key(p)); return true; } catch (IllegalStateException e) { return false; }
    }

    /** Indice do rank atual (0 se ainda nao carregou ou se o rank salvo nao existe mais). */
    int index(Player p) {
        if (!loaded(p)) { return 0; }
        String id = progress.peek(key(p)).rank;
        return data.read(s -> Math.max(0, s.index(id)));
    }

    Rank current(Player p) { if (!data.isLoaded()) { return null; } int i = index(p); return data.read(s -> s.ranks.get(Math.min(i, s.ranks.size() - 1))); }
    Rank next(Player p) { if (!data.isLoaded()) { return null; } int i = index(p); return data.read(s -> i + 1 < s.ranks.size() ? s.ranks.get(i + 1) : null); }

    /** O jogador ja chegou no rank (id)? */
    boolean reached(Player p, String rankId) {
        if (rankId == null || rankId.isEmpty()) { return true; }
        int need = data.read(s -> s.index(rankId));
        return need < 0 || index(p) >= need;
    }

    String rankTag(String rankId) {
        return data.read(s -> { int i = s.index(rankId); return i < 0 ? rankId : s.ranks.get(i).tag; }).replace('&', '§');
    }

    int progressPercent(Player p) {
        Rank n = next(p);
        if (n == null) { return 100; }
        EconomyService eco = getServer().getServiceManager().load(EconomyService.class);
        long have = eco == null ? 0 : eco.balance(p.getName(), Currency.MONEY);
        return n.price <= 0 ? 100 : (int) Math.min(100, have * 100 / n.price);
    }

    /** Permissoes do rank atual e de todos os anteriores, por anexo do proprio plugin (nunca OP nem cargo). */
    void applyPermissions(Player p) {
        PermissionAttachment old = attachments.remove(p);
        if (old != null) { try { p.removeAttachment(old); } catch (RuntimeException ignored) { } }
        int idx = index(p);
        PermissionAttachment a = p.addAttachment(this);
        data.read(s -> {
            for (int i = 0; i <= idx && i < s.ranks.size(); i++) {
                Rank r = s.ranks.get(i);
                a.setPermission("goldrankup.rank." + r.id, true);
                for (String perm : r.permissions) { if (RankState.safePermission(perm)) { a.setPermission(perm, true); } }
            }
            return null;
        });
        attachments.put(p, a);
    }

    // ---------- rankup ----------

    static String money(long cents) { return "$ " + BigDecimal.valueOf(cents, 2).toPlainString(); }

    private MenuService menus() { return getServer().getServiceManager().load(MenuService.class); }
    private static Item icon(int id, int meta, String name) { Item i = Item.get(id, meta, 1); i.setCustomName(name); return i; }

    void rankupMenu(Player p) {
        if (!loaded(p)) { p.sendMessage(PREFIX + M.t("carregando")); return; }
        Rank cur = current(p), nx = next(p);
        if (nx == null) { p.sendMessage(PREFIX + M.t("voce_ja_esta_no_rank_maximo")); return; }
        EconomyService eco = getServer().getServiceManager().load(EconomyService.class);
        long have = eco == null ? 0 : eco.balance(p.getName(), Currency.MONEY);
        Map<Integer, Item> icons = new HashMap<>();
        icons.put(11, icon(cur.iconId, cur.iconMeta, M.t("menu_rank_atual", cur.tag.replace('&', '§'))));
        icons.put(13, icon(nx.iconId, nx.iconMeta, M.t("menu_rank_proximo", nx.tag.replace('&', '§'), money(nx.price), nx.mine.isEmpty() ? "" : M.t("libera_mina", minaName(nx.mine)))));
        icons.put(15, icon(339, 0, M.t("menu_seu_saldo", money(have), progressPercent(p))));
        icons.put(21, icon(35, 5, have >= nx.price ? M.t("botao_confirmar_rankup", money(nx.price)) : M.t("botao_faltam", money(nx.price - have))));
        icons.put(23, icon(35, 14, M.t("botao_cancelar")));
        String expected = cur.id;
        menus().open(p, M.t("titulo_rankup"), icons, (who, slot) -> { if (slot == 21) { rankup(who, expected); } });
    }

    /** Cobra e sobe numa operacao so: o segundo toque e ignorado e, se a gravacao falhar, o dinheiro volta. */
    void rankup(Player p, String expectedCurrent) {
        String k = key(p);
        if (!loaded(p) || !busy.add(k)) { return; }
        Rank cur = current(p), nx = next(p);
        if (nx == null || !cur.id.equals(expectedCurrent)) { busy.remove(k); return; }
        EconomyService eco = getServer().getServiceManager().load(EconomyService.class);
        if (eco == null) { busy.remove(k); return; }
        if (eco.balance(p.getName(), Currency.MONEY) < nx.price) { busy.remove(k); p.sendMessage(PREFIX + M.t("dinheiro_insuficiente", money(nx.price - eco.balance(p.getName(), Currency.MONEY)))); return; }
        String name = p.getName();
        long price = nx.price;
        CompletableFuture<Void> paid = price > 0 ? eco.debit(name, Currency.MONEY, price) : CompletableFuture.completedFuture(null);
        paid.whenComplete((v, error) -> {
            if (error != null) { busy.remove(k); if (p.isOnline()) { p.sendMessage(PREFIX + M.t("dinheiro_insuficiente", money(price))); } return; }
            String first = data.read(st -> st.ranks.get(0).id);
            progress.change(k, pr -> {
                // Quem nunca subiu tem o rank vazio, que vale o primeiro da escada.
                String now = pr.rank.isEmpty() ? first : pr.rank;
                if (!now.equals(expectedCurrent)) { throw new IllegalStateException(M.t("rank_mudou")); }
                pr.rank = nx.id; pr.since = System.currentTimeMillis();
            }).whenComplete((saved, fail) -> {
                busy.remove(k);
                if (fail != null) {
                    if (price > 0) { eco.credit(name, Currency.MONEY, price); }
                    if (p.isOnline()) { p.sendMessage(PREFIX + M.t("rankup_falhou_dinheiro_devolvido")); }
                    return;
                }
                if (!p.isOnline()) { return; }
                applyPermissions(p);
                p.sendMessage(PREFIX + M.t("voce_subiu_para", nx.tag.replace('&', '§')));
                if (!nx.mine.isEmpty()) { p.sendMessage(PREFIX + M.t("mina_liberada", minaName(nx.mine))); }
                if (announce) { getServer().broadcastMessage(PREFIX + M.t("anuncio_rankup", p.getName(), nx.tag.replace('&', '§'))); }
                EffectService fx = getServer().getServiceManager().load(EffectService.class);
                if (fx != null && !DeviceService.low(p)) { fx.play(p.getPosition(), EffectService.Shape.CELEBRACAO, 40); }
            });
        });
    }

    String minaName(String id) { return data.read(s -> { Mine m = s.mines.get(id); return m == null ? id : m.name; }); }

    void ranksMenu(Player p) {
        if (!loaded(p)) { p.sendMessage(PREFIX + M.t("carregando")); return; }
        int idx = index(p), pct = progressPercent(p);
        List<Rank> ranks = data.read(s -> new ArrayList<>(s.ranks));
        Map<Integer, Item> icons = new HashMap<>();
        for (int i = 0; i < ranks.size() && i < 26; i++) {
            Rank r = ranks.get(i);
            String state = i < idx ? M.t("rank_conquistado") : i == idx ? M.t("rank_atual") : i == idx + 1 ? M.t("rank_proximo_pct", pct) : M.t("rank_bloqueado");
            icons.put(i, icon(r.iconId, r.iconMeta, M.t("rank_icone", r.tag.replace('&', '§'), money(r.price), state)));
        }
        icons.put(26, icon(35, 5, M.t("botao_ir_para_rankup")));
        menus().open(p, M.t("titulo_ranks"), icons, (who, slot) -> { if (slot == 26 || slot == idx + 1) { rankupMenu(who); } });
    }

    void minesMenu(Player p) {
        if (!loaded(p)) { p.sendMessage(PREFIX + M.t("carregando")); return; }
        List<Map.Entry<String, Mine>> list = data.read(s -> new ArrayList<>(s.mines.entrySet()));
        Map<Integer, Item> icons = new HashMap<>();
        Map<Integer, String> bySlot = new HashMap<>();
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 1, 2, 3, 4, 5, 6, 7};
        int i = 0;
        for (Map.Entry<String, Mine> e : list) {
            if (i >= slots.length) { break; }
            Mine m = e.getValue();
            boolean open = reached(p, m.rank);
            String line = !m.placed() ? M.t("mina_em_construcao") : open ? M.t("mina_liberada_toque") : M.t("mina_bloqueada_precisa", rankTag(m.rank));
            icons.put(slots[i], icon(open ? m.iconId : 101, open ? m.iconMeta : 0, M.t("mina_icone", m.name, line)));
            bySlot.put(slots[i], e.getKey());
            i++;
        }
        menus().open(p, M.t("titulo_minas"), icons, (who, slot) -> { String id = bySlot.get(slot); if (id != null) { goMine(who, id); } });
    }

    void goMine(Player p, String id) {
        Mine m = data.read(s -> s.mines.get(id));
        if (m == null || !m.placed()) { p.sendMessage(PREFIX + M.t("mina_em_construcao")); return; }
        if (!reached(p, m.rank)) { p.sendMessage(PREFIX + M.t("mina_bloqueada_precisa", rankTag(m.rank))); return; }
        TravelService travel = getServer().getServiceManager().load(TravelService.class);
        Destination to = Destination.position(m.world, m.tx, m.ty, m.tz, m.tyaw, 0);
        if (travel != null) { travel.travel(p, to, TravelService.Transition.PORTAL); }
        else { Level l = getServer().getLevelByName(m.world); if (l != null) { p.teleport(new cn.nukkit.level.Location(m.tx, m.ty, m.tz, m.tyaw, 0, l)); } }
    }

    // ---------- comandos ----------

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!data.isLoaded()) { sender.sendMessage(PREFIX + M.t("carregando")); return true; }
        Player p = sender instanceof Player ? (Player) sender : null;
        try {
            switch (command.getName()) {
                case "rankup": if (p != null) { rankupMenu(p); } return true;
                case "ranks": if (p != null) { ranksMenu(p); } return true;
                case "minas": if (p != null) { if (args.length > 0) { goMine(p, RankState.id(args[0])); } else { minesMenu(p); } } return true;
                case "rankadmin": rankAdmin(sender, args); return true;
                case "mina": mineAdmin(sender, args); return true;
                default: return false;
            }
        } catch (NumberFormatException e) { sender.sendMessage(PREFIX + M.t("numero_invalido")); return true; }
        catch (RuntimeException e) { sender.sendMessage(PREFIX + "§c" + root(e)); return true; }
    }

    private void rankAdmin(CommandSender sender, String[] a) {
        if (a.length < 2) { sender.sendMessage(PREFIX + M.t("uso_rankadmin")); return; }
        String who = a[1].toLowerCase(Locale.ROOT);
        if (!who.matches("[a-z0-9_]{3,16}")) { throw new IllegalArgumentException(M.t("jogador_invalido")); }
        String target;
        if (a[0].equalsIgnoreCase("set")) {
            String id = RankState.id(a.length > 2 ? a[2] : "");
            if (data.read(s -> s.index(id)) < 0) { throw new IllegalArgumentException(M.t("rank_inexistente", id)); }
            target = id;
        } else if (a[0].equalsIgnoreCase("reset")) { target = data.read(s -> s.ranks.get(0).id); }
        else { sender.sendMessage(PREFIX + M.t("uso_rankadmin")); return; }
        // Offline tambem: segura o registro so durante a gravacao.
        progress.retain(who).thenCompose(v -> progress.change(who, pr -> { pr.rank = target; pr.since = System.currentTimeMillis(); })).whenComplete((v, e) -> {
            progress.release(who);
            if (e != null) { sender.sendMessage(PREFIX + "§c" + root(e)); return; }
            Player online = getServer().getPlayerExact(who);
            if (online != null) { applyPermissions(online); online.sendMessage(PREFIX + M.t("seu_rank_agora", rankTag(target))); }
            sender.sendMessage(PREFIX + M.t("rank_definido", who, rankTag(target)));
        });
    }

    private void mineAdmin(CommandSender sender, String[] a) {
        String sub = a.length == 0 ? "lista" : a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "lista": {
                for (Map.Entry<String, Mine> e : data.read(s -> new ArrayList<>(s.mines.entrySet()))) {
                    Mine m = e.getValue(); Mines.Runtime rt = mines.rt(e.getKey());
                    sender.sendMessage(M.t("mina_linha", e.getKey(), m.name, m.placed() ? m.world + " " + m.x1 + "," + m.y1 + "," + m.z1 + " .. " + m.x2 + "," + m.y2 + "," + m.z2 : "-", rankTag(m.rank), m.minutes, m.percent, rt.mined * 100L / Math.max(1, m.volume())));
                }
                return;
            }
            case "gerarmundo": {
                // /mina gerarmundo <mundo>: monta patio, avenida e todas as minas da lista, ja configuradas.
                String world = Destination.world(a.length > 1 ? a[1] : "minas").target;
                if (getServer().getLevelByName(world) == null && !getServer().loadLevel(world)) { throw new IllegalArgumentException(M.t("mundo_inexistente", world)); }
                Level level = getServer().getLevelByName(world);
                List<String> ids = data.read(s -> new ArrayList<>(s.mines.keySet()));
                for (String line : mines.layout(world, level, ids)) { sender.sendMessage(M.t("mina_montada", line)); }
                return;
            }
            case "criar": {
                // /mina criar <id> <mundo> <x1> <y1> <z1> <x2> <y2> <z2>
                if (a.length < 9) { sender.sendMessage(PREFIX + M.t("uso_mina")); return; }
                String id = RankState.id(a[1]);
                String world = Destination.world(a[2]).target;
                int[] v = new int[6]; for (int i = 0; i < 6; i++) { v[i] = Integer.parseInt(a[3 + i]); }
                data.change(s -> {
                    Mine m = s.mines.get(id);
                    if (m == null) { if (s.mines.size() >= RankState.MAX_MINES) { throw new IllegalArgumentException(M.t("limite_de_minas")); } m = new Mine(); m.name = id; m.blocks = RankState.parts("1=100"); s.mines.put(id, m); }
                    m.world = world; m.x1 = Math.min(v[0], v[3]); m.x2 = Math.max(v[0], v[3]); m.y1 = Math.min(v[1], v[4]); m.y2 = Math.max(v[1], v[4]); m.z1 = Math.min(v[2], v[5]); m.z2 = Math.max(v[2], v[5]);
                    m.tx = (m.x1 + m.x2) / 2.0 + 0.5; m.ty = m.y2 + 2; m.tz = (m.z1 + m.z2) / 2.0 + 0.5;
                }).whenComplete((x, e) -> { mines.runtime.remove(id); sender.sendMessage(PREFIX + (e != null ? "§c" + root(e) : M.t("mina_salva", id))); });
                return;
            }
            case "composicao": case "rank": case "tempo": case "porcentagem": case "topo": case "nome": {
                String id = RankState.id(a.length > 1 ? a[1] : "");
                String rest = a.length > 2 ? String.join(" ", Arrays.copyOfRange(a, 2, a.length)) : "";
                if (rest.isEmpty()) { sender.sendMessage(PREFIX + M.t("uso_mina")); return; }
                data.change(s -> {
                    Mine m = s.mines.get(id);
                    if (m == null) { throw new IllegalArgumentException(M.t("mina_inexistente", id)); }
                    switch (sub) {
                        case "composicao": m.blocks = RankState.parts(rest); break;
                        case "rank": { String r = RankState.id(rest); if (s.index(r) < 0) { throw new IllegalArgumentException(M.t("rank_inexistente", r)); } m.rank = r; break; }
                        case "tempo": m.minutes = Integer.parseInt(rest.trim()); break;
                        case "porcentagem": m.percent = Integer.parseInt(rest.trim()); break;
                        case "nome": if (rest.length() > 24) { throw new IllegalArgumentException(M.t("nome_invalido")); } m.name = rest; break;
                        default: {
                            String[] c = rest.trim().split("\\s+");
                            m.tx = Double.parseDouble(c[0]); m.ty = Double.parseDouble(c[1]); m.tz = Double.parseDouble(c[2]); m.tyaw = c.length > 3 ? Float.parseFloat(c[3]) : 0;
                        }
                    }
                }).whenComplete((x, e) -> {
                    // Tempo menor vale ja: a proxima regeneracao nao espera o relogio antigo.
                    if (e == null && sub.equals("tempo")) { Mines.Runtime rt = mines.rt(id); long limit = System.currentTimeMillis() + Long.parseLong(rest.trim()) * 60_000L; if (rt.nextAt > limit) { rt.nextAt = limit; } }
                    sender.sendMessage(PREFIX + (e != null ? "§c" + root(e) : M.t("mina_salva", id)));
                });
                return;
            }
            case "resetar": {
                String id = RankState.id(a.length > 1 ? a[1] : "");
                sender.sendMessage(PREFIX + (mines.resetNow(id) ? M.t("mina_regenerando_agora", id) : M.t("mina_nao_regenerou", id)));
                return;
            }
            case "apagar": {
                String id = RankState.id(a.length > 1 ? a[1] : "");
                data.change(s -> { if (s.mines.remove(id) == null) { throw new IllegalArgumentException(M.t("mina_inexistente", id)); } })
                        .whenComplete((x, e) -> { mines.runtime.remove(id); sender.sendMessage(PREFIX + (e != null ? "§c" + root(e) : M.t("mina_apagada", id))); });
                return;
            }
            default: sender.sendMessage(PREFIX + M.t("uso_mina"));
        }
    }

    static String root(Throwable e) { while (e.getCause() != null) { e = e.getCause(); } return e.getMessage(); }
}
