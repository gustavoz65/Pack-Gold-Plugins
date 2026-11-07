package br.goldsuite.rankup;

import java.util.*;

/**
 * Configuracao do Rankup (documento unico, pequeno): a escada de ranks e as minas.
 * O progresso de cada jogador fica a parte, num RecordStore por jogador (so os online na memoria).
 */
public final class RankState {
    public static final int MAX_RANKS = 60, MAX_MINES = 40, MAX_PARTS = 12;
    public List<Rank> ranks = new ArrayList<>();
    public Map<String, Mine> mines = new LinkedHashMap<>();

    public static final class Rank {
        public String id = "", name = "", tag = "";
        /** Centavos de money para chegar neste rank (o primeiro e gratis). */
        public long price;
        /** Mina liberada a partir deste rank (vazio = nenhuma nova). */
        public String mine = "";
        /** Item do icone no menu. */
        public int iconId = 1, iconMeta;
        /** Permissoes que este rank da (somadas as dos ranks anteriores). */
        public List<String> permissions = new ArrayList<>();
    }

    public static final class Mine {
        public String name = "";
        /** Mundo e caixa da mina (cantos inclusivos). */
        public String world = "";
        public int x1, y1, z1, x2, y2, z2;
        /** Ponto de chegada (borda da mina). */
        public double tx, ty, tz;
        public float tyaw;
        /** Rank minimo (id) para entrar e minerar. */
        public String rank = "";
        /** Regenera a cada N minutos ou quando N% foi minerado (o que vier primeiro). */
        public int minutes = 10, percent = 70;
        public int iconId = 1, iconMeta;
        public List<Part> blocks = new ArrayList<>();

        public boolean placed() { return !world.isEmpty(); }
        public int volume() { return (x2 - x1 + 1) * (y2 - y1 + 1) * (z2 - z1 + 1); }
        public boolean contains(String w, int x, int y, int z) { return world.equals(w) && x >= x1 && x <= x2 && y >= y1 && y <= y2 && z >= z1 && z <= z2; }
    }

    public static final class Part {
        public int id, meta, weight;
        public Part() { }
        public Part(int id, int meta, int weight) { this.id = id; this.meta = meta; this.weight = weight; }
    }

    public int index(String id) { for (int i = 0; i < ranks.size(); i++) { if (ranks.get(i).id.equals(id)) { return i; } } return -1; }

    public static String id(String text) {
        String v = text == null ? "" : text.toLowerCase(Locale.ROOT);
        if (!v.matches("[a-z0-9_]{1,24}")) { throw new IllegalArgumentException(M.t("nome_invalido")); }
        return v;
    }

    /** Permissao segura: nunca curinga, OP, staff ou bypass. */
    public static boolean safePermission(String p) {
        if (p == null || !p.matches("[a-z0-9_.]{3,80}")) { return false; }
        return !p.equals("*") && !p.endsWith(".*") && !p.contains("admin") && !p.contains("bypass") && !p.startsWith("nukkit.") && !p.contains(".staff") && !p.startsWith("goldperms") && !p.startsWith("goldcore");
    }

    /** Composicao "1=80,16=20" ou "21:0=30,..." (id[:meta]=peso). */
    public static List<Part> parts(String text) {
        List<Part> out = new ArrayList<>();
        for (String piece : text.split(",")) {
            String[] kv = piece.trim().split("=");
            if (kv.length != 2) { throw new IllegalArgumentException(M.t("composicao_invalida")); }
            String[] im = kv[0].split(":");
            try {
                Part p = new Part(Integer.parseInt(im[0]), im.length > 1 ? Integer.parseInt(im[1]) : 0, Integer.parseInt(kv[1]));
                if (p.id < 1 || p.id > 255 || p.meta < 0 || p.meta > 15 || p.weight < 1 || p.weight > 1000) { throw new NumberFormatException(); }
                out.add(p);
            } catch (NumberFormatException e) { throw new IllegalArgumentException(M.t("composicao_invalida")); }
        }
        if (out.isEmpty() || out.size() > MAX_PARTS) { throw new IllegalArgumentException(M.t("composicao_invalida")); }
        return out;
    }

