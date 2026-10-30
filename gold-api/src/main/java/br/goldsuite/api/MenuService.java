package br.goldsuite.api;

import cn.nukkit.Player;
import cn.nukkit.item.Item;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Menus em bau virtual (27 slots): o bau existe so no cliente de quem abriu, os itens nunca saem
 * da janela e o toque num icone vira um clique. Chamadas na thread principal.
 */
public interface MenuService {
    int SIZE = 27;
    /** Abre o menu; onClick recebe o slot tocado (0 a 26) e o menu fecha em seguida. */
    boolean open(Player player, String title, Map<Integer, Item> icons, BiConsumer<Player, Integer> onClick);
    void close(Player player);
    boolean isOpen(Player player);
}
