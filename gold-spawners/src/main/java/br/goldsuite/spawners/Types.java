package br.goldsuite.spawners;

import java.util.Locale;

/**
 * Os 10 spawners, do mais barato ao mais caro. O tipo vai no dano (meta) do item 52: 52:1 = galinha ... 52:10 = golem.
 * Drops: {id, meta, quantidade} por mob morto. Os precos de venda ficam nas placas da loja fisica.
 */
final class Types {
    static final class Type {
        final int id; final String key; final int network; final float height; final int[][] drops;
        Type(int id, String key, int network, float height, int[][] drops) { this.id = id; this.key = key; this.network = network; this.height = height; this.drops = drops; }
        String name() { String messageKey = "mob_" + key; return M.t(messageKey); }
    }

    static final Type[] ALL = {
        new Type(1, "galinha", 10, 0.7f, new int[][] {{288, 0, 1}, {365, 0, 1}}),
        new Type(2, "ovelha", 13, 1.3f, new int[][] {{35, 0, 2}}),
        new Type(3, "porco", 12, 0.9f, new int[][] {{319, 0, 2}}),
        new Type(4, "vaca", 11, 1.4f, new int[][] {{334, 0, 1}, {363, 0, 2}}),
        new Type(5, "zumbi", 32, 1.95f, new int[][] {{367, 0, 2}}),
        new Type(6, "esqueleto", 34, 1.95f, new int[][] {{352, 0, 2}, {262, 0, 2}}),
        new Type(7, "aranha", 35, 0.9f, new int[][] {{287, 0, 2}, {375, 0, 1}}),
        new Type(8, "creeper", 33, 1.7f, new int[][] {{289, 0, 2}}),
        new Type(9, "blaze", 43, 1.8f, new int[][] {{369, 0, 1}, {377, 0, 1}}),
        new Type(10, "golem", 20, 2.7f, new int[][] {{265, 0, 6}, {38, 0, 1}}),
    };

    static Type get(int id) { return id >= 1 && id <= ALL.length ? ALL[id - 1] : null; }

    /** Aceita numero (1-10) ou nome (zumbi). */
    static Type parse(String text) {
        String t = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        for (Type type : ALL) { if (type.key.equals(t) || String.valueOf(type.id).equals(t)) { return type; } }
        throw new IllegalArgumentException(M.t("tipo_invalido"));
    }

    private Types() {}
}
