package br.goldsuite.skills;

public final class SkillsTest {
    public static void main(String[] args) throws Exception {
        check(Xp.needed(0) == 1020 && Xp.needed(100) == 3020, "Curva de XP diferente do mcMMO do pack.");
        check(Xp.chance(500, 100, 1000) == 50 && Xp.chance(2000, 75, 750) == 75 && Xp.chance(0, 20, 800) == 0, "Chance por nivel errada.");
        check(Xp.skillOf(56, 0, true, false, false) == Skill.MINERACAO && Xp.skillOf(56, 0, false, false, false) == null, "Minerio so com picareta.");
        check(Xp.skillOf(17, 2, false, true, false) == Skill.LENHADOR && Xp.blockXp(Skill.LENHADOR, 17, 2 | 4) == 90, "Tronco de betula (com rotacao) deve dar 90.");
        check(Xp.skillOf(2, 0, false, false, true) == Skill.ESCAVACAO && Xp.blockXp(Skill.ESCAVACAO, 2, 0) * Skill.ESCAVACAO.modifier == 4, "Grama com pa: 2 x 2.");
        check(Xp.skillOf(59, 7, false, false, false) == Skill.HERBALISMO && Xp.skillOf(59, 3, false, false, false) == null, "Trigo verde nao conta.");
        check(Xp.blockXp(Skill.HERBALISMO, 38, 2) == 300 && Xp.blockXp(Skill.HERBALISMO, 38, 0) == 100, "Flores com valores do pack.");
        check(Xp.blockXp(Skill.MINERACAO, 56, 0) * Skill.MINERACAO.modifier == 10, "Diamante: 5 x 2.");
        check(Xp.tracked(1) && Xp.tracked(17) && Xp.tracked(59) && !Xp.tracked(54), "Blocos rastreados errados.");
        check(Xp.combat(33) == 8 && Xp.combat(63) == 1 && Xp.combat(37) == 0, "Multiplicador de combate errado.");
        check(Xp.repairMaterial(257)[0] == 265 && Xp.recipeUnits(257) == 3 && Xp.recipeUnits(311) == 8 && Xp.repairMaterial(1) == null, "Reparo errado.");
        check(Skill.parse("mining") == Skill.MINERACAO && Skill.parse("Mineração") == Skill.MINERACAO && Skill.parse("lenh") == Skill.LENHADOR, "Nomes de habilidade.");
        // Perfil: ida e volta, boosters vencidos somem.
        GoldSkills.State s = new GoldSkills.State();
        s.level[0] = 12; s.xp[0] = 345; s.level[9] = 1000; s.abilities = false;
        s.boosters.put(GoldSkills.N, new long[] {20, System.currentTimeMillis() + 60_000});
        s.boosters.put(1, new long[] {20, 1});
        String enc = GoldSkills.encode(s);
        GoldSkills.State back = new GoldSkills.State();
        GoldSkills.decode(enc, back);
        check(back.level[0] == 12 && back.xp[0] == 345 && back.level[9] == 1000 && !back.abilities && back.boosters.containsKey(GoldSkills.N) && !back.boosters.containsKey(1), "Perfil perdeu dados: " + enc);
        check(GoldSkills.levelsOf(enc)[9] == 1000 && GoldSkills.levelsOf("lixo") == null, "Niveis do ranking.");
        // Blocos colocados: tabela sem objetos, cresce, remove e grava.
        PlacedBlocks pb = new PlacedBlocks();
        for (int i = 0; i < 20_000; i++) { pb.add(pb.key("world", i, 64, -i)); }
        check(pb.size() == 20_000 && pb.contains(pb.key("world", 77, 64, -77)) && !pb.contains(pb.key("world", 77, 65, -77)) && !pb.contains(pb.key("lobby", 77, 64, -77)), "Tabela de blocos colocados errada.");
        check(pb.remove(pb.key("world", 77, 64, -77)) && !pb.contains(pb.key("world", 77, 64, -77)) && pb.size() == 19_999, "Remocao errada.");
        java.io.File f = java.io.File.createTempFile("colocados", ".bin");
        pb.save(f);
        PlacedBlocks loaded = new PlacedBlocks();
        loaded.load(f);
        check(loaded.size() == 19_999 && loaded.contains(loaded.key("world", 5, 64, -5)) && !loaded.contains(loaded.key("world", 77, 64, -77)), "Arquivo de blocos colocados.");
        f.delete();
        System.out.println("GoldSkills: curva e chances do pack, XP por bloco e combate, reparo, perfil, boosters e blocos colocados aprovados.");
    }
    private static void check(boolean c, String m) { if (!c) { throw new AssertionError(m); } }
}
