package br.goldsuite.pvp;

import com.google.gson.Gson;
import java.util.HashMap;
import java.util.Map;

public final class PvpTest {
    public static void main(String[] args) {
        Profile p = new Profile();
        p.kill(); p.kill(); p.death(); p.kill();
        check(p.kills == 3 && p.deaths == 1 && p.streak == 1 && p.bestStreak == 2 && p.kdr().equals("3.00"), "Perfil errado.");
        check(Profile.eloDelta(1000, 1000) == 16, "ELO entre iguais deveria dar 16.");
        check(Profile.eloDelta(1400, 1000) < 16 && Profile.eloDelta(1000, 1400) > 16, "Zebra deveria valer mais.");
        // Elos estilo Valorant: 100 PR por divisao, Imortal a partir de 2100, Divino so com a vaga do top 10.
        check(Ranks.name(0, false).equals("Ferro 1") && Ranks.name(299, false).equals("Ferro 3") && Ranks.name(300, false).equals("Bronze 1"), "Divisoes erradas.");
        check(Ranks.name(2099, false).equals("Ascendente 3") && Ranks.name(2100, false).equals("Imortal 1") && Ranks.name(2100 + 95, false).equals("Imortal 2") && Ranks.name(2100 + 250, false).equals("Imortal 3"), "Imortal errado.");
        check(Ranks.name(5000, true).equals("Divino") && Ranks.name(1500, true).equals("Diamante 1") && Ranks.points(2350) == 250 && Ranks.points(1234) == 34, "Divino ou pontos errados.");
        check(Ranks.placement(5000) == Ranks.PLACEMENT_CAP && Ranks.placement(500) == 0 && Ranks.name(-1, false).equals("Sem elo"), "Classificatoria errada.");
        check(Ranks.winGain(1900, 0) > Ranks.winGain(800, 0) && Ranks.lossCost(1900, 0) < Ranks.lossCost(800, 2000), "MMR nao ajusta os pontos.");
        // Anti-fraude: mesma dupla, abates repetidos, entrega de partida e advertencias.
        Fraud f = new Fraud();
        long t = 1_000_000L;
        check(Fraud.pair("bob", "ana").equals(Fraud.pair("ana", "bob")) && Fraud.sameIp("1.2.3.4", "1.2.3.4") && !Fraud.sameIp("", ""), "Dupla/IP errados.");
        check(f.canPair("ana", "bob", t), "Primeira partida bloqueada.");
        f.recordMatch("ana", "bob", t); f.recordMatch("bob", "ana", t + 1000);
        check(!f.canPair("ana", "bob", t + 2000) && f.canPair("ana", "caio", t + 2000) && f.canPair("ana", "bob", t + Fraud.PAIR_WINDOW + 2000), "Janela da dupla errada.");
        for (int i = 0; i < Fraud.KILL_MAX; i++) { check(f.killCounts("ana", "bob", t + i), "Abate valido recusado."); }
        check(!f.killCounts("ana", "bob", t + 10) && f.killCounts("bob", "ana", t + 10) && f.killCounts("ana", "bob", t + Fraud.KILL_WINDOW + 20), "Farm de abates nao detectado.");
        check(Fraud.feeding(10_000, 0) && !Fraud.feeding(10_000, 10) && !Fraud.feeding(60_000, 0), "Entrega de partida errada.");
        check(f.strike("ana", t) == 1 && f.strike("ana", t + 1) == 2 && f.strike("ana", t + 2) == Fraud.KICK_AT && f.strikes("ana", t + Fraud.STRIKE_WINDOW + 10) == 0, "Advertencias erradas.");
        f.prune(t + Fraud.STRIKE_WINDOW * 2);
        check(f.canPair("ana", "bob", t + Fraud.STRIKE_WINDOW * 2), "Limpeza falhou.");
        for (int mmr = 0; mmr <= 3000; mmr += 100) { for (int l = 0; l <= 3000; l += 150) { int w = Ranks.winGain(mmr, l), c = Ranks.lossCost(mmr, l); check(w >= 12 && w <= 30 && c >= 10 && c <= 28, "PR fora dos limites."); } }
        Profile back = new Gson().fromJson(new Gson().toJson(p), Profile.class);
        back.validate();
        check(back.rating == Profile.START_RATING && back.ladder == -1 && !back.showElo, "Perfil inicial perdido.");
        Profile old = new com.google.gson.Gson().fromJson("{\"kills\":3,\"rating\":1200}", Profile.class);
        check(old.ladder == -1 && old.peakLadder == -1, "Perfil antigo deveria comecar nas classificatorias.");

        Map<String, Integer> board = new HashMap<>();
        for (int i = 0; i < Boards.SIZE + 20; i++) { Boards.offer(board, "j" + String.format("%03d", i), i); }
        check(board.size() == Boards.SIZE && !board.containsKey("j000") && board.containsKey("j119"), "Top 100 nao cortou os menores.");
        check(Boards.position(board, "j119") == 1 && Boards.sorted(board).get(0).getKey().equals("j119"), "Ordenacao errada.");
        Boards b = new Boards(); b.kills.putAll(board);
        Boards.validate(new Gson().fromJson(new Gson().toJson(b), Boards.class));
        System.out.println("GoldPvP: perfil, elos estilo Valorant, anti-fraude, top 100 e validacao aprovados.");
    }
    private static void check(boolean c, String m) { if (!c) { throw new AssertionError(m); } }
}
