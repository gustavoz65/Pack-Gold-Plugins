package br.goldsuite.auth;

import java.util.ArrayList;
import java.util.List;

/** Verificacao em duas etapas de uma conta: segredo TOTP e aparelhos confiaveis (hashes). */
final class Security {
    static final int MAX_DEVICES = 10;
    String secret;
    List<String> trusted = new ArrayList<>();

    boolean enabled() { return secret != null && !secret.isEmpty(); }

    /** Aparelho vazio (cliente sem id da instalacao) nunca vira confiavel: a impressao seria publica e igual para muitos. */
    void trust(String device) {
        if (device == null || device.isEmpty()) { return; }
        if (trusted == null) { trusted = new ArrayList<>(); }
        trusted.remove(device);
        trusted.add(device);
        while (trusted.size() > MAX_DEVICES) { trusted.remove(0); }
    }

    boolean trusts(String device) { return device != null && !device.isEmpty() && trusted != null && trusted.contains(device); }

    void validate() {
        if (trusted == null) { trusted = new ArrayList<>(); }
        if (secret != null && !secret.matches("[A-Z2-7]{16,64}")) { throw new IllegalArgumentException(M.t("segredo_2fa_invalido")); }
        trusted.removeIf(d -> d == null || !d.matches("[0-9a-f]{32}"));
        while (trusted.size() > MAX_DEVICES) { trusted.remove(0); }
    }
}
