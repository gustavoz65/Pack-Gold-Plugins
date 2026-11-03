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
    static String PREFIX = M.t("chat");
    private static final long MIN_INTERVAL_MS = 1500, REPEAT_WINDOW_MS = 30_000;

    private PersistentState<ChatState> data;
    private final Map<Player, long[]> lastAt = new IdentityHashMap<>();
    private final Map<Player, String> lastText = new IdentityHashMap<>();
    private final Map<Player, Player> lastTell = new IdentityHashMap<>();
    private int announceIndex, announceElapsed;
    private boolean showJoinQuit = true;

    @Override public void onEnable() {
        M.B.attach(this);
        Settings.prefix(this, PREFIX, v -> PREFIX = v);
        CoreService core = getServer().getServiceManager().load(CoreService.class);
        if (core == null) { throw new IllegalStateException(M.t("goldcore_precisa_estar_ativo_antes_do")); }
        data = new PersistentState<>(core.getStore(), "goldchat", ChatState.class, ChatState::new, ChatState::validate);
        Settings.number(this, "entrada_saida", 1, "1 = mostra quem entrou e saiu; 0 = esconde (como o OcultJoinQuit)", v -> showJoinQuit = v != 0);
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
            if (now - last[0] < MIN_INTERVAL_MS) { event.setCancelled(); p.sendMessage(PREFIX + M.t("devagar_espere_um_pouco_entre_as")); return; }
            String previous = lastText.get(p);
            if (message.equalsIgnoreCase(previous) && now - last[0] < REPEAT_WINDOW_MS) { event.setCancelled(); p.sendMessage(PREFIX + M.t("nao_repita_mesma_mensagem")); return; }
            if (!p.hasPermission("goldchat.links") && ChatState.LINK.matcher(message).find()) { event.setCancelled(); p.sendMessage(PREFIX + M.t("links_ips_nao_sao_permitidos")); return; }
            message = ChatState.calm(message);
            if (message.length() > 200) { message = message.substring(0, 200); }
            last[0] = now;
            lastText.put(p, message);
        } else if (p.hasPermission("goldchat.cores")) {
            message = ChatState.color(event.getMessage().trim());
        }
        String group = group(p);
        String tag = tag(p);
        // Cargo sem formato proprio usa a tag dele na frente do nome.
        String format = data.read(s -> s.formats.containsKey(group) ? s.formats.get(group) : !tag.isEmpty() ? ChatState.TAGGED : s.formats.get("padrao"));
        event.setMessage(message);
        String nf = ChatState.nukkitFormat(format, group, tag);
        // Placeholders de outros plugins no formato ({mctop}, {clan_tag}, {poder}...): valores sem chaves nem %, que o formato usa.
        if (nf.indexOf('{') >= 0) {
            PlaceholderService ph = getServer().getServiceManager().load(PlaceholderService.class);
            if (ph != null) {
                StringBuilder out = new StringBuilder(nf.length() + 16);
                int i = 0;
                while (i < nf.length()) {
                    int open = nf.indexOf('{', i), close = open < 0 ? -1 : nf.indexOf('}', open);
                    if (open < 0 || close < 0) { out.append(nf, i, nf.length()); break; }
                    String k = nf.substring(open + 1, close);
                    out.append(nf, i, open);
                    if (k.matches("[a-z0-9_]{1,32}")) { out.append(ph.apply(p, "{" + k + "}").replaceAll("[{}%]", "")); } else { out.append(nf, open, close + 1); }
                    i = close + 1;
                }
                nf = out.toString();
            }
        }
        event.setFormat(nf);
    }

    /** Motivo de bloqueio ou null. */
    private String blocked(Player p, String name, boolean staff) {
        if (!staff && !data.read(s -> s.enabled)) { return M.t("chat_esta_desativado"); }
        Long until = data.read(s -> s.mutes.get(name));
        if (until != null && (until == 0 || until > System.currentTimeMillis())) {
            return until == 0 ? M.t("voce_esta_silenciado") : M.t("voce_esta_silenciado_por_mais_min", ((until - System.currentTimeMillis()) / 60000 + 1));
        }
        return null;
    }

    private String group(Player p) {
        PermissionService perms = getServer().getServiceManager().load(PermissionService.class);
        try { return perms == null ? "padrao" : perms.getGroup(p.getName()); } catch (RuntimeException e) { return "padrao"; }
    }

    private String tag(Player p) {
        PermissionService perms = getServer().getServiceManager().load(PermissionService.class);
        try { return perms == null ? "" : perms.getTag(p.getName()); } catch (RuntimeException e) { return ""; }
    }

    @EventHandler(priority = EventPriority.HIGH) public void join(PlayerJoinEvent event) {
        if (!showJoinQuit) { event.setJoinMessage(""); return; }
        if (data.isLoaded()) { event.setJoinMessage(ChatState.color(data.read(s -> s.join)).replace("{nome}", event.getPlayer().getName())); }
    }

    @EventHandler(priority = EventPriority.HIGH) public void quit(PlayerQuitEvent event) {
        Player p = event.getPlayer();
        lastAt.remove(p); lastText.remove(p); lastTell.remove(p);
        lastTell.values().removeIf(other -> other == p);
        if (!showJoinQuit) { event.setQuitMessage(""); return; }
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
                    if (to == null || !to.isOnline()) { throw new IllegalArgumentException(M.t("ninguem_para_responder")); }
                    tell(sender, to, String.join(" ", args));
                    return true;
                }
                case "tellon": {
                    if (!(sender instanceof Player)) { return true; }
                    String name = sender.getName().toLowerCase(Locale.ROOT);
                    boolean[] off = new boolean[1];
                    data.change(s -> { off[0] = s.tellOff.add(name); if (!off[0]) { s.tellOff.remove(name); } })
                            .whenComplete((v, e) -> sender.sendMessage(PREFIX + (e != null ? "§c" + root(e) : off[0] ? M.t("mensagens_privadas_desativadas") : M.t("mensagens_privadas_ativadas"))));
                    return true;
                }
                case "chat": {
                    boolean on = args.length > 0 && (args[0].equalsIgnoreCase("on") || args[0].equalsIgnoreCase("ligar"));
                    data.change(s -> s.enabled = on).thenRun(() -> getServer().broadcastMessage(PREFIX + (on ? M.t("chat_ativado") : M.t("chat_desativado_pela_staff"))));
                    return true;
                }
                case "aviso": {
                    if (args.length == 0) { return false; }
                    // Aviso grande (BaconAviso): no chat com destaque e no meio da tela por alguns segundos (o 0.15 nao tem titulo).
                    String text = ChatState.color(String.join(" ", args));
                    for (Player p : getServer().getOnlinePlayers().values()) { p.sendMessage(""); p.sendMessage(M.t("aviso", text)); p.sendMessage(""); }
                    for (int i = 0; i < 4; i++) {
                        getServer().getScheduler().scheduleDelayedTask(() -> { for (Player p : getServer().getOnlinePlayers().values()) { p.sendPopup(M.t("aviso_2"), "\u00a7f" + text); } }, i * 30);
                    }
                    return true;
                }
                case "limparchat": {
                    // Linhas vazias sao descartadas pelo sendMessage: cada linha leva um codigo de cor invisivel.
                    for (Player p : getServer().getOnlinePlayers().values()) { if (!p.hasPermission("goldchat.staff")) { for (int i = 0; i < 50; i++) { p.sendMessage("§r"); } } }
                    getServer().broadcastMessage(PREFIX + M.t("chat_limpo_por", sender.getName()));
                    return true;
                }
                case "mute": {
                    if (args.length < 1) { return false; }
                    String name = args[0].toLowerCase(Locale.ROOT);
                    if (!name.matches("[a-z0-9_]{3,16}")) { throw new IllegalArgumentException(M.t("nome_invalido")); }
                    int minutes = args.length > 1 ? Integer.parseInt(args[1]) : 0;
                    if (minutes < 0 || minutes > 525600) { throw new IllegalArgumentException(M.t("minutos_entre_0_permanente_525600")); }
                    long until = minutes == 0 ? 0 : System.currentTimeMillis() + minutes * 60_000L;
                    data.change(s -> s.mutes.put(name, until)).whenComplete((v, e) -> sender.sendMessage(PREFIX + (e != null ? "§c" + root(e) : M.t("silenciado", name, (minutes == 0 ? M.t("permanentemente") : M.t("por_min", minutes))))));
                    return true;
                }
                case "desmute": {
                    if (args.length < 1) { return false; }
                    String name = args[0].toLowerCase(Locale.ROOT);
                    data.change(s -> { s.mutes.remove(name); s.mutes.values().removeIf(t -> t != 0 && t < System.currentTimeMillis()); })
                            .whenComplete((v, e) -> sender.sendMessage(PREFIX + (e != null ? "§c" + root(e) : M.t("pode_falar_de_novo", name))));
                    return true;
                }
                case "anuncio": {
                    if (args.length < 1) { return false; }
                    getServer().broadcastMessage(M.t("anuncio", ChatState.color(String.join(" ", args))));
                    return true;
                }
                default: return false;
            }
        } catch (NumberFormatException e) { sender.sendMessage(PREFIX + M.t("numero_invalido")); return true; }
        catch (RuntimeException e) { sender.sendMessage(PREFIX + "§c" + root(e)); return true; }
    }

    private void tell(CommandSender from, Player to, String text) {
        if (from == to) { throw new IllegalArgumentException(M.t("voce_nao_pode_mandar_mensagem_para")); }
        String fromName = from.getName().toLowerCase(Locale.ROOT), toName = to.getName().toLowerCase(Locale.ROOT);
        if (from instanceof Player) {
            String reason = blocked((Player) from, fromName, from.hasPermission("goldchat.staff"));
            if (reason != null && !reason.startsWith("O chat")) { throw new IllegalArgumentException(reason); }
            long now = System.currentTimeMillis();
            long[] last = lastAt.computeIfAbsent((Player) from, k -> new long[1]);
            if (!from.hasPermission("goldchat.staff") && now - last[0] < MIN_INTERVAL_MS) { throw new IllegalArgumentException(M.t("devagar")); }
            last[0] = now;
        }
        if (!from.hasPermission("goldchat.staff") && data.read(s -> s.tellOff.contains(toName))) { throw new IllegalArgumentException(M.t("desativou_mensagens_privadas", to.getName())); }
        String message = ChatState.clean(text);
        to.sendMessage(M.t("voce", from.getName(), message));
        from.sendMessage(M.t("voce_2", to.getName(), message));
        if (from instanceof Player) { lastTell.put(to, (Player) from); lastTell.put((Player) from, to); }
    }

    private Player online(String name) {
        Player p = getServer().getPlayerExact(name);
        if (p == null) { p = getServer().getPlayer(name); }
        if (p == null) { throw new IllegalArgumentException(M.t("jogador_offline")); }
        return p;
    }

    static String root(Throwable e) { while (e.getCause() != null) { e = e.getCause(); } return e.getMessage(); }
}
