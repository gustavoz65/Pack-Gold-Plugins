package br.goldsuite.rankup;

import java.util.List;

/** Regressao sem servidor: escada padrao, economia, composicao e permissoes seguras. */
public final class RankupTest {
    public static void main(String[] args) {
        RankState s = RankState.defaults();
        RankState.validate(s);
        check(s.ranks.size() == 20, "20 ranks");
        check(s.ranks.get(0).price == 0, "primeiro rank gratis");
        for (int i = 1; i < s.ranks.size(); i++) { check(s.ranks.get(i).price > s.ranks.get(i - 1).price, "preco crescente em " + s.ranks.get(i).id); }
        check(s.mines.size() == 10, "10 minas");
        for (RankState.Mine m : s.mines.values()) { check(s.index(m.rank) >= 0, "rank da mina existe"); check(!m.placed(), "mina comeca sem lugar"); }
        check(s.ranks.get(0).permissions.contains("goldplots.limite.1") && s.ranks.get(0).permissions.contains("goldrankup.mina.pedra"), "rank inicial da plot e mina");
        List<RankState.Part> parts = RankState.parts("1=80, 16:0=20");
        check(parts.size() == 2 && parts.get(1).id == 16 && parts.get(1).weight == 20, "composicao lida");
        for (String bad : new String[] {"", "1", "1=0", "300=5", "1=5,2=5,3=5,4=5,5=5,6=5,7=5,8=5,9=5,10=5,11=5,12=5,13=5"}) {
            try { RankState.parts(bad); check(false, "composicao invalida aceita: " + bad); } catch (IllegalArgumentException expected) { }
        }
        for (String perm : new String[] {"*", "goldcomandos.bypass", "nukkit.command.op", "goldperms.cargo", "goldstaff.admin", "a.*", "goldchat.staff"}) {
            check(!RankState.safePermission(perm), "permissao perigosa recusada: " + perm);
        }
        check(RankState.safePermission("goldplots.limite.3") && RankState.safePermission("goldkits.kit.ferramentas_ferro"), "permissoes normais aceitas");
        s.ranks.get(3).permissions.add("goldcomandos.bypass");
        try { RankState.validate(s); check(false, "rank com bypass aceito"); } catch (IllegalArgumentException expected) { }
        RankState.Mine m = new RankState.Mine();
        m.world = "minas"; m.x1 = 0; m.x2 = 14; m.y1 = 44; m.y2 = 63; m.z1 = 0; m.z2 = 14;
        check(m.volume() == 15 * 20 * 15 && m.contains("minas", 7, 50, 7) && !m.contains("minas", 7, 64, 7) && !m.contains("outro", 7, 50, 7), "caixa da mina");
        System.out.println("GoldRankup: 20 ranks, precos crescentes, 10 minas, composicao e permissoes seguras aprovados.");
    }

    private static void check(boolean ok, String msg) { if (!ok) { throw new AssertionError(msg); } }
}
