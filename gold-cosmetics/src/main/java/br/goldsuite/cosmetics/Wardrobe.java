package br.goldsuite.cosmetics;

import java.util.*;

/** Cosmeticos de um jogador: os que tem e o equipado de cada tipo. Uma chave por jogador no SQLite. */
final class Wardrobe {
    Set<String> owned = new TreeSet<>();
    Map<String, String> equipped = new TreeMap<>();

    String equipped(Catalog.Type type) { return equipped.get(type.name()); }

    /** Descarta ids que nao existem mais e equipados que o jogador nao tem. */
    Wardrobe clean() {
        if (owned == null) { owned = new TreeSet<>(); }
        if (equipped == null) { equipped = new TreeMap<>(); }
        owned.removeIf(id -> !Catalog.ALL.containsKey(id));
        equipped.entrySet().removeIf(e -> {
            Catalog.Cosmetic c = Catalog.ALL.get(e.getValue());
            return c == null || !owned.contains(c.id) || !c.type.name().equals(e.getKey());
        });
        return this;
    }

    /** Equipa (ou desequipa se ja estava). Retorna true se ficou equipado. */
    boolean toggle(Catalog.Cosmetic c) {
        if (!owned.contains(c.id)) { throw new IllegalArgumentException("Voce nao tem esse cosmetico."); }
        if (c.id.equals(equipped.get(c.type.name()))) { equipped.remove(c.type.name()); return false; }
        equipped.put(c.type.name(), c.id);
        return true;
    }
}
