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
        check(Profile.tier(1000).contains("Bronze") && Profile.tier(1550).contains("Platina") && Profile.tier(2000).contains("Mestre"), "Ligas erradas.");
        Profile back = new Gson().fromJson(new Gson().toJson(p), Profile.class);
        back.validate();
        check(back.rating == Profile.START_RATING, "Rating inicial perdido.");

        Map<String, Integer> board = new HashMap<>();
        for (int i = 0; i < Boards.SIZE + 20; i++) { Boards.offer(board, "j" + String.format("%03d", i), i); }
        check(board.size() == Boards.SIZE && !board.containsKey("j000") && board.containsKey("j119"), "Top 100 nao cortou os menores.");
        check(Boards.position(board, "j119") == 1 && Boards.sorted(board).get(0).getKey().equals("j119"), "Ordenacao errada.");
        Boards b = new Boards(); b.kills.putAll(board);
        Boards.validate(new Gson().fromJson(new Gson().toJson(b), Boards.class));
        System.out.println("GoldPvP: perfil, ELO, ligas, top 100 e validacao aprovados.");
    }
    private static void check(boolean c, String m) { if (!c) { throw new AssertionError(m); } }
}
