package br.goldsuite.core;

import br.goldsuite.api.RankingService;
import java.util.*;
import java.util.function.IntFunction;

/** Quadros de ranking publicados pelos plugins; uma falha numa fonte nao derruba quem consulta. */
final class RankingRegistry implements RankingService {
    private final Map<String, IntFunction<List<Entry>>> sources = new TreeMap<>();

    @Override public void register(String board, IntFunction<List<Entry>> source) {
        if (board == null || !board.matches("[a-z0-9_-]{1,24}")) { throw new IllegalArgumentException("Quadro invalido."); }
        sources.put(board, Objects.requireNonNull(source));
    }
    @Override public void unregister(String board) { sources.remove(board); }
    @Override public List<String> boards() { return new ArrayList<>(sources.keySet()); }
    @Override public List<Entry> top(String board, int limit) {
        IntFunction<List<Entry>> source = sources.get(board);
        if (source == null || limit < 1) { return Collections.emptyList(); }
        try { List<Entry> list = source.apply(Math.min(limit, 100)); return list == null ? Collections.emptyList() : list; }
        catch (RuntimeException error) { return Collections.emptyList(); }
    }
}
