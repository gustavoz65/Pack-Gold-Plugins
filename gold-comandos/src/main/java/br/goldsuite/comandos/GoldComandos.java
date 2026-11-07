package br.goldsuite.comandos;

import br.goldsuite.api.*;
import cn.nukkit.Player;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.command.ConsoleCommandSender;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.entity.EntityLevelChangeEvent;
import cn.nukkit.event.player.*;
import cn.nukkit.item.Item;
import cn.nukkit.level.Level;
import cn.nukkit.plugin.PluginBase;
import java.util.*;

/**
 * Comandos por zona (grupo de mundos): quais comandos cada mundo libera ou bloqueia, /help so com o que o jogador pode
 * usar ali, boas-vindas na primeira visita, itens da hotbar que executam comandos e OP so pelo console ou de um IP
 * autorizado. Cada comando digitado custa so duas consultas em mapas.
 */
public final class GoldComandos extends PluginBase implements Listener, ZoneService {
    static String PREFIX = M.t("comandos");
    private String blockedMessage = M.t("esse_comando_nao_esta_disponivel_aqui");
    private PersistentState<CmdState> data;
    /** Mundo (minusculo) -> zona; refeito a cada mudanca. */
    private Map<String, String> index = new HashMap<>();
    private final Map<Player, Integer> lastHeld = new IdentityHashMap<>();
    private final Map<Player, String> zoneOf = new IdentityHashMap<>();

