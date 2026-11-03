package br.goldsuite.vip;

import br.goldsuite.api.*;
import cn.nukkit.Player;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.player.PlayerJoinEvent;
import cn.nukkit.plugin.PluginBase;
import cn.nukkit.scheduler.PluginTask;
import java.text.SimpleDateFormat;
import java.util.*;

/** Chaves de VIP com tempo somado (sobre o GoldPerms) e anuncio de VIP entrando. */
public final class GoldVip extends PluginBase implements Listener {
    static String PREFIX = M.t("vip");
    private PersistentState<VipState> data;
    private final Set<String> redeeming = new HashSet<>();

    @Override public void onEnable() {
        M.B.attach(this);
        Settings.prefix(this, PREFIX, v -> PREFIX = v);
        CoreService core = getServer().getServiceManager().load(CoreService.class);
        if (core == null) { throw new IllegalStateException(M.t("goldcore_precisa_estar_ativo_antes_do")); }
        data = new PersistentState<>(core.getStore(), "goldvip", VipState.class, VipState::new, VipState::validate);
        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override public void onDisable() { redeeming.clear(); if (data != null) { data.close(); } }

    private PermissionService perms() {
        PermissionService perms = getServer().getServiceManager().load(PermissionService.class);
        if (perms == null) { throw new IllegalStateException(M.t("goldperms_precisa_estar_instalado")); }
        return perms;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void join(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        // Espera o login: o anuncio so sai para quem de fato entrou na conta.
        getServer().getScheduler().scheduleDelayedTask(new PluginTask<GoldVip>(this) {
            @Override public void onRun(int tick) {
                if (!p.isOnline() || !data.isLoaded()) { return; }
                PermissionService perms = getServer().getServiceManager().load(PermissionService.class);
                if (perms == null) { return; }
                String group = perms.getGroup(p.getName());
                if (!data.read(s -> s.announced.contains(group))) { return; }
                getServer().broadcastMessage(PREFIX + M.t("entrou_no_servidor", p.getName(), group.toUpperCase(Locale.ROOT)));
                EffectService effects = getServer().getServiceManager().load(EffectService.class);
                if (effects != null) { effects.follow(p, EffectService.Shape.CELEBRACAO, 40); }
                long expiry = perms.getExpiry(p.getName());
                if (expiry > 0 && expiry - System.currentTimeMillis() < 3 * 86_400_000L) { p.sendMessage(PREFIX + M.t("seu_vip_vence_em", remaining(expiry))); }
            }
        }, 60);
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "tempo" : args[0].toLowerCase(Locale.ROOT);
        try {
            switch (sub) {
                case "ativar": case "usar": {
                    if (!(sender instanceof Player) || args.length < 2) { sender.sendMessage(PREFIX + M.t("uso_vip_ativar_chave")); return true; }
                    redeem((Player) sender, VipState.normalize(String.join("", Arrays.copyOfRange(args, 1, args.length))));
                    return true;
                }
                case "tempo": {
                    String name = args.length > 1 && sender.hasPermission("goldvip.admin") ? args[1] : sender.getName();
                    String group = perms().getGroup(name);
                    long expiry = perms().getExpiry(name);
                    sender.sendMessage(PREFIX + M.t("grupo", name, group, (group.equals("padrao") ? "" : expiry == 0 ? M.t("permanente") : M.t("ate", new SimpleDateFormat("dd/MM/yyyy HH:mm").format(new Date(expiry)), remaining(expiry)))));
                    return true;
                }
                case "gerar": {
                    admin(sender);
                    if (args.length < 3) { sender.sendMessage(PREFIX + M.t("uso_vip_gerar_grupo_dias_quantidade")); return true; }
                    String group = args[1].toLowerCase(Locale.ROOT);
                    int days = Integer.parseInt(args[2]), amount = args.length > 3 ? Math.max(1, Math.min(20, Integer.parseInt(args[3]))) : 1;
                    List<String> created = new ArrayList<>();
                    data.change(s -> {
                        for (int i = 0; i < amount; i++) { String k; do { k = VipState.newKey(); } while (s.keys.containsKey(k)); s.keys.put(k, new VipState.Key(group, days)); created.add(k); }
                    }).whenComplete((v, e) -> {
                        if (e != null) { sender.sendMessage(PREFIX + "§c" + root(e)); return; }
                        sender.sendMessage(PREFIX + M.t("chave_de_por_dia", amount, group, days));
                        for (String k : created) { sender.sendMessage("§a" + k); }
                        getLogger().info(sender.getName() + " gerou " + amount + " chave(s) de " + group + " (" + days + " dias).");
                    });
                    return true;
                }
                case "dar": {
                    admin(sender);
                    if (args.length < 4) { sender.sendMessage(PREFIX + M.t("uso_vip_dar_jogador_grupo_dias")); return true; }
                    String name = args[1], group = args[2].toLowerCase(Locale.ROOT);
                    int days = Integer.parseInt(args[3]);
                    long now = System.currentTimeMillis();
                    long expiry = days == 0 ? 0 : VipState.extend(perms().getExpiry(name), perms().getGroup(name).equals(group), days, now);
                    perms().setGroup(name, group, expiry).whenComplete((v, e) -> sender.sendMessage(PREFIX + (e != null ? "§c" + root(e) : M.t("agora", name, group, (days == 0 ? M.t("permanente_2") : M.t("por", remaining(expiry)))))));
                    return true;
                }
                case "chaves": {
                    admin(sender);
                    sender.sendMessage(PREFIX + M.t("chave_sem_uso", data.<Object>read(s -> s.keys.size())));
                    return true;
                }
                case "anunciar": {
                    admin(sender);
                    if (args.length < 2) { sender.sendMessage(PREFIX + M.t("grupos_anunciados", data.<Object>read(s -> s.announced))); return true; }
                    String group = args[1].toLowerCase(Locale.ROOT);
                    boolean[] on = new boolean[1];
                    data.change(s -> { on[0] = s.announced.add(group); if (!on[0]) { s.announced.remove(group); } })
                            .whenComplete((v, e) -> sender.sendMessage(PREFIX + (e != null ? "§c" + root(e) : group + (on[0] ? M.t("sera_anunciado") : M.t("nao_sera_anunciado")))));
                    return true;
                }
                default:
                    sender.sendMessage(PREFIX + M.t("vip_ativar_chave_vip_tempo"));
                    if (sender.hasPermission("goldvip.admin")) { sender.sendMessage(PREFIX + M.t("admin_vip_gerar_grupo_dias_qtd")); }
                    return true;
            }
        } catch (NumberFormatException e) { sender.sendMessage(PREFIX + M.t("numero_invalido")); return true; }
        catch (RuntimeException e) { sender.sendMessage(PREFIX + "§c" + root(e)); return true; }
    }

    /** A chave sai do banco antes de dar o grupo; se o grupo falhar, ela volta. Nunca vale duas vezes. */
    private void redeem(Player p, String key) {
        PermissionService perms = perms();
        String name = p.getName().toLowerCase(Locale.ROOT);
        if (!redeeming.add(name)) { return; }
        VipState.Key[] taken = new VipState.Key[1];
        data.change(s -> { taken[0] = s.keys.remove(key); if (taken[0] == null) { throw new IllegalArgumentException(M.t("chave_invalida_ou_ja_usada")); } })
                .thenCompose(v -> {
                    long expiry = VipState.extend(perms.getExpiry(name), perms.getGroup(name).equals(taken[0].group), taken[0].days, System.currentTimeMillis());
                    return perms.setGroup(name, taken[0].group, expiry).exceptionally(error -> {
                        data.change(s -> s.keys.put(key, taken[0]));
                        throw new IllegalStateException(M.t("nao_foi_possivel_ativar", root(error)));
                    });
                })
                .whenComplete((v, e) -> {
                    redeeming.remove(name);
                    if (!isEnabled() || !p.isOnline()) { return; }
                    if (e != null) { p.sendMessage(PREFIX + "§c" + root(e)); return; }
                    getServer().broadcastMessage(PREFIX + M.t("ativou_obrigado_pelo_apoio", p.getName(), taken[0].group.toUpperCase(Locale.ROOT)));
                    EffectService effects = getServer().getServiceManager().load(EffectService.class);
                    if (effects != null) { effects.follow(p, EffectService.Shape.CELEBRACAO, 60); }
                    getLogger().info(p.getName() + " ativou a chave " + key + " (" + taken[0].group + ", " + taken[0].days + " dias).");
                });
    }

    private static void admin(CommandSender s) { if (!s.hasPermission("goldvip.admin")) { throw new IllegalArgumentException(M.t("sem_permissao")); } }

    static String remaining(long expiry) {
        long minutes = Math.max(0, (expiry - System.currentTimeMillis()) / 60_000);
        if (minutes >= 1440) { return (minutes / 1440) + " dia(s)"; }
        if (minutes >= 60) { return (minutes / 60) + " h"; }
        return minutes + " min";
    }

    static String root(Throwable e) { while (e.getCause() != null) { e = e.getCause(); } return e.getMessage(); }
}
