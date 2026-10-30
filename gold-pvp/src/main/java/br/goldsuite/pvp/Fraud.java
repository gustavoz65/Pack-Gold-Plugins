package br.goldsuite.pvp;

import java.util.*;

/**
 * Anti-fraude do PvP (boosting): mesmo IP, a mesma dupla se enfrentando de novo e de novo, e o
 * "entregador" que entra so para morrer. Tudo em memoria com janelas de tempo; nada vai ao disco.
 * Cada fraude detectada vira uma advertencia; com {@link #KICK_AT} em 24 h o jogador e expulso.
 */
final class Fraud {
    /** A mesma dupla no ranked: no maximo 2 partidas validas a cada 30 min. */
    static final long PAIR_WINDOW = 30 * 60_000L;
    static final int PAIR_MAX = 2;
    /** Abates no mundo aberto: o mesmo assassino na mesma vitima conta no maximo 3 vezes a cada 10 min. */
    static final long KILL_WINDOW = 10 * 60_000L;
    static final int KILL_MAX = 3;
    /** Entregar a partida: perdeu em menos de 25 s causando menos de 2 coracoes de dano. */
    static final long FEED_MS = 25_000;
    static final long FEED_DAMAGE = 4;
    static final long STRIKE_WINDOW = 24 * 60 * 60_000L;
    static final int KICK_AT = 3, BAN_AT = 4;
    static final long RANKED_BAN_MS = 24 * 60 * 60_000L;

    private final Map<String, Deque<Long>> pairs = new HashMap<>();
    private final Map<String, Deque<Long>> kills = new HashMap<>();
    private final Map<String, Deque<Long>> strikes = new HashMap<>();

    static String pair(String a, String b) { return a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a; }

    static boolean sameIp(String a, String b) { return a != null && !a.isEmpty() && a.equals(b); }

    /** Derrota rapida e sem reacao: o perdedor quase nao bateu. */
    static boolean feeding(long durationMs, long loserDamage) { return durationMs < FEED_MS && loserDamage < FEED_DAMAGE; }

    private static int recent(Map<String, Deque<Long>> map, String key, long window, long now) {
        Deque<Long> times = map.get(key);
        if (times == null) { return 0; }
        while (!times.isEmpty() && now - times.peekFirst() > window) { times.pollFirst(); }
        if (times.isEmpty()) { map.remove(key); return 0; }
        return times.size();
    }

    private static int add(Map<String, Deque<Long>> map, String key, long window, long now) {
        recent(map, key, window, now);
        Deque<Long> times = map.computeIfAbsent(key, k -> new ArrayDeque<>());
        times.addLast(now);
        return times.size();
    }

    /** A dupla ainda pode ser pareada pela fila. */
    boolean canPair(String a, String b, long now) { return recent(pairs, pair(a, b), PAIR_WINDOW, now) < PAIR_MAX; }

    void recordMatch(String a, String b, long now) { add(pairs, pair(a, b), PAIR_WINDOW, now); }

    /** Registra o abate e diz se ele conta (false = repeticao suspeita). */
    boolean killCounts(String killer, String victim, long now) { return add(kills, killer + ">" + victim, KILL_WINDOW, now) <= KILL_MAX; }

    /** Soma uma advertencia e devolve quantas o jogador tem nas ultimas 24 h. */
    int strike(String player, long now) { return add(strikes, player, STRIKE_WINDOW, now); }

    int strikes(String player, long now) { return recent(strikes, player, STRIKE_WINDOW, now); }

    /** Limpa janelas vencidas para a memoria nao crescer. */
    void prune(long now) {
        for (String k : new ArrayList<>(pairs.keySet())) { recent(pairs, k, PAIR_WINDOW, now); }
        for (String k : new ArrayList<>(kills.keySet())) { recent(kills, k, KILL_WINDOW, now); }
        for (String k : new ArrayList<>(strikes.keySet())) { recent(strikes, k, STRIKE_WINDOW, now); }
    }
}
