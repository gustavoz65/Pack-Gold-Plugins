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
        M.B.attach(this);
        CoreService core = getServer().getServiceManager().load(CoreService.class);
        if (core == null) { throw new IllegalStateException(M.t("goldcore_precisa_estar_ativo_antes_do")); }
        data = new PersistentState<>(core.getStore(), "goldperms", PermissionState.class, PermissionState::new, PermissionState::validate);
        getServer().getPluginManager().registerEvents(this, this);
        data.ready().whenComplete((unused, error) -> {
            if (!isEnabled()) { return; }
            if (error != null) { getLogger().error(M.t("falha_ao_carregar_permissoes"), error); getServer().getPluginManager().disablePlugin(this); return; }
            getServer().getServiceManager().register(PermissionService.class, this, this);
            Settings.placeholder(this, "grupo", p -> getGroup(p.getName()).toUpperCase(Locale.ROOT));
            Settings.placeholder(this, "tag", p -> { String t = getTag(p.getName()); return t.isEmpty() ? "\u00a77" + getGroup(p.getName()).toUpperCase(Locale.ROOT) : t; });
            if (data.read(st -> !st.groups.containsKey("dono"))) {
                change(st -> st.preset()).whenComplete((v, e) -> { if (e != null) { getLogger().warning("Cargos padrao nao criados: " + rootMessage(e)); } else { getLogger().info("Cargos da equipe criados: dono, gerente, admin, moderador e ajudante (/cargos)."); } });
            }
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
    @Override public long getExpiry(String player) { long now = System.currentTimeMillis(); return data.read(s -> s.expiry(player, now)); }
    @Override public Map<String, Boolean> getPermissions(String player) { long now = System.currentTimeMillis(); return data.read(s -> s.permissions(player, now)); }
    @Override public int getWeight(String player) { long now = System.currentTimeMillis(); return data.read(s -> { PermissionState.Group g = s.info(player, now); return g == null ? 0 : g.weight; }); }
    @Override public String getTag(String player) { long now = System.currentTimeMillis(); return data.read(s -> { PermissionState.Group g = s.info(player, now); return g == null ? "" : g.tag.replace('&', '\u00a7'); }); }
    @Override public boolean isStaff(String player) { long now = System.currentTimeMillis(); return data.read(s -> { PermissionState.Group g = s.info(player, now); return g != null && g.staff; }); }
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
        if (!data.isLoaded()) { sender.sendMessage(M.t("permissoes_ainda_carregando")); return true; }
        switch (command.getName()) {
            case "cargos": cargos(sender, args); return true;
            case "equipe": equipe(sender); return true;
            case "cargo": cargo(sender, args); return true;
            default: break;
        }
        if (!sender.hasPermission("goldperms.admin")) { sender.sendMessage(M.t("sem_permissao")); return true; }
        AuthService auth = getServer().getServiceManager().load(AuthService.class);
        if (sender instanceof Player && auth != null && !auth.isAuthenticated((Player) sender)) { sender.sendMessage(M.t("autentique_se_primeiro")); return true; }
        try {
            if (args.length == 0 || args[0].equals("listar")) { sender.sendMessage(M.t("grupos", data.<Object>read(s -> new ArrayList<>(s.groups.keySet())))); return true; }
            if (args[0].equals("ver") && args.length == 2) { sender.sendMessage(M.t("peso", args[1], getGroup(args[1]), getWeight(args[1]), getPermissions(args[1]))); return true; }
            CompletableFuture<Void> result;
            switch (args[0]) {
                case "criar":
                    if (args.length != 2) { return false; }
                    result = change(state -> { String name = PermissionState.groupName(args[1]); if (state.groups.containsKey(name)) { throw new IllegalArgumentException(M.t("grupo_ja_existe")); } state.groups.put(name, new PermissionState.Group()); }); break;
                case "herdar":
                    if (args.length != 3) { return false; }
                    result = change(state -> requireGroup(state, args[1]).parent = args[2].equals("nenhum") ? "" : args[2]); break;
                case "permissao":
                    if (args.length != 4 || !Arrays.asList("true", "false", "remover").contains(args[3])) { return false; }
                    result = change(state -> { Map<String, Boolean> nodes = requireGroup(state, args[1]).permissions; if (args[3].equals("remover")) { nodes.remove(args[2]); } else { nodes.put(args[2], Boolean.valueOf(args[3])); } }); break;
                case "jogador":
                    if (args.length != 3) { return false; }
                    result = setGroup(args[1], args[2], 0); break;
                case "peso":
                    if (args.length != 3) { return false; }
                    result = change(state -> { int w = Integer.parseInt(args[2]); if (w < 0 || w > 1000) { throw new IllegalArgumentException(M.t("peso_de_0_1000")); } requireGroup(state, args[1]).weight = w; }); break;
                case "tag":
                    if (args.length < 3) { return false; }
                    result = change(state -> { String t = String.join(" ", Arrays.copyOfRange(args, 2, args.length)); requireGroup(state, args[1]).tag = t.equals("nenhuma") ? "" : PermissionState.tag(t); }); break;
                case "staff":
                    if (args.length != 3) { return false; }
                    result = change(state -> requireGroup(state, args[1]).staff = args[2].equals("on") || args[2].equals("sim")); break;
                case "descricao":
                    if (args.length < 3) { return false; }
                    result = change(state -> { String d = String.join(" ", Arrays.copyOfRange(args, 2, args.length)).replace("\u00a7", ""); if (d.length() > 100) { throw new IllegalArgumentException(M.t("descricao_de_ate_100_caracteres")); } requireGroup(state, args[1]).description = d; }); break;
                case "padroes":
                    result = change(PermissionState::preset); break;
                case "apagar":
                    if (args.length != 2) { return false; }
                    result = change(state -> { requireGroup(state, args[1]); if (args[1].equals("padrao")) { throw new IllegalArgumentException(M.t("grupo_padrao_nao_pode_ser_apagado")); } if (state.members.values().stream().anyMatch(m -> m.group.equals(args[1]))) { throw new IllegalArgumentException(M.t("grupo_ainda_possui_jogadores")); } state.groups.remove(args[1]); }); break;
                default: return false;
            }
            result.whenComplete((unused, error) -> {
                if (!isEnabled()) { return; }
                if (error != null) { sender.sendMessage(M.t("alteracao_recusada", rootMessage(error))); }
                else { refresh(); sender.sendMessage(M.t("permissoes_salvas_aplicadas")); }
            });
        } catch (NumberFormatException error) { sender.sendMessage(M.t("numero_invalido")); } catch (RuntimeException error) { sender.sendMessage(M.t("alteracao_recusada", rootMessage(error))); }
        return true;
    }
    // ---------- cargos ----------

    /** Peso de quem manda o comando: console e OP ficam acima de todos. */
    private int power(CommandSender sender) { return !(sender instanceof Player) || sender.isOp() ? Integer.MAX_VALUE : getWeight(sender.getName()); }

    private static String color(String text) { return text.replace('&', '\u00a7'); }

    private List<Map.Entry<String, PermissionState.Group>> ranked() {
        List<Map.Entry<String, PermissionState.Group>> list = data.read(s -> new ArrayList<>(s.groups.entrySet()));
        list.sort((a, b) -> b.getValue().weight != a.getValue().weight ? Integer.compare(b.getValue().weight, a.getValue().weight) : a.getKey().compareTo(b.getKey()));
        return list;
    }

    /** /cargos: a hierarquia; /cargos <cargo>: o que o cargo pode fazer. */
    private void cargos(CommandSender sender, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(M.t("cargos_do_maior_para_menor"));
            for (Map.Entry<String, PermissionState.Group> e : ranked()) {
                PermissionState.Group g = e.getValue();
                sender.sendMessage(M.t("peso_2", (g.tag.isEmpty() ? "\u00a77" + e.getKey() : color(g.tag)), e.getKey(), g.weight, (g.staff ? M.t("equipe") : ""), g.description));
            }
            sender.sendMessage(M.t("veja_que_cada_um_pode_cargos"));
            return;
        }
        String name = args[0].toLowerCase(Locale.ROOT);
        Map<String, Boolean> nodes;
        PermissionState.Group g = data.read(s -> s.groups.get(name));
        if (g == null) { sender.sendMessage(M.t("cargo_inexistente_veja_cargos")); return; }
        try { nodes = data.read(s -> s.groupPermissions(name)); } catch (RuntimeException e) { sender.sendMessage("\u00a7c" + rootMessage(e)); return; }
        sender.sendMessage(M.t("peso_3", (g.tag.isEmpty() ? name : color(g.tag)), g.weight));
        if (!g.description.isEmpty()) { sender.sendMessage("\u00a77" + g.description); }
        if (!g.parent.isEmpty()) { sender.sendMessage(M.t("herda_tudo_de", g.parent)); }
        int shown = 0;
        for (Map.Entry<String, Boolean> e : nodes.entrySet()) {
            if (shown++ >= 40) { sender.sendMessage(M.t("mais", (nodes.size() - 40))); break; }
            cn.nukkit.permission.Permission perm = getServer().getPluginManager().getPermission(e.getKey());
            String what = perm != null && perm.getDescription() != null && !perm.getDescription().isEmpty() ? perm.getDescription() : e.getKey();
            sender.sendMessage((e.getValue() ? "\u00a7a+ " : "\u00a7c- ") + "\u00a7f" + what + " \u00a78" + e.getKey());
        }
        if (nodes.isEmpty()) { sender.sendMessage(M.t("sem_permissoes_extras")); }
    }

    /** /equipe: staff online, do maior cargo para o menor. */
    private void equipe(CommandSender sender) {
        long now = System.currentTimeMillis();
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, PermissionState.Group> e : ranked()) {
            if (!e.getValue().staff) { continue; }
            StringBuilder names = new StringBuilder();
            for (Player p : getServer().getOnlinePlayers().values()) {
                String group = data.read(s -> s.group(p.getName(), now));
                if (group.equals(e.getKey())) { names.append(names.length() == 0 ? "" : ", ").append(p.getName()); }
            }
            if (names.length() > 0) { lines.add(color(e.getValue().tag.isEmpty() ? "&f" + e.getKey() : e.getValue().tag) + " \u00a7f" + names); }
        }
        sender.sendMessage(M.t("equipe_online"));
        if (lines.isEmpty()) { sender.sendMessage(M.t("ninguem_da_equipe_online_agora_use")); }
        for (String l : lines) { sender.sendMessage(l); }
    }

    /** /cargo <jogador> <cargo> [dias] e /cargo remover <jogador>: so mexe em quem esta abaixo e so da cargos abaixo do proprio. */
    private void cargo(CommandSender sender, String[] args) {
        if (!sender.hasPermission("goldperms.cargo")) { sender.sendMessage(M.t("sem_permissao")); return; }
        AuthService auth = getServer().getServiceManager().load(AuthService.class);
        if (sender instanceof Player && auth != null && !auth.isAuthenticated((Player) sender)) { sender.sendMessage(M.t("autentique_se_primeiro")); return; }
        if (args.length < 2) { sender.sendMessage(M.t("uso_cargo_jogador_cargo_dias_cargo")); return; }
        boolean remove = args[0].equalsIgnoreCase("remover");
        String target = remove ? args[1] : args[0], group = remove ? "padrao" : args[1].toLowerCase(Locale.ROOT);
        try {
            PermissionState.player(target);
            int days = !remove && args.length > 2 ? Integer.parseInt(args[2]) : 0;
            if (days < 0 || days > 3650) { throw new IllegalArgumentException(M.t("dias_de_0_permanente_3650")); }
            PermissionState.Group g = data.read(s -> s.groups.get(group));
            if (g == null) { throw new IllegalArgumentException(M.t("cargo_inexistente_veja_cargos_2")); }
            int mine = power(sender);
            if (mine != Integer.MAX_VALUE) {
                if (g.weight >= mine) { throw new IllegalArgumentException(M.t("voce_so_pode_dar_cargos_abaixo")); }
                if (getWeight(target) >= mine || getServer().isOp(target)) { throw new IllegalArgumentException(M.t("voce_nao_pode_mexer_no_cargo")); }
            }
            long expires = days == 0 ? 0 : System.currentTimeMillis() + days * 86_400_000L;
            setGroup(target, group, expires).whenComplete((v, e) -> {
                if (!isEnabled()) { return; }
                if (e != null) { sender.sendMessage(M.t("alteracao_recusada_2", rootMessage(e))); return; }
                String shown = g.tag.isEmpty() ? group : color(g.tag);
                sender.sendMessage(M.t("agora", target, shown, (days > 0 ? M.t("por_dia", days) : "")));
                Player online = getServer().getPlayerExact(target);
                if (online != null && online != sender) { online.sendMessage(M.t("seu_cargo_agora_veja_que_pode", shown, group)); }
                if (g.staff && !remove) { getServer().broadcastMessage(M.t("entrou_para_equipe_como", target, shown)); }
            });
        } catch (NumberFormatException e) { sender.sendMessage(M.t("dias_invalidos")); }
        catch (RuntimeException e) { sender.sendMessage("\u00a7c" + rootMessage(e)); }
    }

    private static PermissionState.Group requireGroup(PermissionState state, String name) {
        PermissionState.Group group = state.groups.get(name); if (group == null) { throw new IllegalArgumentException(M.t("grupo_inexistente")); } return group;
    }
    private static String rootMessage(Throwable error) { while (error.getCause() != null) { error = error.getCause(); } return error.getMessage(); }
}
