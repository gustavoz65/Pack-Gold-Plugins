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
public final class GoldComandos extends PluginBase implements Listener {
    static String PREFIX = "§6[Comandos]§f ";
    private String blockedMessage = "§cEsse comando nao esta disponivel aqui.";
    private PersistentState<CmdState> data;
    /** Mundo (minusculo) -> zona; refeito a cada mudanca. */
    private Map<String, String> index = new HashMap<>();
    private final Map<Player, Integer> lastHeld = new IdentityHashMap<>();
    private final Map<Player, String> zoneOf = new IdentityHashMap<>();

    @Override public void onEnable() {
        Settings.prefix(this, PREFIX, v -> PREFIX = v);
        Settings.text(this, "mensagem_bloqueado", "&cEsse comando nao esta disponivel aqui.", "Mensagem quando o comando nao e liberado no mundo", v -> blockedMessage = v);
        CoreService core = getServer().getServiceManager().load(CoreService.class);
        if (core == null) { throw new IllegalStateException("GoldCore precisa estar ativo antes do GoldComandos."); }
        data = new PersistentState<>(core.getStore(), "goldcomandos", CmdState.class, CmdState::new, CmdState::validate);
        data.ready().thenRun(this::reindex);
        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override public void onDisable() { if (data != null) { data.close(); } lastHeld.clear(); zoneOf.clear(); }

    private void reindex() { if (data.isLoaded()) { index = data.read(CmdState::worldIndex); } }

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
            p.sendMessage(PREFIX + "§cOP so pode ser dado pelo console ou de um IP autorizado (/opseguro).");
            Settings.alert("Comandos", p.getName() + " (" + p.getAddress() + ") tentou usar /" + real + ".");
            return;
        }
        if (typed.equals("help") || typed.equals("?") || real.equals("help")) {
            e.setCancelled();
            int page = 1;
            try { page = parts.length > 1 ? Math.max(1, Integer.parseInt(parts[1])) : 1; } catch (NumberFormatException ignored) { }
            help(p, page);
            return;
        }
        if (p.hasPermission("goldcomandos.bypass") || p.hasPermission("goldcomandos.cmd." + real)) { return; }
        CmdState.Zone z = zone(p.level);
        if (z != null && !data.read(s -> s.allowed(z, typed, real))) { e.setCancelled(); p.sendMessage(blockedMessage); }
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
            if (!bypass && !p.hasPermission("goldcomandos.cmd." + name) && z != null && !data.read(s -> s.allowed(z, name, name))) { continue; }
            String d = c.getDescription() == null ? "" : getServer().getLanguage().translateString(c.getDescription());
            list.put(name, d.length() > 50 ? d.substring(0, 47) + "..." : d);
        }
        int per = 8, pages = Math.max(1, (list.size() + per - 1) / per);
        page = Math.min(page, pages);
        String zn = zoneName(p.level);
        p.sendMessage("§6--- Comandos" + (zn != null ? " (" + zn + ")" : "") + " - pagina " + page + "/" + pages + " ---");
        if (z != null && page == 1) { for (String h : z.help) { p.sendMessage(h.replace('&', '§')); } }
        int i = 0;
        for (Map.Entry<String, String> e : list.entrySet()) {
            if (i++ < (page - 1) * per) { continue; }
            if (i > page * per) { break; }
            p.sendMessage("§e/" + e.getKey() + " §7" + e.getValue());
        }
        if (page < pages) { p.sendMessage("§7Mais: /help " + (page + 1)); }
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
            Settings.alert("Comandos", "OP de " + p.getName() + " suspenso: entrou de um IP nao autorizado (" + p.getAddress() + ").");
        } else if (ok && data.read(s -> s.suspended.contains(name))) {
            p.setOp(true);
            data.change(s -> s.suspended.remove(name));
            p.sendMessage(PREFIX + "§aSeu OP voltou (IP autorizado).");
        }
        enter(p, p.level, true, true);
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

    private void run(Player p, CmdState.HubItem h) { getServer().dispatchCommand(p, h.command.replace("{nome}", p.getName())); }

    @EventHandler(priority = EventPriority.LOW)
    public void interact(PlayerInteractEvent e) {
        if (e.getAction() == PlayerInteractEvent.PHYSICAL) { return; }
        CmdState.HubItem h = hubItem(e.getPlayer(), e.getPlayer().getInventory().getItemInHand());
        if (h == null) { return; }
        e.setCancelled();
        run(e.getPlayer(), h);
    }

    /** No 0.15 tocar no ar com item comum nao chega ao servidor: selecionar o item ja executa e a hotbar volta. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void held(PlayerItemHeldEvent e) {
        Player p = e.getPlayer();
        CmdState.HubItem h = hubItem(p, e.getItem());
        if (h == null) { lastHeld.put(p, p.getInventory().getHeldItemIndex()); return; }
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

    // ---------- administracao ----------

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!data.isLoaded()) { sender.sendMessage(PREFIX + "Carregando..."); return true; }
        try {
            if (command.getName().equals("opseguro")) { opseguro(sender, args); return true; }
            zona(sender, args);
        } catch (NumberFormatException e) { sender.sendMessage(PREFIX + "§cNumero invalido."); }
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

    private static CmdState.Zone need(CmdState s, String z) { CmdState.Zone zone = s.zones.get(z); if (zone == null) { throw new IllegalArgumentException("Zona inexistente: " + z); } return zone; }
    private static String rest(String[] a, int from) { return String.join(" ", Arrays.copyOfRange(a, from, a.length)); }

    private void zona(CommandSender sender, String[] a) {
        String sub = a.length > 0 ? a[0].toLowerCase(Locale.ROOT) : "lista";
        String z = a.length > 1 ? CmdState.zone(a[1]) : "";
        switch (sub) {
            case "lista": {
                sender.sendMessage(PREFIX + "Zonas:");
                for (Map.Entry<String, CmdState.Zone> e : data.read(s -> new ArrayList<>(s.zones.entrySet()))) {
                    sender.sendMessage("§e" + e.getKey() + " §7mundos " + e.getValue().worlds + ", " + (e.getValue().whitelist ? "so libera " : "bloqueia ") + e.getValue().commands.size() + " comando(s), " + e.getValue().items.size() + " item(ns)");
                }
                sender.sendMessage("§7Sempre liberados: " + data.read(s -> String.join(", ", s.always)));
                return;
            }
            case "ver": {
                CmdState.Zone zone = data.read(s -> need(s, z));
                sender.sendMessage("§6--- Zona " + z + " ---");
                sender.sendMessage("§fMundos: §7" + zone.worlds);
                sender.sendMessage("§fModo: §7" + (zone.whitelist ? "so os comandos da lista funcionam" : "os comandos da lista ficam bloqueados") + ": " + zone.commands);
                sender.sendMessage("§fBoas-vindas" + (zone.welcomeAlways ? " (sempre)" : " (primeira vez)") + ": §r" + zone.welcome.replace('&', '§'));
                for (Map.Entry<Integer, CmdState.HubItem> e : zone.items.entrySet()) { sender.sendMessage("§fSlot " + e.getKey() + ": §r" + e.getValue().name.replace('&', '§') + " §7(" + e.getValue().id + ":" + e.getValue().meta + ") -> /" + e.getValue().command); }
                for (String h : zone.help) { sender.sendMessage("§fAjuda: §r" + h.replace('&', '§')); }
                return;
            }
            case "criar": change(sender, s -> { if (s.zones.containsKey(z)) { throw new IllegalArgumentException("Zona ja existe."); } s.zones.put(z, new CmdState.Zone()); }, "Zona " + z + " criada. Adicione mundos: /zona mundo " + z + " <mundo>"); return;
            case "apagar": change(sender, s -> { if (s.zones.remove(z) == null) { throw new IllegalArgumentException("Zona inexistente."); } }, "Zona " + z + " apagada."); return;
            case "mundo": {
                String w = a.length > 2 ? a[2] : "";
                if (w.isEmpty() || getServer().getLevelByName(w) == null && !new java.io.File(getServer().getDataPath() + "worlds/" + w).isDirectory()) { throw new IllegalArgumentException("Mundo inexistente: " + w); }
                change(sender, s -> {
                    CmdState.Zone zone = need(s, z);
                    if (!zone.worlds.remove(w)) {
                        for (Map.Entry<String, CmdState.Zone> e : s.zones.entrySet()) { if (e.getValue().worlds.stream().anyMatch(x -> x.equalsIgnoreCase(w))) { throw new IllegalArgumentException("O mundo ja esta na zona " + e.getKey() + "."); } }
                        zone.worlds.add(w);
                    }
                }, "Mundos da zona " + z + " atualizados.");
                return;
            }
            case "modo": {
                boolean wl = a.length > 2 && a[2].toLowerCase(Locale.ROOT).startsWith("lib");
                change(sender, s -> need(s, z).whitelist = wl, wl ? "Zona " + z + ": so os comandos da lista funcionam (/zona cmd " + z + " <comando>)." : "Zona " + z + ": os comandos da lista ficam bloqueados.");
                return;
            }
            case "cmd": {
                if (a.length < 3) { throw new IllegalArgumentException("Uso: /zona cmd <zona> <comando...> (alterna cada um)"); }
                List<String> cmds = new ArrayList<>();
                for (int i = 2; i < a.length; i++) { cmds.add(CmdState.command(a[i])); }
                change(sender, s -> { Set<String> set = need(s, z).commands; for (String c : cmds) { if (!set.remove(c)) { set.add(c); } } if (set.size() > CmdState.MAX_COMMANDS) { throw new IllegalArgumentException("Comandos demais."); } }, "Lista da zona " + z + " atualizada (/zona ver " + z + ").");
                return;
            }
            case "sempre": {
                String c = CmdState.command(a.length > 1 ? a[1] : "");
                change(sender, s -> { if (!s.always.remove(c)) { s.always.add(c); } }, "Comandos sempre liberados atualizados.");
                return;
            }
            case "boasvindas": {
                String text = rest(a, 2);
                if (text.length() > 200) { throw new IllegalArgumentException("Ate 200 caracteres."); }
                change(sender, s -> need(s, z).welcome = text.equalsIgnoreCase("nenhuma") ? "" : text, "Boas-vindas da zona " + z + " salva. {nome} vira o nick.");
                return;
            }
            case "boasvindas_sempre": {
                boolean on = a.length > 2 && (a[2].equalsIgnoreCase("on") || a[2].equalsIgnoreCase("sim"));
                change(sender, s -> need(s, z).welcomeAlways = on, on ? "Boas-vindas a cada entrada." : "Boas-vindas so na primeira visita.");
                return;
            }
            case "ajuda": {
                String text = rest(a, 2);
                change(sender, s -> { List<String> h = need(s, z).help; if (text.equalsIgnoreCase("limpar")) { h.clear(); } else { if (text.isEmpty() || text.length() > 150 || h.size() >= 15) { throw new IllegalArgumentException("Linha de 1 a 150 caracteres, ate 15 linhas."); } h.add(text); } }, "Ajuda da zona " + z + " atualizada.");
                return;
            }
            case "item": {
                if (a.length < 6) { throw new IllegalArgumentException("Uso: /zona item <zona> <slot 1-9> <id[:meta]> <nome_com_underline> <comando...>"); }
                int slot = Integer.parseInt(a[2]) - 1;
                String[] id = a[3].split(":");
                CmdState.HubItem h = new CmdState.HubItem(Integer.parseInt(id[0]), id.length > 1 ? Integer.parseInt(id[1]) : 0, a[4].replace('_', ' '), rest(a, 5).replaceFirst("^/", ""));
                change(sender, s -> { if (slot < 0 || slot > 8) { throw new IllegalArgumentException("Slot de 1 a 9."); } need(s, z).items.put(slot, h); }, "Item no slot " + (slot + 1) + " da zona " + z + " executa /" + h.command + ".");
                return;
            }
            case "itemremover": {
                int slot = Integer.parseInt(a.length > 2 ? a[2] : "0") - 1;
                change(sender, s -> { if (need(s, z).items.remove(slot) == null) { throw new IllegalArgumentException("Nao ha item nesse slot."); } }, "Item removido.");
                return;
            }
            default:
                sender.sendMessage(PREFIX + "/zona lista | ver <zona> | criar <zona> | apagar <zona> | mundo <zona> <mundo> | modo <zona> liberar|bloquear | cmd <zona> <comandos...>");
                sender.sendMessage(PREFIX + "/zona boasvindas <zona> <texto|nenhuma> | boasvindas_sempre <zona> on|off | ajuda <zona> <linha|limpar> | item <zona> <slot> <id[:meta]> <nome> <comando> | itemremover <zona> <slot> | sempre <comando>");
        }
    }

    /** So o console (ou quem ja esta na lista, do proprio IP) mexe na lista de IPs do OP. */
    private void opseguro(CommandSender sender, String[] a) {
        boolean console = sender instanceof ConsoleCommandSender;
        if (!console && !(sender instanceof Player && data.read(s -> s.opAllowed(sender.getName(), ((Player) sender).getAddress())))) {
            sender.sendMessage(PREFIX + "§cSo pelo console do servidor.");
            return;
        }
        String sub = a.length > 0 ? a[0].toLowerCase(Locale.ROOT) : "lista";
        switch (sub) {
            case "add": {
                if (a.length < 3) { throw new IllegalArgumentException("Uso: /opseguro add <nick> <ip>"); }
                String nick = a[1].toLowerCase(Locale.ROOT), ip = CmdState.ip(a[2]);
                change(sender, s -> { Set<String> set = s.opIps.computeIfAbsent(nick, k -> new TreeSet<>()); set.add(ip); if (set.size() > 10) { throw new IllegalArgumentException("Ate 10 IPs por nick."); } }, nick + " pode usar OP pelo IP " + ip + ".");
                return;
            }
            case "remover": {
                if (a.length < 2) { throw new IllegalArgumentException("Uso: /opseguro remover <nick> [ip]"); }
                String nick = a[1].toLowerCase(Locale.ROOT);
                String ip = a.length > 2 ? CmdState.ip(a[2]) : null;
                change(sender, s -> { Set<String> set = s.opIps.get(nick); if (set == null) { throw new IllegalArgumentException("Nick fora da lista."); } if (ip == null || (set.remove(ip) && set.isEmpty())) { s.opIps.remove(nick); } }, "Lista de OP atualizada.");
                return;
            }
            case "exigir": {
                boolean on = a.length > 1 && (a[1].equalsIgnoreCase("on") || a[1].equalsIgnoreCase("sim"));
                change(sender, s -> s.opRequireIp = on, on ? "OP fora da lista de IPs fica suspenso ao entrar." : "OP nao e mais suspenso por IP (so /op e /deop continuam protegidos).");
                return;
            }
            default:
                sender.sendMessage(PREFIX + "IPs do OP " + (data.read(s -> s.opRequireIp) ? "(exigido)" : "(nao exigido)") + ": " + data.read(s -> s.opIps.toString()));
                sender.sendMessage(PREFIX + "/opseguro add <nick> <ip> | remover <nick> [ip] | exigir on|off");
        }
    }

    static String root(Throwable e) { while (e.getCause() != null) { e = e.getCause(); } return e.getMessage(); }
}
