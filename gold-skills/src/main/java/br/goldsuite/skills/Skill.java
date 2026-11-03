package br.goldsuite.skills;

import java.util.Locale;

/** As habilidades (as do mcMMO que o 0.15 suporta), com o modificador de XP do servidor original. */
enum Skill {
    MINERACAO("Mineracao", 2.0, 257, M.t("super_picareta")),
    LENHADOR("Lenhador", 1.0, 258, M.t("derrubador_de_arvores")),
    ESCAVACAO("Escavacao", 2.0, 256, M.t("super_pa")),
    HERBALISMO("Herbalismo", 10.0, 292, M.t("terra_verde")),
    ESPADAS("Espadas", 100.0, 267, M.t("golpes_serrilhados")),
    MACHADO("Machado", 500.0, 258, M.t("racha_cranio")),
    ARQUEIRO("Arqueiro", 750.0, 261, null),
    DESARMADO("Desarmado", 1.0, 334, "Furia"),
    ACROBACIA("Acrobacia", 1.0, 288, null),
    REPARACAO("Reparacao", 1.0, 42, null);

    static final Skill[] ALL = values();
    final String label, ability;
    /** Experience_Formula.Modifier do mcMMO do pack. */
    final double modifier;
    final int icon;

    Skill(String label, double modifier, int icon, String ability) { this.label = label; this.modifier = modifier; this.icon = icon; this.ability = ability; }

    String id() { return name().toLowerCase(Locale.ROOT); }

    static Skill parse(String text) {
        String t = text == null ? "" : text.toLowerCase(Locale.ROOT).replace("ç", "c").replace("ã", "a");
        for (Skill s : ALL) { if (s.id().equals(t) || s.id().startsWith(t) && t.length() >= 3) { return s; } }
        switch (t) {
            case "mining": return MINERACAO; case "woodcutting": return LENHADOR; case "excavation": return ESCAVACAO; case "herbalism": return HERBALISMO;
            case "swords": return ESPADAS; case "axes": case "machados": return MACHADO; case "archery": return ARQUEIRO; case "unarmed": return DESARMADO;
            case "acrobatics": return ACROBACIA; case "repair": return REPARACAO;
            default: throw new IllegalArgumentException(M.t("habilidade_invalida_use_mineracao_lenhador_escav", text));
        }
    }
}
