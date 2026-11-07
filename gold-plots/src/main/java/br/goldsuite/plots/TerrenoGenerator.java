package br.goldsuite.plots;

import java.util.Collections;
import java.util.Map;

/** Gerador "goldterrenos": grade do mapa terrenos do pack (terreno 42, borda de pedra com laje, rua de quartzo de 5). */
public final class TerrenoGenerator extends GridGenerator {
    static final int TYPE = 11;
    static volatile PlotGrid grid = PlotGrid.terrenos();
    public TerrenoGenerator() { this(Collections.emptyMap()); }
    public TerrenoGenerator(Map<String, Object> options) { super(options); }
    @Override PlotGrid grid() { return grid; }
    @Override public int getId() { return TYPE; }
    @Override public String getName() { return "goldterrenos"; }
}
