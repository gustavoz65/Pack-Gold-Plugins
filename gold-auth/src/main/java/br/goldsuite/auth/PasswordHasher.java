package br.goldsuite.auth;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** Hash lento com sal individual, usando o provedor criptografico do JDK. */
final class PasswordHasher {
    static final int ITERATIONS = 600_000;

    static void validate(char[] password) {
        if (password == null || password.length < 8 || password.length > 128) {
            throw new IllegalArgumentException("A senha precisa ter entre 8 e 128 caracteres.");
        }
    }

    static String hash(char[] password) {
        validate(password);
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        return "pbkdf2-sha256$" + ITERATIONS + "$" + Base64.getEncoder().encodeToString(salt) + "$"
                + Base64.getEncoder().encodeToString(derive(password, salt, ITERATIONS));
    }

    static boolean verify(char[] password, String encoded) {
        try {
            validate(password);
            if (encoded == null || encoded.length() > 256) { return false; }
            String[] fields = encoded.split("\\$", -1);
            if (fields.length != 4 || !fields[0].equals("pbkdf2-sha256")) { return false; }
            int iterations = Integer.parseInt(fields[1]);
            if (iterations < ITERATIONS || iterations > 1_200_000) { return false; }
            byte[] salt = Base64.getDecoder().decode(fields[2]);
            byte[] expected = Base64.getDecoder().decode(fields[3]);
            return salt.length == 16 && expected.length == 32
                    && MessageDigest.isEqual(expected, derive(password, salt, iterations));
        } catch (IllegalArgumentException error) { return false; }
    }

    private static byte[] derive(char[] password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, 256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        catch (GeneralSecurityException error) { throw new IllegalStateException("PBKDF2-HMAC-SHA256 indisponivel na JVM.", error); }
        finally { spec.clearPassword(); }
    }
}
