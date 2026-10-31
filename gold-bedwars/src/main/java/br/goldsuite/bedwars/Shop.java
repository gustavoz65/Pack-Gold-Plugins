package br.goldsuite.bedwars;

import java.util.*;

/** Loja no padrao do BedWars brasileiro (MushMC/Hypixel): categorias, itens e precos em ferro, ouro, diamante e esmeralda. */
final class Shop {
    static final int IRON = 265, GOLD = 266, DIAMOND = 264, EMERALD = 388;

    /** Uma oferta: {nome, icone "id:meta", item entregue (ou acao especial), moeda, preco}. Lã e argila usam a cor do time. */
    static final class Offer {
        final String name, icon, give; final int currency, price;
        Offer(String name, String icon, String give, int currency, int price) { this.name = name; this.icon = icon; this.give = give; this.currency = currency; this.price = price; }
    }

    static final Map<String, List<Offer>> CATEGORIES = new LinkedHashMap<>();
    static final Map<String, String> CATEGORY_ICON = new LinkedHashMap<>();

    private static void cat(String name, String icon, Offer... offers) { CATEGORIES.put(name, Arrays.asList(offers)); CATEGORY_ICON.put(name, icon); }

    static {
        cat("Blocos", "35:0",
                new Offer("Lã x16", "35:0", "wool:16", IRON, 4), new Offer("Argila endurecida x16", "159:0", "clay:16", IRON, 12),
                new Offer("Vidro x4", "20:0", "20:0:4", IRON, 12), new Offer("Pedra do End x12", "121:0", "121:0:12", IRON, 24),
                new Offer("Escada x16", "65:0", "65:0:16", IRON, 4), new Offer("Madeira x16", "5:0", "5:0:16", GOLD, 4),
                new Offer("Obsidiana x4", "49:0", "49:0:4", EMERALD, 4));
        cat("Armas", "272:0",
                new Offer("Espada de pedra", "272:0", "sword:272", IRON, 10), new Offer("Espada de ferro", "267:0", "sword:267", GOLD, 7),
                new Offer("Espada de diamante", "276:0", "sword:276", EMERALD, 4), new Offer("Graveto Repulsao I", "280:0", "280:0:1/12:1", GOLD, 5));
        cat("Armaduras", "303:0",
                new Offer("Armadura de malha (permanente)", "304:0", "armor:1", IRON, 40), new Offer("Armadura de ferro (permanente)", "308:0", "armor:2", GOLD, 12),
                new Offer("Armadura de diamante (permanente)", "312:0", "armor:3", EMERALD, 6));
        cat("Ferramentas", "257:0",
                new Offer("Picareta (melhorar)", "257:0", "pickaxe", IRON, 10), new Offer("Machado (melhorar)", "258:0", "axe", IRON, 10),
                new Offer("Tesoura (permanente)", "359:0", "shears", IRON, 20));
        cat("Arcos", "261:0",
                new Offer("Flechas x8", "262:0", "262:0:8", GOLD, 2), new Offer("Arco", "261:0", "261", GOLD, 12),
                new Offer("Arco Forca I", "261:0", "261:0:1/19:1", GOLD, 24), new Offer("Arco Forca I + Impacto I", "261:0", "261:0:1/19:1/20:1", EMERALD, 6));
        cat("Pocoes", "373:16",
                new Offer("Velocidade II (45 s)", "373:16", "effect:1:1:45", EMERALD, 1), new Offer("Super Pulo (45 s)", "373:11", "effect:8:4:45", EMERALD, 1),
                new Offer("Invisibilidade (30 s)", "373:7", "effect:14:0:30", EMERALD, 2));
        cat("Utilidades", "46:0",
                new Offer("Maca dourada", "322:0", "322", GOLD, 3), new Offer("Bola de Fogo", "377:0", "fireball", IRON, 40),
                new Offer("TNT (acende sozinha)", "46:0", "46", GOLD, 4), new Offer("Perola do Fim", "332:0", "pearl", EMERALD, 4),
                new Offer("Balde de agua", "325:8", "325:8:1", GOLD, 3), new Offer("Ovo-Ponte", "344:0", "egg", EMERALD, 1),
                new Offer("Esponja x4", "19:0", "19:0:4", GOLD, 3));
    }

    static String currency(int id, int amount) {
        switch (id) { case IRON: return amount + " ferro"; case GOLD: return amount + " ouro"; case DIAMOND: return amount + " diamante" + (amount > 1 ? "s" : ""); default: return amount + " esmeralda" + (amount > 1 ? "s" : ""); }
    }

    /** Escadas de ferramenta: niveis 1..4 (picareta: madeira, ferro, ouro, diamante; machado: madeira, pedra, ferro, diamante). Custo do proximo nivel. */
    static final int[] PICKAXE = {270, 257, 285, 278}, AXE = {271, 275, 258, 279};
    static final int[][] TOOL_COST = {{IRON, 10}, {IRON, 10}, {GOLD, 3}, {GOLD, 6}};
    /** Armadura permanente: 0 couro, 1 malha, 2 ferro, 3 diamante (calca e bota; capacete e peitoral sao de couro). */
    static final int[][] ARMOR = {{300, 301}, {304, 305}, {308, 309}, {312, 313}};

    /** Melhorias do time (diamantes): {id, nome, custos por nivel}. */
    static final Object[][] UPGRADES = {
            {"afiada", "Espadas Afiadas (Afiada I)", new int[] {4}},
            {"protecao", "Armadura Reforcada (Protecao)", new int[] {2, 4, 8, 16}},
            {"pressa", "Mineracao Maniaca (Pressa)", new int[] {2, 4}},
            {"forja", "Forja (ferro/ouro mais rapido, esmeralda na base)", new int[] {2, 4, 6, 8}},
            {"cura", "Cura na Base (Regeneracao)", new int[] {1}},
            {"armadilha", "Armadilha: Cegueira e Lentidao no invasor", new int[] {1}}};

    /** Intervalo (ticks) da forja do time por nivel: {ferro, ouro}. */
    static int[] forge(int level) {
        switch (level) { case 1: return new int[] {13, 80}; case 2: return new int[] {10, 60}; case 3: return new int[] {10, 60}; case 4: return new int[] {7, 40}; default: return new int[] {20, 120}; }
    }

    /** Geradores do meio: segundos por nivel (I, II, III). */
    static final int[] DIAMOND_TIERS = {30, 23, 12}, EMERALD_TIERS = {65, 50, 35};

    /** Linha do tempo (segundo da partida, evento). */
    static final Object[][] EVENTS = {{360, "Diamante II"}, {720, "Esmeralda II"}, {1080, "Diamante III"}, {1440, "Esmeralda III"}, {1800, "Camas destruidas"}, {2400, "Fim do jogo"}};

    private Shop() {}
}
