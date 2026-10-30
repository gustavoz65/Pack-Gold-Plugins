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
        System.out.println("GoldAuth: senha correta/incorreta, sal, hash malformado e limite de erros por IP verificados.");
    }
}
