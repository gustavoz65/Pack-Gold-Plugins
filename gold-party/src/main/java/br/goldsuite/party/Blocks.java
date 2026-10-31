package br.goldsuite.party;

import cn.nukkit.block.Block;
import cn.nukkit.level.Level;
import cn.nukkit.level.format.FullChunk;
import cn.nukkit.math.Vector3;
import java.util.*;

/**
 * Fila de mudancas de bloco com limite por tick dividido entre as salas: construir ou limpar uma arena nunca trava o
 * servidor. Cada sala tem a propria fila; o tick atende as filas em rodizio ate gastar o limite.
 */
final class Blocks {
    /** Uma mudanca: x, y, z, id, meta. */
    static final class Queue {
        private int[] ops = new int[5 * 1024];
        private int head, tail;
        void add(int x, int y, int z, int id, int meta) {
            if (tail + 5 > ops.length) {
                if (head > 0) { System.arraycopy(ops, head, ops, 0, tail - head); tail -= head; head = 0; }
                if (tail + 5 > ops.length) { ops = Arrays.copyOf(ops, ops.length * 2); }
            }
            ops[tail++] = x; ops[tail++] = y; ops[tail++] = z; ops[tail++] = id; ops[tail++] = meta;
        }
        boolean empty() { return head >= tail; }
        int size() { return (tail - head) / 5; }
        void clear() { head = tail = 0; }
    }

    private final Level level;
    private final List<Queue> queues = new ArrayList<>();
    private int next;
    int perTick = 3000;
    private final Vector3 pos = new Vector3();

    Blocks(Level level) { this.level = level; }

    void register(Queue q) { if (!queues.contains(q)) { queues.add(q); } }
    void unregister(Queue q) { queues.remove(q); q.clear(); }

    /** Aplica ate perTick mudancas no total, em rodizio entre as salas. */
    void tick() {
        int budget = perTick, idle = 0;
        while (budget > 0 && !queues.isEmpty() && idle < queues.size()) {
            if (next >= queues.size()) { next = 0; }
            Queue q = queues.get(next++);
            if (q.empty()) { idle++; continue; }
            idle = 0;
            int share = Math.max(64, budget / queues.size());
            while (share-- > 0 && budget > 0 && !q.empty()) { apply(q.ops[q.head], q.ops[q.head + 1], q.ops[q.head + 2], q.ops[q.head + 3], q.ops[q.head + 4]); q.head += 5; budget--; }
            if (q.empty()) { q.head = q.tail = 0; }
        }
    }

    private void apply(int x, int y, int z, int id, int meta) {
        FullChunk c = level.getChunk(x >> 4, z >> 4, false);
        if (c == null) {
            level.loadChunk(x >> 4, z >> 4, true);
            c = level.getChunk(x >> 4, z >> 4, false);
            // Chunk novo do mundo vazio: marcado como gerado antes de mexer, senao o gerador o recria por cima.
            if (c != null && !c.isGenerated()) { c.setGenerated(true); c.setPopulated(true); }
        }
        if (c == null) { return; }
        if (c.getBlockId(x & 15, y, z & 15) == id && c.getBlockData(x & 15, y, z & 15) == meta) { return; }
        pos.x = x; pos.y = y; pos.z = z;
        // Sem fisica nem atualizacao em cadeia; o mundo junta as mudancas e manda em lote no proprio tick.
        level.setBlock(pos, Block.get(id, meta), false, false);
    }
}
