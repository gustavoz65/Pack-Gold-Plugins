package br.goldsuite.chat;

import br.goldsuite.api.*;
import cn.nukkit.Player;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.player.PlayerChatEvent;
import cn.nukkit.event.player.PlayerJoinEvent;
import cn.nukkit.event.player.PlayerQuitEvent;
import cn.nukkit.plugin.PluginBase;
import cn.nukkit.scheduler.PluginTask;
import java.util.*;

/** Formato por grupo, anti-spam, mensagens privadas, mute e anuncios automaticos. */
public final class GoldChat extends PluginBase implements Listener {
    static final String PREFIX = "§b[§3Chat§b]§f ";
    private static final long MIN_INTERVAL_MS = 1500, REPEAT_WINDOW_MS = 30_000;

    private PersistentState<ChatState> data;
    private final Map<Player, long[]> lastAt = new IdentityHashMap<>();
    private final Map<Player, String> lastText = new IdentityHashMap<>();
    private final Map<Player, Player> lastTell = new IdentityHashMap<>();
    private int announceIndex, announceElapsed;

    @Override public void onEnable() {
        CoreService core = getServer().getServiceManager().load(CoreService.class);
        if (core == null) { throw new IllegalStateException("GoldCore precisa estar ativo antes do GoldChat."); }
        data = new PersistentState<>(core.getStore(), "goldchat", ChatState.class, ChatState::new, ChatState::validate);
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getScheduler().scheduleRepeatingTask(new PluginTask<GoldChat>(this) { @Override public void onRun(int tick) { announce(); } }, 20 * 10);
    }

    @Override public void onDisable() { lastAt.clear(); lastText.clear(); lastTell.clear(); if (data != null) { data.close(); } }

    private void announce() {
        if (!data.isLoaded() || getServer().getOnlinePlayers().isEmpty()) { return; }
        announceElapsed += 10;
        List<String> list = data.read(s -> s.announcements);
        int every = data.read(s -> s.announceSeconds);
        if (list.isEmpty() || announceElapsed < every) { return; }
        announceElapsed = 0;
        getServer().broadcastMessage(ChatState.color(list.get(announceIndex++ % list.size())));
    }

