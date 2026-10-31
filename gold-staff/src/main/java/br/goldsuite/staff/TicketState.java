package br.goldsuite.staff;

import java.util.*;

/** Tickets do /suporte: fila aberta, historico recente e metricas por membro da equipe. */
public final class TicketState {
    public static final int MAX_OPEN = 200, MAX_CLOSED = 120, MAX_LOG = 25, MAX_LINE = 160, KEEP_DAYS = 90;
    public static final List<String> CATEGORIES = Arrays.asList("bug", "denuncia", "compra", "conta", "punicao", "duvida", "outro");
    public static final List<String> PRIORITIES = Arrays.asList("baixa", "media", "alta");

    public int next = 1;
    public Map<Integer, Ticket> open = new TreeMap<>();
    public Map<Integer, Ticket> closed = new TreeMap<>();
    /** Membro da equipe (minusculo) -> numeros dele. */
    public Map<String, Metric> metrics = new TreeMap<>();
    /** Tickets que o assistente resolveu sem precisar da equipe. */
    public int solvedByAi;

    public static final class Ticket {
        public int id;
        public String player = "", category = "outro", priority = "media", summary = "", staff = "", closedBy = "", result = "";
        public long createdAt, claimedAt, closedAt;
        public int rating;
        public List<String> log = new ArrayList<>();
    }

    public static final class Metric {
        public int total, ratingSum, ratingCount;
        public long waitSeconds, durationSeconds;
        /** Dia (epoch) -> atendimentos fechados nesse dia. */
        public Map<Long, Integer> days = new TreeMap<>();

        public int since(long firstDay) { int n = 0; for (Map.Entry<Long, Integer> e : days.entrySet()) { if (e.getKey() >= firstDay) { n += e.getValue(); } } return n; }
    }

    public static String category(String c) { String v = c == null ? "" : c.trim().toLowerCase(Locale.ROOT); return CATEGORIES.contains(v) ? v : "outro"; }
    public static String priority(String p) { String v = p == null ? "" : p.trim().toLowerCase(Locale.ROOT).replace("é", "e"); return PRIORITIES.contains(v) ? v : "media"; }

    /** Texto vindo do jogador ou da IA: sem cores, sem controle e com tamanho limitado. */
    public static String clean(String text, int max) {
        if (text == null) { return ""; }
        String t = text.replaceAll("§.?", "").replaceAll("[\\p{Cntrl}]", " ").replaceAll("\\s{2,}", " ").trim();
        return t.length() > max ? t.substring(0, max - 3) + "..." : t;
    }

    public Ticket create(String player, String category, String priority, String summary, List<String> log, long now) {
        if (open.size() >= MAX_OPEN) { throw new IllegalArgumentException("Fila de suporte cheia. Tente mais tarde."); }
        Ticket t = new Ticket();
        t.id = next++;
        t.player = player; t.category = category(category); t.priority = priority(priority); t.summary = clean(summary, 400); t.createdAt = now;
        appendAll(t, log);
        open.put(t.id, t);
        return t;
    }

    static void appendAll(Ticket t, List<String> lines) { for (String l : lines) { append(t, l); } }

    static void append(Ticket t, String line) {
        t.log.add(clean(line, MAX_LINE));
        while (t.log.size() > MAX_LOG) { t.log.remove(0); }
    }

    /** Fecha, guarda no historico e soma nas metricas de quem atendeu. */
    public Ticket close(int id, String by, String result, List<String> log, long now) {
        Ticket t = open.remove(id);
        if (t == null) { throw new IllegalArgumentException("Ticket inexistente ou ja fechado."); }
        appendAll(t, log);
        t.closedAt = now; t.closedBy = by; t.result = clean(result, 120);
        closed.put(t.id, t);
        while (closed.size() > MAX_CLOSED) { closed.remove(((TreeMap<Integer, Ticket>) closed).firstKey()); }
        if (!t.staff.isEmpty() && t.claimedAt > 0) {
            Metric m = metrics.computeIfAbsent(t.staff, k -> new Metric());
            m.total++;
            m.waitSeconds += Math.max(0, (t.claimedAt - t.createdAt) / 1000);
            m.durationSeconds += Math.max(0, (now - t.claimedAt) / 1000);
            long day = now / 86_400_000L;
            m.days.merge(day, 1, Integer::sum);
            m.days.keySet().removeIf(d -> d < day - KEEP_DAYS);
        }
        return t;
    }

    /** Nota de 1 a 5, uma vez por ticket. */
    public void rate(int id, String player, int rating) {
        if (rating < 1 || rating > 5) { throw new IllegalArgumentException("Nota de 1 a 5."); }
        Ticket t = closed.get(id);
        if (t == null || !t.player.equals(player)) { throw new IllegalArgumentException("Atendimento nao encontrado."); }
        if (t.rating != 0) { throw new IllegalArgumentException("Voce ja avaliou este atendimento."); }
        t.rating = rating;
        Metric m = t.staff.isEmpty() ? null : metrics.get(t.staff);
        if (m != null) { m.ratingSum += rating; m.ratingCount++; }
    }

    public static void validate(TicketState s) {
        if (s == null || s.open == null || s.closed == null || s.metrics == null || s.next < 1 || s.open.size() > MAX_OPEN || s.closed.size() > MAX_CLOSED
                || s.metrics.size() > 1000 || s.solvedByAi < 0) { throw new IllegalArgumentException("Tickets invalidos."); }
        for (Map<Integer, Ticket> map : Arrays.asList(s.open, s.closed)) {
            for (Map.Entry<Integer, Ticket> e : map.entrySet()) {
                Ticket t = e.getValue();
                if (t == null || t.id != e.getKey() || t.id >= s.next || t.player == null || t.summary == null || t.staff == null || t.closedBy == null || t.result == null
                        || t.log == null || t.log.size() > MAX_LOG || !CATEGORIES.contains(t.category) || !PRIORITIES.contains(t.priority) || t.rating < 0 || t.rating > 5
                        || t.summary.length() > 400) { throw new IllegalArgumentException("Ticket invalido."); }
                for (String l : t.log) { if (l == null || l.length() > MAX_LINE) { throw new IllegalArgumentException("Historico de ticket invalido."); } }
            }
        }
        for (Metric m : s.metrics.values()) {
            if (m == null || m.days == null || m.days.size() > KEEP_DAYS + 2 || m.total < 0 || m.ratingSum < 0 || m.ratingCount < 0 || m.waitSeconds < 0 || m.durationSeconds < 0) {
                throw new IllegalArgumentException("Metrica invalida.");
            }
        }
    }
}
