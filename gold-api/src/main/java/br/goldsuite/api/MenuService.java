package br.goldsuite.api;

import cn.nukkit.Player;
import cn.nukkit.item.Item;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Menus em bau virtual (27 slots): o bau existe so no cliente de quem abriu, os itens nunca saem
 * da janela e o toque num icone vira um clique. Chamadas na thread principal.
 */
public interface MenuService {
    int SIZE = 27;
    /** Abre o menu; onClick recebe o slot tocado (0 a 26) e o menu fecha em seguida. */
    boolean open(Player player, String title, Map<Integer, Item> icons, BiConsumer<Player, Integer> onClick);
    /**
     * Bau virtual que guarda itens de verdade (bau pessoal). onChange recebe o conteudo inteiro no tick
     * seguinte a qualquer mudanca e ao fechar, para o dono gravar sem esperar o jogador sair.
     */
    boolean openStorage(Player player, String title, Map<Integer, Item> contents, Consumer<Map<Integer, Item>> onChange);
    void close(Player player);
    boolean isOpen(Player player);
}
