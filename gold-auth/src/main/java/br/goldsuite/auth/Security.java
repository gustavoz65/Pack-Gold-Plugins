package br.goldsuite.auth;

import java.util.ArrayList;
import java.util.List;

/** Verificacao em duas etapas de uma conta: segredo TOTP e aparelhos confiaveis (hashes). */
final class Security {
    static final int MAX_DEVICES = 10;
    String secret;
    List<String> trusted = new ArrayList<>();

    boolean enabled() { return secret != null && !secret.isEmpty(); }

    void trust(String device) {
        if (trusted == null) { trusted = new ArrayList<>(); }
        trusted.remove(device);
        trusted.add(device);
        while (trusted.size() > MAX_DEVICES) { trusted.remove(0); }
    }

    boolean trusts(String device) { return trusted != null && trusted.contains(device); }

    void validate() {
        if (trusted == null) { trusted = new ArrayList<>(); }
        if (secret != null && !secret.matches("[A-Z2-7]{16,64}")) { throw new IllegalArgumentException("Segredo 2FA invalido."); }
        trusted.removeIf(d -> d == null || !d.matches("[0-9a-f]{32}"));
        while (trusted.size() > MAX_DEVICES) { trusted.remove(0); }
    }
}
