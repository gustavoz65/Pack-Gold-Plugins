package br.goldsuite.skills;

import java.util.*;

/** Tabelas e formulas do mcMMO do pack (experience.yml e advanced.yml), sem nada do servidor: testavel. */
final class Xp {
    private Xp() { }

    static final int LEVEL_CAP = 1000, POWER_CAP = 7000;

    /** XP para passar do nivel atual para o proximo (curva LINEAR: base 1020, multiplicador 20). */
    static int needed(int level) { return 1020 + 20 * level; }

    /** Chance em % que cresce ate {@code max} no nivel {@code maxLevel}. */
    static double chance(int level, double max, int maxLevel) { return Math.min(max, max * level / (double) maxLevel); }

    // ---------- blocos: (id << 8 | meta) -> XP base; meta -1 = qualquer ----------
    private static final Map<Integer, Integer> MINING = new HashMap<>(), WOOD = new HashMap<>(), DIG = new HashMap<>(), HERB = new HashMap<>();
    private static final Set<Integer> TRACKED = new HashSet<>();
    private static void put(Map<Integer, Integer> m, int id, int meta, int xp) { m.put(id << 8 | (meta & 0xff), xp); TRACKED.add(id); }

    static {
        // Mineracao (valores do pack; o modificador 2.0 e aplicado depois).
        put(MINING, 1, -1, 1); put(MINING, 16, -1, 2); put(MINING, 15, -1, 2); put(MINING, 14, -1, 2); put(MINING, 56, -1, 5); put(MINING, 129, -1, 5);
        put(MINING, 21, -1, 2); put(MINING, 73, -1, 1); put(MINING, 74, -1, 1); put(MINING, 24, -1, 1);
        for (int id : new int[] {87, 49, 153, 89, 121, 48, 172, 159, 174}) { put(MINING, id, -1, 0); }
        // Lenhador: carvalho, pinheiro, betula, selva; acacia e carvalho escuro; cogumelos gigantes.
        put(WOOD, 17, 0, 70); put(WOOD, 17, 1, 80); put(WOOD, 17, 2, 90); put(WOOD, 17, 3, 100); put(WOOD, 162, 0, 90); put(WOOD, 162, 1, 90);
        put(WOOD, 99, -1, 70); put(WOOD, 100, -1, 70);
        // Escavacao.
        for (int id : new int[] {2, 3, 13, 12, 82, 78, 80, 88, 110, 198}) { put(DIG, id, -1, 2); }
        // Herbalismo.
        for (int id : new int[] {59, 141, 142, 244, 115}) { put(HERB, id, -1, 50); }
        put(HERB, 103, -1, 30); put(HERB, 86, -1, 20); put(HERB, 83, -1, 37); put(HERB, 81, -1, 30); put(HERB, 127, -1, 30);
        put(HERB, 37, -1, 100); put(HERB, 38, 0, 100); put(HERB, 38, 2, 300);
        for (int m : new int[] {1, 3, 4, 5, 6, 7, 8}) { put(HERB, 38, m, 150); }
        put(HERB, 31, -1, 10); put(HERB, 32, -1, 30); put(HERB, 106, -1, 10); put(HERB, 111, -1, 100); put(HERB, 39, -1, 150); put(HERB, 40, -1, 150); put(HERB, 175, -1, 50);
    }

    private static Integer find(Map<Integer, Integer> m, int id, int meta) {
        Integer v = m.get(id << 8 | (meta & 0xff));
        return v != null ? v : m.get(id << 8 | 0xff);
    }

    /** Habilidade do bloco (pela ferramenta certa) ou null. */
    static Skill skillOf(int id, int meta, boolean pickaxe, boolean axe, boolean shovel) {
        if (pickaxe && find(MINING, id, meta) != null) { return Skill.MINERACAO; }
        if (axe && find(WOOD, id, meta) != null) { return Skill.LENHADOR; }
        if (shovel && find(DIG, id, meta) != null) { return Skill.ESCAVACAO; }
        if (find(HERB, id, meta) != null && mature(id, meta)) { return Skill.HERBALISMO; }
        return null;
    }

    /** O bloco conta para alguma habilidade (para rastrear os colocados por jogadores). */
    static boolean tracked(int id) { return TRACKED.contains(id); }

    static int blockXp(Skill s, int id, int meta) {
        Integer v = s == Skill.MINERACAO ? find(MINING, id, meta) : s == Skill.LENHADOR ? find(WOOD, id, meta & 3) : s == Skill.ESCAVACAO ? find(DIG, id, meta) : find(HERB, id, id == 38 ? meta : 0);
        return v == null ? 0 : v;
    }

    /** Planta madura (so ela da XP e drop duplo). */
    static boolean mature(int id, int meta) {
        switch (id) {
            case 59: case 141: case 142: case 244: return meta >= 7;
            case 115: return meta >= 3;
            case 127: return (meta >> 2) >= 2;
            default: return true;
        }
    }

