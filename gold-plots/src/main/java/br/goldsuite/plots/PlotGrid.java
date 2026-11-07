package br.goldsuite.plots;

/**
 * Grade dos plots, so pela coordenada (nada pre-gerado nem guardado). Medidas do plotworld do pack:
 * plot de 42x42, borda de 1 bloco (com laje em cima) e rua de 11: uma celula de 55 blocos.
 * Celula (em x e em z): [0, rua) rua, rua = borda, (rua, rua+tamanho] plot, rua+tamanho+1 = borda.
 */
public final class PlotGrid {
    public enum Kind { ROAD, BORDER, PLOT }

    public final int size, road, height;
    /** Desloca a grade para casar com um mapa pronto (no plotworld do pack o plot comeca em x = -158, deslocamento 5). */
    public final int offset;
    public final int roadBlock, borderBlock, slabBlock, topBlock, fillBlock, roadFill;

    public PlotGrid(int size, int road, int height) { this(size, road, height, 0); }

    /** Blocos do plotworld do pack: rua de terra, borda de grama com laje de madeira, plot de grama sobre terra. */
    public PlotGrid(int size, int road, int height, int offset) { this(size, road, height, offset, 3, 2, 158, 2, 3, 3); }

    public PlotGrid(int size, int road, int height, int offset, int roadBlock, int borderBlock, int slabBlock, int topBlock, int fillBlock, int roadFill) {
        if (size < 8 || size > 200 || road < 3 || road > 40 || height < 8 || height > 120) { throw new IllegalArgumentException(M.t("grade_invalida")); }
        this.size = size; this.road = road; this.height = height; this.offset = offset;
        this.roadBlock = roadBlock; this.borderBlock = borderBlock; this.slabBlock = slabBlock; this.topBlock = topBlock; this.fillBlock = fillBlock; this.roadFill = roadFill;
    }

    /** Mapa "terrenos" do pack: terreno 42, borda de pedra com laje de pedra, rua de quartzo de 5 (rua de x=-2 a 2). */
    public static PlotGrid terrenos() { return new PlotGrid(42, 5, 64, 2, 155, 1, 44, 2, 1, 155); }

    public int cell() { return size + 2 + road; }

    public Kind kind(int v) {
        int l = Math.floorMod(v + offset, cell());
        if (l < road) { return Kind.ROAD; }
        if (l == road || l == road + size + 1) { return Kind.BORDER; }
        return Kind.PLOT;
    }

    /** Tipo da coluna: plot so quando x e z estao dentro do plot; borda vence a rua no canto. */
    public Kind kind(int x, int z) {
        Kind a = kind(x), b = kind(z);
        if (a == Kind.PLOT && b == Kind.PLOT) { return Kind.PLOT; }
        if (a == Kind.ROAD || b == Kind.ROAD) {
            // A borda de um plot continua ate a rua do lado; a rua cruza onde as duas coordenadas sao rua ou plot/rua.
            if ((a == Kind.BORDER && b != Kind.ROAD) || (b == Kind.BORDER && a != Kind.ROAD)) { return Kind.BORDER; }
            return Kind.ROAD;
        }
        return Kind.BORDER;
    }

    public int index(int v) { return Math.floorDiv(v + offset, cell()); }

    /** Id do plot ("x;z") ou null na rua e na borda. */
    public String id(int x, int z) { return kind(x, z) == Kind.PLOT ? index(x) + ";" + index(z) : null; }

    public static String key(int px, int pz) { return px + ";" + pz; }

    public static int[] parse(String id) {
        String[] p = id == null ? new String[0] : id.split(";");
        if (p.length != 2) { throw new IllegalArgumentException(M.t("plot_invalido")); }
        try { return new int[] {Integer.parseInt(p[0]), Integer.parseInt(p[1])}; } catch (NumberFormatException e) { throw new IllegalArgumentException(M.t("plot_invalido")); }
    }

    /** Primeiro bloco do plot (x ou z). */
    public int min(int index) { return index * cell() + road + 1 - offset; }
    public int max(int index) { return min(index) + size - 1; }

    /** Bloco da coluna na altura y, como o gerador faz (para limpar o plot). */
    public int block(Kind kind, int y) {
        if (y == 0) { return 7; }
        if (kind == Kind.ROAD) { return y < height ? roadFill : y == height ? roadBlock : 0; }
        if (kind == Kind.BORDER) { return y < height ? fillBlock : y == height ? borderBlock : y == height + 1 ? slabBlock : 0; }
        return y < height ? fillBlock : y == height ? topBlock : 0;
    }
}
