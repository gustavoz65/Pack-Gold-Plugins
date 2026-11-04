package br.goldsuite.chat;

import java.util.*;

/**
 * So perguntas e totais (poucos KB). Cada voto e uma linha propria em {@link Polls#VOTES}, entao votar nao regrava
 * todas as enquetes e o numero de votos nao tem limite.
 */
final class PollState {
    Map<String, Poll> polls = new LinkedHashMap<>();
    long serial;
    static final class Poll {
        String question = "";
        List<String> options = new ArrayList<>();
        int[] counts = new int[0];
        long id, closes;
        boolean closed, announced;
        boolean open(long now) { return !closed && now < closes; }
        int total() { int n = 0; for (int c : counts) { n += c; } return n; }
    }
    static String id(String input) {
        String id = input.toLowerCase(Locale.ROOT);
        if (!id.matches("[a-z0-9_-]{1,24}")) { throw new IllegalArgumentException(M.t("enquete_id_invalido")); }
        return id;
    }
    static void validate(PollState s) {
        if (s == null || s.polls == null || s.polls.size() > 32 || s.serial < 0) { throw new IllegalArgumentException(M.t("enquete_limite")); }
        for (Map.Entry<String, Poll> e : s.polls.entrySet()) {
            id(e.getKey()); Poll p = e.getValue();
            if (p == null || p.question == null || p.question.length() < 1 || p.question.length() > 200 ||
                    p.options == null || p.options.size() < 2 || p.options.size() > 8 || p.closes <= 0) {
                throw new IllegalArgumentException(M.t("enquete_uso"));
            }
            if (p.counts == null || p.counts.length != p.options.size()) { p.counts = Arrays.copyOf(p.counts == null ? new int[0] : p.counts, p.options.size()); }
            for (int c : p.counts) { if (c < 0) { throw new IllegalArgumentException(M.t("enquete_opcao_invalida")); } }
            Set<String> unique = new HashSet<>();
            for (String option : p.options) { if (option == null || option.trim().isEmpty() || option.length() > 80 || !unique.add(option.toLowerCase(Locale.ROOT))) { throw new IllegalArgumentException(M.t("enquete_opcao_invalida")); } }
        }
    }
}