    /** Multiplicador de XP de combate por entidade (Combat.Multiplier). */
    static double combat(int networkId) {
        switch (networkId) {
            case 63: return 1.0;                                   // jogador
            case 10: case 11: case 12: case 13: case 14: case 16: case 18: case 22: return 1.0; // animais
            case 32: case 34: case 35: case 36: case 40: case 43: return 4.0;
            case 33: return 8.0;
            case 20: return 6.0;
            default: return 0;
        }
    }

    // ---------- tesouros da escavacao (treasures.yml) ----------
    static final class Treasure {
        final int id, meta, level, xp; final double chance; final int[] from;
        Treasure(int id, int meta, int xp, double chance, int level, int... from) { this.id = id; this.meta = meta; this.xp = xp; this.chance = chance; this.level = level; this.from = from; }
    }
    static final int[] ALL_DIRT = {3, 2, 12, 13, 82, 110, 88}, DIRT = {3, 2, 110}, GRASS = {2, 110}, SAND = {12};
    static final List<Treasure> TREASURES = Arrays.asList(
            new Treasure(354, 0, 300, 0.05, 750, ALL_DIRT),       // bolo
            new Treasure(289, 0, 5, 10.0, 75, 13),                // polvora
            new Treasure(352, 0, 5, 10.0, 175, 13),               // osso
            new Treasure(260, 0, 5, 0.1, 250, GRASS),             // maca
            new Treasure(341, 0, 5, 5.0, 150, 82),                // slime
            new Treasure(325, 0, 5, 0.1, 500, 82),                // balde
            new Treasure(87, 0, 5, 0.5, 850, 13),                 // netherrack
            new Treasure(40, 0, 10, 0.5, 500, DIRT),              // cogumelo vermelho
            new Treasure(39, 0, 10, 0.5, 500, DIRT),              // cogumelo marrom
            new Treasure(344, 0, 5, 1.0, 250, 2),                 // ovo
            new Treasure(88, 0, 10, 0.5, 650, SAND),              // areia das almas
            new Treasure(347, 0, 10, 0.1, 500, 82),               // relogio
            new Treasure(30, 0, 10, 5.0, 750, 82),                // teia
            new Treasure(287, 0, 10, 5.0, 250, 82),               // linha
            new Treasure(348, 0, 5, 5.0, 25, 3, 2, 12, 110),      // po de pedra luminosa
            new Treasure(266, 0, 50, 0.05, 750, ALL_DIRT),        // ouro
            new Treasure(264, 0, 100, 0.02, 900, ALL_DIRT));      // diamante

    static boolean from(Treasure t, int blockId) { for (int f : t.from) { if (f == blockId) { return true; } } return false; }

    /** Material de reparo de um item (id) e o XP do material (Repair.*), ou null se nao repara. */
    static int[] repairMaterial(int itemId) {
        if (itemId == 268 || itemId == 269 || itemId == 270 || itemId == 271 || itemId == 290) { return new int[] {5, 10}; }      // madeira
        if (itemId == 272 || itemId == 273 || itemId == 274 || itemId == 275 || itemId == 291) { return new int[] {4, 10}; }      // pedra
        if (itemId == 256 || itemId == 257 || itemId == 258 || itemId == 267 || itemId == 292 || (itemId >= 306 && itemId <= 309) || (itemId >= 302 && itemId <= 305)) { return new int[] {265, 60}; }
        if (itemId == 283 || itemId == 284 || itemId == 285 || itemId == 286 || itemId == 294 || (itemId >= 314 && itemId <= 317)) { return new int[] {266, 80}; }
        if (itemId == 276 || itemId == 277 || itemId == 278 || itemId == 279 || itemId == 293 || (itemId >= 310 && itemId <= 313)) { return new int[] {264, 90}; }
        if (itemId >= 298 && itemId <= 301) { return new int[] {334, 60}; }
        return null;
    }

    /** Quantas unidades do material o item leva na receita (o reparo devolve 1/n da durabilidade por unidade). */
    static int recipeUnits(int itemId) {
        if (itemId == 298 || itemId == 302 || itemId == 306 || itemId == 310 || itemId == 314) { return 5; }
        if (itemId == 299 || itemId == 303 || itemId == 307 || itemId == 311 || itemId == 315) { return 8; }
        if (itemId == 300 || itemId == 304 || itemId == 308 || itemId == 312 || itemId == 316) { return 7; }
        if (itemId == 301 || itemId == 305 || itemId == 309 || itemId == 313 || itemId == 317) { return 4; }
        int[] pick = {257, 270, 274, 278, 285}, axe = {258, 271, 275, 279, 286}, sword = {267, 268, 272, 276, 283}, hoe = {290, 291, 292, 293, 294};
        for (int i : pick) { if (i == itemId) { return 3; } }
        for (int i : axe) { if (i == itemId) { return 3; } }
        for (int i : sword) { if (i == itemId) { return 2; } }
        for (int i : hoe) { if (i == itemId) { return 2; } }
        return 1;
    }
}
