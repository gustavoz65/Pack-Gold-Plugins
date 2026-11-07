package br.goldsuite.shop;

import java.math.BigDecimal;
import java.util.*;

/** Lojas por placa (chave "mundo:x:y:z") e tabela de precos do /vender. */
public final class ShopState {
    public static final long MAX_PRICE = 1_000_000_000_00L;
    public Map<String, Shop> shops = new TreeMap<>();
    /** "id:meta" -> centavos por unidade. */
    public Map<String, Long> sellPrices = new TreeMap<>();
    /** Loja do servidor em menu (/comprar): categoria -> icone e ofertas, na ordem em que foram criadas. */
    public Map<String, Category> catalog = new LinkedHashMap<>();
    public static final int MAX_CATEGORIES = 14, MAX_OFFERS = 18;

    public static final class Category {
        public int iconId = 54, iconMeta;
        public String title = "";
        public List<Offer> offers = new ArrayList<>();
    }

    public static final class Offer {
        public int id, meta, amount;
        public long price;
    }

    public static String category(String text) {
        String c = text == null ? "" : text.toLowerCase(Locale.ROOT);
        if (!c.matches("[a-z0-9_-]{1,16}")) { throw new IllegalArgumentException(M.t("categoria_invalida")); }
        return c;
    }

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
        } catch (NumberFormatException e) { throw new IllegalArgumentException(M.t("item_use_id_ou_id_meta")); }
    }

    public static int amount(String text) {
        try { int n = Integer.parseInt(text.trim()); if (n < 1 || n > 64) { throw new NumberFormatException(); } return n; }
        catch (NumberFormatException e) { throw new IllegalArgumentException(M.t("quantidade_entre_1_64")); }
    }

    /** Preco em reais com ate 2 casas ("10", "10,50", "$ 10.5") para centavos. */
    public static long price(String text) {
        String t = text.replace("$", "").replace(",", ".").trim();
        if (!t.matches("[0-9]{1,11}(\\.[0-9]{1,2})?")) { throw new IllegalArgumentException(M.t("preco_invalido_ex_10_ou_10")); }
        long cents = new BigDecimal(t).movePointRight(2).longValueExact();
        if (cents < 1 || cents > MAX_PRICE) { throw new IllegalArgumentException(M.t("preco_fora_do_limite")); }
        return cents;
    }

    public static String money(long cents) { return BigDecimal.valueOf(cents, 2).toPlainString(); }

    public static void validate(ShopState s) {
        if (s == null || s.shops == null || s.sellPrices == null || s.shops.size() > 5000 || s.sellPrices.size() > 1000) { throw new IllegalArgumentException(M.t("lojas_invalidas")); }
        for (Map.Entry<String, Shop> e : s.shops.entrySet()) {
            Shop shop = e.getValue();
            if (!e.getKey().matches("[A-Za-z0-9_][A-Za-z0-9_.-]{0,31}:-?\\d+:\\d+:-?\\d+") || shop == null || shop.type == null || shop.owner == null
                    || shop.id < 1 || shop.id > 511 || shop.meta < 0 || shop.amount < 1 || shop.amount > 64 || shop.price < 1 || shop.price > MAX_PRICE
                    || (shop.type == Type.LOJA && !shop.owner.matches("[a-z0-9_]{3,16}"))) { throw new IllegalArgumentException(M.t("loja_invalida", e.getKey())); }
        }
        if (s.catalog == null) { s.catalog = new LinkedHashMap<>(); }
        if (s.catalog.size() > MAX_CATEGORIES) { throw new IllegalArgumentException(M.t("catalogo_invalido")); }
        for (Map.Entry<String, Category> e : s.catalog.entrySet()) {
            category(e.getKey());
            Category c = e.getValue();
            if (c == null || c.title == null || c.offers == null || c.title.length() > 40 || c.offers.size() > MAX_OFFERS || c.iconId < 1 || c.iconId > 511 || c.iconMeta < 0 || c.iconMeta > 32767) { throw new IllegalArgumentException(M.t("catalogo_invalido")); }
            for (Offer o : c.offers) {
                if (o == null || o.id < 1 || o.id > 511 || o.meta < 0 || o.meta > 32767 || o.amount < 1 || o.amount > 64 || o.price < 1 || o.price > MAX_PRICE) { throw new IllegalArgumentException(M.t("catalogo_invalido")); }
            }
        }
        for (Map.Entry<String, Long> e : s.sellPrices.entrySet()) {
            item(e.getKey());
            if (e.getValue() == null || e.getValue() < 1 || e.getValue() > MAX_PRICE) { throw new IllegalArgumentException(M.t("preco_de_venda_invalido")); }
        }
    }
}
