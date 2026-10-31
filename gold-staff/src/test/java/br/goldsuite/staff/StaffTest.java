package br.goldsuite.staff;

import com.google.gson.Gson;

public final class StaffTest {
    public static void main(String[] args) {
        StaffState s = new StaffState();
        s.reports.put(s.nextReport++, new StaffState.Report("ana", "bob", "hack"));
        s.scheduled.put("say Bem-vindo!", 30);
        StaffState.validate(s);
        StaffState back = new Gson().fromJson(new Gson().toJson(s), StaffState.class);
        StaffState.validate(back);
        check(back.reports.get(1).target.equals("bob") && back.scheduled.get("say Bem-vindo!") == 30 && back.blocked.contains("pl"), "Serializacao perdeu dados.");
        check(StaffState.command("/PL").equals("pl"), "Comando nao normalizado.");
        rejects(() -> StaffState.command("say oi"));
        back.scheduled.put("x", 0);
        rejects(() -> StaffState.validate(back));
        tickets();
        groq();
        System.out.println("GoldStaff: tickets, metricas, avaliacao, resposta da IA, reports, agendamentos, comandos bloqueados e validacao aprovados.");
    }
    private static void tickets() {
        TicketState s = new TicketState();
        long now = 10L * 86_400_000L;
        TicketState.Ticket a = s.create("ana", "BUG", "ALTA", "Caiu no void §cno spawn", java.util.Arrays.asList("ana: caí", "IA: onde?"), now);
        TicketState.Ticket b = s.create("bob", "xyz", "urgente", "duvida", java.util.Collections.emptyList(), now);
        check(a.id == 1 && b.id == 2 && a.category.equals("bug") && a.priority.equals("alta") && b.category.equals("outro") && b.priority.equals("media"), "Categoria ou prioridade nao normalizada.");
        check(a.summary.equals("Caiu no void no spawn"), "Cor da IA/jogador entrou no resumo.");
        a.staff = "mod"; a.claimedAt = now + 60_000;
        s.close(1, "mod", "devolvido", java.util.Collections.singletonList("mod: resolvido"), now + 360_000);
        TicketState.Metric m = s.metrics.get("mod");
        check(m.total == 1 && m.waitSeconds == 60 && m.durationSeconds == 300 && m.since(10) == 1 && m.since(11) == 0, "Metrica errada: " + new Gson().toJson(m));
        check(s.closed.get(1).log.size() == 3 && !s.open.containsKey(1), "Ticket nao foi para o historico.");
        s.rate(1, "ana", 5);
        check(m.ratingSum == 5 && m.ratingCount == 1, "Nota nao contada.");
        rejects(() -> s.rate(1, "ana", 4));
        rejects(() -> s.rate(1, "bob", 4));
        rejects(() -> s.rate(2, "bob", 9));
        s.close(2, "bob", "cancelado", java.util.Collections.emptyList(), now);
        check(s.metrics.size() == 1, "Ticket sem atendente entrou nas metricas.");
        rejects(() -> s.close(2, "x", "", java.util.Collections.emptyList(), now));
        for (int i = 0; i < 40; i++) { TicketState.append(s.closed.get(2), "linha " + i + " " + new String(new char[300]).replace('\0', 'x')); }
        check(s.closed.get(2).log.size() == TicketState.MAX_LOG && s.closed.get(2).log.get(0).length() <= TicketState.MAX_LINE, "Historico sem limite.");
        for (int i = 0; i < TicketState.MAX_CLOSED + 10; i++) { TicketState.Ticket t = s.create("p" + i, "duvida", "baixa", "x", java.util.Collections.emptyList(), now); s.close(t.id, "p", "ok", java.util.Collections.emptyList(), now); }
        check(s.closed.size() == TicketState.MAX_CLOSED, "Historico fechado sem limite.");
        TicketState back = new Gson().fromJson(new Gson().toJson(s), TicketState.class);
        TicketState.validate(back);
        check(back.metrics.get("mod").days.get(10L) == 1, "Dias das metricas perdidos na serializacao.");
        back.open.put(999, new TicketState.Ticket());
        rejects(() -> TicketState.validate(back));
    }

    private static void groq() {
        try {
            String api = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Claro! {\\\"mensagem\\\":\\\"Em qual modo?\\\",\\\"pronto\\\":false,\\\"resolvido\\\":\\\"false\\\"}\"}}]}";
            com.google.gson.JsonObject o = Groq.parse(api);
            check(Groq.str(o, "mensagem").equals("Em qual modo?") && !Groq.bool(o, "pronto") && !Groq.bool(o, "resolvido") && Groq.str(o, "resumo").isEmpty(), "Resposta da IA mal lida.");
        } catch (java.io.IOException e) { throw new AssertionError(e); }
        try { Groq.parse("{\"choices\":[{\"message\":{\"content\":\"sem json\"}}]}"); throw new AssertionError("Aceitou resposta sem JSON."); } catch (java.io.IOException expected) { }
        try { Groq.parse("<html>erro</html>"); throw new AssertionError("Aceitou resposta quebrada."); } catch (java.io.IOException expected) { }
        check(Groq.validUrl("https://api.groq.com/x") && Groq.validUrl("http://127.0.0.1:9000/x") && !Groq.validUrl("http://evil.com/x"), "Validacao de endereco da IA errada.");
    }

    private static void check(boolean c, String m) { if (!c) { throw new AssertionError(m); } }
    private static void rejects(Runnable r) { try { r.run(); } catch (IllegalArgumentException e) { return; } throw new AssertionError("Entrada invalida aceita."); }
}
