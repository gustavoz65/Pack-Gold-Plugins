package br.goldsuite.api;

import cn.nukkit.Player;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/** Leva jogadores a mundos, posicoes ou (com proxy) outros servidores, com uma transicao visual. */
public interface TravelService {
    enum Transition {
        /** Teleporte imediato. */
        INSTANTANEA,
        /** Particulas de portal girando e nausea leve; 1,5 s. */
        PORTAL,
        /** Helice dupla de particulas subindo; 1,5 s. */
        ESPIRAL,
        /** Tela escurece (cegueira), teleporta no escuro e clareia; 1 s. */
        ESCURECER,
        /** Contagem 3, 2, 1 com som; cancela se o jogador andar. */
        CONTAGEM,
        /** Clarao de explosao sem dano e teleporte. */
        EXPLOSAO;

        public static Transition parse(String text) {
            try { return valueOf(text.toUpperCase(Locale.ROOT)); }
            catch (RuntimeException error) { throw new IllegalArgumentException("Transicao invalida. Use: instantanea, portal, espiral, escurecer, contagem ou explosao."); }
        }
    }
    /** Conclui com true ao chegar, false se cancelada; falha se o destino nao existir ou nao for suportado. */
    CompletableFuture<Boolean> travel(Player player, Destination destination, Transition transition);
    boolean isTraveling(Player player);
    void cancel(Player player);
}
