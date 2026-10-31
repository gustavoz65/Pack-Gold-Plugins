package br.goldsuite.party;

import com.google.gson.Gson;
import java.util.*;

public final class PartyTest {
    public static void main(String[] args) {
        Set<String> ids = new HashSet<>();
        for (java.util.function.Supplier<Mini> s : Minis.ALL) {
            Mini m = s.get();
            check(ids.add(m.id()) && m.id().matches("[a-z_]+") && !m.name().isEmpty() && m.description().length() > 20 && m.seconds() >= 60, "Prova mal definida: " + m.id());
            // Toda a geometria cabe na regiao da prova e abaixo de y 128 (mundo McRegion).
            m.ox = 0; m.oz = 0;
            Blocks.Queue q = new Blocks.Queue();
            m.buildStatic(q);
            check(q.size() < 60_000, "Arena grande demais: " + m.id() + " " + q.size());
        }
        check(ids.size() == 10, "Esperava 10 provas.");
        int kinds = 0;
        for (Mini.Kind k : Mini.Kind.values()) { for (java.util.function.Supplier<Mini> s : Minis.ALL) { if (s.get().kind() == k) { kinds++; break; } } }
        check(kinds == 3, "Faltam tipos de prova (tempo, sobrevivencia, pontos).");
        Blocks.Queue q = new Blocks.Queue();
        for (int i = 0; i < 5000; i++) { q.add(i, 1, 2, 3, 4); }
        check(q.size() == 5000 && !q.empty(), "Fila de blocos errada.");
        PartyState s = new PartyState();
        s.of("Ana").wins = 2; s.of("bia").wins = 5; s.of("caio").wins = 2; s.of("caio").points = 50;
        check(s.top(2).get(0).getKey().equals("bia") && s.top(3).get(1).getKey().equals("caio"), "Top errado.");
        s.built.add("0:sumo:1");
        PartyState back = new Gson().fromJson(new Gson().toJson(s), PartyState.class);
        PartyState.validate(back);
        check(back.of("ana").wins == 2 && back.built.contains("0:sumo:1"), "Serializacao perdeu dados.");
        check(Room.POINTS[0] > Room.POINTS[1] && Room.POINTS.length == 8, "Tabela de pontos errada.");
        System.out.println("GoldParty: 10 provas nos 3 tipos, arenas dentro da regiao, fila de blocos, top e validacao aprovados.");
    }
    private static void check(boolean c, String m) { if (!c) { throw new AssertionError(m); } }
}
