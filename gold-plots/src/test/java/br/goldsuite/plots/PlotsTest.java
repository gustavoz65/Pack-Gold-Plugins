package br.goldsuite.plots;

/** Regressao sem servidor: grade do plotworld (42 + borda + rua 11 = 55), ids, limites e camadas. */
public final class PlotsTest {
    public static void main(String[] args) {
        PlotGrid g = new PlotGrid(42, 11, 64);
        check(g.cell() == 55, "celula de 55");
        for (int x = 0; x < 11; x++) { check(g.kind(x) == PlotGrid.Kind.ROAD, "rua em " + x); }
        check(g.kind(11) == PlotGrid.Kind.BORDER && g.kind(54) == PlotGrid.Kind.BORDER, "bordas");
        for (int x = 12; x <= 53; x++) { check(g.kind(x) == PlotGrid.Kind.PLOT, "plot em " + x); }
        check(g.id(12, 12).equals("0;0") && g.id(53, 53).equals("0;0") && g.id(11, 20) == null && g.id(5, 20) == null, "ids do plot 0;0");
        check(g.id(-1, 20) == null && g.id(-2, 20).equals("-1;0") && g.id(-43, 20).equals("-1;0") && g.id(-44, 20) == null, "plot negativo");
        check(g.min(0) == 12 && g.max(0) == 53 && g.min(-1) == -43, "limites");
        check(g.kind(5, 30) == PlotGrid.Kind.ROAD && g.kind(11, 30) == PlotGrid.Kind.BORDER && g.kind(11, 5) == PlotGrid.Kind.ROAD && g.kind(30, 30) == PlotGrid.Kind.PLOT, "colunas");
        check(g.block(PlotGrid.Kind.PLOT, 0) == 7 && g.block(PlotGrid.Kind.PLOT, 63) == 3 && g.block(PlotGrid.Kind.PLOT, 64) == 2 && g.block(PlotGrid.Kind.PLOT, 65) == 0, "camadas do plot");
        check(g.block(PlotGrid.Kind.BORDER, 65) == 158 && g.block(PlotGrid.Kind.ROAD, 64) == 3, "laje na borda e terra na rua (como o pack)");
        // Grade do plotworld do pack: plot de x = -158 a -117, borda em -159 e -116, rua ate -160.
        PlotGrid pack = new PlotGrid(42, 11, 64, 5);
        check(pack.kind(-158) == PlotGrid.Kind.PLOT && pack.kind(-117) == PlotGrid.Kind.PLOT && pack.kind(-159) == PlotGrid.Kind.BORDER && pack.kind(-116) == PlotGrid.Kind.BORDER && pack.kind(-160) == PlotGrid.Kind.ROAD, "grade casa com o pack");
        int i = pack.index(-130);
        check(pack.min(i) == -158 && pack.max(i) == -117 && pack.id(-130, -130).equals(i + ";" + i), "limites com deslocamento");
        // Mapa "terrenos" do pack: terreno de x=4 a 45, borda (laje de pedra) em 3 e 46, rua de quartzo de -2 a 2.
        PlotGrid t = PlotGrid.terrenos();
        check(t.cell() == 49 && t.kind(4) == PlotGrid.Kind.PLOT && t.kind(45) == PlotGrid.Kind.PLOT && t.kind(3) == PlotGrid.Kind.BORDER && t.kind(46) == PlotGrid.Kind.BORDER && t.kind(-2) == PlotGrid.Kind.ROAD && t.kind(2) == PlotGrid.Kind.ROAD && t.kind(47) == PlotGrid.Kind.ROAD, "grade casa com o terrenos");
        check(t.id(10, 20).equals("0;0") && t.min(0) == 4 && t.min(-1) == -45 && t.block(PlotGrid.Kind.ROAD, 64) == 155 && t.block(PlotGrid.Kind.BORDER, 65) == 44 && t.block(PlotGrid.Kind.PLOT, 63) == 1, "ids e blocos do terrenos");
        int[] ij = PlotGrid.parse("-3;7");
        check(ij[0] == -3 && ij[1] == 7, "parse");
        for (String bad : new String[] {"", "1", "a;b", "1;2;3"}) { try { PlotGrid.parse(bad); check(false, "id invalido aceito"); } catch (IllegalArgumentException expected) { } }
        try { new PlotGrid(2, 11, 64); check(false, "grade pequena aceita"); } catch (IllegalArgumentException expected) { }
        System.out.println("GoldPlots: grade 42/11/64, ids, bordas, ruas e camadas aprovados.");
    }

    private static void check(boolean ok, String msg) { if (!ok) { throw new AssertionError(msg); } }
}
