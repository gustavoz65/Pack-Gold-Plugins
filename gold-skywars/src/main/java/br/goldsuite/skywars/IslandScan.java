package br.goldsuite.skywars;

import java.util.*;

/** Busca incremental e limitada: nenhum acesso ao mundo ocorre fora da thread do servidor. */
final class IslandScan {
    interface Blocks { int id(int x, int y, int z); }
    static final int MAX_BLOCKS = 32768, RADIUS = 48;
    final int sx, sy, sz;
    private final ArrayDeque<Long> queue = new ArrayDeque<>();
    private final Set<Long> visited = new HashSet<>();
    final List<Long> candidates = new ArrayList<>();
    final int[] ores = new int[3];
    int minX = Integer.MAX_VALUE, minY = 256, minZ = Integer.MAX_VALUE;
    int maxX = Integer.MIN_VALUE, maxY, maxZ = Integer.MIN_VALUE, solid;
    boolean done;
    private final Random random = new Random();
    private int eligible;
    IslandScan(int x, int y, int z) { sx = x; sy = y; sz = z; queue.add(pack(x, y, z)); visited.add(pack(x, y, z)); }
    void step(Blocks blocks, int budget) {
        for (int n = 0; n < budget && !queue.isEmpty(); n++) {
            long pos = queue.removeFirst();
            int x = x(pos), y = y(pos), z = z(pos);
            int id = blocks.id(x, y, z);
            if (id == 0 || id == 8 || id == 9 || id == 10 || id == 11) { continue; }
            if (Math.abs(x - sx) >= RADIUS || Math.abs(z - sz) >= RADIUS || Math.abs(y - sy) >= 64 || ++solid > MAX_BLOCKS) {
                throw new IllegalArgumentException(M.t("minerios_ilha_grande"));
            }
            minX = Math.min(minX, x); minY = Math.min(minY, y); minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x); maxY = Math.max(maxY, y); maxZ = Math.max(maxZ, z);
            if (id == 56) { ores[0]++; } else if (id == 15) { ores[1]++; } else if (id == 16) { ores[2]++; }
            // Somente terra/pedra coberta por bloco solido: nao modifica baus, ar, construcao ou grama.
            if ((id == 1 || id == 3) && blocks.id(x, y + 1, z) != 0) {
                eligible++;
                if (candidates.size() < 256) { candidates.add(pos); }
                else { int replace = random.nextInt(eligible); if (replace < 256) { candidates.set(replace, pos); } }
            }
            for (int[] d : DIRECTIONS) {
                int ny = y + d[1]; if (ny < 1 || ny > 127) { continue; }
                long next = pack(x + d[0], ny, z + d[2]);
                if (visited.add(next)) { queue.addLast(next); }
            }
        }
        done = queue.isEmpty();
        if (done) { visited.clear(); if (solid == 0) { throw new IllegalArgumentException(M.t("minerios_sem_ilha")); } }
    }
    boolean overlaps(IslandScan other) {
        return minX <= other.maxX && maxX >= other.minX && minZ <= other.maxZ && maxZ >= other.minZ && minY <= other.maxY && maxY >= other.minY;
    }
    private static final int[][] DIRECTIONS = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
    static long pack(int x, int y, int z) { return ((long)x & 0x3ffffffL) << 38 | ((long)z & 0x3ffffffL) << 12 | (y & 0xfff); }
    static int x(long p) { return (int)(p >> 38); }
    static int y(long p) { return (int)(p & 0xfff); }
    static int z(long p) { return (int)(p << 26 >> 38); }
}

