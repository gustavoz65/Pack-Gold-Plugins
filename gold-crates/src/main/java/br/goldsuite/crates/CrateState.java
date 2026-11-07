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
        /** Nome mostrado na caixa misteriosa (item); vazio = usa o nome da caixa. */
        public String display = "";
        /** Preco no /caixas: centavos de dinheiro ou unidades de cash (cash = true). 0 = nao esta a venda. */
        public long price;
        public boolean cash;
    }

    public static final class Reward {
        public ItemData item;
        public int weight;
        /** Comando executado pelo console ao ganhar ({jogador} vira o nick); vazio = premio e so o item. */
        public String command = "";
        /** Premio so de comando (o item e apenas o icone na lista). */
        public boolean commandOnly;
        public Reward() { }
        public Reward(ItemData item, int weight) { this.item = item; this.weight = weight; }
    }

    /** Raridade pela chance (como o yCaixasMisteriosas): null quando e comum. */
    public static String rarity(double chance, double rare, double veryRare) {
        return chance <= veryRare ? M.t("muito_raro") : chance <= rare ? M.t("raro") : null;
    }

    public static String command(String text) {
        String c = text == null ? "" : text.trim().replaceFirst("^/", "");
        if (c.isEmpty() || c.length() > 120 || c.indexOf('\n') >= 0) { throw new IllegalArgumentException(M.t("comando_de_1_120_caracteres")); }
        return c;
    }

    public static String name(String text) {
        String n = text == null ? "" : text.toLowerCase(Locale.ROOT);
        if (!n.matches("[a-z0-9_-]{1,20}")) { throw new IllegalArgumentException(M.t("nome_ate_20_letras_numeros_ou")); }
        return n;
    }

    /** Sorteio por peso; random(total) devolve um inteiro em [0, total). */
    public static Reward roll(List<Reward> rewards, IntUnaryOperator random) {
        int total = 0;
        for (Reward r : rewards) { total += r.weight; }
        if (total <= 0) { throw new IllegalArgumentException(M.t("caixa_sem_premios")); }
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
        if (s == null || s.crates == null || s.blocks == null || s.crates.size() > 50 || s.blocks.size() > 200) { throw new IllegalArgumentException(M.t("caixas_invalidas")); }
        for (Map.Entry<String, Crate> e : s.crates.entrySet()) {
            name(e.getKey());
            if (e.getValue() == null || e.getValue().rewards == null || e.getValue().rewards.size() > 27) { throw new IllegalArgumentException(M.t("caixa_invalida")); }
            if (e.getValue().display == null || e.getValue().display.length() > 40) { throw new IllegalArgumentException(M.t("nome_de_caixa_invalido")); }
            if (e.getValue().price < 0 || e.getValue().price > 1_000_000_000_00L) { throw new IllegalArgumentException(M.t("caixa_invalida")); }
            for (Reward r : e.getValue().rewards) {
                if (r == null || r.item == null || r.weight < 1 || r.weight > 10000 || r.command == null || r.command.length() > 120 || (r.commandOnly && r.command.isEmpty())) { throw new IllegalArgumentException(M.t("premio_invalido")); }
                r.item.validate();
            }
        }
        for (Map.Entry<String, String> e : s.blocks.entrySet()) { if (!s.crates.containsKey(e.getValue()) || !e.getKey().matches("[A-Za-z0-9_][A-Za-z0-9_.-]{0,31}:-?\\d+:\\d+:-?\\d+")) { throw new IllegalArgumentException(M.t("bloco_de_caixa_invalido")); } }
    }
}
