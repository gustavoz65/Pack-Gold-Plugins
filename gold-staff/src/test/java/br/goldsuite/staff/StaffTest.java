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
        System.out.println("GoldStaff: reports, agendamentos, comandos bloqueados e validacao aprovados.");
    }
    private static void check(boolean c, String m) { if (!c) { throw new AssertionError(m); } }
    private static void rejects(Runnable r) { try { r.run(); } catch (IllegalArgumentException e) { return; } throw new AssertionError("Entrada invalida aceita."); }
}
