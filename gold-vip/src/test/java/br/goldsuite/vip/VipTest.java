package br.goldsuite.vip;

import com.google.gson.Gson;
import java.util.HashSet;
import java.util.Set;

public final class VipTest {
    public static void main(String[] args) {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) { String k = VipState.newKey(); check(k.matches("[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}") && !k.contains("O") && !k.contains("I") && seen.add(k), "Chave fora do formato ou repetida: " + k); }
        check(VipState.normalize("abcd efgh jkmn").equals("ABCD-EFGH-JKMN") && VipState.normalize("ABCD-EFGH-JKMN").equals("ABCD-EFGH-JKMN"), "Normalizacao falhou.");
        rejects(() -> VipState.normalize("abc"));
        long now = 1_000_000L, day = 86_400_000L;
        check(VipState.extend(now + 5 * day, true, 30, now) == now + 35 * day, "Dias nao somados ao VIP atual.");
        check(VipState.extend(now + 5 * day, false, 30, now) == now + 30 * day, "Grupo diferente nao deve somar.");
        check(VipState.extend(now - day, true, 30, now) == now + 30 * day, "VIP vencido deve recomecar agora.");
        VipState s = new VipState();
        s.keys.put("ABCD-EFGH-JKMN", new VipState.Key("vip", 30));
        VipState back = new Gson().fromJson(new Gson().toJson(s), VipState.class);
        VipState.validate(back);
        back.keys.put("ABCD-EFGH-JKMP", new VipState.Key("../x", 30));
        rejects(() -> VipState.validate(back));
        System.out.println("GoldVip: chaves, normalizacao, soma de dias e validacao aprovados.");
    }
    private static void check(boolean c, String m) { if (!c) { throw new AssertionError(m); } }
    private static void rejects(Runnable r) { try { r.run(); } catch (IllegalArgumentException e) { return; } throw new AssertionError("Entrada invalida aceita."); }
}
