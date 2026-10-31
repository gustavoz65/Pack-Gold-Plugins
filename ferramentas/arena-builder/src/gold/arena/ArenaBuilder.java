package gold.arena;

import cn.nukkit.block.Block;
import cn.nukkit.blockentity.BlockEntity;
import cn.nukkit.level.Level;
import cn.nukkit.level.format.FullChunk;
import cn.nukkit.level.generator.Flat;
import cn.nukkit.math.Vector3;
import cn.nukkit.nbt.tag.CompoundTag;
import cn.nukkit.nbt.tag.ListTag;
import cn.nukkit.plugin.PluginBase;
import java.util.*;

/**
 * Gera dois mapas prontos: "sw_ilhas" (SkyWars, 8 ilhas + meio com baus) e "bw_quatro" (BedWars, 4 bases com cama
 * e forja, 4 ilhas de diamante e o meio com esmeraldas). Escreve em plugins/ArenaBuilder/pontos.txt onde marcar cada
 * jaula, cama, forja e gerador. Os chunks saem marcados como gerados (senao o Nukkit os recria vazios).
 */
public final class ArenaBuilder extends PluginBase {
    static final int Y = 64;
    final List<String> out = new ArrayList<>();
    Level w;

    @Override public void onEnable() {
        getServer().getScheduler().scheduleDelayedTask(() -> {
            try { skywars(); bedwars(); getDataFolder().mkdirs(); java.nio.file.Files.write(new java.io.File(getDataFolder(), "pontos.txt").toPath(), out); }
            catch (Throwable t) { getLogger().error("Falha", t); }
            getLogger().info("ARENAS_PRONTAS");
        }, 40);
    }

    Level world(String name) {
        Map<String, Object> o = new HashMap<>(); o.put("preset", "2;0;1;");
        if (!getServer().isLevelGenerated(name)) { getServer().generateLevel(name, 1L, Flat.class, o); }
        if (getServer().getLevelByName(name) == null) { getServer().loadLevel(name); }
        Level lv = getServer().getLevelByName(name);
        for (int cx = -6; cx <= 5; cx++) { for (int cz = -6; cz <= 5; cz++) { lv.loadChunk(cx, cz, true); } }
        return lv;
    }

    void finish(Level lv, double sx, double sy, double sz) {
        for (int cx = -6; cx <= 5; cx++) { for (int cz = -6; cz <= 5; cz++) { FullChunk c = lv.getChunk(cx, cz, false); if (c != null) { paintBiome(c); c.setGenerated(true); c.setPopulated(true); c.setChanged(true); } } }
        lv.setSpawnLocation(new Vector3(sx, sy, sz));
        lv.save(true);
    }


    /** Bioma de planicie e a cor dele em todas as colunas: sem isso a grama aparece preta no cliente. */
    static void paintBiome(cn.nukkit.level.format.FullChunk c) {
        int color = cn.nukkit.level.generator.biome.Biome.getBiome(1).getColor();
        for (int x = 0; x < 16; x++) { for (int z = 0; z < 16; z++) { c.setBiomeId(x, z, 1); c.setBiomeColor(x, z, (color >> 16) & 0xff, (color >> 8) & 0xff, color & 0xff); } }
    }

    void set(int x, int y, int z, int id, int meta) { w.setBlock(new Vector3(x, y, z), Block.get(id, meta), true, false); }

    void chest(int x, int y, int z) {
        set(x, y, z, 54, 2);
        CompoundTag nbt = new CompoundTag().putList(new ListTag<>("Items")).putString("id", BlockEntity.CHEST).putInt("x", x).putInt("y", y).putInt("z", z);
        BlockEntity.createBlockEntity(BlockEntity.CHEST, w.getChunk(x >> 4, z >> 4, true), nbt);
    }

    /** Ilha arredondada: grama em cima, terra e pedra afinando para baixo. */
    void island(int cx, int cz, int r, int top, int grassId, int grassMeta) {
        for (int x = -r - 1; x <= r + 1; x++) {
            for (int z = -r - 1; z <= r + 1; z++) {
                double d = Math.sqrt(x * x + z * z) + Math.sin(Math.atan2(z, x) * 3 + cx) * 0.5;
                if (d > r) { continue; }
                int depth = 2 + (int) ((r - d) * 0.9);
                for (int y = top - depth; y <= top; y++) { set(cx + x, y, cz + z, y == top ? grassId : y > top - 3 ? 3 : 1, y == top ? grassMeta : 0); }
            }
        }
    }

    void tree(int x, int y, int z) {
        for (int i = 0; i < 4; i++) { set(x, y + i, z, 17, 0); }
        for (int dx = -2; dx <= 2; dx++) { for (int dy = 3; dy <= 5; dy++) { for (int dz = -2; dz <= 2; dz++) { if (Math.abs(dx) + Math.abs(dz) + (dy - 3) <= 3 && (dx != 0 || dz != 0 || dy > 3)) { set(x + dx, y + dy, z + dz, 18, 0); } } } }
    }

