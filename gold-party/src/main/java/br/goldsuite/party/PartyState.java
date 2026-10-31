package br.goldsuite.party;

import java.util.*;

/** Estatisticas do Party e quais arenas fixas ja foram construidas em cada vaga de sala. */
public final class PartyState {
    public Map<String, Stats> stats = new HashMap<>();
    /** "vaga:minigame:versao" ja construidos (nao refaz depois de reiniciar). */
    public Set<String> built = new TreeSet<>();

    public static final class Stats {
        public int wins, games, points, firsts;
    }

    public Stats of(String player) { return stats.computeIfAbsent(player.toLowerCase(Locale.ROOT), k -> new Stats()); }

    public List<Map.Entry<String, Stats>> top(int n) {
        List<Map.Entry<String, Stats>> list = new ArrayList<>(stats.entrySet());
        list.sort((a, b) -> a.getValue().wins != b.getValue().wins ? Integer.compare(b.getValue().wins, a.getValue().wins) : Integer.compare(b.getValue().points, a.getValue().points));
        return list.subList(0, Math.min(n, list.size()));
    }

    public static void validate(PartyState s) {
        if (s == null || s.stats == null || s.built == null || s.stats.size() > 200_000 || s.built.size() > 2000) { throw new IllegalArgumentException("Dados do Party invalidos."); }
        for (Map.Entry<String, Stats> e : s.stats.entrySet()) {
            Stats st = e.getValue();
            if (e.getKey() == null || !e.getKey().matches("[a-z0-9_ ]{1,16}") || st == null || st.wins < 0 || st.games < 0 || st.points < 0 || st.firsts < 0) { throw new IllegalArgumentException("Estatistica invalida."); }
        }
    }
}
