package br.goldsuite.api;

import cn.nukkit.level.Level;

/**
 * Zonas do GoldComandos: cada mapa (lobby do Rankup, lobby de minigames...) diz quais plugins e recursos funcionam nele.
 * Recursos tem o nome "plugin.recurso" (ex.: "goldpvp.perfil", "goldfactions.territorio", "goldskills.xp").
 * Bloquear um plugin inteiro numa zona desliga todos os recursos dele e todos os comandos dele.
 */
public interface ZoneService {
    /** O recurso funciona neste mundo? Sem zona configurada, tudo funciona. */
    boolean allows(Level level, String feature);
    /** Linhas da barra (HUD) desta zona, ou null para usar as do painel. */
    default String[] hud(Level level) { return null; }
}
