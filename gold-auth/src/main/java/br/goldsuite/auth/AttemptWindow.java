package br.goldsuite.auth;

/**
 * Janela por IP; a mesma instancia sobrevive a reconexoes do jogador.
 *
 * <p>So senhas erradas contam. No Brasil muita gente joga no 4G atras do mesmo IP
 * (CGNAT); contar logins corretos bloquearia jogadores legitimos no horario de pico.</p>
 */
final class AttemptWindow {
    static final int MAX_FAILURES = 20;
    private long start;
    private int failures;

    AttemptWindow(long now) { this.start = now; }

    private void roll(long now) {
        if (now - this.start >= 60_000_000_000L) { this.start = now; this.failures = 0; }
    }

    boolean allowed(long now) {
        roll(now);
        return this.failures < MAX_FAILURES;
    }

    void fail(long now) {
        roll(now);
        ++this.failures;
    }

    boolean expired(long now) { return now - this.start >= 600_000_000_000L; }
}
