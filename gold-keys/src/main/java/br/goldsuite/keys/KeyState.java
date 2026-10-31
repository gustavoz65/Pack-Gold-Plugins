package br.goldsuite.keys;

import java.util.*;

/** Produtos da loja e pedidos aguardando pagamento (documento pequeno). As keys ficam uma por chave no banco. */
public final class KeyState {
    public Map<String, Product> products = new TreeMap<>();
    /** Pedido -> dados; sai daqui ao ser pago, cancelado ou vencer. */
    public Map<String, Order> pending = new TreeMap<>();

    public static final class Product {
        public String name;
        /** Preco mostrado ao jogador, em centavos de real. */
        public long priceCents;
        public long cash, moneyCents;
        public List<String> cosmetics = new ArrayList<>();
        public String vipGroup;
        public int vipDays;

        String rewards() {
            List<String> r = new ArrayList<>();
            if (cash > 0) { r.add(cash + " cash"); }
            if (moneyCents > 0) { r.add(String.format(Locale.ROOT, "$%d.%02d", moneyCents / 100, moneyCents % 100)); }
            for (String c : cosmetics) { r.add("cosmetico " + c); }
            if (vipGroup != null) { r.add(vipGroup + (vipDays > 0 ? " por " + vipDays + " dias" : " permanente")); }
            return r.isEmpty() ? "nada configurado" : String.join(", ", r);
        }
    }

    public static final class Order {
        public String player, product, keyHash;
        public long created;
    }

    /** Uma key no banco (pelo hash do codigo). */
    public static final class Key {
        public String product, owner, order, status, redeemedBy;
        public long created, paidAt, expires, redeemedAt;
    }

    static final String PENDENTE = "PENDENTE", PAGA = "PAGA", RESGATADA = "RESGATADA", CANCELADA = "CANCELADA";

    static String price(long cents) { return String.format(Locale.ROOT, "R$ %d,%02d", cents / 100, cents % 100); }

    public static void validate(KeyState s) {
        if (s == null || s.products == null || s.pending == null || s.products.size() > 200 || s.pending.size() > 2000) { throw new IllegalArgumentException("Loja de keys invalida."); }
        for (Map.Entry<String, Product> e : s.products.entrySet()) {
            Product p = e.getValue();
            if (!e.getKey().matches("[a-z0-9_]{2,24}") || p == null || p.name == null || p.name.length() > 48 || p.priceCents < 0 || p.cash < 0 || p.moneyCents < 0
                    || p.cosmetics == null || p.cosmetics.size() > 20 || p.vipDays < 0 || p.vipDays > 3650 || (p.vipGroup != null && !p.vipGroup.matches("[a-z0-9_]{1,32}"))) {
                throw new IllegalArgumentException("Produto invalido: " + e.getKey());
            }
        }
        for (Map.Entry<String, Order> e : s.pending.entrySet()) {
            Order o = e.getValue();
            if (!e.getKey().matches("[A-Z2-9]{6}") || o == null || o.player == null || o.product == null || o.keyHash == null || !o.keyHash.matches("[0-9a-f]{64}")) { throw new IllegalArgumentException("Pedido invalido: " + e.getKey()); }
        }
    }
}
