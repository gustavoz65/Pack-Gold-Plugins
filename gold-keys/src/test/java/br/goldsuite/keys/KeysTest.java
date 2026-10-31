package br.goldsuite.keys;

import com.google.gson.Gson;
import java.util.HashSet;
import java.util.Set;

public final class KeysTest {
    public static void main(String[] args) {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 20000; i++) {
            String c = Codes.newCode();
            check(c.matches("GOLD(-[A-HJKMNP-Z2-9]{5}){4}") && seen.add(c), "Codigo invalido ou repetido: " + c);
        }
        String c = Codes.newCode();
        check(c.equals(Codes.normalize(c.toLowerCase().replace("-", " "))) && c.equals(Codes.normalize(c.substring(5))), "Normalizacao falhou.");
        check(Codes.normalize("GOLD-AAAA") == null && Codes.normalize("GOLD-OOOOO-IIIII-LLLLL-00000") == null, "Codigo invalido aceito.");
        check(Codes.hash(c).matches("[0-9a-f]{64}") && !Codes.hash(c).equals(Codes.hash(Codes.newCode())), "Hash errado.");
        check(Codes.newOrder().matches("[A-HJKMNP-Z2-9]{6}"), "Pedido invalido.");

        KeyState s = new KeyState();
        KeyState.Product p = new KeyState.Product(); p.name = "Pacote 500 cash"; p.priceCents = 1990; p.cash = 500; p.cosmetics.add("montaria_nuvem"); p.vipGroup = "vip"; p.vipDays = 30;
        s.products.put("cash500", p);
        KeyState.Order o = new KeyState.Order(); o.player = "ana"; o.product = "cash500"; o.keyHash = Codes.hash(c); o.created = 1;
        s.pending.put(Codes.newOrder(), o);
        KeyState.validate(s);
        KeyState back = new Gson().fromJson(new Gson().toJson(s), KeyState.class);
        KeyState.validate(back);
        check(back.products.get("cash500").rewards().contains("500 cash") && KeyState.price(1990).equals("R$ 19,90"), "Produto perdeu dados.");
        back.products.get("cash500").vipGroup = "VIP ADMIN";
        try { KeyState.validate(back); throw new AssertionError("Grupo invalido aceito."); } catch (IllegalArgumentException expected) { }
        System.out.println("GoldKeys: 20 mil codigos unicos, formato, normalizacao, hash, produtos e validacao aprovados.");
    }
    private static void check(boolean c, String m) { if (!c) { throw new AssertionError(m); } }
}
