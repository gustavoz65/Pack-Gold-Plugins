package br.goldsuite.device;

/** Cliques (hits) do ultimo segundo de um jogador, num vetor circular fixo: sem alocar nada por hit. */
final class ClickLimiter {
    private final long[] times = new long[64];
    private int next;
    private int blocked;
    private long blockedWindow;

    /** Registra o hit e diz se ele passa no limite de cliques por segundo. */
    boolean allow(long now, int limit) {
        int recent = 0;
        for (long t : times) { if (t != 0 && now - t < 1000) { recent++; } }
        if (recent >= Math.min(limit, times.length)) {
            if (now - blockedWindow > 5000) { blockedWindow = now; blocked = 0; }
            blocked++;
            return false;
        }
        times[next] = now;
        next = (next + 1) % times.length;
        return true;
    }

    /** Hits barrados nos ultimos 5 s (para avisar a staff de autoclick). */
    int blocked(long now) { return now - blockedWindow > 5000 ? 0 : blocked; }
}
