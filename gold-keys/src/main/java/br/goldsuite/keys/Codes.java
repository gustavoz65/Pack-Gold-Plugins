package br.goldsuite.keys;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;

/**
 * Codigos de key: 100 bits do SecureRandom em Base32 sem letras ambiguas (GOLD-XXXXX-XXXXX-XXXXX-XXXXX).
 * O servidor guarda so o SHA-256 do codigo: quem copiar o banco nao consegue resgatar nada.
 */
final class Codes {
    /** Sem 0/O e 1/I/L, que se confundem na hora de digitar. */
    static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private Codes() {}

    static String newCode() {
        StringBuilder s = new StringBuilder("GOLD");
        for (int g = 0; g < 4; g++) {
            s.append('-');
            for (int i = 0; i < 5; i++) { s.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length()))); }
        }
        return s.toString();
    }

    /** Pedido: 6 caracteres faceis de ditar no Discord. */
    static String newOrder() {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < 6; i++) { s.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length()))); }
        return s.toString();
    }

    /** Aceita minusculas, espacos e sem tracos; devolve no formato oficial ou null se nao for uma key. */
    static String normalize(String typed) {
        String raw = typed == null ? "" : typed.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        if (raw.startsWith("GOLD")) { raw = raw.substring(4); }
        if (raw.length() != 20) { return null; }
        for (char c : raw.toCharArray()) { if (ALPHABET.indexOf(c) < 0) { return null; } }
        return "GOLD-" + raw.substring(0, 5) + "-" + raw.substring(5, 10) + "-" + raw.substring(10, 15) + "-" + raw.substring(15);
    }

    static String hash(String code) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(("goldkeys:" + code).getBytes(StandardCharsets.UTF_8));
            StringBuilder s = new StringBuilder();
            for (byte b : h) { s.append(String.format("%02x", b)); }
            return s.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
