package br.goldsuite.auth;

public final class AuthTest {
    public static void main(String[] args) {
        char[] password = "Senha de teste com ação".toCharArray();
        String first = PasswordHasher.hash(password);
        String second = PasswordHasher.hash(password);
        if (first.equals(second) || !PasswordHasher.verify(password, first) || PasswordHasher.verify("Senha incorreta".toCharArray(), first)) {
            throw new AssertionError("Sal individual ou verificacao de senha falhou.");
        }
        if (PasswordHasher.verify(password, "hash-corrompido")
                || PasswordHasher.verify(password, first.replace("$600000$", "$2147483647$"))) {
            throw new AssertionError("Hash malformado ou custo excessivo aceito.");
        }
        AttemptWindow window = new AttemptWindow(0);
        // Logins corretos nao contam: muitos jogadores podem dividir o mesmo IP (CGNAT).
        for (int i = 0; i < 100; i++) { if (!window.allowed(0)) { throw new AssertionError("Login sem erro foi limitado."); } }
        for (int i = 0; i < AttemptWindow.MAX_FAILURES; i++) { window.fail(0); }
        if (window.allowed(59_999_999_999L) || !window.allowed(60_000_000_000L)) {
            throw new AssertionError("Limite de senhas erradas por IP nao respeitou a janela.");
        }
        // TOTP: vetor oficial da RFC 6238 (segredo "12345678901234567890", 59 s -> 94287082, 6 digitos = 287082).
        String rfc = Totp.base32("12345678901234567890".getBytes());
        if (!rfc.equals("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ") || Totp.code(Totp.decode(rfc), 59 / 30) != 287082) { throw new AssertionError("TOTP fora da RFC 6238."); }
        if (!Totp.verify(rfc, "287082", 59_000) || !Totp.verify(rfc, "287 082", 59_000 + 30_000) || Totp.verify(rfc, "287083", 59_000) || Totp.verify(rfc, "abc", 59_000)) { throw new AssertionError("Verificacao TOTP errada."); }
        if (Totp.verify(rfc, "287082", 59_000 + 120_000)) { throw new AssertionError("Codigo velho aceito."); }
        String novo = Totp.newSecret();
        if (!novo.matches("[A-Z2-7]{32}") || novo.equals(Totp.newSecret()) || Totp.decode(novo).length != 20) { throw new AssertionError("Segredo novo invalido."); }
        Security sec = new Security(); sec.secret = novo;
        for (int i = 0; i < 15; i++) { sec.trust(Totp.device("aparelho" + i)); }
        if (sec.trusted.size() != Security.MAX_DEVICES || sec.trusts(Totp.device("aparelho0")) || !sec.trusts(Totp.device("aparelho14"))) { throw new AssertionError("Aparelhos confiaveis errados."); }
        sec.validate();
        try { Security bad = new Security(); bad.secret = "abc"; bad.validate(); throw new AssertionError("Segredo invalido aceito."); } catch (IllegalArgumentException expected) { }
        System.out.println("GoldAuth: senha correta/incorreta, sal, hash malformado, limite por IP, TOTP (RFC 6238) e aparelhos confiaveis verificados.");
    }
}
