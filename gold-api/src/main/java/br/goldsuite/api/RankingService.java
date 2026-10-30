package br.goldsuite.api;

import java.util.List;
import java.util.function.IntFunction;

/**
 * Registro central de rankings, mantido pelo GoldCore: plugins publicam quadros (ex.: GoldPvP publica
 * "pvp" e "ranked") e outros leem (ex.: NPCs do top 3). Chamadas na thread principal.
 */
public interface RankingService {
    final class Entry {
        public final String player;
        public final long value;
        /** Texto pronto para exibir o valor (ex.: "1520 (Ouro)"). */
        public final String label;
        public Entry(String player, long value, String label) { this.player = player; this.value = value; this.label = label; }
    }
    /** A fonte recebe o limite e devolve os primeiros colocados, do 1o em diante. */
    void register(String board, IntFunction<List<Entry>> source);
    void unregister(String board);
    List<String> boards();
    /** Lista vazia se o quadro nao existir. */
    List<Entry> top(String board, int limit);
}