    public static void validate(RankState s) {
        if (s == null || s.ranks == null || s.mines == null || s.ranks.isEmpty() || s.ranks.size() > MAX_RANKS || s.mines.size() > MAX_MINES) { throw new IllegalArgumentException(M.t("configuracao_invalida")); }
        Set<String> ids = new HashSet<>();
        for (Rank r : s.ranks) {
            if (r == null || r.permissions == null || r.name == null || r.tag == null || r.mine == null || !ids.add(id(r.id)) || r.price < 0 || r.price > 1_000_000_000_00L || r.name.length() > 40 || r.tag.length() > 40) { throw new IllegalArgumentException(M.t("configuracao_invalida")); }
            for (String p : r.permissions) { if (!safePermission(p)) { throw new IllegalArgumentException(M.t("permissao_proibida", p)); } }
        }
        for (Map.Entry<String, Mine> e : s.mines.entrySet()) {
            id(e.getKey());
            Mine m = e.getValue();
            if (m == null || m.blocks == null || m.world == null || m.rank == null || m.name == null || m.blocks.isEmpty() || m.blocks.size() > MAX_PARTS || m.minutes < 1 || m.minutes > 1440 || m.percent < 10 || m.percent > 100) { throw new IllegalArgumentException(M.t("mina_invalida", e.getKey())); }
            if (m.placed() && (m.x2 < m.x1 || m.y2 < m.y1 || m.z2 < m.z1 || m.y1 < 0 || m.y2 > 255 || m.volume() > 200_000)) { throw new IllegalArgumentException(M.t("mina_invalida", e.getKey())); }
        }
    }

    // ---------- valores iniciais (economia calculada para 10 min / 5 h / 20 h) ----------

    static final String[][] GROUPS = {
            {"pedra", "Pedra", "&7", "1"}, {"carvao", "Carvao", "&8", "263"}, {"ferro", "Ferro", "&f", "265"}, {"ouro", "Ouro", "&6", "266"},
            {"lapis", "Lapis", "&9", "351:4"}, {"redstone", "Redstone", "&c", "331"}, {"diamante", "Diamante", "&b", "264"},
            {"esmeralda", "Esmeralda", "&a", "388"}, {"obsidiana", "Obsidiana", "&5", "49"}, {"lendario", "Lendario", "&e&l", "57"}};
    /** Preco em reais para chegar em cada rank (indice 0 = inicial). */
    static final long[] PRICES = {0, 450, 700, 2500, 3150, 6550, 7750, 13250, 15300, 22900, 24800, 34450, 36950, 61600, 65450, 107150, 113100, 190950, 205500, 491550};
    /** Composicao de cada mina (id[:meta]=peso). */
    static final String[] COMPOSITION = {
            "1=85,16=15", "16=75,1=25", "15=70,16=15,1=15", "14=70,15=15,1=15", "21=70,14=15,1=15",
            "73=70,21=15,1=15", "56=70,73=15,1=15", "129=70,56=15,1=15", "49=70,129=15,56=15", "42=25,41=25,57=20,129=30"};
    static final int[] ICONS = {1, 16, 15, 14, 21, 73, 56, 129, 49, 57};

    public static RankState defaults() {
        RankState s = new RankState();
        int i = 0;
        for (int g = 0; g < GROUPS.length; g++) {
            String[] gr = GROUPS[g];
            for (int level = 1; level <= 2; level++, i++) {
                Rank r = new Rank();
                r.id = gr[0] + "_" + level;
                r.name = gr[1] + (level == 1 ? " I" : " II");
                r.tag = gr[2] + "[" + r.name + "]";
                r.price = PRICES[i] * 100;
                String[] icon = gr[3].split(":");
                r.iconId = Integer.parseInt(icon[0]); r.iconMeta = icon.length > 1 ? Integer.parseInt(icon[1]) : 0;
                if (level == 1) {
                    r.mine = gr[0];
                    r.permissions.add("goldrankup.mina." + gr[0]);
                    // Plots: 1 no inicio, 2 a partir de Ferro, 3 de Redstone, 4 de Obsidiana.
                    if (g == 0) { r.permissions.add("goldplots.limite.1"); }
                    if (g == 2) { r.permissions.add("goldplots.limite.2"); }
                    if (g == 5) { r.permissions.add("goldplots.limite.3"); }
                    if (g == 8) { r.permissions.add("goldplots.limite.4"); }
                    // Kits de ferramenta por grupo: pedra, ferro, diamante e lendario.
                    if (g == 0) { r.permissions.add("goldkits.kit.ferramentas_pedra"); }
                    if (g == 2) { r.permissions.add("goldkits.kit.ferramentas_ferro"); }
                    if (g == 6) { r.permissions.add("goldkits.kit.ferramentas_diamante"); }
                    if (g == 9) { r.permissions.add("goldkits.kit.ferramentas_lendario"); }
                }
                s.ranks.add(r);
            }
            Mine m = new Mine();
            m.name = gr[1];
            m.rank = gr[0] + "_1";
            m.iconId = ICONS[g];
            m.blocks = parts(COMPOSITION[g]);
            s.mines.put(gr[0], m);
        }
        return s;
    }
}
