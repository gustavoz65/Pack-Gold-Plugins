package br.goldsuite.market;

import br.goldsuite.api.ItemData;
import com.google.gson.Gson;

public final class MarketTest {
    public static void main(String[] args) {
        MarketState s = new MarketState();
        for (int i = 0; i < 2; i++) {
            MarketState.Listing l = new MarketState.Listing();
            l.seller = "ana"; l.price = 1000; l.created = i == 0 ? 0 : System.currentTimeMillis();
            ItemData d = new ItemData(); d.id = 264; d.count = 3; l.item = d;
            s.listings.put(s.nextId++, l);
        }
        MarketState.validate(s);
        check(s.count("ana") == 2, "Contagem errada.");
        check(s.expire(System.currentTimeMillis()) == 1 && s.listings.size() == 1 && s.returns.get("ana").size() == 1, "Vencimento nao devolveu o item.");
        MarketState back = new Gson().fromJson(new Gson().toJson(s), MarketState.class);
        MarketState.validate(back);
        check(GoldMarket.price("99,90") == 9990 && GoldMarket.money(9990).equals("99.90"), "Preco errado.");
        rejects(() -> GoldMarket.price("0")); rejects(() -> GoldMarket.price("1e3"));
        back.listings.values().iterator().next().seller = "../x";
        rejects(() -> MarketState.validate(back));
        System.out.println("GoldMarket: anuncios, vencimento, coleta, precos e validacao aprovados.");
    }
    private static void check(boolean c, String m) { if (!c) { throw new AssertionError(m); } }
    private static void rejects(Runnable r) { try { r.run(); } catch (IllegalArgumentException e) { return; } throw new AssertionError("Entrada invalida aceita."); }
}
