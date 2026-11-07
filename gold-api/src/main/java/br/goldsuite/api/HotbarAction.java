package br.goldsuite.api;

import cn.nukkit.Player;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Quando um item de menu da hotbar age. O padrao e tocar no chao (ou num bloco) com o item na mao:
 * so selecionar o slot nao faz nada, para quem so esta passando o dedo pela hotbar nao disparar o item
 * (e nao cair num vai-e-volta de teleportes entre lobbies).
 * O painel do GoldCore tem "hotbar.ao_selecionar" (0 = tocar no chao, 1 = ao selecionar o slot, o jeito antigo).
 * Excecao de cada plugin: quem nao tem chao para tocar (espectador voando) continua agindo ao selecionar.
 * So e usado na thread principal do servidor.
 */
public final class HotbarAction {
    /** Um toque no 0.15 pode gerar mais de um evento (bloco e ar); dentro desta janela so o primeiro vale. */
    public static final long SAME_TAP_MS = 500;
    private static volatile boolean onSelect;
    /** Ultimo toque que agiu, por jogador; fraco para nao segurar quem saiu. */
    private static final Map<Player, Long> lastTap = new WeakHashMap<>();

    private HotbarAction() { }

    /** true quando o painel pede o jeito antigo (agir ao selecionar o slot). */
    public static boolean onSelect() { return onSelect; }
    public static void setOnSelect(boolean value) { onSelect = value; }

    /** Chame so depois de saber que o item e seu. true = este toque age; false = segundo evento do mesmo toque. */
    public static boolean tap(Player player) { return tap(player, System.currentTimeMillis()); }

    static boolean tap(Player player, long now) {
        Long last = lastTap.get(player);
        if (last != null && now - last >= 0 && now - last < SAME_TAP_MS) { return false; }
        lastTap.put(player, now);
        return true;
    }
}
