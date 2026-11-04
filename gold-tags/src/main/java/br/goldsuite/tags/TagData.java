package br.goldsuite.tags;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Tags de um jogador: a escolhida ("" = automatica, a do cargo) e as ganhas em eventos com o prazo (0 = para sempre). */
final class TagData {
    static final String DEFAULT_ID = "jogador";
    String selected = "";
    final Map<String, Long> owned = new TreeMap<>();

    static String id(String text) {
        String id = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        if (!id.matches("[a-z0-9_]{1,24}")) { throw new IllegalArgumentException(M.t("id_invalido")); }
        return id;
    }

    boolean owns(String id, long now) { Long until = owned.get(id); return until != null && (until == 0 || until > now); }

    void prune(long now) { owned.values().removeIf(until -> until != 0 && until <= now); }

    /** Formato gravado: "escolhida|id=expira,id=expira". */
    String encode() {
        StringBuilder sb = new StringBuilder(selected).append('|');
        boolean first = true;
        for (Map.Entry<String, Long> e : owned.entrySet()) { sb.append(first ? "" : ",").append(e.getKey()).append('=').append(e.getValue()); first = false; }
        return sb.toString();
    }

    static TagData decode(String value) {
        TagData d = new TagData();
        if (value == null || value.isEmpty()) { return d; }
        int bar = value.indexOf('|');
        d.selected = bar < 0 ? value : value.substring(0, bar);
        if (bar >= 0 && bar + 1 < value.length()) {
            for (String part : value.substring(bar + 1).split(",")) {
                int eq = part.indexOf('=');
                if (eq <= 0) { continue; }
                try { d.owned.put(part.substring(0, eq), Long.parseLong(part.substring(eq + 1))); } catch (NumberFormatException ignored) { }
            }
        }
        return d;
    }

    /** Nome curto de uma tag para o jogador digitar: "&6[VIP]" vira "VIP". */
    static String plain(String tag) {
        return tag.replaceAll("[§&][0-9a-fk-or]", "").replace("[", "").replace("]", "").replace("«", "").replace("»", "").trim();
    }
}
