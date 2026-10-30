package br.goldsuite.crates;

import br.goldsuite.api.ItemData;
import java.util.*;
import java.util.function.IntUnaryOperator;

/** Caixas: premios com peso e posicoes dos baus que funcionam como caixa. */
public final class CrateState {
    public Map<String, Crate> crates = new TreeMap<>();
    /** "mundo:x:y:z" -> nome da caixa. */
    public Map<String, String> blocks = new TreeMap<>();

    public static final class Crate {
        public List<Reward> rewards = new ArrayList<>();
    }

    public static final class Reward {
        public ItemData item;
        public int weight;
        public Reward() { }
        public Reward(ItemData item, int weight) { this.item = item; this.weight = weight; }
    }

    public static String name(String text) {
        String n = text == null ? "" : text.toLowerCase(Locale.ROOT);
        if (!n.matches("[a-z0-9_-]{1,20}")) { throw new IllegalArgumentException("Nome: ate 20 letras, numeros, _ ou -."); }
        return n;
    }

    /** Sorteio por peso; random(total) devolve um inteiro em [0, total). */
    public static Reward roll(List<Reward> rewards, IntUnaryOperator random) {
        int total = 0;
        for (Reward r : rewards) { total += r.weight; }
        if (total <= 0) { throw new IllegalArgumentException("Caixa sem premios."); }
        int pick = random.applyAsInt(total);
        for (Reward r : rewards) { pick -= r.weight; if (pick < 0) { return r; } }
        return rewards.get(rewards.size() - 1);
    }

    public static double chance(List<Reward> rewards, Reward r) {
        int total = 0;
        for (Reward x : rewards) { total += x.weight; }
        return total == 0 ? 0 : 100.0 * r.weight / total;
    }

    public static void validate(CrateState s) {
        if (s == null || s.crates == null || s.blocks == null || s.crates.size() > 50 || s.blocks.size() > 200) { throw new IllegalArgumentException("Caixas invalidas."); }
        for (Map.Entry<String, Crate> e : s.crates.entrySet()) {
            name(e.getKey());
            if (e.getValue() == null || e.getValue().rewards == null || e.getValue().rewards.size() > 27) { throw new IllegalArgumentException("Caixa invalida."); }
            for (Reward r : e.getValue().rewards) { if (r == null || r.item == null || r.weight < 1 || r.weight > 10000) { throw new IllegalArgumentException("Premio invalido."); } r.item.validate(); }
        }
        for (Map.Entry<String, String> e : s.blocks.entrySet()) { if (!s.crates.containsKey(e.getValue()) || !e.getKey().matches("[A-Za-z0-9_][A-Za-z0-9_.-]{0,31}:-?\\d+:\\d+:-?\\d+")) { throw new IllegalArgumentException("Bloco de caixa invalido."); } }
    }
}
