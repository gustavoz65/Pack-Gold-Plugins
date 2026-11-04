package br.goldsuite.staff;

import java.util.*;

/** Advertencias nao expiram com o ban temporario. Revogacao explicita preserva o historico. */
final class PunishmentRecord {
    int warnings;
    long until;
    boolean permanent;
    String reason = "", actor = "";
    Set<String> ips = new LinkedHashSet<>(), devices = new LinkedHashSet<>();
    List<String> history = new ArrayList<>();
    boolean active(long now) { return permanent || until > now; }
    void warn(String actor, String reason, long now, int temporaryAt, int permanentAt, long duration) {
        warnings = Math.addExact(warnings, 1);
        this.actor = actor; this.reason = reason;
        if (warnings >= permanentAt) { permanent = true; until = 0; }
        else if (warnings >= temporaryAt) { until = Math.max(until, Math.addExact(now, duration)); }
        audit(now, actor, "ADV " + warnings + ": " + reason);
    }
    void audit(long now, String actor, String action) {
        history.add(now + " | " + actor + " | " + action);
        while (history.size() > 50) { history.remove(0); }
    }
    void identify(String ip, String device) { Punishments.add(ips, ip); Punishments.add(devices, device); }
    static void validate(PunishmentRecord r) {
        if (r == null || r.warnings < 0 || r.until < 0 || r.ips == null || r.devices == null ||
                r.history == null || r.history.size() > 50 || r.ips.size() > 8 || r.devices.size() > 8 ||
                r.reason == null || r.reason.length() > 200 || r.actor == null) { throw new IllegalArgumentException("Punicao invalida."); }
        for (String hash : r.ips) { if (!hash.matches("[a-f0-9]{64}")) { throw new IllegalArgumentException("IP invalido."); } }
        for (String hash : r.devices) { if (!hash.matches("[a-f0-9]{64}")) { throw new IllegalArgumentException("Aparelho invalido."); } }
    }
}