    void skywars() {
        w = world("sw_ilhas");
        out.add("== SkyWars (mundo sw_ilhas) ==");
        out.add("# Cole os comandos na ordem, dentro do jogo (o admin precisa estar no mundo sw_ilhas).");
        out.add("/mundo tp sw_ilhas");
        out.add("/sw criar ilhas solo");
        out.add("/sw meio 0.5 " + (Y + 3) + " 0.5");
        for (int i = 0; i < 8; i++) {
            double a = Math.PI * 2 * i / 8;
            int cx = (int) Math.round(Math.cos(a) * 34), cz = (int) Math.round(Math.sin(a) * 34);
            island(cx, cz, 5, Y, 2, 0);
            // Dois baus na ilha e uma arvore pequena.
            int ox = (int) Math.round(Math.cos(a)), oz = (int) Math.round(Math.sin(a));
            chest(cx + 2 * ox - oz * 2, Y + 1, cz + 2 * oz + ox * 2);
            chest(cx + 2 * ox + oz * 2, Y + 1, cz + 2 * oz - ox * 2);
            tree(cx + 3 * ox, Y + 1, cz + 3 * oz);
            out.add("/sw jaula " + (cx + 0.5) + " " + (Y + 4) + " " + (cz + 0.5));
        }
        island(0, 0, 8, Y + 2, 2, 0);
        for (int x = -2; x <= 2; x++) { for (int z = -2; z <= 2; z++) { set(x, Y + 2, z, 98, (x + z) % 3 == 0 ? 3 : 0); } }
        int[][] mid = {{2, 0}, {-2, 0}, {0, 2}, {0, -2}};
        for (int[] m : mid) { chest(m[0], Y + 3, m[1]); }
        set(0, Y + 3, 0, 89, 0);
        out.add("/sw salvar");
        finish(w, 0.5, Y + 4, 0.5);
    }

    void bed(int x, int y, int z, int dir) {
        int dx = dir == 3 ? 1 : dir == 1 ? -1 : 0, dz = dir == 0 ? 1 : dir == 2 ? -1 : 0;
        set(x, y, z, 26, dir);
        set(x + dx, y, z + dz, 26, dir | 8);
    }

    void bedwars() {
        w = world("bw_quatro");
        out.add("");
        out.add("== BedWars (mundo bw_quatro) ==");
        out.add("# Cole os comandos na ordem, dentro do jogo. Troque solo por dupla ou quarteto se quiser.");
        out.add("/mundo tp bw_quatro");
        out.add("/bw criar quatro solo");
        out.add("/bw espera 0.5 " + (Y + 31) + " 0.5");
        String[] teams = {"vermelho", "azul", "verde", "amarelo"};
        int[] wool = {14, 11, 5, 4};
        int[][] dirs = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};
        for (int i = 0; i < 4; i++) {
            int bx = dirs[i][0] * 42, bz = dirs[i][1] * 42;
            island(bx, bz, 7, Y, 2, 0);
            for (int x = -2; x <= 2; x++) { for (int z = -2; z <= 2; z++) { set(bx + x, Y, bz + z, 35, wool[i]); } }
            // Cama atras do spawn (lado de fora), forja na frente.
            int backX = bx + dirs[i][0] * 4, backZ = bz + dirs[i][1] * 4, frontX = bx - dirs[i][0] * 4, frontZ = bz - dirs[i][1] * 4;
            int bedDir = dirs[i][0] == 0 ? (dirs[i][1] < 0 ? 2 : 0) : (dirs[i][0] > 0 ? 3 : 1);
            bed(backX - (dirs[i][0] == 0 ? 0 : dirs[i][0]), Y + 1, backZ - (dirs[i][1] == 0 ? 0 : dirs[i][1]), bedDir);
            for (int x = -1; x <= 1; x++) { for (int z = -1; z <= 1; z++) { set(frontX + x, Y, frontZ + z, 42, 0); } }
            out.add("/bw time " + teams[i] + " spawn " + (bx + 0.5) + " " + (Y + 1) + " " + (bz + 0.5));
            out.add("/bw time " + teams[i] + " cama " + backX + " " + (Y + 1) + " " + backZ);
            out.add("/bw time " + teams[i] + " forja " + (frontX + 0.5) + " " + (Y + 1) + " " + (frontZ + 0.5));
            // Ilha de diamante na diagonal seguinte.
            int[] nx = dirs[(i + 1) % 4];
            int dxI = (dirs[i][0] + nx[0]) * 22, dzI = (dirs[i][1] + nx[1]) * 22;
            island(dxI, dzI, 3, Y, 2, 0);
            set(dxI, Y, dzI, 57, 0);
            out.add("/bw diamante " + (dxI + 0.5) + " " + (Y + 1) + " " + (dzI + 0.5));
        }
        island(0, 0, 9, Y + 3, 2, 0);
        for (int x = -3; x <= 3; x++) { for (int z = -3; z <= 3; z++) { set(x, Y + 3, z, 98, 0); } }
        set(2, Y + 3, 0, 133, 0); set(-2, Y + 3, 0, 133, 0);
        out.add("/bw esmeralda 2.5 " + (Y + 4) + " 0.5");
        out.add("/bw esmeralda -1.5 " + (Y + 4) + " 0.5");
        // Sala de espera flutuando acima do meio.
        for (int x = -4; x <= 4; x++) { for (int z = -4; z <= 4; z++) { set(x, Y + 30, z, 20, 0); } }
        out.add("/bw salvar");
        out.add("# Lojas: dois NPCs perto do spawn de cada time, com /npc comando bw loja e /npc comando bw melhorias.");
        finish(w, 0.5, Y + 31, 0.5);
    }
}
