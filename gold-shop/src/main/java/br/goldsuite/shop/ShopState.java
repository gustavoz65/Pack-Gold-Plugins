package br.goldsuite.shop;

import java.math.BigDecimal;
import java.util.*;

/** Lojas por placa (chave "mundo:x:y:z") e tabela de precos do /vender. */
public final class ShopState {
    public static final long MAX_PRICE = 1_000_000_000_00L;
    public Map<String, Shop> shops = new TreeMap<>();
    /** "id:meta" -> centavos por unidade. */
    public Map<String, Long> sellPrices = new TreeMap<>();

    public enum Type { COMPRAR, VENDER, LOJA }

    public static final class Shop {
        public Type type;
        public int id, meta, amount;
        public long price;
        /** Dono (so LOJA) e baú do estoque. */
        public String owner = "";
        public int chestX, chestY, chestZ;
    }

    public static String key(String world, int x, int y, int z) { return world + ":" + x + ":" + y + ":" + z; }

    public static int[] item(String text) {
        String[] parts = text.trim().split(":");
        try {
            int id = Integer.parseInt(parts[0]), meta = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            if (id < 1 || id > 511 || meta < 0 || meta > 32767 || parts.length > 2) { throw new NumberFormatException(); }
            return new int[] {id, meta};
        } catch (NumberFormatException e) { throw new IllegalArgumentException("Item: use id ou id:meta (ex.: 264 ou 351:4)."); }
    }

    public static int amount(String text) {
        try { int n = Integer.parseInt(text.trim()); if (n < 1 || n > 64) { throw new NumberFormatException(); } return n; }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Quantidade entre 1 e 64."); }
    }

    /** Preco em reais com ate 2 casas ("10", "10,50", "$ 10.5") para centavos. */
    public static long price(String text) {
        String t = text.replace("$", "").replace(",", ".").trim();
        if (!t.matches("[0-9]{1,11}(\\.[0-9]{1,2})?")) { throw new IllegalArgumentException("Preco invalido (ex.: 10 ou 10,50)."); }
        long cents = new BigDecimal(t).movePointRight(2).longValueExact();
        if (cents < 1 || cents > MAX_PRICE) { throw new IllegalArgumentException("Preco fora do limite."); }
        return cents;
    }

    public static String money(long cents) { return BigDecimal.valueOf(cents, 2).toPlainString(); }

    public static void validate(ShopState s) {
        if (s == null || s.shops == null || s.sellPrices == null || s.shops.size() > 5000 || s.sellPrices.size() > 1000) { throw new IllegalArgumentException("Lojas invalidas."); }
        for (Map.Entry<String, Shop> e : s.shops.entrySet()) {
            Shop shop = e.getValue();
            if (!e.getKey().matches("[A-Za-z0-9_][A-Za-z0-9_.-]{0,31}:-?\\d+:\\d+:-?\\d+") || shop == null || shop.type == null || shop.owner == null
                    || shop.id < 1 || shop.id > 511 || shop.meta < 0 || shop.amount < 1 || shop.amount > 64 || shop.price < 1 || shop.price > MAX_PRICE
                    || (shop.type == Type.LOJA && !shop.owner.matches("[a-z0-9_]{3,16}"))) { throw new IllegalArgumentException("Loja invalida: " + e.getKey()); }
        }
        for (Map.Entry<String, Long> e : s.sellPrices.entrySet()) {
            item(e.getKey());
            if (e.getValue() == null || e.getValue() < 1 || e.getValue() > MAX_PRICE) { throw new IllegalArgumentException("Preco de venda invalido."); }
        }
    }
}
