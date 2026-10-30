package br.goldsuite.hg;

import br.goldsuite.api.Destination;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Configuracao persistida. Nada aqui e lido durante a partida alem dos campos ja carregados em memoria. */
public final class HgState {
    public static final int MAX_SPAWNS = 24;
    public Map<String, Arena> arenas = new TreeMap<>();
    public Rules rules = new Rules();
    /** Jogadores que preferem tomar sopa ao selecionar o slot (celular). */
    public Set<String> soupOnSelect = new TreeSet<>();
    /** Mundos fora do HG onde a sopa tambem cura na hora (arenas de PvP). */
    public Set<String> soupWorlds = new TreeSet<>();
    /** Quem saiu no meio da partida: o inventario e limpo ao voltar (evita levar o loot do HG). */
    public Set<String> pendingClear = new TreeSet<>();

    public static final class Arena {
        /** {x, y, z, yaw} de cada spawn, na ordem de entrada. */
        public List<double[]> spawns = new ArrayList<>();
        /** Centro da batalha final; vazio usa o spawn do mundo. */
        public double[] finale = new double[0];
    }

    /** Tempos em segundos; valores padrao do HG-PLUS original. */
    public static final class Rules {
        public int minPlayers = 2;
        public int waitSeconds = 180;
        public int graceSeconds = 180;
        public int gameSeconds = 1380;
        /** Segundos restantes quando todos vao para a batalha final. */
        public int finaleAt = 180;
        /** Segundos restantes do segundo reabastecimento dos baus. */
        public int refillAt = 420;
        /** Segundos restantes quando o feast aparece no centro (0 desliga). Anunciado 5 min antes. */
        public int feastAt = 780;
        /** Premio do vencedor em centavos (GoldEconomy). */
        public long rewardCents = 10000;
        /** Vida curada por sopa (2 = 1 coracao). */
        public int soupHeal = 7;
        /** Devolve a tigela vazia; desligado para nao encher a hotbar no celular. */
        public boolean soupBowl = false;
        /** {id, meta, quantidade}; cada slot do bau tem 1/3 de chance de receber um sorteado. */
        public List<int[]> chestItems = defaultItems();
        /** Itens dos baus do feast: equipamento melhor que o dos baus do mapa. */
        public List<int[]> feastItems = feastItems();
    }

    static List<int[]> defaultItems() {
        int[][] items = {{258,0,1},{260,0,5},{261,0,1},{262,0,6},{264,0,6},{265,0,8},{272,0,1},{276,0,1},{282,0,1},{282,0,1},{282,0,1},{275,0,1},{297,0,3},
            {298,0,1},{299,0,1},{300,0,1},{301,0,1},{302,0,1},{303,0,1},{304,0,1},{305,0,1},{306,0,1},{307,0,1},{308,0,1},{309,0,1},
            {314,0,1},{315,0,1},{316,0,1},{317,0,1},{320,0,4},{280,0,1},{364,0,4},{366,0,5},{391,0,5},{368,0,1},{345,0,1}};
        List<int[]> list = new ArrayList<>();
        for (int[] item : items) { list.add(item); }
        return list;
    }

    static List<int[]> feastItems() {
        int[][] items = {{276,0,1},{310,0,1},{311,0,1},{312,0,1},{313,0,1},{322,0,1},{282,0,1},{282,0,1},{282,0,1},{282,0,1},
            {261,0,1},{262,0,16},{267,0,1},{368,0,1},{259,0,1},{320,0,6},{264,0,3}};
        List<int[]> list = new ArrayList<>();
        for (int[] item : items) { list.add(item); }
        return list;
    }

    public static String arenaName(String name) { return Destination.world(name).target; }
    public static String player(String name) { return name.toLowerCase(Locale.ROOT); }

    public Arena require(String name) {
        Arena arena = arenas.get(name);
        if (arena == null) { throw new IllegalArgumentException("Arena inexistente: " + name); }
        return arena;
    }

    public static void validate(HgState state) {
        if (state == null || state.arenas == null || state.rules == null || state.soupOnSelect == null || state.soupWorlds == null
                || state.pendingClear == null || state.arenas.size() > 32 || state.soupOnSelect.size() > 10000 || state.soupWorlds.size() > 64 || state.pendingClear.size() > 10000) { throw new IllegalArgumentException("Dados do HG invalidos ou excessivos."); }
        Rules r = state.rules;
        if (r.minPlayers < 2 || r.minPlayers > MAX_SPAWNS || r.waitSeconds < 5 || r.waitSeconds > 900 || r.graceSeconds < 0 || r.gameSeconds < 60 || r.gameSeconds > 7200
                || r.graceSeconds >= r.gameSeconds || r.finaleAt < 0 || r.finaleAt >= r.gameSeconds || r.refillAt < 0 || r.rewardCents < 0 || r.rewardCents > 1_000_000_000L
                || r.soupHeal < 1 || r.soupHeal > 20 || r.feastAt < 0 || r.feastAt >= r.gameSeconds || r.chestItems == null || r.chestItems.isEmpty() || r.chestItems.size() > 256
                || r.feastItems == null || r.feastItems.isEmpty() || r.feastItems.size() > 256) { throw new IllegalArgumentException("Regras do HG invalidas."); }
        List<int[]> all = new ArrayList<>(r.chestItems); all.addAll(r.feastItems);
        for (int[] item : all) {
            if (item == null || item.length != 3 || item[0] < 1 || item[0] > 511 || item[1] < 0 || item[1] > 32767 || item[2] < 1 || item[2] > 64) { throw new IllegalArgumentException("Item de bau invalido."); }
        }
        for (Map.Entry<String, Arena> e : state.arenas.entrySet()) {
            arenaName(e.getKey());
            Arena arena = e.getValue();
            if (arena == null || arena.spawns == null || arena.spawns.size() > MAX_SPAWNS || arena.finale == null || (arena.finale.length != 0 && arena.finale.length != 3)) { throw new IllegalArgumentException("Arena invalida: " + e.getKey()); }
            for (double[] spawn : arena.spawns) {
                if (spawn == null || spawn.length != 4) { throw new IllegalArgumentException("Spawn invalido."); }
                Destination.position(e.getKey(), spawn[0], spawn[1], spawn[2], (float) spawn[3], 0);
            }
            if (arena.finale.length == 3) { Destination.position(e.getKey(), arena.finale[0], arena.finale[1], arena.finale[2], 0, 0); }
        }
        for (String world : state.soupWorlds) { arenaName(world); }
        for (String name : state.pendingClear) { if (!name.matches("[a-z0-9_]{3,16}")) { throw new IllegalArgumentException("Nome invalido na lista de limpeza."); } }
        for (String name : state.soupOnSelect) { if (!name.matches("[a-z0-9_]{3,16}")) { throw new IllegalArgumentException("Nome invalido na lista de sopa."); } }
    }
}
