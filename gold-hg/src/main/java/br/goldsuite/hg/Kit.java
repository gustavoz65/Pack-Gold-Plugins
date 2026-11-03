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
    NENHUM(Category.NENHUM, M.t("sem_kit"), 345, 0, M.t("so_bussola")),
    // Mobilidade e posicionamento
    CANGURU(Category.MOBILIDADE, "Kangaroo", 414, 0, M.t("toque_com_pata_de_coelho_para")),
    GRAPPLER(Category.MOBILIDADE, "Grappler", 346, 0, M.t("mire_num_bloco_toque_com_vara")),
    NINJA(Category.MOBILIDADE, "Ninja", 351, 0, M.t("agache_para_aparecer_nas_costas_do")),
    FLASH(Category.MOBILIDADE, "Flash", 76, 0, M.t("toque_com_tocha_de_redstone_para")),
    BLINK(Category.MOBILIDADE, "Blink", 331, 0, M.t("toque_com_redstone_para_avancar_6")),
    SWITCHER(Category.MOBILIDADE, "Switcher", 332, 0, M.t("bolas_de_neve_trocam_voce_de")),
    PESCADOR(Category.MOBILIDADE, "Fisherman", 346, 0, M.t("mire_num_jogador_toque_com_vara")),
    FORJADOR(Category.MOBILIDADE, "Forger", 263, 0, M.t("toque_com_carvao_para_criar_uma")),
    METEORO(Category.MOBILIDADE, "Meteor", 378, 0, M.t("toque_com_creme_de_magma_para")),
    // Combate
    ANCORA(Category.COMBATE, "Anchor", 145, 0, M.t("voce_nao_recebe_nao_causa_repulsao")),
    BOXER(Category.COMBATE, "Boxer", 336, 0, M.t("soco_com_dano_de_espada_de")),
    VIPER(Category.COMBATE, "Viper", 375, 0, M.t("33_de_chance_de_envenenar_cada")),
    SNAIL(Category.COMBATE, "Snail", 341, 0, M.t("33_de_chance_de_causar_lentidao")),
    CRITICO(Category.COMBATE, "Critical", 377, 0, M.t("20_de_chance_de_golpe_critico")),
    BARBARO(Category.COMBATE, "Barbarian", 268, 0, M.t("sua_espada_ganha_afiacao_cada_abate")),
    MONGE(Category.COMBATE, "Monk", 280, 0, M.t("bata_com_graveto_para_trocar_item")),
    MAGMA(Category.COMBATE, "Magma", 325, 10, M.t("33_de_chance_de_incendiar_ao")),
    CEIFADOR(Category.COMBATE, "Reaper", 290, 0, M.t("golpes_com_enxada_aplicam_wither")),
    HULK(Category.COMBATE, "Hulk", 33, 0, M.t("toque_num_jogador_com_mao_vazia")),
    THOR(Category.COMBATE, "Thor", 271, 0, M.t("toque_chao_com_machado_de_madeira")),
    GLADIADOR(Category.COMBATE, "Gladiator", 42, 0, M.t("toque_num_jogador_com_bloco_de")),
    STOMPER(Category.COMBATE, "Stomper", 309, 0, M.t("cair_em_cima_dos_outros_repassa")),
    // Defesa e sobrevivencia
    AQUILES(Category.DEFESA, "Achilles", 311, 0, M.t("espadas_causam_1_3_do_dano")),
    TANQUE(Category.DEFESA, "Tank", 46, 0, M.t("cada_abate_explode_no_local_imune")),
    TARTARUGA(Category.DEFESA, "Turtle", 307, 0, M.t("agachado_recebe_no_maximo_meio_coracao")),
    NEO(Category.DEFESA, "Neo", 262, 0, M.t("imune_projeteis_habilidades_que_movem_jogadores")),
    // Economia e farm
    CULTIVADOR(Category.FARM, "Cultivator", 6, 0, M.t("mudas_sementes_crescem_na_hora_ao")),
    ESPECIALISTA(Category.FARM, "Specialist", 116, 0, M.t("mesa_de_encantamento_portatil_3_niveis")),
    LENHADOR(Category.FARM, "Lumberjack", 17, 0, M.t("quebrar_base_com_machado_derruba_arvore")),
    DEMOLIDOR(Category.FARM, "Demoman", 13, 0, M.t("placa_de_pressao_sobre_cascalho_vira")),
    MINERADOR(Category.FARM, "Miner", 274, 0, M.t("picareta_eficiente_minerio_de_ferro_ou")),
    // Estrategicas
    ENDERMAGE(Category.ESTRATEGIA, "Endermage", 120, 0, M.t("coloque_portal_quem_estiver_acima_ou")),
    FANTASMA(Category.ESTRATEGIA, "Phantom", 288, 0, M.t("toque_com_pena_para_voar_por")),
    VOVO(Category.ESTRATEGIA, "Grandpa", 280, 0, M.t("graveto_com_repulsao_ii")),
    ESPIAO(Category.ESTRATEGIA, "Spy", 20, 0, M.t("fique_agachado_parado_por_3_para")),
    ARANHA(Category.ESTRATEGIA, "Spider", 30, 0, M.t("teias_para_prender_inimigos_agachado_escala")),
    SURPRESA(Category.NENHUM, "Surpresa", 54, 0, M.t("um_kit_aleatorio_no_inicio_da"));

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
            case BARBARO: list.add(named(Item.get(268, 0, 1), M.t("espada_do_barbaro_2"))); break;
            case MONGE: list.add(named(Item.get(STICK, 0, 1), M.t("bastao_do_monge"))); break;
            case CEIFADOR: list.add(named(Item.get(WOODEN_HOE, 0, 1), "Foice")); break;
            case THOR: list.add(named(Item.get(WOODEN_AXE, 0, 1), M.t("martelo_de_thor"))); break;
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
                list.add(named(stick, M.t("bengala_do_vovo")));
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
        throw new IllegalArgumentException(M.t("kit_inexistente_use_hgkits_para_ver"));
    }

    /** Copia: Enchantment.get devolve a instancia global. */
    static Enchantment enchant(int id, int level) { Enchantment e = Enchantment.getEnchantment(id); e.setLevel(level); return e; }

    private static Item named(Item item, String name) { item.setCustomName("§r" + name); return item; }
}
