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

/**
 * Conta + aparelho + IP (o 0.15 nao envia MAC). Ban temporario bloqueia conta e aparelho; ban permanente bloqueia tambem o IP.
 * So os punidos ficam na memoria. A identidade (IPs e aparelhos usados por cada conta, em hash) fica em outro
 * namespace, lida do banco so ao punir.
 */
final class Punishments implements Listener {
    /** IPs e aparelhos (hash SHA-256) que a conta usou depois de autenticada. */
    static final class Identity {
        Set<String> ips = new LinkedHashSet<>(), devices = new LinkedHashSet<>();
        static void validate(Identity i) {
            if (i == null || i.ips == null || i.devices == null || i.ips.size() > 8 || i.devices.size() > 8) { throw new IllegalArgumentException("Identidade invalida."); }
            for (String h : i.ips) { if (!h.matches("[a-f0-9]{64}")) { throw new IllegalArgumentException("IP invalido."); } }
            for (String h : i.devices) { if (!h.matches("[a-f0-9]{64}")) { throw new IllegalArgumentException("Aparelho invalido."); } }
        }
    }
    private static final java.time.format.DateTimeFormatter DATE = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(java.time.ZoneId.of("America/Sao_Paulo"));
    private final GoldStaff plugin;
    final RecordStore<PunishmentRecord> records;
    final RecordStore<Identity> identities;
    private final Map<String, Set<String>> ipIndex = new HashMap<>(), deviceIndex = new HashMap<>();
    private int temporaryAt = 3, permanentAt = 4;
    private long days = 3;
    private String discord = "Configure o Discord no /painel.";
    Punishments(GoldStaff plugin, CoreService core) {
        this.plugin = plugin;
        records = new RecordStore<>(core.getStore(), "goldstaff-punicoes", PunishmentRecord.class, PunishmentRecord::new, PunishmentRecord::validate);
        identities = new RecordStore<>(core.getStore(), "goldstaff-identidade", Identity.class, Identity::new, Identity::validate, false);
        records.ready().thenRun(this::reindex);
        Settings.number(plugin, "punicoes.adv_temporario", 3, "Advertencias para ban temporario", v -> temporaryAt = (int) Math.max(1, Math.min(99, v)));
        Settings.number(plugin, "punicoes.adv_permanente", 4, "Advertencias para ban permanente (sempre acima do temporario)", v -> permanentAt = (int) Math.max(2, Math.min(100, v)));
        Settings.number(plugin, "punicoes.dias", 3, "Dias do ban por advertencias", v -> days = Math.max(1, Math.min(365, v)));
        Settings.text(plugin, "punicoes.discord", discord, "Link do Discord para contestar punicoes", v -> discord = v);
    }
    /** Indice so dos bans ativos: aparelho para todos, IP so para o permanente (IP muda e e compartilhado). */
    private void reindex() {
        if (!records.isLoaded()) { return; }
        ipIndex.clear(); deviceIndex.clear();
        long now = System.currentTimeMillis();
        for (String name : records.keys()) {
            PunishmentRecord r = records.peek(name);
            if (!r.active(now)) { continue; }
            if (r.permanent) { for (String ip : r.ips) { ipIndex.computeIfAbsent(ip, k -> new HashSet<>()).add(name); } }
            for (String d : r.devices) { deviceIndex.computeIfAbsent(d, k -> new HashSet<>()).add(name); }
        }
    }
    static String hash(String value) {
        if (value == null || value.isEmpty()) { return ""; }
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder s = new StringBuilder(64); for (byte b : bytes) { s.append(Character.forDigit((b >> 4) & 15, 16)).append(Character.forDigit(b & 15, 16)); } return s.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private String name(Player p) { return p.getName().toLowerCase(Locale.ROOT); }
    /**
     * Sem id da instalacao (cliente modificado, bot ou proxy) a impressao do aparelho seria a mesma para todos
     * ("null:null" ou "...:0") e banir um expulsaria todo mundo: nesse caso o aparelho nao entra.
     */
    private String device(Player p) {
        Long client = p.getClientId();
        if (p.getUniqueId() == null || client == null || client == 0L) { return ""; }
        return hash(p.getDeviceFingerprint());
    }
    PunishmentRecord blocked(Player p) {
        long now = System.currentTimeMillis();
        PunishmentRecord result = null;
        Set<String> candidates = new HashSet<>();
        candidates.add(name(p));
        candidates.addAll(ipIndex.getOrDefault(hash(p.getAddress()), Collections.emptySet()));
        candidates.addAll(deviceIndex.getOrDefault(device(p), Collections.emptySet()));
        for (String n : candidates) {
            PunishmentRecord r = records.peek(n);
            if (r.active(now) && (result == null || r.permanent || (!result.permanent && r.until > result.until))) { result = r; }
        }
        return result;
    }
    static String date(long millis) { return DATE.format(java.time.Instant.ofEpochMilli(millis)); }
    private String message(PunishmentRecord r) {
        return M.t("punicao_bloqueio", r.permanent ? M.t("punicao_permanente") : M.t("punicao_ate", date(r.until)), r.reason, discord);
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
    /** Grava IP e aparelho da conta (so depois do login; nunca de quem so digitou o nick). Sem mudanca, nao escreve. */
    private void identify(Player p) {
        if (!identities.isLoaded()) { return; }
        String ip = hash(p.getAddress()), d = device(p), n = name(p);
        identities.retain(n).whenComplete((v, error) -> {
            if (error != null || !plugin.isEnabled()) { identities.release(n); if (error != null) { plugin.getLogger().error("Falha ao ler identidade de moderacao", error); } return; }
            Identity known = identities.peek(n);
            if ((ip.isEmpty() || known.ips.contains(ip)) && (d.isEmpty() || known.devices.contains(d))) { identities.release(n); return; }
            identities.change(n, r -> { add(r.ips, ip); add(r.devices, d); }).whenComplete((r, x) -> {
                identities.release(n);
                if (x != null) { plugin.getLogger().error("Falha ao registrar identidade de moderacao", x); }
            });
        });
    }
    static void add(Set<String> values, String value) {
        if (value == null || value.isEmpty()) { return; }
        values.remove(value); values.add(value);
        while (values.size() > 8) { values.remove(values.iterator().next()); }
    }
    boolean command(CommandSender sender, String command, String[] args) {
        if (!records.isLoaded()) { sender.sendMessage(M.t("punicao_carregando")); return true; }
        if (!sender.hasPermission(command.equals("desbanir") ? "goldstaff.desbanir" : "goldstaff.punir")) { sender.sendMessage(M.t("punicao_sem_permissao")); return true; }
        if (args.length < 1) { sender.sendMessage(M.t("punicao_uso")); return true; }
        String target = args[0].toLowerCase(Locale.ROOT);
        if (!cn.nukkit.utils.PlayerName.isValid(target)) { sender.sendMessage(M.t("jogador_invalido")); return true; }
        if (sender instanceof Player && !plugin.outranks((Player) sender, target)) { sender.sendMessage(M.t("voce_nao_pode_punir_alguem_do")); return true; }
        if (command.equals("advertencias")) {
            PunishmentRecord r = records.peek(target);
            sender.sendMessage(M.t("punicao_historico", target, r.warnings, r.permanent ? M.t("punicao_permanente") : r.until > System.currentTimeMillis() ? M.t("punicao_ate", date(r.until)) : M.t("punicao_nenhum_ban")));
            for (String line : r.history) {
                int bar = line.indexOf(" | ");
                try { sender.sendMessage(bar > 0 ? date(Long.parseLong(line.substring(0, bar))) + line.substring(bar) : line); }
                catch (NumberFormatException bad) { sender.sendMessage(line); }
            }
            return true;
        }
        int reasonStart = command.equals("tempban") ? 2 : 1;
        if (args.length <= reasonStart) { sender.sendMessage(M.t("punicao_uso")); return true; }
        String reason = String.join(" ", Arrays.copyOfRange(args, reasonStart, args.length)).replace('\u00a7', ' ').trim();
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
        // Identificadores conhecidos da conta (de logins anteriores) entram junto: ban de quem esta offline tambem pega o aparelho.
        identities.retain(target).whenComplete((v, loadError) -> {
            Set<String> knownIps = new LinkedHashSet<>(), knownDevices = new LinkedHashSet<>();
            if (loadError == null && identities.isLoaded()) { Identity known = identities.peek(target); knownIps.addAll(known.ips); knownDevices.addAll(known.devices); }
            identities.release(target);
            if (!plugin.isEnabled()) { return; }
            if (loadError != null) { plugin.getLogger().warning("Identidade de " + target + " indisponivel; punindo so pela conta."); }
            apply(sender, command, target, reason, duration, temp, perm, ip, device, knownIps, knownDevices);
        });
        return true;
    }
    private void apply(CommandSender sender, String command, String target, String reason, long duration, int temp, int perm, String ip, String device, Set<String> knownIps, Set<String> knownDevices) {
        records.change(target, r -> {
            long now = System.currentTimeMillis();
            for (String h : knownIps) { add(r.ips, h); }
            for (String h : knownDevices) { add(r.devices, h); }
            r.identify(ip, device);
            if (command.equals("advertir")) { r.warn(sender.getName(), reason, now, temp, perm, duration); }
            else {
                r.reason = reason; r.actor = sender.getName();
                // Contestacao aceita anula a punicao: o ban sai e as advertencias zeram (o historico fica).
                if (command.equals("desbanir")) { r.permanent = false; r.until = 0; r.warnings = 0; }
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
    }
    void close() { records.close(); identities.close(); ipIndex.clear(); deviceIndex.clear(); }
}
