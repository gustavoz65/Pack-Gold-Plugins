package br.goldsuite.api;

import cn.nukkit.Player;
import cn.nukkit.plugin.Plugin;
import java.util.function.Function;

/**
 * Valores por jogador que qualquer texto configuravel pode usar como {chave}: HUD, chat, mensagens.
 * Cada plugin registra os seus (economia: {money} e {cash}; cargos: {grupo}; PvP: {kdr}...).
 * Textos usados toda hora devem ser compilados uma vez: o modelo so calcula as chaves que aparecem nele.
 */
public interface PlaceholderService {
    /** Registra (ou troca) a chave; o valor nunca deve ser lento: e chamado a cada atualizacao. */
    void register(Plugin owner, String key, Function<Player, String> value);
    /** Texto ja dividido em partes fixas e chaves. */
    Template compile(String text);
    /** Atalho para textos usados raramente. */
    default String apply(Player player, String text) { return compile(text).render(player); }

    interface Template {
        String render(Player player);
        /** O modelo usa esta chave? (para pular calculos caros) */
        boolean uses(String key);
    }

    /** 1234 -> "1.23k", 5600000 -> "5.6M" (valor inteiro, sem centavos). */
    static String compact(long value) {
        long v = Math.abs(value);
        String sign = value < 0 ? "-" : "";
        if (v < 1000) { return sign + v; }
        String[] units = {"k", "M", "B", "T", "Q"};
        double d = v;
        int u = -1;
        while (d >= 1000 && u < units.length - 1) { d /= 1000; u++; }
        String s = d >= 100 ? String.valueOf((long) d) : d >= 10 ? String.format(java.util.Locale.ROOT, "%.1f", d) : String.format(java.util.Locale.ROOT, "%.2f", d);
        if (s.contains(".")) { s = s.replaceAll("0+$", "").replaceAll("\\.$", ""); }
        return sign + s + units[u];
    }
}
