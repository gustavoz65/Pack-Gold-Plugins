package br.goldsuite.skywars;

import java.util.*;

/**
 * Kits no estilo do SkyWars do Hypixel, adaptados aos itens do 0.15.10. Cada kit so muda o inventario inicial;
 * o equilibrio vem dos baus. Precos em centavos (dinheiro do GoldEconomy), editaveis no /painel.
 */
final class Kits {
    static final class Kit {
        final String id, name, description; final int icon, iconMeta; final long price; final String[] items;
        Kit(String id, String name, int icon, int iconMeta, long price, String description, String... items) {
            this.id = id; this.name = name; this.icon = icon; this.iconMeta = iconMeta; this.price = price; this.description = description; this.items = items;
        }
    }

    static final Map<String, Kit> ALL = new LinkedHashMap<>();
    static final String DEFAULT = "padrao";
    /** Nome do item que vira a "Perola do Fim" (bola de neve que teleporta, ja que o 0.15 nao tem perola). */
    static final String PEARL = "§r§5Perola do Fim";

    private static void add(Kit k) { ALL.put(k.id, k); }

    static {
        add(new Kit("padrao", "Padrao", 270, 0, 0, "Picareta, machado e pa de madeira", "270", "271", "269"));
        add(new Kit("armeiro", "Armeiro", 315, 0, 150_000, "Peitoral de ouro Protecao II e capacete de ouro", "315:0:1/0:2", "314"));
        add(new Kit("arqueiro", "Arqueiro", 261, 0, 200_000, "Arco, 12 flechas e capacete de couro", "261", "262:0:12", "298"));
        add(new Kit("beisebol", "Jogador de Beisebol", 268, 0, 150_000, "Espada de madeira Repulsao I e capacete de ferro", "268:0:1/12:1", "306"));
        add(new Kit("canhoneiro", "Canhoneiro", 46, 0, 250_000, "6 TNTs que acendem sozinhas e botas de couro", "46:0:6", "301"));
        add(new Kit("piromano", "Piromaniaco", 259, 0, 250_000, "Isqueiro, balde de lava e pocao de resistencia ao fogo", "259", "325:10:1", "373:12:1"));
        add(new Kit("boneco", "Boneco de Neve", 332, 0, 100_000, "16 bolas de neve e capacete de couro", "332:0:16", "298"));
        add(new Kit("batedor", "Batedor", 373, 16, 200_000, "2 pocoes de Velocidade II e botas de ferro", "373:16:2", "309"));
        add(new Kit("curandeiro", "Curandeiro", 373, 21, 250_000, "2 pocoes de Cura e 1 de Regeneracao", "373:21:2", "373:28:1"));
        add(new Kit("troll", "Troll", 30, 0, 150_000, "8 teias e 8 bolas de neve", "30:0:8", "332:0:8"));
        add(new Kit("enderman", "Enderman", 332, 0, 400_000, "1 Perola do Fim (teleporta) e 16 blocos de pedra do end", "pearl", "121:0:16"));
        add(new Kit("fazendeiro", "Fazendeiro", 344, 0, 100_000, "16 ovos, 1 maca dourada e 4 paes", "344:0:16", "322", "297:0:4"));
        add(new Kit("ecologista", "Ecologista", 275, 0, 150_000, "Machado de ferro e 32 blocos de madeira", "258", "5:0:32"));
        add(new Kit("espeleologo", "Espeleologo", 278, 0, 300_000, "Picareta de diamante Eficiencia II e 32 de pedregulho", "278:0:1/15:2", "4:0:32"));
        add(new Kit("cavaleiro", "Cavaleiro", 283, 0, 250_000, "Espada de ouro Afiada II e calca de malha", "283:0:1/9:2", "304"));
        add(new Kit("sapo", "Sapo", 111, 0, 100_000, "2 pocoes de Salto e 8 vitorias-regias", "373:11:2", "111:0:8"));
        add(new Kit("princesa", "Princesa", 261, 0, 350_000, "Arco Forca I, 16 flechas e capacete de ouro Protecao I", "261:0:1/19:1", "262:0:16", "314:0:1/0:1"));
        add(new Kit("granadeiro", "Granadeiro", 438, 23, 300_000, "3 pocoes arremessaveis de Dano", "438:23:3"));
        add(new Kit("construtor", "Construtor", 4, 0, 100_000, "64 de pedregulho e picareta de pedra", "4:0:64", "274"));
    }

    static List<cn.nukkit.item.Item> items(Kit k) {
        List<cn.nukkit.item.Item> out = new ArrayList<>();
        for (String s : k.items) { out.add(s.equals("pearl") ? Items.named(cn.nukkit.item.Item.get(332, 0, 1), PEARL) : Items.parse(s)); }
        return out;
    }

    private Kits() {}
}
