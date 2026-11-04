package br.goldsuite.staff;

import br.goldsuite.api.*;
import br.goldsuite.api.event.PlayerAuthEvent;
import cn.nukkit.Player;
import cn.nukkit.command.CommandSender;
import cn.nukkit.event.*;
import cn.nukkit.event.player.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Conta + IP + fingerprint; nenhum desses dados e um MAC ou prova inviolavel de identidade. */
final class Punishments implements Listener {
    private final GoldStaff plugin;
    final RecordStore<PunishmentRecord> records;
    private final Map<String, Set<String>> ipIndex = new HashMap<>(), deviceIndex = new HashMap<>();
    private int temporaryAt = 3, permanentAt = 4;
    private long days = 3;
    private String discord = "Configure o Discord no /painel.";
    Punishments(GoldStaff plugin, CoreService core) {
        this.plugin = plugin;
        records = new RecordStore<>(core.getStore(), "goldstaff-punicoes", PunishmentRecord.class, PunishmentRecord::new, PunishmentRecord::validate);
        records.ready().thenRun(this::reindex);
        Settings.number(plugin, "punicoes.adv_temporario", 3, "Advertencias para ban temporario", v -> temporaryAt = (int) Math.max(1, Math.min(99, v)));
        Settings.number(plugin, "punicoes.adv_permanente", 4, "Advertencias para ban permanente (sempre acima do temporario)", v -> permanentAt = (int) Math.max(2, Math.min(100, v)));
        Settings.number(plugin, "punicoes.dias", 3, "Dias do ban por advertencias", v -> days = Math.max(1, Math.min(365, v)));
        Settings.text(plugin, "punicoes.discord", discord, "Link do Discord para contestar punicoes", v -> discord = v);
    }
    private void reindex() {
        if (!records.isLoaded()) { return; }
        ipIndex.clear(); deviceIndex.clear();
        for (String name : records.keys()) {
            PunishmentRecord r = records.snapshot(name);
            for (String ip : r.ips) { ipIndex.computeIfAbsent(ip, k -> new HashSet<>()).add(name); }
            for (String d : r.devices) { deviceIndex.computeIfAbsent(d, k -> new HashSet<>()).add(name); }
        }
    }
    static String hash(String value) {
        if (value == null || value.isEmpty()) { return ""; }
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder s = new StringBuilder(); for (byte b : bytes) { s.append(String.format("%02x", b & 255)); } return s.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private String name(Player p) { return p.getName().toLowerCase(Locale.ROOT); }
    private String device(Player p) { return hash(p.getDeviceFingerprint()); }
    private PunishmentRecord blocked(Player p) {
        Set<String> candidates = new HashSet<>();
        candidates.add(name(p));
        candidates.addAll(ipIndex.getOrDefault(hash(p.getAddress()), Collections.emptySet()));
        candidates.addAll(deviceIndex.getOrDefault(device(p), Collections.emptySet()));
        PunishmentRecord result = null;
        for (String n : candidates) {
            PunishmentRecord r = records.snapshot(n);
            if (r.active(System.currentTimeMillis()) && (result == null || r.permanent || r.until > result.until)) { result = r; }
        }
        return result;
    }
    private String message(PunishmentRecord r) {
        return M.t("punicao_bloqueio", r.permanent ? M.t("punicao_permanente") : M.t("punicao_ate", new java.text.SimpleDateFormat("dd/MM/yyyy HH:mm 'UTC'").format(new Date(r.until))), r.reason, discord);
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void preLogin(PlayerPreLoginEvent e) {
        if (!records.isLoaded()) { e.setCancelled(); e.setKickMessage(M.t("punicao_carregando")); return; }
        PunishmentRecord r = blocked(e.getPlayer());
        if (r != null) { e.setCancelled(); e.setKickMessage(message(r)); }
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void authenticated(PlayerAuthEvent e) { identify(e.getPlayer()); }
    @EventHandler(priority = EventPriority.MONITOR)
    public void join(PlayerJoinEvent e) {
        if (plugin.getServer().getServiceManager().load(AuthService.class) == null) { identify(e.getPlayer()); }
    }
    private void identify(Player p) {
        if (!records.isLoaded()) { return; }
        String ip = hash(p.getAddress()), d = device(p), n = name(p);
        records.change(n, r -> r.identify(ip, d)).whenComplete((r, error) -> {
            if (!plugin.isEnabled()) { return; }
            if (error != null) { plugin.getLogger().error("Falha ao registrar identidade de moderacao", error); return; }
            reindex();
            if (p.isOnline()) { PunishmentRecord ban = blocked(p); if (ban != null) { p.kick(message(ban)); } }
        });
    }
    boolean command(CommandSender sender, String command, String[] args) {
        if (!records.isLoaded()) { sender.sendMessage(M.t("punicao_carregando")); return true; }
        if (!sender.hasPermission(command.equals("desbanir") ? "goldstaff.desbanir" : "goldstaff.punir")) { sender.sendMessage(M.t("punicao_sem_permissao")); return true; }
        if (args.length < 1) { sender.sendMessage(M.t("punicao_uso")); return true; }
        String target = args[0].toLowerCase(Locale.ROOT);
        if (!cn.nukkit.utils.PlayerName.isValid(target)) { sender.sendMessage(M.t("jogador_invalido")); return true; }
        if (sender instanceof Player && !plugin.outranks((Player) sender, target)) { sender.sendMessage(M.t("voce_nao_pode_punir_alguem_do")); return true; }
        if (command.equals("advertencias")) {
            PunishmentRecord r = records.snapshot(target);
            sender.sendMessage(M.t("punicao_historico", target, r.warnings));
            for (String line : r.history) { sender.sendMessage(line); }
            return true;
        }
        int reasonStart = command.equals("tempban") ? 2 : 1;
        if (args.length <= reasonStart) { sender.sendMessage(M.t("punicao_uso")); return true; }
        String reason = String.join(" ", Arrays.copyOfRange(args, reasonStart, args.length)).replace('§', ' ').trim();
        if (reason.isEmpty() || reason.length() > 200) { sender.sendMessage(M.t("punicao_uso")); return true; }
        long duration;
        try { duration = command.equals("tempban") ? Math.multiplyExact(Math.max(1, Math.min(365, Long.parseLong(args[1]))), 86_400_000L) : days * 86_400_000L; }
        catch (RuntimeException e) { sender.sendMessage(M.t("numero_invalido")); return true; }
        Player online = plugin.getServer().getPlayerExact(target);
        AuthService auth = plugin.getServer().getServiceManager().load(AuthService.class);
        // Nunca associe um invasor que apenas digitou o nick de outra conta.
        boolean trusted = online != null && (auth == null || auth.isAuthenticated(online));
        String ip = trusted ? hash(online.getAddress()) : "", device = trusted ? device(online) : "";
        final int temp = temporaryAt, perm = Math.max(permanentAt, temp + 1);
        records.change(target, r -> {
            long now = System.currentTimeMillis();
            r.identify(ip, device);
            if (command.equals("advertir")) { r.warn(sender.getName(), reason, now, temp, perm, duration); }
            else {
                r.reason = reason; r.actor = sender.getName();
                if (command.equals("desbanir")) { r.permanent = false; r.until = 0; }
                else if (command.equals("banir")) { r.permanent = true; r.until = 0; }
                else if (!r.permanent) { r.until = Math.max(r.until, now + duration); }
                r.audit(now, sender.getName(), command + ": " + reason);
            }
        }).whenComplete((r, error) -> {
            if (!plugin.isEnabled()) { return; }
            if (error != null) { sender.sendMessage(M.t("punicao_falha")); return; }
            reindex();
            sender.sendMessage(M.t("punicao_registrada", target, r.warnings));
            Settings.alert("Punicoes", M.t("punicao_alerta", sender.getName(), command, target, reason));
            for (Player p : plugin.getServer().getOnlinePlayers().values().toArray(new Player[0])) {
                PunishmentRecord ban = blocked(p);
                if (ban != null) { p.kick(message(ban)); }
                else if (name(p).equals(target)) { p.sendMessage(M.t("punicao_aviso", r.warnings, reason)); }
            }
        });
        return true;
    }
    void close() { records.close(); ipIndex.clear(); deviceIndex.clear(); }
}

