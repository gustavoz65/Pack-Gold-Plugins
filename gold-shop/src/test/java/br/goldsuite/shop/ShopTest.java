package br.goldsuite.shop;

import com.google.gson.Gson;

public final class ShopTest {
    public static void main(String[] args) {
        check(ShopState.price("10") == 1000 && ShopState.price("$ 10,5") == 1050 && ShopState.price("0.01") == 1, "Preco errado.");
        for (String bad : new String[] {"0", "-1", "1e5", "abc", "1.001"}) { rejects(() -> ShopState.price(bad)); }
        check(ShopState.item("351:4")[1] == 4 && ShopState.item("264")[0] == 264, "Item errado.");
        rejects(() -> ShopState.item("999")); rejects(() -> ShopState.item("1:2:3")); rejects(() -> ShopState.amount("65"));
        ShopState s = new ShopState();
        ShopState.Shop shop = new ShopState.Shop();
        shop.type = ShopState.Type.LOJA; shop.id = 264; shop.amount = 1; shop.price = 500; shop.owner = "gustavo";
        s.shops.put(ShopState.key("world", -10, 64, 20), shop);
        s.sellPrices.put("4:0", 5L);
        ShopState.validate(s);
        ShopState back = new Gson().fromJson(new Gson().toJson(s), ShopState.class);
        ShopState.validate(back);
        check(back.shops.get("world:-10:64:20").type == ShopState.Type.LOJA && ShopState.money(1050).equals("10.50"), "Serializacao ou formato errados.");
        back.shops.get("world:-10:64:20").owner = "";
        rejects(() -> ShopState.validate(back));
        System.out.println("GoldShop: precos, itens, lojas e validacao aprovados.");
    }
    private static void check(boolean c, String m) { if (!c) { throw new AssertionError(m); } }
    private static void rejects(Runnable r) { try { r.run(); } catch (IllegalArgumentException e) { return; } throw new AssertionError("Entrada invalida aceita."); }
}
