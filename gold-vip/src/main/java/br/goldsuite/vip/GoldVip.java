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
    static final String PREFIX = "§6[§eVIP§6]§f ";
    private PersistentState<VipState> data;
    private final Set<String> redeeming = new HashSet<>();

    @Override public void onEnable() {
        CoreService core = getServer().getServiceManager().load(CoreService.class);
        if (core == null) { throw new IllegalStateException("GoldCore precisa estar ativo antes do GoldVip."); }
        data = new PersistentState<>(core.getStore(), "goldvip", VipState.class, VipState::new, VipState::validate);
        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override public void onDisable() { redeeming.clear(); if (data != null) { data.close(); } }

    private PermissionService perms() {
        PermissionService perms = getServer().getServiceManager().load(PermissionService.class);
        if (perms == null) { throw new IllegalStateException("O GoldPerms precisa estar instalado."); }
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
                getServer().broadcastMessage(PREFIX + "§e" + p.getName() + " §f(" + group.toUpperCase(Locale.ROOT) + ") entrou no servidor!");
                EffectService effects = getServer().getServiceManager().load(EffectService.class);
                if (effects != null) { effects.follow(p, EffectService.Shape.CELEBRACAO, 40); }
                long expiry = perms.getExpiry(p.getName());
                if (expiry > 0 && expiry - System.currentTimeMillis() < 3 * 86_400_000L) { p.sendMessage(PREFIX + "§cSeu VIP vence em " + remaining(expiry) + "."); }
            }
        }, 60);
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "tempo" : args[0].toLowerCase(Locale.ROOT);
        try {
            switch (sub) {
                case "ativar": case "usar": {
                    if (!(sender instanceof Player) || args.length < 2) { sender.sendMessage(PREFIX + "Uso: /vip ativar <chave>"); return true; }
                    redeem((Player) sender, VipState.normalize(String.join("", Arrays.copyOfRange(args, 1, args.length))));
                    return true;
                }
                case "tempo": {
                    String name = args.length > 1 && sender.hasPermission("goldvip.admin") ? args[1] : sender.getName();
                    String group = perms().getGroup(name);
                    long expiry = perms().getExpiry(name);
                    sender.sendMessage(PREFIX + name + ": grupo §e" + group + "§f" + (group.equals("padrao") ? "" : expiry == 0 ? " (permanente)" : " ate " + new SimpleDateFormat("dd/MM/yyyy HH:mm").format(new Date(expiry)) + " (" + remaining(expiry) + ")"));
                    return true;
                }
                case "gerar": {
                    admin(sender);
                    if (args.length < 3) { sender.sendMessage(PREFIX + "Uso: /vip gerar <grupo> <dias> [quantidade]"); return true; }
                    String group = args[1].toLowerCase(Locale.ROOT);
                    int days = Integer.parseInt(args[2]), amount = args.length > 3 ? Math.max(1, Math.min(20, Integer.parseInt(args[3]))) : 1;
                    List<String> created = new ArrayList<>();
                    data.change(s -> {
                        for (int i = 0; i < amount; i++) { String k; do { k = VipState.newKey(); } while (s.keys.containsKey(k)); s.keys.put(k, new VipState.Key(group, days)); created.add(k); }
                    }).whenComplete((v, e) -> {
                        if (e != null) { sender.sendMessage(PREFIX + "§c" + root(e)); return; }
                        sender.sendMessage(PREFIX + amount + " chave(s) de " + group + " por " + days + " dia(s):");
                        for (String k : created) { sender.sendMessage("§a" + k); }
                        getLogger().info(sender.getName() + " gerou " + amount + " chave(s) de " + group + " (" + days + " dias).");
                    });
                    return true;
                }
                case "dar": {
                    admin(sender);
                    if (args.length < 4) { sender.sendMessage(PREFIX + "Uso: /vip dar <jogador> <grupo> <dias, 0 = permanente>"); return true; }
                    String name = args[1], group = args[2].toLowerCase(Locale.ROOT);
                    int days = Integer.parseInt(args[3]);
                    long now = System.currentTimeMillis();
                    long expiry = days == 0 ? 0 : VipState.extend(perms().getExpiry(name), perms().getGroup(name).equals(group), days, now);
                    perms().setGroup(name, group, expiry).whenComplete((v, e) -> sender.sendMessage(PREFIX + (e != null ? "§c" + root(e) : name + " agora e " + group + (days == 0 ? " permanente." : " por " + remaining(expiry) + "."))));
                    return true;
                }
                case "chaves": {
                    admin(sender);
                    sender.sendMessage(PREFIX + data.read(s -> s.keys.size()) + " chave(s) sem uso.");
                    return true;
                }
                case "anunciar": {
                    admin(sender);
                    if (args.length < 2) { sender.sendMessage(PREFIX + "Grupos anunciados: " + data.read(s -> s.announced)); return true; }
                    String group = args[1].toLowerCase(Locale.ROOT);
                    boolean[] on = new boolean[1];
                    data.change(s -> { on[0] = s.announced.add(group); if (!on[0]) { s.announced.remove(group); } })
                            .whenComplete((v, e) -> sender.sendMessage(PREFIX + (e != null ? "§c" + root(e) : group + (on[0] ? " sera anunciado." : " nao sera anunciado."))));
                    return true;
                }
                default:
                    sender.sendMessage(PREFIX + "/vip ativar <chave>, /vip tempo");
                    if (sender.hasPermission("goldvip.admin")) { sender.sendMessage(PREFIX + "Admin: /vip gerar <grupo> <dias> [qtd], dar <jogador> <grupo> <dias>, chaves, anunciar <grupo>, tempo <jogador>"); }
                    return true;
            }
        } catch (NumberFormatException e) { sender.sendMessage(PREFIX + "§cNumero invalido."); return true; }
        catch (RuntimeException e) { sender.sendMessage(PREFIX + "§c" + root(e)); return true; }
    }

    /** A chave sai do banco antes de dar o grupo; se o grupo falhar, ela volta. Nunca vale duas vezes. */
    private void redeem(Player p, String key) {
        PermissionService perms = perms();
        String name = p.getName().toLowerCase(Locale.ROOT);
        if (!redeeming.add(name)) { return; }
        VipState.Key[] taken = new VipState.Key[1];
        data.change(s -> { taken[0] = s.keys.remove(key); if (taken[0] == null) { throw new IllegalArgumentException("Chave invalida ou ja usada."); } })
                .thenCompose(v -> {
                    long expiry = VipState.extend(perms.getExpiry(name), perms.getGroup(name).equals(taken[0].group), taken[0].days, System.currentTimeMillis());
                    return perms.setGroup(name, taken[0].group, expiry).exceptionally(error -> {
                        data.change(s -> s.keys.put(key, taken[0]));
                        throw new IllegalStateException("Nao foi possivel ativar: " + root(error));
                    });
                })
                .whenComplete((v, e) -> {
                    redeeming.remove(name);
                    if (!isEnabled() || !p.isOnline()) { return; }
                    if (e != null) { p.sendMessage(PREFIX + "§c" + root(e)); return; }
                    getServer().broadcastMessage(PREFIX + "§e" + p.getName() + "§f ativou §6" + taken[0].group.toUpperCase(Locale.ROOT) + "§f! Obrigado pelo apoio!");
                    EffectService effects = getServer().getServiceManager().load(EffectService.class);
                    if (effects != null) { effects.follow(p, EffectService.Shape.CELEBRACAO, 60); }
                    getLogger().info(p.getName() + " ativou a chave " + key + " (" + taken[0].group + ", " + taken[0].days + " dias).");
                });
    }

    private static void admin(CommandSender s) { if (!s.hasPermission("goldvip.admin")) { throw new IllegalArgumentException("Sem permissao."); } }

    static String remaining(long expiry) {
        long minutes = Math.max(0, (expiry - System.currentTimeMillis()) / 60_000);
        if (minutes >= 1440) { return (minutes / 1440) + " dia(s)"; }
        if (minutes >= 60) { return (minutes / 60) + " h"; }
        return minutes + " min";
    }

    static String root(Throwable e) { while (e.getCause() != null) { e = e.getCause(); } return e.getMessage(); }
}
