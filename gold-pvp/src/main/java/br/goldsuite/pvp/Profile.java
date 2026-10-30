package br.goldsuite.pvp;

import java.util.Locale;

/** Perfil de PvP de um jogador; gravado em chave propria ("goldpvp-perfil"). */
public final class Profile {
    public static final int START_RATING = 1000, K = 32;
    public int kills, deaths, streak, bestStreak;
    /** MMR oculto (ELO): decide o pareamento e quanto PR cada partida vale. */
    public int rating = START_RATING, peakRating = START_RATING, rankedWins, rankedLosses, rankedDraws;
    /** Pontos de rank na escada de elos (-1 = ainda nas classificatorias). Ver {@link Ranks}. */
    public int ladder = -1, peakLadder = -1;
    /** Mostra o elo antes do nome no chat. */
    public boolean showElo;
    /** Fila do ranked bloqueada ate este instante (fraude repetida). */
    public long rankedBanUntil;
    /** Em meios coracoes. */
    public long damageDealt, damageTaken;
    public long firstSeen = System.currentTimeMillis();

    public void kill() { kills++; streak++; bestStreak = Math.max(bestStreak, streak); }
    public void death() { deaths++; streak = 0; }

    public String kdr() { return deaths == 0 ? String.valueOf(kills) : String.format(Locale.ROOT, "%.2f", (double) kills / deaths); }
    public int games() { return rankedWins + rankedLosses + rankedDraws; }
    public String winRate() { int games = rankedWins + rankedLosses; return games == 0 ? "-" : (rankedWins * 100 / games) + "%"; }

    /** Pontos que o vencedor ganha (e o perdedor perde) no ELO classico com K = 32. */
    public static int eloDelta(int winner, int loser) {
        double expected = 1.0 / (1.0 + Math.pow(10, (loser - winner) / 400.0));
        return Math.max(1, (int) Math.round(K * (1 - expected)));
    }


    public void validate() {
        if (kills < 0 || deaths < 0 || streak < 0 || bestStreak < 0 || rating < 0 || rating > 10000 || rankedWins < 0 || rankedLosses < 0 || rankedDraws < 0 || ladder < -1 || ladder > 1_000_000 || peakLadder < -1 || peakLadder > 1_000_000 || damageDealt < 0 || damageTaken < 0) {
            throw new IllegalArgumentException("Perfil invalido.");
        }
    }
}
