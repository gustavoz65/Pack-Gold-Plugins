package br.goldsuite.perms;

import br.goldsuite.api.*;
import br.goldsuite.api.event.PlayerAuthEvent;
import cn.nukkit.Player;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.event.*;
import cn.nukkit.event.player.PlayerJoinEvent;
import cn.nukkit.event.player.PlayerQuitEvent;
import cn.nukkit.permission.PermissionAttachment;
import cn.nukkit.plugin.PluginBase;
import cn.nukkit.scheduler.PluginTask;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public final class GoldPerms extends PluginBase implements Listener, PermissionService {
    private PersistentState<PermissionState> data;
    private final Map<Player, PermissionAttachment> attachments = new IdentityHashMap<>();

    @Override public void onEnable() {
        CoreService core = getServer().getServiceManager().load(CoreService.class);
        if (core == null) { throw new IllegalStateException("GoldCore precisa estar ativo antes do GoldPerms."); }
        data = new PersistentState<>(core.getStore(), "goldperms", PermissionState.class, PermissionState::new, PermissionState::validate);
        getServer().getPluginManager().registerEvents(this, this);
        data.ready().whenComplete((unused, error) -> {
            if (!isEnabled()) { return; }
            if (error != null) { getLogger().error("Falha ao carregar permissoes.", error); getServer().getPluginManager().disablePlugin(this); return; }
            getServer().getServiceManager().register(PermissionService.class, this, this);
            refresh();
        });
        getServer().getScheduler().scheduleRepeatingTask(new PluginTask<GoldPerms>(this) {
            @Override public void onRun(int tick) { if (data.isLoaded()) { refresh(); } }
        }, 20);
    }
    @Override public void onDisable() {
        if (data != null) { data.close(); }
        for (PermissionAttachment attachment : attachments.values()) { attachment.remove(); }
        attachments.clear();
    }
    @Override public String getGroup(String player) { long now = System.currentTimeMillis(); return data.read(s -> s.group(player, now)); }
    @Override public Map<String, Boolean> getPermissions(String player) { long now = System.currentTimeMillis(); return data.read(s -> s.permissions(player, now)); }
    @Override public CompletableFuture<Void> setGroup(String player, String group, long expiresAt) {
        return change(state -> state.assign(player, group, expiresAt)).thenRun(this::refresh);
    }
    /** Toda gravacao descarta antes os vinculos expirados. */
    private CompletableFuture<Void> change(java.util.function.Consumer<PermissionState> mutation) {
        return data.change(state -> { state.prune(System.currentTimeMillis()); mutation.accept(state); });
    }
    private void refresh() { if (isEnabled()) { for (Player player : getServer().getOnlinePlayers().values()) { apply(player); } } }
    private void apply(Player player) {
        AuthService auth = getServer().getServiceManager().load(AuthService.class);
        Map<String, Boolean> nodes = auth != null && !auth.isAuthenticated(player) ? Collections.emptyMap() : getPermissions(player.getName());
        PermissionAttachment attachment = attachments.get(player);
        if (attachment == null) { attachment = player.addAttachment(this); attachments.put(player, attachment); }
        if (!attachment.getPermissions().equals(nodes)) { attachment.clearPermissions(); attachment.setPermissions(nodes); }
    }
    @EventHandler public void join(PlayerJoinEvent event) { data.ready().thenRun(() -> { if (isEnabled() && event.getPlayer().isOnline()) { apply(event.getPlayer()); } }); }
    @EventHandler public void authenticated(PlayerAuthEvent event) { data.ready().thenRun(() -> { if (isEnabled() && event.getPlayer().isOnline()) { apply(event.getPlayer()); } }); }
    @EventHandler public void quit(PlayerQuitEvent event) { PermissionAttachment attachment = attachments.remove(event.getPlayer()); if (attachment != null) { attachment.remove(); } }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("goldperms.admin")) { sender.sendMessage("Sem permissao."); return true; }
        AuthService auth = getServer().getServiceManager().load(AuthService.class);
        if (sender instanceof Player && auth != null && !auth.isAuthenticated((Player) sender)) { sender.sendMessage("Autentique-se primeiro."); return true; }
        try {
            if (args.length == 0 || args[0].equals("listar")) { sender.sendMessage("Grupos: " + data.read(s -> new ArrayList<>(s.groups.keySet()))); return true; }
            if (args[0].equals("ver") && args.length == 2) { sender.sendMessage(args[1] + ": " + getGroup(args[1]) + " " + getPermissions(args[1])); return true; }
            CompletableFuture<Void> result;
            switch (args[0]) {
                case "criar":
                    if (args.length != 2) { return false; }
                    result = change(state -> { String name = PermissionState.groupName(args[1]); if (state.groups.containsKey(name)) { throw new IllegalArgumentException("Grupo ja existe."); } state.groups.put(name, new PermissionState.Group()); }); break;
                case "herdar":
                    if (args.length != 3) { return false; }
                    result = change(state -> requireGroup(state, args[1]).parent = args[2].equals("nenhum") ? "" : args[2]); break;
                case "permissao":
                    if (args.length != 4 || !Arrays.asList("true", "false", "remover").contains(args[3])) { return false; }
                    result = change(state -> { Map<String, Boolean> nodes = requireGroup(state, args[1]).permissions; if (args[3].equals("remover")) { nodes.remove(args[2]); } else { nodes.put(args[2], Boolean.valueOf(args[3])); } }); break;
                case "jogador":
                    if (args.length != 3) { return false; }
                    result = setGroup(args[1], args[2], 0); break;
                case "apagar":
                    if (args.length != 2) { return false; }
                    result = change(state -> { requireGroup(state, args[1]); if (args[1].equals("padrao")) { throw new IllegalArgumentException("O grupo padrao nao pode ser apagado."); } if (state.members.values().stream().anyMatch(m -> m.group.equals(args[1]))) { throw new IllegalArgumentException("Grupo ainda possui jogadores."); } state.groups.remove(args[1]); }); break;
                default: return false;
            }
            result.whenComplete((unused, error) -> {
                if (!isEnabled()) { return; }
                if (error != null) { sender.sendMessage("Alteracao recusada: " + rootMessage(error)); }
                else { refresh(); sender.sendMessage("Permissoes salvas e aplicadas."); }
            });
        } catch (RuntimeException error) { sender.sendMessage("Alteracao recusada: " + rootMessage(error)); }
        return true;
    }
    private static PermissionState.Group requireGroup(PermissionState state, String name) {
        PermissionState.Group group = state.groups.get(name); if (group == null) { throw new IllegalArgumentException("Grupo inexistente."); } return group;
    }
    private static String rootMessage(Throwable error) { while (error.getCause() != null) { error = error.getCause(); } return error.getMessage(); }
}
