package br.goldsuite.market;

import br.goldsuite.api.ItemData;
import java.util.*;

/** Anuncios ativos e itens devolvidos esperando coleta. */
public final class MarketState {
    public static final int MAX_LISTINGS = 1000, PER_PLAYER = 5;
    public static final long DURATION_MS = 48L * 3600 * 1000;
    public long nextId = 1;
    public Map<Long, Listing> listings = new TreeMap<>();
    public Map<String, List<ItemData>> returns = new TreeMap<>();

    public static final class Listing {
        public String seller;
        public ItemData item;
        public long price, created;
    }

    int count(String seller) { int n = 0; for (Listing l : listings.values()) { if (l.seller.equals(seller)) { n++; } } return n; }

    /** Anuncios vencidos voltam para a lista de coleta do vendedor. */
    int expire(long now) {
        int moved = 0;
        for (Iterator<Listing> it = listings.values().iterator(); it.hasNext(); ) {
            Listing l = it.next();
            if (now - l.created > DURATION_MS) { giveBack(l.seller, l.item); it.remove(); moved++; }
        }
        return moved;
    }

    void giveBack(String player, ItemData item) { returns.computeIfAbsent(player, k -> new ArrayList<>()).add(item); }

    public static void validate(MarketState s) {
        if (s == null || s.listings == null || s.returns == null || s.listings.size() > MAX_LISTINGS || s.returns.size() > 10000 || s.nextId < 1) { throw new IllegalArgumentException("Leilao invalido."); }
        for (Map.Entry<Long, Listing> e : s.listings.entrySet()) {
            Listing l = e.getValue();
            if (e.getKey() == null || e.getKey() >= s.nextId || l == null || l.seller == null || !l.seller.matches("[a-z0-9_]{3,16}") || l.item == null || l.price < 1 || l.price > 1_000_000_000_00L) { throw new IllegalArgumentException("Anuncio invalido."); }
            l.item.validate();
        }
        for (Map.Entry<String, List<ItemData>> e : s.returns.entrySet()) {
            if (!e.getKey().matches("[a-z0-9_]{3,16}") || e.getValue() == null || e.getValue().size() > 100) { throw new IllegalArgumentException("Coleta invalida."); }
            for (ItemData d : e.getValue()) { d.validate(); }
        }
    }
}
