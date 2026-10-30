package br.goldsuite.pvp;

/**
 * Elos no estilo Valorant. Cada divisao vale 100 pontos de rank (PR): Ferro 1 a Ascendente 3 sao 21 divisoes.
 * A partir de 2100 o jogador e Imortal e os pontos acumulam sem teto; os 10 Imortais com mais pontos sao Divinos.
 * O MMR (rating ELO) continua oculto, decide o pareamento e quanto PR cada partida vale.
 */
final class Ranks {
    static final String[] NAMES = {"Ferro", "Bronze", "Prata", "Ouro", "Platina", "Diamante", "Ascendente", "Imortal", "Divino"};
    static final char[] COLORS = {'8', '6', '7', 'e', '3', 'b', 'a', 'c', '6'};
    /** Item de cada elo no menu. */
    static final int[] ICONS = {265, 336, 406, 266, 370, 264, 388, 331, 466};
    static final int PLACEMENTS = 5, DIVISION = 100, IMMORTAL = 2100, DIVINE_SLOTS = 10, PLACEMENT_CAP = 1800;
    /** Pontos de Imortal para Imortal 2 e Imortal 3. */
    static final int IMMORTAL_2 = 90, IMMORTAL_3 = 200;

    private Ranks() {}

    /** Indice do elo (0 Ferro ... 7 Imortal, 8 Divino). */
    static int tier(int ladder, boolean divine) {
        if (ladder >= IMMORTAL) { return divine ? 8 : 7; }
        return Math.max(0, ladder) / (DIVISION * 3);
    }

    /** Pontos dentro da divisao atual (no Imortal/Divino, o total acima de 2100). */
    static int points(int ladder) { return ladder >= IMMORTAL ? ladder - IMMORTAL : Math.max(0, ladder) % DIVISION; }

    static String name(int ladder, boolean divine) {
        if (ladder < 0) { return "Sem elo"; }
        int t = tier(ladder, divine);
        if (t == 8) { return NAMES[8]; }
        int division;
        if (t == 7) { int p = points(ladder); division = p >= IMMORTAL_3 ? 3 : p >= IMMORTAL_2 ? 2 : 1; }
        else { division = Math.max(0, ladder) / DIVISION % 3 + 1; }
        return NAMES[t] + " " + division;
    }

    /** Nome colorido com os pontos, para o perfil e mensagens. */
    static String display(int ladder, boolean divine, int games) {
        if (ladder < 0) { return "§7Sem elo §8(classificatoria " + Math.min(games, PLACEMENTS) + "/" + PLACEMENTS + ")"; }
        int t = tier(ladder, divine);
        return "§" + COLORS[t] + (t == 8 ? "§l" : "") + name(ladder, divine) + "§r §7(" + points(ladder) + " PR)";
    }

    /** Tag curta para o chat. */
    static String chatTag(int ladder, boolean divine) {
        int t = tier(ladder, divine);
        return "§8[§" + COLORS[t] + (t == 8 ? "§l" : "") + name(ladder, divine) + "§r§8]";
    }

    /** Onde o MMR diz que o jogador deveria estar na escada. */
    static int expected(int mmr) { return (int) Math.max(0, Math.min(IMMORTAL + 600, (mmr - 700) * 1.75)); }

    /** Elo inicial depois das classificatorias: segue o MMR, no maximo Ascendente 1 (como no Valorant). */
    static int placement(int mmr) { return Math.min(PLACEMENT_CAP, expected(mmr)); }

    /** Ajuste pelo MMR: quem joga acima do elo mostrado sobe mais rapido e cai mais devagar. */
    private static int bias(int mmr, int ladder) { return (expected(mmr) - ladder) / 50; }

    static int winGain(int mmr, int ladder) { return 20 + clamp(bias(mmr, ladder), -8, 10); }
    static int lossCost(int mmr, int ladder) { return clamp(18 - bias(mmr, ladder), 10, 28); }
    static int drawChange(int mmr, int ladder) { return clamp(bias(mmr, ladder) / 2, -5, 5); }

    private static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }
}
