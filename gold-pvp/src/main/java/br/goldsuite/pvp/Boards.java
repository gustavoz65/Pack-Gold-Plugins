package br.goldsuite.pvp;

import br.goldsuite.api.Destination;
import br.goldsuite.api.ItemData;
import java.util.*;

/** Top 100 de abates e de rating, arenas do ranked e kit do ranked. Documento pequeno de proposito. */
public final class Boards {
    public static final int SIZE = 100;
    public Map<String, Integer> kills = new HashMap<>();
    public Map<String, Integer> rating = new HashMap<>();
    public Map<String, Arena> arenas = new TreeMap<>();
    public List<ItemData> kit = new ArrayList<>();
    /** Mundos onde o item de perfil aparece na hotbar. */
    public Set<String> lobbyWorlds = new TreeSet<>();

    public static final class Arena { public Destination a, b; }

    /** Atualiza a posicao do jogador e corta o excedente (menor valor sai). */
    static void offer(Map<String, Integer> board, String player, int value) {
        board.put(player, value);
        if (board.size() > SIZE) {
            String lowest = null;
            for (Map.Entry<String, Integer> e : board.entrySet()) { if (lowest == null || e.getValue() < board.get(lowest)) { lowest = e.getKey(); } }
            board.remove(lowest);
        }
    }

    static List<Map.Entry<String, Integer>> sorted(Map<String, Integer> board) {
        List<Map.Entry<String, Integer>> list = new ArrayList<>(board.entrySet());
        list.sort((x, y) -> y.getValue().equals(x.getValue()) ? x.getKey().compareTo(y.getKey()) : y.getValue() - x.getValue());
        return list;
    }

    static int position(Map<String, Integer> board, String player) {
        List<Map.Entry<String, Integer>> list = sorted(board);
        for (int i = 0; i < list.size(); i++) { if (list.get(i).getKey().equals(player)) { return i + 1; } }
        return 0;
    }

    public static void validate(Boards s) {
        if (s == null || s.kills == null || s.rating == null || s.arenas == null || s.kit == null || s.lobbyWorlds == null
                || s.kills.size() > SIZE || s.rating.size() > SIZE || s.arenas.size() > 20 || s.kit.size() > 40 || s.lobbyWorlds.size() > 16) { throw new IllegalArgumentException("Rankings invalidos."); }
        for (String n : s.kills.keySet()) { if (!n.matches("[a-z0-9_]{3,16}")) { throw new IllegalArgumentException("Nome invalido."); } }
        for (String n : s.rating.keySet()) { if (!n.matches("[a-z0-9_]{3,16}")) { throw new IllegalArgumentException("Nome invalido."); } }
        for (Map.Entry<String, Arena> e : s.arenas.entrySet()) {
            Arena a = e.getValue();
            if (!e.getKey().matches("[a-z0-9_-]{1,20}") || a == null || a.a == null || a.b == null) { throw new IllegalArgumentException("Arena invalida."); }
            a.a.validate(); a.b.validate();
        }
        for (br.goldsuite.api.ItemData d : s.kit) { d.validate(); }
        for (String w : s.lobbyWorlds) { Destination.world(w); }
    }
}
