package br.goldsuite.staff;

import java.util.*;

/** Reports, comandos bloqueados, ajuda, agendamentos, clearlag e restart. */
public final class StaffState {
    public static final int MAX_REPORTS = 500;
    public int nextReport = 1;
    public Map<Integer, Report> reports = new TreeMap<>();
    /** Comandos (sem /) que so a staff pode usar. */
    public Set<String> blocked = new TreeSet<>(Arrays.asList("pl", "plugins", "version", "ver", "about", "me"));
    public List<String> help = new ArrayList<>(Arrays.asList(
            "&6--- Ajuda ---",
            "&e/spawn &f- volta ao lobby   &e/home &f- suas casas",
            "&e/tpa <jogador> &f- pede teleporte   &e/warp &f- lugares do servidor",
            "&e/money &f- seu dinheiro   &e/pagar <jogador> <valor>",
            "&e/hg entrar &f- Hunger Games   &e/kits &f- kits do HG",
            "&e/report <jogador> <motivo> &f- denuncia"));
    /** Comando de console -> intervalo em minutos (TimeCommander). */
    public Map<String, Integer> scheduled = new TreeMap<>();
    /** Intervalo do clearlag em minutos (0 desliga). */
    public int clearlagMinutes = 5;
    /** Restart automatico a cada N horas (0 desliga). */
    public int restartHours = 0;

    public static final class Report {
        public String reporter, target, reason;
        public long at;
        public Report() { }
        public Report(String reporter, String target, String reason) { this.reporter = reporter; this.target = target; this.reason = reason; this.at = System.currentTimeMillis(); }
    }

    public static String command(String text) {
        String c = text.toLowerCase(Locale.ROOT);
        if (c.startsWith("/")) { c = c.substring(1); }
        if (!c.matches("[a-z0-9_:-]{1,32}")) { throw new IllegalArgumentException("Comando invalido."); }
        return c;
    }

    public static void validate(StaffState s) {
        if (s == null || s.reports == null || s.blocked == null || s.help == null || s.scheduled == null || s.reports.size() > MAX_REPORTS
                || s.blocked.size() > 200 || s.help.size() > 30 || s.scheduled.size() > 50 || s.clearlagMinutes < 0 || s.clearlagMinutes > 120
                || s.restartHours < 0 || s.restartHours > 72 || s.nextReport < 1) { throw new IllegalArgumentException("Configuracao da staff invalida."); }
        for (Report r : s.reports.values()) { if (r == null || r.reporter == null || r.target == null || r.reason == null || r.reason.length() > 150) { throw new IllegalArgumentException("Report invalido."); } }
        for (String b : s.blocked) { command(b); }
        for (String h : s.help) { if (h == null || h.length() > 200) { throw new IllegalArgumentException("Linha de ajuda invalida."); } }
        for (Map.Entry<String, Integer> e : s.scheduled.entrySet()) {
            if (e.getKey() == null || e.getKey().length() > 200 || e.getKey().indexOf('\n') >= 0 || e.getValue() == null || e.getValue() < 1 || e.getValue() > 1440) { throw new IllegalArgumentException("Agendamento invalido."); }
        }
    }
}
