package br.goldsuite.auth;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Codigos de 6 digitos que mudam a cada 30 s (RFC 6238), compativeis com Google Authenticator, Authy e Microsoft Authenticator. */
final class Totp {
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final SecureRandom RANDOM = new SecureRandom();

    private Totp() {}

    /** Segredo novo de 160 bits em Base32 (32 letras). */
    static String newSecret() {
        byte[] bytes = new byte[20];
        RANDOM.nextBytes(bytes);
        return base32(bytes);
    }

    static String base32(byte[] data) {
        StringBuilder out = new StringBuilder();
        int buffer = 0, bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff); bits += 8;
            while (bits >= 5) { out.append(ALPHABET.charAt((buffer >> (bits - 5)) & 31)); bits -= 5; }
        }
        if (bits > 0) { out.append(ALPHABET.charAt((buffer << (5 - bits)) & 31)); }
        return out.toString();
    }

    static byte[] decode(String secret) {
        String s = secret.replace(" ", "").toUpperCase();
        ByteBuffer out = ByteBuffer.allocate(s.length() * 5 / 8);
        int buffer = 0, bits = 0;
        for (char c : s.toCharArray()) {
            int v = ALPHABET.indexOf(c);
            if (v < 0) { throw new IllegalArgumentException("Segredo invalido."); }
            buffer = (buffer << 5) | v; bits += 5;
            if (bits >= 8) { out.put((byte) (buffer >> (bits - 8))); bits -= 8; }
        }
        return java.util.Arrays.copyOf(out.array(), out.position());
    }

    static int code(byte[] key, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] h = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int o = h[h.length - 1] & 0xf;
            int bin = ((h[o] & 0x7f) << 24) | ((h[o + 1] & 0xff) << 16) | ((h[o + 2] & 0xff) << 8) | (h[o + 3] & 0xff);
            return bin % 1_000_000;
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    /** Aceita o codigo do intervalo atual e de um antes/depois (relogio do celular um pouco fora). */
    static boolean verify(String secret, String typed, long nowMillis) {
        String digits = typed == null ? "" : typed.replace(" ", "");
        if (!digits.matches("\\d{6}")) { return false; }
        byte[] key = decode(secret);
        long step = nowMillis / 30_000;
        boolean ok = false;
        for (long s = step - 1; s <= step + 1; s++) {
            // Compara em tempo constante: nao vaza quantos digitos acertou.
            ok |= MessageDigest.isEqual(String.format("%06d", code(key, s)).getBytes(), digits.getBytes());
        }
        return ok;
    }

    /** Impressao do aparelho guardada como hash (o servidor nunca guarda o identificador cru). */
    static String device(String fingerprint) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(("goldauth-aparelho:" + fingerprint).getBytes("UTF-8"));
            StringBuilder s = new StringBuilder();
            for (int i = 0; i < 16; i++) { s.append(String.format("%02x", h[i])); }
            return s.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
