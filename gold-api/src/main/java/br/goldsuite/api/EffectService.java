package br.goldsuite.api;

import cn.nukkit.Player;
import cn.nukkit.level.Position;

/**
 * Efeitos de particulas com orcamento: so quem esta a ate 24 blocos recebe, ha limite de efeitos
 * simultaneos e a densidade cai sozinha quando o TPS baixa. Chamadas na thread principal.
 */
public interface EffectService {
    enum Shape {
        /** Anel que sobe em volta do ponto. */
        ANEL,
        /** Helice dupla subindo. */
        ESPIRAL,
        /** Explosao de particulas a partir do centro. */
        EXPLOSAO,
        /** Coracoes e brilhos (boas-vindas). */
        CELEBRACAO,
        /** Portal girando. */
        PORTAL
    }
    /** Toca o efeito por alguns ticks. Retorna false se o orcamento estiver cheio. */
    boolean play(Position center, Shape shape, int ticks);
    /** Efeito que acompanha o jogador enquanto dura. */
    boolean follow(Player player, Shape shape, int ticks);
}