    @Override public void onEnable() {
        M.B.attach(this);
        Settings.prefix(this, PREFIX, v -> PREFIX = v);
        Settings.text(this, "mensagem_bloqueado", "&cEsse comando nao esta disponivel aqui.", "Mensagem quando o comando nao e liberado no mundo", v -> blockedMessage = v);
        CoreService core = getServer().getServiceManager().load(CoreService.class);
        if (core == null) { throw new IllegalStateException(M.t("goldcore_precisa_estar_ativo_antes_do")); }
        data = new PersistentState<>(core.getStore(), "goldcomandos", CmdState.class, CmdState::new, CmdState::validate);
        data.ready().thenRun(this::reindex);
        getServer().getServiceManager().register(ZoneService.class, this, this);
        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override public void onDisable() { if (data != null) { data.close(); } lastHeld.clear(); zoneOf.clear(); }

    private void reindex() { if (data.isLoaded()) { index = data.read(CmdState::worldIndex); } }

    // ---------- servico de zonas (os outros plugins perguntam o que pode em cada mapa) ----------

    @Override public boolean allows(Level level, String feature) {
        if (!data.isLoaded() || level == null) { return true; }
        CmdState.Zone z = zone(level);
        return CmdState.allows(z, feature);
    }

    @Override public String[] hud(Level level) {
        if (!data.isLoaded() || level == null) { return null; }
        CmdState.Zone z = zone(level);
        return z == null || (z.hud1.isEmpty() && z.hud2.isEmpty()) ? null : new String[] {z.hud1, z.hud2};
    }

    /** Plugin dono do comando (comandos dos plugins Gold e de qualquer outro). */
    private static String owner(Command cmd) {
        return cmd instanceof cn.nukkit.command.PluginIdentifiableCommand ? ((cn.nukkit.command.PluginIdentifiableCommand) cmd).getPlugin().getName().toLowerCase(Locale.ROOT) : "";
    }

    private String zoneName(Level level) { return level == null ? null : index.get(level.getFolderName().toLowerCase(Locale.ROOT)); }
    private CmdState.Zone zone(Level level) { String n = zoneName(level); return n == null ? null : data.read(s -> s.zones.get(n)); }

    // ---------- comandos digitados ----------

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void command(PlayerCommandPreprocessEvent e) {
        if (!data.isLoaded()) { return; }
        Player p = e.getPlayer();
        String msg = e.getMessage();
        if (msg.length() < 2) { return; }
        String[] parts = msg.substring(1).trim().split("\\s+");
        String typed;
        try { typed = CmdState.command(parts[0]); } catch (IllegalArgumentException ex) { return; }
        Command cmd = getServer().getCommandMap().getCommand(parts[0].toLowerCase(Locale.ROOT));
        String real = cmd == null ? typed : cmd.getName().toLowerCase(Locale.ROOT);
        // OP so pelo console ou de um IP autorizado.
        if ((real.equals("op") || real.equals("deop")) && !data.read(s -> s.opAllowed(p.getName(), p.getAddress()))) {
            e.setCancelled();
            p.sendMessage(PREFIX + M.t("op_so_pode_ser_dado_pelo"));
            Settings.alert("Comandos", M.t("tentou_usar", p.getName(), p.getAddress(), real));
            return;
        }
        if (typed.equals("help") || typed.equals("?") || real.equals("help")) {
            e.setCancelled();
            int page = 1;
            try { page = parts.length > 1 ? Math.max(1, Integer.parseInt(parts[1])) : 1; } catch (NumberFormatException ignored) { }
            help(p, page);
            return;
        }
        if (real.equals("lobbies") || p.hasPermission("goldcomandos.bypass") || p.hasPermission("goldcomandos.cmd." + real)) { return; }
        CmdState.Zone z = zone(p.level);
        if (z != null && (!data.read(s -> s.allowed(z, typed, real)) || (cmd != null && z.plugins.contains(owner(cmd)) && !data.read(s -> s.always.contains(real))))) { e.setCancelled(); p.sendMessage(blockedMessage); }
    }

    /** /help so com os comandos que o jogador tem permissao e que a zona dele libera. */
    private void help(Player p, int page) {
        CmdState.Zone z = zone(p.level);
        boolean bypass = p.hasPermission("goldcomandos.bypass");
        TreeMap<String, String> list = new TreeMap<>();
        for (Command c : new HashSet<>(getServer().getCommandMap().getCommands().values())) {
            String name = c.getName().toLowerCase(Locale.ROOT);
            if (name.contains(":") || list.containsKey(name) || !c.testPermissionSilent(p)) { continue; }
            if (name.equals("op") || name.equals("deop")) { if (!data.read(s -> s.opAllowed(p.getName(), p.getAddress()))) { continue; } }
            if (!bypass && !p.hasPermission("goldcomandos.cmd." + name) && z != null && (!data.read(s -> s.allowed(z, name, name)) || (z.plugins.contains(owner(c)) && !data.read(s -> s.always.contains(name))))) { continue; }
            String d = c.getDescription() == null ? "" : getServer().getLanguage().translateString(c.getDescription());
            list.put(name, d.length() > 50 ? d.substring(0, 47) + "..." : d);
        }
        int per = 8, pages = Math.max(1, (list.size() + per - 1) / per);
        page = Math.min(page, pages);
        String zn = zoneName(p.level);
        p.sendMessage(M.t("comandos_pagina", (zn != null ? " (" + zn + ")" : ""), page, pages));
        if (z != null && page == 1) { for (String h : z.help) { p.sendMessage(h.replace('&', '§')); } }
        int i = 0;
        for (Map.Entry<String, String> e : list.entrySet()) {
            if (i++ < (page - 1) * per) { continue; }
            if (i > page * per) { break; }
            p.sendMessage("§e/" + e.getKey() + " §7" + e.getValue());
        }
        if (page < pages) { p.sendMessage(M.t("mais_help", (page + 1))); }
    }

    // ---------- OP por IP ----------

    @EventHandler(priority = EventPriority.HIGHEST)
    public void join(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (!data.isLoaded()) { return; }
        String name = p.getName().toLowerCase(Locale.ROOT);
        boolean listed = data.read(s -> !s.opIps.isEmpty() && s.opRequireIp);
        boolean ok = data.read(s -> s.opAllowed(name, p.getAddress()));
        if (listed && p.isOp() && !ok) {
            p.setOp(false);
            data.change(s -> s.suspended.add(name));
            Settings.alert("Comandos", M.t("op_de_suspenso_entrou_de_um", p.getName(), p.getAddress()));
        }
        enter(p, p.level, true, true);
        // O cliente so termina de receber o inventario depois de nascer: entrega de novo em seguida.
        regive(p, 20);
    }

    /** Entrega de novo os itens da zona onde o jogador esta (depois de nascer, renascer ou fazer login). */
    private void regive(Player p, int ticks) {
        getServer().getScheduler().scheduleDelayedTask(() -> {
            if (!isEnabled() || !p.isOnline() || !data.isLoaded()) { return; }
            // So entrega os itens da zona em que ele ja esta: a troca de zona (boas-vindas, tirar itens) fica com o enter.
            String now = zoneName(p.level);
            if (now == null || !now.equals(zoneOf.get(p))) { return; }
            CmdState.Zone z = data.read(s -> s.zones.get(now));
            if (z != null) { giveItems(p, z); }
        }, ticks);
    }

    /** Morrer limpa o inventario e renascer no mesmo mundo nao troca de zona: os itens precisam voltar. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void respawn(PlayerRespawnEvent e) { regive(e.getPlayer(), 5); }

    /** Itens da zona nunca caem no chao ao morrer. */
    @EventHandler(priority = EventPriority.HIGH)
    public void death(PlayerDeathEvent e) {
        Player p = e.getEntity();
        String zn = zoneOf.get(p);
        CmdState.Zone z = zn == null || !data.isLoaded() ? null : data.read(s -> s.zones.get(zn));
        if (z == null || z.items.isEmpty() || e.getDrops() == null) { return; }
        e.setDrops(Arrays.stream(e.getDrops()).filter(i -> z.items.values().stream().noneMatch(h -> same(i, h))).toArray(Item[]::new));
    }

    /**
     * OP suspenso volta so depois do login (GoldAuth) e de um IP autorizado. Nunca no join: antes do login qualquer
     * um pode usar o nick. Sem GoldAuth nao ha volta automatica (use op pelo console).
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void authenticated(br.goldsuite.api.event.PlayerAuthEvent e) {
        Player p = e.getPlayer();
        if (!data.isLoaded() || !p.isOnline()) { return; }
        regive(p, 10);
        String name = p.getName().toLowerCase(Locale.ROOT);
        if (!data.read(s -> s.suspended.contains(name) && s.opAllowed(name, p.getAddress()))) { return; }
        data.change(s -> s.suspended.remove(name)).whenComplete((v, error) -> {
            if (error != null || !p.isOnline()) { return; }
            p.setOp(true);
            p.sendMessage(PREFIX + M.t("seu_op_voltou_ip_autorizado"));
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void quit(PlayerQuitEvent e) { lastHeld.remove(e.getPlayer()); zoneOf.remove(e.getPlayer()); }

    // ---------- entrada nas zonas ----------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void changeWorld(EntityLevelChangeEvent e) {
        if (!(e.getEntity() instanceof Player)) { return; }
        Player p = (Player) e.getEntity();
        Level target = e.getTarget();
        // Espera o teleporte terminar para mexer na hotbar.
        getServer().getScheduler().scheduleDelayedTask(() -> { if (isEnabled() && p.isOnline()) { enter(p, target, false, true); } }, 5);
    }

    private void enter(Player p, Level level, boolean joining, boolean greet) {
        if (!data.isLoaded()) { return; }
        String before = zoneOf.get(p), now = zoneName(level);
        if (Objects.equals(before, now) && !joining) { return; }
        if (now == null) { zoneOf.remove(p); } else { zoneOf.put(p, now); }
        CmdState.Zone old = before == null ? null : data.read(s -> s.zones.get(before));
        CmdState.Zone z = now == null ? null : data.read(s -> s.zones.get(now));
        if (old != null && old != z) { removeItems(p, old); }
        // Itens de menu de outras zonas que ficaram salvos no inventario (ex.: entrou no servidor ja nas minas) tambem saem:
        // os menus da hotbar so existem nos lobbies que os configuram.
        removeForeign(p, z);
        if (z == null) { return; }
        giveItems(p, z);
        if (!greet || z.welcome.isEmpty()) { return; }
        String name = p.getName().toLowerCase(Locale.ROOT);
        boolean first = !data.read(s -> s.visited.getOrDefault(name, Collections.emptySet()).contains(now));
        if (first) { data.change(s -> s.visit(name, now)); }
        if (first || z.welcomeAlways) {
            String text = z.welcome.replace('&', '§').replace("{nome}", p.getName());
            p.sendMessage(text);
            p.sendPopup(text);
        }
    }

    // ---------- itens da hotbar ----------

    private static boolean same(Item it, CmdState.HubItem h) { return it != null && it.getId() == h.id && it.hasCustomName() && it.getCustomName().equals(h.name.replace('&', '§')); }

    private CmdState.HubItem hubItem(Player p, Item it) {
        String zn = zoneOf.get(p);
        if (zn == null || it == null || !it.hasCustomName()) { return null; }
        CmdState.Zone z = data.read(s -> s.zones.get(zn));
        if (z == null) { return null; }
        for (CmdState.HubItem h : z.items.values()) { if (same(it, h)) { return h; } }
        return null;
    }

    private void giveItems(Player p, CmdState.Zone z) {
        for (Map.Entry<Integer, CmdState.HubItem> e : z.items.entrySet()) {
            Item current = p.getInventory().getItem(e.getKey());
            if (current.getId() != 0 && !same(current, e.getValue())) { continue; }
            Item it = Item.get(e.getValue().id, e.getValue().meta, 1);
            it.setCustomName(e.getValue().name.replace('&', '§'));
            p.getInventory().setItem(e.getKey(), it);
        }
    }

    private void removeItems(Player p, CmdState.Zone z) {
        for (Map.Entry<Integer, Item> e : new HashMap<>(p.getInventory().getContents()).entrySet()) {
            for (CmdState.HubItem h : z.items.values()) { if (same(e.getValue(), h)) { p.getInventory().clear(e.getKey()); } }
        }
    }

    private void removeForeign(Player p, CmdState.Zone current) {
        List<CmdState.HubItem> foreign = data.read(s -> {
            List<CmdState.HubItem> out = new ArrayList<>();
            for (CmdState.Zone other : s.zones.values()) { if (other != current) { out.addAll(other.items.values()); } }
            return out;
        });
        if (foreign.isEmpty()) { return; }
        for (Map.Entry<Integer, Item> e : new HashMap<>(p.getInventory().getContents()).entrySet()) {
            Item it = e.getValue();
            if (!it.hasCustomName()) { continue; }
            boolean mine = false;
            if (current != null) { for (CmdState.HubItem h : current.items.values()) { if (same(it, h)) { mine = true; break; } } }
            if (mine) { continue; }
            for (CmdState.HubItem h : foreign) { if (same(it, h)) { p.getInventory().clear(e.getKey()); break; } }
        }
    }

    private void run(Player p, CmdState.HubItem h) { getServer().dispatchCommand(p, h.command.replace("{nome}", p.getName())); }

    @EventHandler(priority = EventPriority.LOW)
    public void interact(PlayerInteractEvent e) {
        if (e.getAction() == PlayerInteractEvent.PHYSICAL) { return; }
        CmdState.HubItem h = hubItem(e.getPlayer(), e.getPlayer().getInventory().getItemInHand());
        if (h == null) { return; }
        e.setCancelled();
        if (HotbarAction.tap(e.getPlayer())) { run(e.getPlayer(), h); }
    }

    /**
     * Por padrao o item age ao tocar no chao com ele (interact acima). Com hotbar.ao_selecionar = 1 no painel do GoldCore,
     * selecionar o slot ja executa e a hotbar volta ao slot anterior (o jeito antigo).
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void held(PlayerItemHeldEvent e) {
        Player p = e.getPlayer();
        CmdState.HubItem h = hubItem(p, e.getItem());
        if (h == null) { lastHeld.put(p, p.getInventory().getHeldItemIndex()); return; }
        if (!HotbarAction.onSelect()) { return; }
        Integer before = lastHeld.get(p);
        int slot = p.getInventory().getHeldItemIndex();
        int back = before == null || before == slot ? (slot == 0 ? 1 : 0) : before;
        run(p, h);
        getServer().getScheduler().scheduleDelayedTask(() -> {
            if (!isEnabled() || !p.isOnline() || hubItem(p, p.getInventory().getItemInHand()) == null) { return; }
            p.getInventory().setHeldItemIndex(back);
            p.getInventory().sendHeldItem(p);
        }, 2);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void drop(PlayerDropItemEvent e) { if (hubItem(e.getPlayer(), e.getItem()) != null) { e.setCancelled(); } }

    // ---------- seletor de lobbies (/lobbies) ----------

    private static final java.util.regex.Pattern WORLD_COUNT = java.util.regex.Pattern.compile("\\{mundo:([A-Za-z0-9_][A-Za-z0-9_.-]{0,31})}");

    /** Abre o menu do seletor; o icone tocado executa o comando dele como o jogador. */
    private void lobbies(CommandSender sender) {
        if (!(sender instanceof Player)) { sender.sendMessage(M.t("use_dentro_do_jogo")); return; }
        Player p = (Player) sender;
        Map<Integer, CmdState.HubItem> entries = data.read(s -> new TreeMap<>(s.selector));
        if (entries.isEmpty()) { p.sendMessage(PREFIX + M.t("seletor_vazio")); return; }
        MenuService menus = getServer().getServiceManager().load(MenuService.class);
        if (menus == null) { return; }
        Map<Integer, Item> icons = new HashMap<>();
        for (Map.Entry<Integer, CmdState.HubItem> e : entries.entrySet()) {
            Item it = Item.get(e.getValue().id, e.getValue().meta, 1);
            it.setCustomName(counts(e.getValue().name).replace('&', '§').replace("|", "\n"));
            icons.put(e.getKey(), it);
        }
        menus.open(p, M.t("seletor_titulo"), icons, (who, slot) -> { CmdState.HubItem h = entries.get(slot); if (h != null) { run(who, h); } });
    }

    /** {mundo:Nome} vira quantos jogadores estao nesse mundo agora; {online} e {max} tambem valem. */
    private String counts(String text) {
        if (text.indexOf('{') < 0) { return text; }
        String out = text.replace("{online}", Integer.toString(getServer().getOnlinePlayers().size())).replace("{max}", Integer.toString(getServer().getMaxPlayers()));
        java.util.regex.Matcher m = WORLD_COUNT.matcher(out);
        StringBuffer sb = new StringBuffer();
        while (m.find()) { Level l = getServer().getLevelByName(m.group(1)); m.appendReplacement(sb, Integer.toString(l == null ? 0 : l.getPlayers().size())); }
        m.appendTail(sb);
        return sb.toString();
    }

    // ---------- administracao ----------

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!data.isLoaded()) { sender.sendMessage(PREFIX + M.t("carregando")); return true; }
        try {
            if (command.getName().equals("opseguro")) { opseguro(sender, args); return true; }
            if (command.getName().equals("lobbies")) { lobbies(sender); return true; }
            zona(sender, args);
        } catch (NumberFormatException e) { sender.sendMessage(PREFIX + M.t("numero_invalido")); }
        catch (RuntimeException e) { sender.sendMessage(PREFIX + "§c" + root(e)); }
        return true;
    }

    private void change(CommandSender sender, java.util.function.Consumer<CmdState> mutation, String done) {
        data.change(mutation).whenComplete((v, e) -> getServer().getScheduler().scheduleTask(() -> {
            if (!isEnabled()) { return; }
            if (e != null) { sender.sendMessage(PREFIX + "§c" + root(e)); return; }
            reindex();
            for (Player p : getServer().getOnlinePlayers().values()) { enter(p, p.level, true, false); }
            sender.sendMessage(PREFIX + "§a" + done);
        }));
    }

    private static CmdState.Zone need(CmdState s, String z) { CmdState.Zone zone = s.zones.get(z); if (zone == null) { throw new IllegalArgumentException(M.t("zona_inexistente", z)); } return zone; }
    private static String rest(String[] a, int from) { return String.join(" ", Arrays.copyOfRange(a, from, a.length)); }

    private void zona(CommandSender sender, String[] a) {
        String sub = a.length > 0 ? a[0].toLowerCase(Locale.ROOT) : "lista";
        String z = a.length > 1 ? CmdState.zone(a[1]) : "";
        switch (sub) {
            case "lista": {
                sender.sendMessage(PREFIX + M.t("zonas"));
                for (Map.Entry<String, CmdState.Zone> e : data.read(s -> new ArrayList<>(s.zones.entrySet()))) {
                    sender.sendMessage(M.t("mundos_comando_item_ns", e.getKey(), e.getValue().worlds, (e.getValue().whitelist ? M.t("so_libera") : M.t("bloqueia")), e.getValue().commands.size(), e.getValue().items.size()));
                }
                sender.sendMessage(M.t("sempre_liberados", data.<String>read(s -> String.join(", ", s.always))));
                return;
            }
            case "ver": {
                CmdState.Zone zone = data.read(s -> need(s, z));
                sender.sendMessage(M.t("zona", z));
                sender.sendMessage(M.t("mundos", zone.worlds));
                sender.sendMessage(M.t("modo", (zone.whitelist ? M.t("so_os_comandos_da_lista_funcionam") : M.t("os_comandos_da_lista_ficam_bloqueados")), zone.commands));
                sender.sendMessage(M.t("boas_vindas", (zone.welcomeAlways ? M.t("sempre") : M.t("primeira_vez")), zone.welcome.replace('&', '§')));
                for (Map.Entry<Integer, CmdState.HubItem> e : zone.items.entrySet()) { sender.sendMessage(M.t("slot", e.getKey() + 1, e.getValue().name.replace('&', '§'), e.getValue().id, e.getValue().meta, e.getValue().command)); }
                for (String h : zone.help) { sender.sendMessage(M.t("ajuda", h.replace('&', '§'))); }
                return;
            }
            case "criar": change(sender, s -> { if (s.zones.containsKey(z)) { throw new IllegalArgumentException(M.t("zona_ja_existe")); } s.zones.put(z, new CmdState.Zone()); }, M.t("zona_criada_adicione_mundos_zona_mundo", z, z)); return;
            case "apagar": change(sender, s -> { if (s.zones.remove(z) == null) { throw new IllegalArgumentException(M.t("zona_inexistente_2")); } }, "Zona " + z + " apagada."); return;
            case "mundo": {
                String w = a.length > 2 ? a[2] : "";
                boolean exists = getServer().getLevelByName(w) != null || new java.io.File(getServer().getDataPath() + "worlds/" + w).isDirectory();
                // Mundo apagado ou renomeado ainda pode sair da zona; so nao pode entrar um que nao existe.
                if (w.isEmpty() || !exists && !data.read(s -> s.zones.containsKey(z) && s.zones.get(z).worlds.contains(w))) { throw new IllegalArgumentException(M.t("mundo_inexistente", w)); }
                change(sender, s -> {
                    CmdState.Zone zone = need(s, z);
                    if (!zone.worlds.remove(w)) {
                        for (Map.Entry<String, CmdState.Zone> e : s.zones.entrySet()) { if (e.getValue().worlds.stream().anyMatch(x -> x.equalsIgnoreCase(w))) { throw new IllegalArgumentException(M.t("mundo_ja_esta_na_zona", e.getKey())); } }
                        zone.worlds.add(w);
                    }
                }, M.t("mundos_da_zona_atualizados", z));
                return;
            }
            case "modo": {
                boolean wl = a.length > 2 && a[2].toLowerCase(Locale.ROOT).startsWith("lib");
                change(sender, s -> need(s, z).whitelist = wl, wl ? M.t("zona_so_os_comandos_da_lista", z, z) : M.t("zona_os_comandos_da_lista_ficam", z));
                return;
            }
            case "cmd": {
                if (a.length < 3) { throw new IllegalArgumentException(M.t("uso_zona_cmd_zona_comando_alterna")); }
                List<String> cmds = new ArrayList<>();
                for (int i = 2; i < a.length; i++) { cmds.add(CmdState.command(a[i])); }
                change(sender, s -> { Set<String> set = need(s, z).commands; for (String c : cmds) { if (!set.remove(c)) { set.add(c); } } if (set.size() > CmdState.MAX_COMMANDS) { throw new IllegalArgumentException(M.t("comandos_demais")); } }, M.t("lista_da_zona_atualizada_zona_ver", z, z));
                return;
            }
            case "plugin": {
                String pl = CmdState.plugin(a.length > 2 ? a[2] : "");
                change(sender, s -> { Set<String> set = need(s, z).plugins; if (!set.remove(pl)) { set.add(pl); } }, M.t("plugins_desligados_na_zona_atualizados_zona", z, z));
                return;
            }
            case "recurso": {
                String f = CmdState.feature(a.length > 2 ? a[2] : "");
                change(sender, s -> { Set<String> set = need(s, z).features; if (!set.remove(f)) { set.add(f); } }, M.t("recursos_desligados_na_zona_atualizados", z));
                return;
            }
            case "recursos": {
                sender.sendMessage(PREFIX + M.t("recursos_que_da_para_desligar_por"));
                for (String[] f : CmdState.FEATURES) { sender.sendMessage("\u00a7e" + f[0] + " \u00a77" + f[1]); }
                sender.sendMessage(M.t("para_desligar_um_plugin_inteiro_comandos"));
                return;
            }
            case "hud": {
                int line = Integer.parseInt(a.length > 2 ? a[2] : "0");
                String text = rest(a, 3);
                if ((line != 1 && line != 2) || text.length() > 200) { throw new IllegalArgumentException(M.t("uso_zona_hud_zona_1_2")); }
                change(sender, s -> { CmdState.Zone zone = need(s, z); String v = text.equalsIgnoreCase("padrao") ? "" : text; if (line == 1) { zone.hud1 = v; } else { zone.hud2 = v; } }, M.t("barra_da_zona_atualizada", z));
                return;
            }
            case "sempre": {
                String c = CmdState.command(a.length > 1 ? a[1] : "");
                change(sender, s -> { if (!s.always.remove(c)) { s.always.add(c); } }, M.t("comandos_sempre_liberados_atualizados"));
                return;
            }
            case "boasvindas": {
                String text = rest(a, 2);
                if (text.length() > 200) { throw new IllegalArgumentException(M.t("ate_200_caracteres")); }
                change(sender, s -> need(s, z).welcome = text.equalsIgnoreCase("nenhuma") ? "" : text, M.t("boas_vindas_da_zona_salva_nome", z));
                return;
            }
            case "boasvindas_sempre": {
                boolean on = a.length > 2 && (a[2].equalsIgnoreCase("on") || a[2].equalsIgnoreCase("sim"));
                change(sender, s -> need(s, z).welcomeAlways = on, on ? M.t("boas_vindas_cada_entrada") : M.t("boas_vindas_so_na_primeira_visita"));
                return;
            }
            case "ajuda": {
                String text = rest(a, 2);
                change(sender, s -> { List<String> h = need(s, z).help; if (text.equalsIgnoreCase("limpar")) { h.clear(); } else { if (text.isEmpty() || text.length() > 150 || h.size() >= 15) { throw new IllegalArgumentException(M.t("linha_de_1_150_caracteres_ate")); } h.add(text); } }, M.t("ajuda_da_zona_atualizada", z));
                return;
            }
            case "item": {
                if (a.length < 6) { throw new IllegalArgumentException(M.t("uso_zona_item_zona_slot_1")); }
                int slot = Integer.parseInt(a[2]) - 1;
                String[] id = a[3].split(":");
                CmdState.HubItem h = new CmdState.HubItem(Integer.parseInt(id[0]), id.length > 1 ? Integer.parseInt(id[1]) : 0, a[4].replace('_', ' '), rest(a, 5).replaceFirst("^/", ""));
                change(sender, s -> { if (slot < 0 || slot > 8) { throw new IllegalArgumentException(M.t("slot_de_1_9")); } need(s, z).items.put(slot, h); }, M.t("item_no_slot_da_zona_executa", (slot + 1), z, h.command));
                return;
            }
            case "itemremover": {
                int slot = Integer.parseInt(a.length > 2 ? a[2] : "0") - 1;
                change(sender, s -> { if (need(s, z).items.remove(slot) == null) { throw new IllegalArgumentException(M.t("nao_ha_item_nesse_slot")); } }, M.t("item_removido"));
                return;
            }
            case "seletor": {
                if (a.length < 2) {
                    sender.sendMessage(PREFIX + M.t("seletor_lista"));
                    for (Map.Entry<Integer, CmdState.HubItem> e : data.read(s -> new ArrayList<>(s.selector.entrySet()))) { sender.sendMessage(M.t("seletor_linha", e.getKey() + 1, e.getValue().name.replace('&', '§').replace("|", " / "), e.getValue().id, e.getValue().meta, e.getValue().command)); }
                    sender.sendMessage(M.t("uso_zona_seletor"));
                    return;
                }
                if (a[1].equalsIgnoreCase("remover")) {
                    int pos = Integer.parseInt(a.length > 2 ? a[2] : "0") - 1;
                    change(sender, s -> { if (s.selector.remove(pos) == null) { throw new IllegalArgumentException(M.t("nao_ha_icone_nessa_posicao")); } }, M.t("icone_removido"));
                    return;
                }
                if (a.length < 5) { throw new IllegalArgumentException(M.t("uso_zona_seletor")); }
                int pos = Integer.parseInt(a[1]) - 1;
                String[] id = a[2].split(":");
                CmdState.HubItem h = new CmdState.HubItem(Integer.parseInt(id[0]), id.length > 1 ? Integer.parseInt(id[1]) : 0, a[3].replace('_', ' '), rest(a, 4).replaceFirst("^/", ""));
                if (pos < 0 || pos >= CmdState.SELECTOR_SIZE) { throw new IllegalArgumentException(M.t("posicao_de_1_27")); }
                change(sender, s -> { s.selector.put(pos, h); CmdState.validate(s); }, M.t("icone_do_seletor_executa", pos + 1, h.command));
                return;
            }
            default:
                sender.sendMessage(PREFIX + M.t("zona_lista_ver_zona_criar_zona"));
                sender.sendMessage(PREFIX + M.t("zona_plugin_zona_plugin_desliga_plugin"));
                sender.sendMessage(PREFIX + M.t("zona_boasvindas_zona_texto_nenhuma_boasvindas"));
        }
    }

    /** So o console (ou quem ja esta na lista, do proprio IP) mexe na lista de IPs do OP. */
    private void opseguro(CommandSender sender, String[] a) {
        boolean console = sender instanceof ConsoleCommandSender;
        if (!console && !(sender instanceof Player && data.read(s -> s.opAllowed(sender.getName(), ((Player) sender).getAddress())))) {
            sender.sendMessage(PREFIX + M.t("so_pelo_console_do_servidor"));
            return;
        }
        String sub = a.length > 0 ? a[0].toLowerCase(Locale.ROOT) : "lista";
        switch (sub) {
            case "add": {
                if (a.length < 3) { throw new IllegalArgumentException(M.t("uso_opseguro_add_nick_ip")); }
                String nick = a[1].toLowerCase(Locale.ROOT), ip = CmdState.ip(a[2]);
                change(sender, s -> { Set<String> set = s.opIps.computeIfAbsent(nick, k -> new TreeSet<>()); set.add(ip); if (set.size() > 10) { throw new IllegalArgumentException(M.t("ate_10_ips_por_nick")); } }, M.t("pode_usar_op_pelo_ip", nick, ip));
                return;
            }
            case "remover": {
                if (a.length < 2) { throw new IllegalArgumentException(M.t("uso_opseguro_remover_nick_ip")); }
                String nick = a[1].toLowerCase(Locale.ROOT);
                String ip = a.length > 2 ? CmdState.ip(a[2]) : null;
                change(sender, s -> { Set<String> set = s.opIps.get(nick); if (set == null) { throw new IllegalArgumentException(M.t("nick_fora_da_lista")); } if (ip == null || (set.remove(ip) && set.isEmpty())) { s.opIps.remove(nick); } }, M.t("lista_de_op_atualizada"));
                return;
            }
            case "exigir": {
                boolean on = a.length > 1 && (a[1].equalsIgnoreCase("on") || a[1].equalsIgnoreCase("sim"));
                change(sender, s -> s.opRequireIp = on, on ? M.t("op_fora_da_lista_de_ips") : M.t("op_nao_mais_suspenso_por_ip"));
                return;
            }
            default:
                sender.sendMessage(PREFIX + M.t("ips_do_op", (data.<Boolean>read(s -> s.opRequireIp) ? M.t("exigido") : M.t("nao_exigido")), data.read(s -> s.opIps.toString())));
                sender.sendMessage(PREFIX + M.t("opseguro_add_nick_ip_remover_nick"));
        }
    }

    static String root(Throwable e) { while (e.getCause() != null) { e = e.getCause(); } return e.getMessage(); }
}
