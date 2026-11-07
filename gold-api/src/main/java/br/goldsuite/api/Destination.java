package br.goldsuite.api;

import java.util.Locale;
import java.util.Objects;

/** Para onde uma viagem leva o jogador. Imutavel; serializavel com Gson. */
public final class Destination {
    public enum Type { WORLD, POSITION, SERVER }
    public final Type type;
    /** Mundo de destino (WORLD e POSITION) ou nome do servidor (SERVER). */
    public final String target;
    public final double x, y, z;
    public final float yaw, pitch;

    private Destination(Type type, String target, double x, double y, double z, float yaw, float pitch) {
        this.type = Objects.requireNonNull(type); this.target = Objects.requireNonNull(target);
        this.x = x; this.y = y; this.z = z; this.yaw = yaw; this.pitch = pitch;
    }
    /** Ponto de spawn seguro do mundo. */
    public static Destination world(String world) { return new Destination(Type.WORLD, name(world), 0, 0, 0, 0, 0); }
    public static Destination position(String world, double x, double y, double z, float yaw, float pitch) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z) || y < 0 || y > 256) { throw new IllegalArgumentException("Coordenadas invalidas."); }
        return new Destination(Type.POSITION, name(world), x, y, z, yaw, pitch);
    }
    /** Outra instancia. Na 0.15.10 o cliente nao tem TransferPacket: exige o proxy da GoldSuite. */
    public static Destination server(String server) { return new Destination(Type.SERVER, name(server), 0, 0, 0, 0, 0); }

    /**
     * Le "&lt;mundo&gt; &lt;x&gt; &lt;y&gt; &lt;z&gt; [yaw]" a partir de args[from], para comandos sem jogador no lugar (console).
     * Devolve null se ali nao houver mundo e tres numeros. O yaw so e lido se for numero; veja {@link #parsedLength}.
     */
    public static Destination parse(String[] args, int from) {
        if (args == null || from < 0 || args.length < from + 4) { return null; }
        Double x = number(args[from + 1]), y = number(args[from + 2]), z = number(args[from + 3]);
        if (x == null || y == null || z == null) { return null; }
        Double yaw = args.length > from + 4 ? number(args[from + 4]) : null;
        return position(args[from], x, y, z, yaw == null ? 0 : (float) (yaw % 360), 0);
    }
    /** Quantos argumentos o {@link #parse} usou: 4 sem yaw, 5 com yaw. */
    public static int parsedLength(String[] args, int from) { return args.length > from + 4 && number(args[from + 4]) != null ? 5 : 4; }
    private static Double number(String text) {
        try { double d = Double.parseDouble(text); return Double.isFinite(d) ? d : null; } catch (NumberFormatException | NullPointerException e) { return null; }
    }

    private static String name(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-][A-Za-z0-9_.-]{0,31}")) { throw new IllegalArgumentException("Nome invalido: use ate 32 letras, numeros, _ . ou -."); }
        return value;
    }
    /** Revalida dados vindos do disco. */
    public void validate() { if (type == Type.POSITION) { position(target, x, y, z, yaw, pitch); } else { name(target); } }
    @Override public String toString() {
        switch (type) {
            case WORLD: return "mundo " + target;
            case POSITION: return String.format(Locale.ROOT, "%s %.1f %.1f %.1f", target, x, y, z);
            default: return "servidor " + target;
        }
    }
}
