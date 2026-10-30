package br.goldsuite.hg;

import cn.nukkit.Player;
import cn.nukkit.level.Level;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Estado de uma partida em memoria. Nao toca disco; a configuracao chega pronta em {@link HgState}. */
final class Game {
    enum Phase {
        /** Menos jogadores que o minimo. */
        AGUARDANDO,
        /** Contagem para comecar; jogadores andam livres, sem dano, e podem entrar e sair. */
        CONTAGEM,
        /** Partida comecou, PvP desligado; ainda aceita quem chega (estilo HG do PC). */
        INVENCIVEL,
        /** PvP ligado. */
        LUTA,
        /** Mapa sendo restaurado; nao aceita entradas. */
        RESETANDO
    }

    final String arena;
    Phase phase = Phase.AGUARDANDO;
    /** Contagem (fases de espera) ou segundos restantes da partida. */
    int seconds;
    /** Jogador -> indice do spawn reservado. */
    final Map<Player, Integer> players = new IdentityHashMap<>();
    boolean finaleDone, refillDone, feastDone;
    /** /hg iniciar: comeca mesmo abaixo do minimo. */
    boolean forced;

    Game(String arena, int waitSeconds) { this.arena = arena; this.seconds = waitSeconds; }

    boolean waiting() { return phase == Phase.AGUARDANDO || phase == Phase.CONTAGEM; }
    /** Entrar e sair sem punicao enquanto o PvP nao foi liberado. */
    boolean open() { return waiting() || phase == Phase.INVENCIVEL; }
    boolean pvp() { return phase == Phase.LUTA; }
    boolean protectedPhase() { return phase != Phase.LUTA; }

    /** Primeiro spawn livre ou -1. */
    int freeSpawn(int spawnCount) {
        boolean[] used = new boolean[spawnCount];
        for (int index : players.values()) { if (index < spawnCount) { used[index] = true; } }
        for (int i = 0; i < spawnCount; i++) { if (!used[i]) { return i; } }
        return -1;
    }

    List<Player> online() {
        List<Player> list = new ArrayList<>(players.size());
        for (Player player : players.keySet()) { if (player.isOnline()) { list.add(player); } }
        return list;
    }

    void reset(int waitSeconds) {
        players.clear();
        phase = Phase.AGUARDANDO;
        seconds = waitSeconds;
        finaleDone = false; refillDone = false; feastDone = false; forced = false;
    }

    static String clock(int seconds) { return String.format("%d:%02d", seconds / 60, seconds % 60); }

    static boolean inLevel(Player player, Level level) { return player.isOnline() && player.level == level; }
}
