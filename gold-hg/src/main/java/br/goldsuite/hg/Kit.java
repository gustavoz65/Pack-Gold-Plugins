package br.goldsuite.hg;

import cn.nukkit.item.Item;
import cn.nukkit.item.enchantment.Enchantment;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Kits do HG classico do Java 1.8 adaptados ao cliente 0.15.10. Onde a versao nao tem o item original
 * (fogo de artificio, anzol), a habilidade usa outro item e mira em vez de projetil.
 */
enum Kit {
    NENHUM(Category.NENHUM, "Sem kit", 345, 0, "So a bussola."),
    // Mobilidade e posicionamento
    CANGURU(Category.MOBILIDADE, "Kangaroo", 414, 0, "Toque com a pata de coelho para saltar para cima ou para frente, ate no ar. Sem dano de queda logo apos saltar."),
    GRAPPLER(Category.MOBILIDADE, "Grappler", 346, 0, "Mire num bloco e toque com a vara: voce e puxado ate ele."),
    NINJA(Category.MOBILIDADE, "Ninja", 351, 0, "Agache para aparecer nas costas do ultimo jogador que voce bateu ou que te bateu."),
    FLASH(Category.MOBILIDADE, "Flash", 76, 0, "Toque com a tocha de redstone para teleportar ao bloco que mira. Deixa um raio e gasta fome."),
    BLINK(Category.MOBILIDADE, "Blink", 331, 0, "Toque com a redstone para avancar 6 blocos. 3 cargas; recarrega em 15 s."),
    SWITCHER(Category.MOBILIDADE, "Switcher", 332, 0, "Bolas de neve trocam voce de lugar com quem acertar."),
    PESCADOR(Category.MOBILIDADE, "Fisherman", 346, 0, "Mire num jogador e toque com a vara: ele vem parar na sua frente."),
    FORJADOR(Category.MOBILIDADE, "Forger", 263, 0, "Toque com carvao para criar uma ponte de 5 blocos a frente."),
    METEORO(Category.MOBILIDADE, "Meteor", 378, 0, "Toque com o creme de magma para ser lancado. Ao cair, explode e incendeia quem estiver perto."),
    // Combate
    ANCORA(Category.COMBATE, "Anchor", 145, 0, "Voce nao recebe e nao causa repulsao."),
    BOXER(Category.COMBATE, "Boxer", 336, 0, "Soco com dano de espada de pedra e meio coracao a menos de dano recebido."),
    VIPER(Category.COMBATE, "Viper", 375, 0, "33% de chance de envenenar a cada golpe."),
    SNAIL(Category.COMBATE, "Snail", 341, 0, "33% de chance de causar lentidao a cada golpe."),
    CRITICO(Category.COMBATE, "Critical", 377, 0, "20% de chance de golpe critico com 50% a mais de dano."),
    BARBARO(Category.COMBATE, "Barbarian", 268, 0, "Sua espada ganha Afiacao a cada abate (ate IV)."),
    MONGE(Category.COMBATE, "Monk", 280, 0, "Bata com o graveto para trocar o item da mao do inimigo por outro do inventario dele."),
    MAGMA(Category.COMBATE, "Magma", 325, 10, "33% de chance de incendiar ao bater. Imune a fogo e lava."),
    CEIFADOR(Category.COMBATE, "Reaper", 290, 0, "Golpes com a enxada aplicam Wither."),
    HULK(Category.COMBATE, "Hulk", 33, 0, "Toque num jogador com a mao vazia para agarra-lo e arremessa-lo longe."),
    THOR(Category.COMBATE, "Thor", 271, 0, "Toque o chao com o machado de madeira para chamar um raio. Imune a raios."),
    GLADIADOR(Category.COMBATE, "Gladiator", 42, 0, "Toque num jogador com o bloco de ferro: os dois vao para uma arena de vidro no ceu para um 1v1."),
    STOMPER(Category.COMBATE, "Stomper", 309, 0, "Cair em cima dos outros repassa o dano da queda; o seu fica em ate 2 coracoes."),
    // Defesa e sobrevivencia
    AQUILES(Category.DEFESA, "Achilles", 311, 0, "Espadas causam 1/3 do dano em voce; socos e machados, 50% a mais."),
    TANQUE(Category.DEFESA, "Tank", 46, 0, "Cada abate explode no local. Imune a explosoes e com Resistencia."),
    TARTARUGA(Category.DEFESA, "Turtle", 307, 0, "Agachado recebe no maximo meio coracao, mas causa pouco dano."),
    NEO(Category.DEFESA, "Neo", 262, 0, "Imune a projeteis e a habilidades que movem jogadores."),
    // Economia e farm
    CULTIVADOR(Category.FARM, "Cultivator", 6, 0, "Mudas e sementes crescem na hora ao plantar."),
    ESPECIALISTA(Category.FARM, "Specialist", 116, 0, "Mesa de encantamento portatil e 3 niveis de XP por abate."),
    LENHADOR(Category.FARM, "Lumberjack", 17, 0, "Quebrar a base com o machado derruba a arvore inteira."),
    DEMOLIDOR(Category.FARM, "Demoman", 13, 0, "Placa de pressao sobre cascalho vira mina: explode quando um inimigo pisa."),
    MINERADOR(Category.FARM, "Miner", 274, 0, "Picareta eficiente; minerio de ferro ou carvao sai com o veio inteiro."),
    // Estrategicas
    ENDERMAGE(Category.ESTRATEGIA, "Endermage", 120, 0, "Coloque o portal: quem estiver acima ou abaixo dele e puxado para o combate."),
    FANTASMA(Category.ESTRATEGIA, "Phantom", 288, 0, "Toque com a pena para voar por 5 s. Recarga de 60 s."),
    VOVO(Category.ESTRATEGIA, "Grandpa", 280, 0, "Graveto com Repulsao II."),
    ESPIAO(Category.ESTRATEGIA, "Spy", 20, 0, "Fique agachado e parado por 3 s para ficar invisivel."),
    ARANHA(Category.ESTRATEGIA, "Spider", 30, 0, "Teias para prender inimigos; agachado, escala paredes."),
    SURPRESA(Category.NENHUM, "Surpresa", 54, 0, "Um kit aleatorio no inicio da partida.");