    // ---------- chat ----------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void chat(PlayerChatEvent event) {
        Player p = event.getPlayer();
        if (!data.isLoaded()) { return; }
        String name = p.getName().toLowerCase(Locale.ROOT);
        boolean staff = p.hasPermission("goldchat.staff");
        String reason = blocked(p, name, staff);
        if (reason != null) { event.setCancelled(); p.sendMessage(PREFIX + "§c" + reason); return; }
        String message = ChatState.clean(event.getMessage());
        if (message.isEmpty()) { event.setCancelled(); return; }
        if (!staff) {
            long now = System.currentTimeMillis();
            long[] last = lastAt.computeIfAbsent(p, k -> new long[1]);
            if (now - last[0] < MIN_INTERVAL_MS) { event.setCancelled(); p.sendMessage(PREFIX + "§cDevagar: espere um pouco entre as mensagens."); return; }
            String previous = lastText.get(p);
            if (message.equalsIgnoreCase(previous) && now - last[0] < REPEAT_WINDOW_MS) { event.setCancelled(); p.sendMessage(PREFIX + "§cNao repita a mesma mensagem."); return; }
            if (!p.hasPermission("goldchat.links") && ChatState.LINK.matcher(message).find()) { event.setCancelled(); p.sendMessage(PREFIX + "§cLinks e IPs nao sao permitidos."); return; }
            message = ChatState.calm(message);
            if (message.length() > 200) { message = message.substring(0, 200); }
            last[0] = now;
            lastText.put(p, message);
        } else if (p.hasPermission("goldchat.cores")) {
            message = ChatState.color(event.getMessage().trim());
        }
        String group = group(p);
        String format = data.read(s -> s.formats.getOrDefault(group, s.formats.get("padrao")));
        event.setMessage(message);
        event.setFormat(ChatState.nukkitFormat(format, group));
    }

    /** Motivo de bloqueio ou null. */
    private String blocked(Player p, String name, boolean staff) {
        if (!staff && !data.read(s -> s.enabled)) { return "O chat esta desativado."; }
        Long until = data.read(s -> s.mutes.get(name));
        if (until != null && (until == 0 || until > System.currentTimeMillis())) {
            return until == 0 ? "Voce esta silenciado." : "Voce esta silenciado por mais " + ((until - System.currentTimeMillis()) / 60000 + 1) + " min.";
        }
        return null;
    }

    private String group(Player p) {
        PermissionService perms = getServer().getServiceManager().load(PermissionService.class);
        try { return perms == null ? "padrao" : perms.getGroup(p.getName()); } catch (RuntimeException e) { return "padrao"; }
    }

    @EventHandler(priority = EventPriority.HIGH) public void join(PlayerJoinEvent event) {
        if (data.isLoaded()) { event.setJoinMessage(ChatState.color(data.read(s -> s.join)).replace("{nome}", event.getPlayer().getName())); }
    }

    @EventHandler(priority = EventPriority.HIGH) public void quit(PlayerQuitEvent event) {
        Player p = event.getPlayer();
        lastAt.remove(p); lastText.remove(p); lastTell.remove(p);
        lastTell.values().removeIf(other -> other == p);
        if (data.isLoaded()) { event.setQuitMessage(ChatState.color(data.read(s -> s.quit)).replace("{nome}", p.getName())); }
    }

    // ---------- comandos ----------

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        try {
            switch (command.getName()) {
                case "tell": {
                    if (args.length < 2) { return false; }
                    tell(sender, online(args[0]), String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
                    return true;
                }
                case "r": {
                    if (!(sender instanceof Player) || args.length == 0) { return false; }
                    Player to = lastTell.get(sender);
                    if (to == null || !to.isOnline()) { throw new IllegalArgumentException("Ninguem para responder."); }
                    tell(sender, to, String.join(" ", args));
                    return true;
                }
                case "tellon": {
                    if (!(sender instanceof Player)) { return true; }
                    String name = sender.getName().toLowerCase(Locale.ROOT);
                    boolean[] off = new boolean[1];
                    data.change(s -> { off[0] = s.tellOff.add(name); if (!off[0]) { s.tellOff.remove(name); } })
                            .whenComplete((v, e) -> sender.sendMessage(PREFIX + (e != null ? "§c" + root(e) : off[0] ? "Mensagens privadas desativadas." : "Mensagens privadas ativadas.")));
                    return true;
                }
                case "chat": {
                    boolean on = args.length > 0 && (args[0].equalsIgnoreCase("on") || args[0].equalsIgnoreCase("ligar"));
                    data.change(s -> s.enabled = on).thenRun(() -> getServer().broadcastMessage(PREFIX + (on ? "§aChat ativado." : "§cChat desativado pela staff.")));
                    return true;
                }
                case "limparchat": {
                    StringBuilder blank = new StringBuilder();
                    for (int i = 0; i < 60; i++) { blank.append("\n"); }
                    for (Player p : getServer().getOnlinePlayers().values()) { if (!p.hasPermission("goldchat.staff")) { p.sendMessage(blank.toString()); } }
                    getServer().broadcastMessage(PREFIX + "Chat limpo por " + sender.getName() + ".");
                    return true;
                }
                case "mute": {
                    if (args.length < 1) { return false; }
                    String name = args[0].toLowerCase(Locale.ROOT);
                    if (!name.matches("[a-z0-9_]{3,16}")) { throw new IllegalArgumentException("Nome invalido."); }
                    int minutes = args.length > 1 ? Integer.parseInt(args[1]) : 0;
                    if (minutes < 0 || minutes > 525600) { throw new IllegalArgumentException("Minutos entre 0 (permanente) e 525600."); }
                    long until = minutes == 0 ? 0 : System.currentTimeMillis() + minutes * 60_000L;
                    data.change(s -> s.mutes.put(name, until)).whenComplete((v, e) -> sender.sendMessage(PREFIX + (e != null ? "§c" + root(e) : name + " silenciado " + (minutes == 0 ? "permanentemente." : "por " + minutes + " min."))));
                    return true;
                }
                case "desmute": {
                    if (args.length < 1) { return false; }
                    String name = args[0].toLowerCase(Locale.ROOT);
                    data.change(s -> { s.mutes.remove(name); s.mutes.values().removeIf(t -> t != 0 && t < System.currentTimeMillis()); })
                            .whenComplete((v, e) -> sender.sendMessage(PREFIX + (e != null ? "§c" + root(e) : name + " pode falar de novo.")));
                    return true;
                }
                case "anuncio": {
                    if (args.length < 1) { return false; }
                    getServer().broadcastMessage("§6[§eAnuncio§6] §f" + ChatState.color(String.join(" ", args)));
                    return true;
                }
                default: return false;
            }
        } catch (NumberFormatException e) { sender.sendMessage(PREFIX + "§cNumero invalido."); return true; }
        catch (RuntimeException e) { sender.sendMessage(PREFIX + "§c" + root(e)); return true; }
    }

    private void tell(CommandSender from, Player to, String text) {
        if (from == to) { throw new IllegalArgumentException("Voce nao pode mandar mensagem para si mesmo."); }
        String fromName = from.getName().toLowerCase(Locale.ROOT), toName = to.getName().toLowerCase(Locale.ROOT);
        if (from instanceof Player) {
            String reason = blocked((Player) from, fromName, from.hasPermission("goldchat.staff"));
            if (reason != null && !reason.startsWith("O chat")) { throw new IllegalArgumentException(reason); }
            long now = System.currentTimeMillis();
            long[] last = lastAt.computeIfAbsent((Player) from, k -> new long[1]);
            if (!from.hasPermission("goldchat.staff") && now - last[0] < MIN_INTERVAL_MS) { throw new IllegalArgumentException("Devagar."); }
            last[0] = now;
        }
        if (!from.hasPermission("goldchat.staff") && data.read(s -> s.tellOff.contains(toName))) { throw new IllegalArgumentException(to.getName() + " desativou mensagens privadas."); }
        String message = ChatState.clean(text);
        to.sendMessage("§d[" + from.getName() + " -> voce] §f" + message);
        from.sendMessage("§d[voce -> " + to.getName() + "] §f" + message);
        if (from instanceof Player) { lastTell.put(to, (Player) from); lastTell.put((Player) from, to); }
    }

    private Player online(String name) {
        Player p = getServer().getPlayerExact(name);
        if (p == null) { p = getServer().getPlayer(name); }
        if (p == null) { throw new IllegalArgumentException("Jogador offline."); }
        return p;
    }

    static String root(Throwable e) { while (e.getCause() != null) { e = e.getCause(); } return e.getMessage(); }
}
