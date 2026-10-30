package br.goldsuite.cosmetics;

import java.util.*;

/** Todos os cosmeticos. Preco em centavos (0 = nao vende, so se ganha). Fica no codigo: nada a validar do disco. */
final class Catalog {
    enum Type {
        PINGENTE("Pingentes", 323, 0), TRILHA("Trilhas", 377, 0), ABATE("Efeitos de abate", 46, 0), COR("Cor do nome", 351, 12);
        final String title; final int icon, meta;
        Type(String title, int icon, int meta) { this.title = title; this.icon = icon; this.meta = meta; }
    }

    static final class Cosmetic {
        final String id, name, display, source; final Type type; final int icon, meta; final long price;
        Cosmetic(String id, Type type, String name, String display, int icon, int meta, long price, String source) {
            this.id = id; this.type = type; this.name = name; this.display = display; this.icon = icon; this.meta = meta; this.price = price; this.source = source;
        }
        boolean forSale() { return price > 0; }
    }

    static final Map<String, Cosmetic> ALL = new LinkedHashMap<>();

    private static void add(String id, Type type, String name, String display, int icon, int meta, long price, String source) {
        ALL.put(id, new Cosmetic(id, type, name, display, icon, meta, price, source));
    }

    static {
        // Pingentes do ranked (como os chaveiros de elo do Valorant): liberados ao alcancar o elo.
        String[][] tiers = {{"ferro", "Ferro", "§7=", "265"}, {"bronze", "Bronze", "§6=", "336"}, {"prata", "Prata", "§f=", "406"}, {"ouro", "Ouro", "§e*", "266"},
                {"platina", "Platina", "§3**", "370"}, {"diamante", "Diamante", "§b<>", "264"}, {"ascendente", "Ascendente", "§a^", "388"}, {"imortal", "Imortal", "§c^^", "331"}};
        for (String[] t : tiers) { add("pingente_" + t[0], Type.PINGENTE, "Pingente " + t[1], t[2], Integer.parseInt(t[3]), 0, 0, "Ranked: alcance " + t[1]); }
        add("pingente_divino", Type.PINGENTE, "Pingente Divino", "§6§l^^^", 466, 0, 0, "Ranked: entre no top 10 (Divino)");
        add("pingente_vitorias_10", Type.PINGENTE, "10 vitorias", "§aV", 267, 0, 0, "Ranked: 10 vitorias");
        add("pingente_vitorias_50", Type.PINGENTE, "50 vitorias", "§aVV", 283, 0, 0, "Ranked: 50 vitorias");
        add("pingente_vitorias_100", Type.PINGENTE, "100 vitorias", "§2VVV", 276, 0, 0, "Ranked: 100 vitorias");
        // Pingentes da loja.
        add("pingente_coracao", Type.PINGENTE, "Coracao", "§c<3", 351, 1, 50_000, null);
        add("pingente_estrela", Type.PINGENTE, "Estrela", "§b+", 348, 0, 40_000, null);
        add("pingente_musica", Type.PINGENTE, "Musica", "§d#", 25, 0, 60_000, null);
        add("pingente_raio", Type.PINGENTE, "Raio", "§eZ", 331, 0, 80_000, null);
        add("pingente_caveira", Type.PINGENTE, "Caveira", "§7x_x", 397, 0, 100_000, null);
        add("pingente_coroa", Type.PINGENTE, "Coroa", "§6W", 314, 0, 150_000, null);
        // Trilhas.
        add("trilha_fumaca", Type.TRILHA, "Fumaca", "fumaca", 289, 0, 60_000, null);
        add("trilha_agua", Type.TRILHA, "Respingos", "agua", 326, 0, 80_000, null);
        add("trilha_redstone", Type.TRILHA, "Redstone", "redstone", 331, 0, 90_000, null);
        add("trilha_encantada", Type.TRILHA, "Encantada", "encantada", 116, 0, 100_000, null);
        add("trilha_portal", Type.TRILHA, "Portal", "portal", 49, 0, 110_000, null);
        add("trilha_coracoes", Type.TRILHA, "Coracoes", "coracoes", 351, 1, 120_000, null);
        add("trilha_chamas", Type.TRILHA, "Chamas", "chamas", 377, 0, 150_000, null);
        add("trilha_divina", Type.TRILHA, "Divina", "divina", 466, 0, 0, "Ranked: entre no top 10 (Divino)");
        // Efeitos de abate.
        add("abate_coracoes", Type.ABATE, "Chuva de coracoes", "coracoes", 351, 1, 120_000, null);
        add("abate_lava", Type.ABATE, "Lava", "lava", 327, 0, 150_000, null);
        add("abate_explosao", Type.ABATE, "Explosao", "explosao", 46, 0, 200_000, null);
        add("abate_imortal", Type.ABATE, "Coroa Imortal", "imortal", 331, 0, 0, "Ranked: alcance Imortal");
        // Cor do nome no chat.
        add("cor_verde", Type.COR, "Verde", "§a", 351, 10, 30_000, null);
        add("cor_azul", Type.COR, "Azul", "§b", 351, 12, 30_000, null);
        add("cor_rosa", Type.COR, "Rosa", "§d", 351, 9, 30_000, null);
        add("cor_amarelo", Type.COR, "Amarelo", "§e", 351, 11, 30_000, null);
        add("cor_vermelho", Type.COR, "Vermelho", "§c", 351, 1, 30_000, null);
        add("cor_divina", Type.COR, "Dourado Divino", "§6§l", 266, 0, 0, "Ranked: entre no top 10 (Divino)");
    }

    static List<Cosmetic> of(Type type) {
        List<Cosmetic> list = new ArrayList<>();
        for (Cosmetic c : ALL.values()) { if (c.type == type) { list.add(c); } }
        return list;
    }

    static String price(long cents) { return String.format(Locale.ROOT, "$%d.%02d", cents / 100, cents % 100); }

    private Catalog() {}
}
