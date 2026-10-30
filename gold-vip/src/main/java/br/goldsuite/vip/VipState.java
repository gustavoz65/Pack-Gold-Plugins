package br.goldsuite.vip;

import java.security.SecureRandom;
import java.util.*;

/** Chaves de VIP ainda nao usadas e grupos anunciados ao entrar. */
public final class VipState {
    public Map<String, Key> keys = new TreeMap<>();
    public Set<String> announced = new TreeSet<>(Arrays.asList("vip", "mvp"));

    public static final class Key {
        public String group;
        public int days;
        public long created;
        public Key() { }
        public Key(String group, int days) { this.group = group; this.days = days; this.created = System.currentTimeMillis(); }
    }

    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 12 caracteres sem 0/O e 1/I, em 3 blocos: facil de digitar no celular. */
    public static String newKey() {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 12; i++) { if (i > 0 && i % 4 == 0) { b.append('-'); } b.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length()))); }
        return b.toString();
    }

    public static String normalize(String key) {
        String k = key.toUpperCase(Locale.ROOT).replace(" ", "");
        if (!k.matches("[A-Z0-9]{4}-?[A-Z0-9]{4}-?[A-Z0-9]{4}")) { throw new IllegalArgumentException("Chave invalida."); }
        k = k.replace("-", "");
        return k.substring(0, 4) + "-" + k.substring(4, 8) + "-" + k.substring(8);
    }

    /** Soma dias ao VIP atual do mesmo grupo; senao comeca agora. */
    public static long extend(long currentExpiry, boolean sameGroup, int days, long now) {
        long base = sameGroup && currentExpiry > now ? currentExpiry : now;
        return base + days * 86_400_000L;
    }

    public static void validate(VipState s) {
        if (s == null || s.keys == null || s.announced == null || s.keys.size() > 5000 || s.announced.size() > 32) { throw new IllegalArgumentException("Dados de VIP invalidos."); }
        for (Map.Entry<String, Key> e : s.keys.entrySet()) {
            normalize(e.getKey());
            Key k = e.getValue();
            if (k == null || k.group == null || !k.group.matches("[a-z0-9_-]{1,24}") || k.days < 1 || k.days > 3650) { throw new IllegalArgumentException("Chave invalida."); }
        }
    }
}
