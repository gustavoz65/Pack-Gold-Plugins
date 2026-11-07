package br.goldsuite.world;

import cn.nukkit.level.ChunkManager;
import cn.nukkit.level.format.FullChunk;
import cn.nukkit.level.generator.Generator;
import cn.nukkit.math.NukkitRandom;
import cn.nukkit.math.Vector3;
import java.util.Collections;
import java.util.Map;

/**
 * Mundo vazio ("void"): chunks novos nascem so com ar. Mapas de arena convertidos do PC dizem "void" no level.dat;
 * sem este gerador o Nukkit usaria o normal e criaria terreno em volta das ilhas.
 */
public final class VoidGenerator extends Generator {
    /** Tipo proprio, para nao trocar o gerador de nenhum tipo padrao do Nukkit. */
    static final int TYPE = 9;
    private ChunkManager level;
    private final Map<String, Object> options;

    public VoidGenerator() { this(Collections.emptyMap()); }
    public VoidGenerator(Map<String, Object> options) { this.options = options; }

    @Override public int getId() { return TYPE; }
    @Override public void init(ChunkManager level, NukkitRandom random) { this.level = level; }
    @Override public void generateChunk(int chunkX, int chunkZ) {
        FullChunk chunk = level.getChunk(chunkX, chunkZ);
        if (chunk != null) { chunk.setGenerated(); }
    }
    @Override public void populateChunk(int chunkX, int chunkZ) { }
    @Override public Map<String, Object> getSettings() { return options; }
    @Override public String getName() { return "void"; }
    @Override public Vector3 getSpawn() { return new Vector3(0.5, 64, 0.5); }
    @Override public ChunkManager getChunkManager() { return level; }
}
