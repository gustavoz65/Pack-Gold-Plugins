package br.goldsuite.pvp;

import java.util.Locale;

/** Perfil de PvP de um jogador; gravado em chave propria ("goldpvp-perfil"). */
public final class Profile {
    public static final int START_RATING = 1000, K = 32;
    public int kills, deaths, streak, bestStreak;
    public int rating = START_RATING, peakRating = START_RATING, rankedWins, rankedLosses, rankedDraws;
    /** Em meios coracoes. */
    public long damageDealt, damageTaken;
    public long firstSeen = System.currentTimeMillis();

    public void kill() { kills++; streak++; bestStreak = Math.max(bestStreak, streak); }
    public void death() { deaths++; streak = 0; }

    public String kdr() { return deaths == 0 ? String.valueOf(kills) : String.format(Locale.ROOT, "%.2f", (double) kills / deaths); }
    public String winRate() { int games = rankedWins + rankedLosses; return games == 0 ? "-" : (rankedWins * 100 / games) + "%"; }

    /** Pontos que o vencedor ganha (e o perdedor perde) no ELO classico com K = 32. */
    public static int eloDelta(int winner, int loser) {
        double expected = 1.0 / (1.0 + Math.pow(10, (loser - winner) / 400.0));
        return Math.max(1, (int) Math.round(K * (1 - expected)));
    }

    public static String tier(int rating) {
        if (rating < 1100) { return "§6Bronze"; }
        if (rating < 1300) { return "§7Prata"; }
        if (rating < 1500) { return "§eOuro"; }
        if (rating < 1700) { return "§bPlatina"; }
        if (rating < 1900) { return "§3Diamante"; }
        return "§dMestre";
    }

    public void validate() {
        if (kills < 0 || deaths < 0 || streak < 0 || bestStreak < 0 || rating < 0 || rating > 10000 || rankedWins < 0 || rankedLosses < 0 || rankedDraws < 0 || damageDealt < 0 || damageTaken < 0) {
            throw new IllegalArgumentException("Perfil invalido.");
        }
    }
}
