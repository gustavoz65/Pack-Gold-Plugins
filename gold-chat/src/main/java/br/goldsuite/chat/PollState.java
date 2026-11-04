package br.goldsuite.chat;

import java.util.*;

final class PollState {
    Map<String, Poll> polls = new LinkedHashMap<>();
    static final class Poll {
        String question = "";
        List<String> options = new ArrayList<>();
        Map<String, Integer> votes = new HashMap<>();
        long closes;
        boolean closed;
        boolean open(long now) { return !closed && now < closes; }
        void vote(String account, int option, long now) {
            if (!open(now)) { throw new IllegalArgumentException(M.t("enquete_fechada")); }
            if (option < 0 || option >= options.size()) { throw new IllegalArgumentException(M.t("enquete_opcao_invalida")); }
            if (votes.containsKey(account)) { throw new IllegalArgumentException(M.t("enquete_ja_votou")); }
            if (votes.size() >= 10000) { throw new IllegalArgumentException(M.t("enquete_limite")); }
            votes.put(account, option);
        }
        int[] totals() { int[] out = new int[options.size()]; for (int v : votes.values()) { out[v]++; } return out; }
    }
    static String id(String input) {
        String id = input.toLowerCase(Locale.ROOT);
        if (!id.matches("[a-z0-9_-]{1,24}")) { throw new IllegalArgumentException(M.t("enquete_id_invalido")); }
        return id;
    }
    static void validate(PollState s) {
        if (s == null || s.polls == null || s.polls.size() > 32) { throw new IllegalArgumentException(M.t("enquete_limite")); }
        for (Map.Entry<String, Poll> e : s.polls.entrySet()) {
            id(e.getKey()); Poll p = e.getValue();
            if (p == null || p.question == null || p.question.length() < 1 || p.question.length() > 200 ||
                    p.options == null || p.options.size() < 2 || p.options.size() > 8 || p.votes == null || p.votes.size() > 10000 || p.closes <= 0) {
                throw new IllegalArgumentException(M.t("enquete_uso"));
            }
            Set<String> unique = new HashSet<>();
            for (String option : p.options) { if (option == null || option.trim().isEmpty() || option.length() > 80 || !unique.add(option.toLowerCase(Locale.ROOT))) { throw new IllegalArgumentException(M.t("enquete_opcao_invalida")); } }
            for (Map.Entry<String, Integer> v : p.votes.entrySet()) { if (!v.getKey().matches("[a-z0-9_]{3,16}") || v.getValue() == null || v.getValue() < 0 || v.getValue() >= p.options.size()) { throw new IllegalArgumentException(M.t("enquete_opcao_invalida")); } }
        }
    }
}

