package br.goldsuite.plots;

import java.util.Collections;
import java.util.Map;

/** Gerador "goldplots": grade do plotworld do pack (plot 42, rua de terra de 11). */
public final class PlotGenerator extends GridGenerator {
    static final int TYPE = 10;
    static volatile PlotGrid grid = new PlotGrid(42, 11, 64, 5);
    public PlotGenerator() { this(Collections.emptyMap()); }
    public PlotGenerator(Map<String, Object> options) { super(options); }
    @Override PlotGrid grid() { return grid; }
    @Override public int getId() { return TYPE; }
    @Override public String getName() { return "goldplots"; }
}
