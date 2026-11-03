package br.goldsuite.skills;

import java.io.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Blocos colocados por jogadores (nao dao XP, como no mcMMO). Tabela hash aberta de long: sem objetos por bloco,
 * 16 bytes por posicao com a tabela meio cheia. Gravada num arquivo binario compactado.
 * Chave: mundo (8 bits) | y (8) | x (24) | z (24).
 */
final class PlacedBlocks {
    private static final long EMPTY = 0, TOMB = -1;
    private long[] table = new long[1 << 12];
    private int size, used;
    private final List<String> worlds = new ArrayList<>();

    int size() { return size; }

    long key(String world, int x, int y, int z) {
        int w = worlds.indexOf(world);
        if (w < 0) { if (worlds.size() >= 255) { return EMPTY; } worlds.add(world); w = worlds.size() - 1; }
        // +1 no mundo garante que nenhuma chave vale 0 (vazio) nem -1 (removido).
        return ((long) (w + 1) << 56) | ((long) (y & 0xff) << 48) | ((long) (x & 0xffffff) << 24) | (z & 0xffffff);
    }

    private static int slot(long k, int mask) { long h = k * 0x9E3779B97F4A7C15L; return (int) (h ^ (h >>> 32)) & mask; }

    boolean contains(long k) {
        if (k == EMPTY || size == 0) { return false; }
        int mask = table.length - 1;
        for (int i = slot(k, mask); ; i = (i + 1) & mask) { long v = table[i]; if (v == EMPTY) { return false; } if (v == k) { return true; } }
    }

    void add(long k) {
        if (k == EMPTY || contains(k)) { return; }
        if ((used + 1) * 2 > table.length) { rehash(size * 2 + 2 > table.length ? table.length * 2 : table.length); }
        int mask = table.length - 1;
        for (int i = slot(k, mask); ; i = (i + 1) & mask) { long v = table[i]; if (v == EMPTY || v == TOMB) { if (v == EMPTY) { used++; } table[i] = k; size++; return; } }
    }

    /** Remove (o bloco foi quebrado). Devolve se estava marcado. */
    boolean remove(long k) {
        if (k == EMPTY || size == 0) { return false; }
        int mask = table.length - 1;
        for (int i = slot(k, mask); ; i = (i + 1) & mask) { long v = table[i]; if (v == EMPTY) { return false; } if (v == k) { table[i] = TOMB; size--; return true; } }
    }

    private void rehash(int capacity) {
        long[] old = table;
        table = new long[Math.max(1 << 12, Integer.highestOneBit(Math.max(capacity, 1) - 1) << 1)];
        size = 0; used = 0;
        for (long v : old) { if (v != EMPTY && v != TOMB) { add(v); } }
    }

    void save(File file) throws IOException {
        File tmp = new File(file.getPath() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(new FileOutputStream(tmp))))) {
            out.writeInt(1);
            out.writeInt(worlds.size());
            for (String w : worlds) { out.writeUTF(w); }
            out.writeInt(size);
            for (long v : table) { if (v != EMPTY && v != TOMB) { out.writeLong(v); } }
        }
        if (!tmp.renameTo(file)) { file.delete(); if (!tmp.renameTo(file)) { throw new IOException("Nao foi possivel trocar " + file); } }
    }

    void load(File file) throws IOException {
        if (!file.exists()) { return; }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(new FileInputStream(file))))) {
            if (in.readInt() != 1) { throw new IOException("Formato desconhecido."); }
            int nw = in.readInt();
            if (nw < 0 || nw > 255) { throw new IOException("Arquivo corrompido."); }
            worlds.clear();
            for (int i = 0; i < nw; i++) { worlds.add(in.readUTF()); }
            int n = in.readInt();
            if (n < 0 || n > 50_000_000) { throw new IOException("Arquivo corrompido."); }
            rehash(n * 2 + 2);
            for (int i = 0; i < n; i++) { add(in.readLong()); }
        }
    }
}
