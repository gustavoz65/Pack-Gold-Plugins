package br.goldsuite.device;

/**
 * Detecta PC x celular pela mira. No PC todo clique sai da mira, no centro da tela: o ponto clicado fica exatamente na
 * direcao do olhar. No celular (0.15) o toque e onde o dedo encosta, quase sempre longe do centro. Guarda os ultimos
 * cliques e decide com folga para nao errar por um clique isolado.
 */
final class AimProfile {
    enum Verdict { PC, CELULAR, INCERTO }

    static final int WINDOW = 12, MIN = 6;
    static final double ALIGNED_DEG = 5.0;
    private final boolean[] samples = new boolean[WINDOW];
    private int count, next;

    void add(boolean aligned) { samples[next] = aligned; next = (next + 1) % WINDOW; if (count < WINDOW) { count++; } }
    int count() { return count; }
    int aligned() { int n = 0; for (int i = 0; i < count; i++) { if (samples[i]) { n++; } } return n; }

    Verdict verdict() {
        if (count < MIN) { return Verdict.INCERTO; }
        double ratio = aligned() / (double) count;
        return ratio >= 0.8 ? Verdict.PC : ratio <= 0.4 ? Verdict.CELULAR : Verdict.INCERTO;
    }

    /** Angulo em graus entre dois vetores (nao precisam estar normalizados). */
    static double angle(double ax, double ay, double az, double bx, double by, double bz) {
        double la = Math.sqrt(ax * ax + ay * ay + az * az), lb = Math.sqrt(bx * bx + by * by + bz * bz);
        if (la < 1e-9 || lb < 1e-9) { return 180; }
        double cos = (ax * bx + ay * by + az * bz) / (la * lb);
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, cos))));
    }

    /** A reta que sai do olho na direcao (dx,dy,dz) atravessa a caixa? (teste das placas) */
    static boolean rayHitsBox(double ox, double oy, double oz, double dx, double dy, double dz, double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        double tmin = 0, tmax = 16;
        double[] o = {ox, oy, oz}, d = {dx, dy, dz}, lo = {minX, minY, minZ}, hi = {maxX, maxY, maxZ};
        for (int i = 0; i < 3; i++) {
            if (Math.abs(d[i]) < 1e-9) { if (o[i] < lo[i] || o[i] > hi[i]) { return false; } continue; }
            double t1 = (lo[i] - o[i]) / d[i], t2 = (hi[i] - o[i]) / d[i];
            tmin = Math.max(tmin, Math.min(t1, t2));
            tmax = Math.min(tmax, Math.max(t1, t2));
            if (tmin > tmax) { return false; }
        }
        return true;
    }

    /** Direcao do olhar do Nukkit (yaw 0 = +z). */
    static double[] look(double yaw, double pitch) {
        double y = Math.toRadians(yaw), p = Math.toRadians(pitch);
        return new double[] {-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p)};
    }
}
