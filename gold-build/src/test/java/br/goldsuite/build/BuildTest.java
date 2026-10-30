package br.goldsuite.build;

import java.util.Iterator;

public final class BuildTest {
    public static void main(String[] args) {
        int[] a = {0, 64, 0}, b = {9, 66, 4};
        check(GoldBuild.volume(a, b) == 10 * 3 * 5, "Volume errado.");
        check(count(GoldBuild.cuboid(a, b, false)) == 150, "Cubo nao percorreu tudo.");
        check(count(GoldBuild.cuboid(a, b, true)) == (10 * 5 - 8 * 3) * 3, "Paredes erradas.");
        check(count(GoldBuild.cuboid(new int[] {0, 120, 0}, new int[] {0, 200, 0}, false)) == 8, "Altura nao limitada a 127.");
        check(count(GoldBuild.sphere(0, 64, 0, 2)) == 33, "Esfera errada.");
        check(GoldBuild.block("35:14")[1] == 14 && GoldBuild.block("0")[0] == 0, "Bloco errado.");
        for (String bad : new String[] {"-1", "256", "1:16", "x", "1:2:3"}) { try { GoldBuild.block(bad); throw new AssertionError("Aceitou " + bad); } catch (IllegalArgumentException expected) { } }
        System.out.println("GoldBuild: cubo, paredes, esfera, limites e blocos aprovados.");
    }
    private static int count(Iterator<int[]> it) { int n = 0; while (it.hasNext()) { it.next(); n++; } return n; }
    private static void check(boolean c, String m) { if (!c) { throw new AssertionError(m); } }
}