    enum Category { NENHUM, MOBILIDADE, COMBATE, DEFESA, FARM, ESTRATEGIA }

    static final int RABBIT_FOOT = 414, ROD = 346, REDSTONE_TORCH = 76, REDSTONE = 331, COAL = 263, MAGMA_CREAM = 378,
            STICK = 280, WOODEN_HOE = 290, WOODEN_AXE = 271, IRON_BLOCK = 42, FEATHER = 288, COMPASS = 345;

    final Category category;
    final String title;
    final int iconId, iconMeta;
    final String description;

    Kit(Category category, String title, int iconId, int iconMeta, String description) {
        this.category = category; this.title = title; this.iconId = iconId; this.iconMeta = iconMeta; this.description = description;
    }

    Item icon(boolean selected) {
        Item icon = Item.get(iconId, iconMeta, 1);
        icon.setCustomName((selected ? "§a✔ " : "§e") + title + "\n§7" + description);
        return icon;
    }

    /** Itens entregues no inicio da partida (alem da bussola). */
    List<Item> items() {
        List<Item> list = new ArrayList<>();
        switch (this) {
            case CANGURU: list.add(named(Item.get(RABBIT_FOOT, 0, 1), "Impulso")); break;
            case GRAPPLER: list.add(named(Item.get(ROD, 0, 1), "Gancho")); break;
            case FLASH: list.add(named(Item.get(REDSTONE_TORCH, 0, 1), "Flash")); break;
            case BLINK: list.add(named(Item.get(REDSTONE, 0, 1), "Blink")); break;
            case SWITCHER: list.add(Item.get(332, 0, 8)); break;
            case PESCADOR: list.add(named(Item.get(ROD, 0, 1), "Anzol")); break;
            case FORJADOR: list.add(named(Item.get(COAL, 0, 16), "Ponte")); break;
            case METEORO: list.add(named(Item.get(MAGMA_CREAM, 0, 1), "Meteoro")); break;
            case BARBARO: list.add(named(Item.get(268, 0, 1), "Espada do Barbaro")); break;
            case MONGE: list.add(named(Item.get(STICK, 0, 1), "Bastao do Monge")); break;
            case CEIFADOR: list.add(named(Item.get(WOODEN_HOE, 0, 1), "Foice")); break;
            case THOR: list.add(named(Item.get(WOODEN_AXE, 0, 1), "Martelo de Thor")); break;
            case GLADIADOR: list.add(named(Item.get(IRON_BLOCK, 0, 1), "Arena")); break;
            case ESPECIALISTA: list.add(Item.get(116, 0, 1)); list.add(Item.get(351, 4, 16)); break;
            case LENHADOR: list.add(Item.get(WOODEN_AXE, 0, 1)); break;
            case DEMOLIDOR: list.add(Item.get(13, 0, 6)); list.add(Item.get(70, 0, 6)); break;
            case MINERADOR: {
                Item pick = Item.get(274, 0, 1);
                pick.addEnchantment(enchant(Enchantment.ID_EFFICIENCY, 2), enchant(Enchantment.ID_DURABILITY, 2));
                list.add(pick); list.add(Item.get(260, 0, 2));
                break;
            }
            case ENDERMAGE: list.add(named(Item.get(120, 0, 1), "Portal")); break;
            case FANTASMA: list.add(named(Item.get(FEATHER, 0, 1), "Voo")); break;
            case VOVO: {
                Item stick = Item.get(STICK, 0, 1);
                stick.addEnchantment(enchant(Enchantment.ID_KNOCKBACK, 2));
                list.add(named(stick, "Bengala do Vovo"));
                break;
            }
            case ARANHA: list.add(Item.get(30, 0, 8)); break;
            default:
        }
        return list;
    }

    static Kit random() {
        Kit[] all = values();
        // Exclui NENHUM (primeiro) e SURPRESA (ultimo).
        return all[1 + ThreadLocalRandom.current().nextInt(all.length - 2)];
    }

    static Kit parse(String text) {
        String wanted = text.toUpperCase(Locale.ROOT);
        for (Kit kit : values()) { if (kit.name().equals(wanted) || kit.title.equalsIgnoreCase(text)) { return kit; } }
        throw new IllegalArgumentException("Kit inexistente. Use /kits para ver a lista.");
    }

    /** Copia: Enchantment.get devolve a instancia global. */
    static Enchantment enchant(int id, int level) { Enchantment e = Enchantment.getEnchantment(id); e.setLevel(level); return e; }

    private static Item named(Item item, String name) { item.setCustomName("§r" + name); return item; }
}
