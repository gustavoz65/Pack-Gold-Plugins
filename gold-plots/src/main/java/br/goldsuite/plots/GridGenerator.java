package br.goldsuite.plots;

import cn.nukkit.level.ChunkManager;
import cn.nukkit.level.format.FullChunk;
import cn.nukkit.level.generator.Generator;
import cn.nukkit.level.generator.biome.Biome;
import cn.nukkit.math.NukkitRandom;
import cn.nukkit.math.Vector3;
import java.util.Collections;
import java.util.Map;

/** Mundo de plots/terrenos infinito: cada chunk e desenhado pela coordenada (grade em PlotGrid), sem pre-gerar nada. */
public abstract class GridGenerator extends Generator {
    /** Grade deste gerador (o plugin troca pela do painel ao ligar, antes de abrir o mundo). */
    abstract PlotGrid grid();
    private ChunkManager level;
    private final Map<String, Object> options;

    GridGenerator() { this(Collections.emptyMap()); }
    GridGenerator(Map<String, Object> options) { this.options = options; }

        @Override public void init(ChunkManager level, NukkitRandom random) { this.level = level; }

    @Override public void generateChunk(int chunkX, int chunkZ) {
        FullChunk chunk = level.getChunk(chunkX, chunkZ);
        if (chunk == null) { return; }
        PlotGrid g = grid();
        int color = Biome.getBiome(1).getColor();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                PlotGrid.Kind kind = g.kind(chunkX * 16 + x, chunkZ * 16 + z);
                chunk.setBiomeId(x, z, 1);
                chunk.setBiomeColor(x, z, (color >> 16) & 0xFF, (color >> 8) & 0xFF, color & 0xFF);
                for (int y = 0; y <= g.height + 1; y++) {
                    int id = g.block(kind, y);
                    if (id != 0) { chunk.setBlock(x, y, z, id, 0); }
                }
            }
        }
        chunk.setGenerated();
    }

    @Override public void populateChunk(int chunkX, int chunkZ) { }
    @Override public Map<String, Object> getSettings() { return options; }
        @Override public Vector3 getSpawn() { PlotGrid g = grid(); return new Vector3(g.road / 2.0, g.height + 1, g.road / 2.0); }
    @Override public ChunkManager getChunkManager() { return level; }
}
