package gold.lobby;

import cn.nukkit.block.Block;
import cn.nukkit.level.Level;
import cn.nukkit.level.Position;
import cn.nukkit.level.generator.Flat;
import cn.nukkit.math.Vector3;
import cn.nukkit.plugin.PluginBase;
import java.util.*;

/**
 * Gera o lobby principal: vilarejo medieval numa ilha flutuante, com praca e fonte no centro e uma construcao por modo
 * (Ranked ao norte, PvP a leste, HG a oeste, Factions ao sul). Roda uma vez num servidor de trabalho, salva o mundo
 * "lobby" e escreve as previas (vista de cima e isometrica) e a lista de pontos para NPCs.
 */
public final class LobbyBuilder extends PluginBase {
    static final int G = 64;          // superficie da grama
    static final int R = 64;          // raio da ilha
    Level w;
    final Random rnd = new Random(20261031L);
    final List<String> spots = new ArrayList<>();
    final Set<Long> reserved = new HashSet<>();

    // Blocos
    static final int[] GRASS = {2, 0}, DIRT = {3, 0}, STONE = {1, 0}, ANDESITE = {1, 5}, P_ANDESITE = {1, 6}, COBBLE = {4, 0}, MOSSY_COBBLE = {48, 0},
            SBRICK = {98, 0}, SBRICK_MOSSY = {98, 1}, SBRICK_CRACK = {98, 2}, SBRICK_CHISEL = {98, 3}, PATH = {198, 0}, COARSE = {3, 1},
            SPRUCE_PLANK = {5, 1}, DARK_PLANK = {5, 5}, SPRUCE_LOG = {17, 1}, OAK_LOG = {17, 0}, DARK_LOG = {162, 1}, WHITE_CLAY = {159, 0},
            ORANGE_CLAY = {159, 1}, BRICK = {45, 0}, GLOW = {89, 0}, PANE = {102, 0}, WALL = {139, 0}, FENCE_DARK = {85, 5}, FENCE_SPRUCE = {85, 1},
            OAK_LEAF = {18, 4}, SPRUCE_LEAF = {18, 5}, DARK_LEAF = {161, 5}, WATER = {9, 0}, QUARTZ = {155, 0}, CHISEL_QUARTZ = {155, 1}, AIR = {0, 0},
            DARK_SLAB = {158, 5}, SPRUCE_SLAB = {158, 1}, SBRICK_SLAB = {44, 5}, BOOKS = {47, 0}, HAY = {170, 0}, GRAVEL = {13, 0}, PODZOL = {243, 0};
    static final int BRICK_STAIRS = 108, SPRUCE_STAIRS = 134, DARK_STAIRS = 164, SBRICK_STAIRS = 109;

    @Override public void onEnable() {
        getServer().getScheduler().scheduleDelayedTask(() -> {
            try { build(); } catch (Throwable t) { getLogger().error("Falha no lobby", t); }
            getLogger().info("LOBBY_PRONTO");
        }, 40);
    }

    void build() throws Exception {
        Map<String, Object> options = new HashMap<>();
        options.put("preset", "2;0;1;");
        if (getServer().getLevelByName("lobby") == null) {
            if (!getServer().isLevelGenerated("lobby")) { getServer().generateLevel("lobby", 1L, Flat.class, options); }
            getServer().loadLevel("lobby");
        }
        w = getServer().getLevelByName("lobby");
        for (int cx = -6; cx <= 5; cx++) { for (int cz = -6; cz <= 5; cz++) { w.loadChunk(cx, cz, true); } }
        long t = System.currentTimeMillis();
        island();
        plaza();
        paths();
        manor(0, -40);
        house(42, 0, 1, 0, "PvP", new int[] {35, 14});
        house(-42, 0, -1, 0, "HG", new int[] {35, 5});
        keep(0, 40);
        cottage(30, -27, 0, -1, new int[] {159, 1});
        cottage(-30, -27, 0, -1, new int[] {159, 14});
        cottage(30, 29, 0, 1, new int[] {159, 1});
        pond(-29, 28);
        stalls();
        lamps();
        garden();
        trees();
        w.setSpawnLocation(new Vector3(0.5, G + 1, 8.5));
        // Ceu de por do sol parado, como na arte.
        w.setTime(12300);
        w.stopTime();
        w.save(true);
        getLogger().info("Lobby construido em " + (System.currentTimeMillis() - t) / 1000 + " s.");
        Render.top(w, getDataFolder(), R + 2, G);
        Render.iso(w, getDataFolder(), R + 2, G - 30, G + 30);
        spots.add(0, "spawn: 0.5 " + (G + 1) + " 8.5 (olhando para o norte)");
        getDataFolder().mkdirs();
        java.nio.file.Files.write(new java.io.File(getDataFolder(), "pontos.txt").toPath(), spots);
    }

    // ---------- utilitarios ----------

    void set(int x, int y, int z, int[] b) { w.setBlock(new Vector3(x, y, z), Block.get(b[0], b[1]), true, false); }
    void set(int x, int y, int z, int id, int meta) { w.setBlock(new Vector3(x, y, z), Block.get(id, meta), true, false); }
    int id(int x, int y, int z) { return w.getBlockIdAt(x, y, z); }
    static long k(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }
    void reserve(int x0, int z0, int x1, int z1, int pad) {
        for (int x = Math.min(x0, x1) - pad; x <= Math.max(x0, x1) + pad; x++) { for (int z = Math.min(z0, z1) - pad; z <= Math.max(z0, z1) + pad; z++) { reserved.add(k(x, z)); } }
    }
    /** Escada voltada (lado alto) para a direcao do mundo (dx,dz). */
    static int stairMeta(int dx, int dz) { return dx > 0 ? 0 : dx < 0 ? 1 : dz > 0 ? 2 : 3; }
    static double noise(double x, double z) { return Math.sin(x * 0.17) * 1.3 + Math.cos(z * 0.21) * 1.1 + Math.sin((x + z) * 0.09) * 1.6 + Math.cos((x - z) * 0.13) * 0.9; }
    double edge(double ang) { return R - 6 + Math.sin(ang * 3) * 3 + Math.cos(ang * 5 + 1) * 2 + Math.sin(ang * 7 + 2) * 1.2; }

    // ---------- ilha ----------

    void island() {
        for (int x = -R - 4; x <= R + 4; x++) {
            for (int z = -R - 4; z <= R + 4; z++) {
                double d = Math.sqrt(x * x + z * z), e = edge(Math.atan2(z, x));
                if (d > e) { continue; }
                double inner = e - d;
                // Relevo suave so longe da praca e dos predios.
                int lift = d > 50 ? (int) Math.max(0, Math.round((noise(x, z) + 2) * Math.min(1, (d - 50) / 6.0) * 0.7)) : 0;
                int top = G + lift;
                int depth = (int) (5 + inner * 0.55 + Math.abs(noise(x * 1.7, z * 1.3)) * 2.5);
                for (int y = top - depth; y <= top; y++) {
                    int[] b = y == top ? (d > e - 1.5 && lift == 0 ? COARSE : GRASS) : y > top - 4 ? DIRT
                            : (y + x + z) % 7 == 0 ? ANDESITE : (y * 3 + x - z) % 11 == 0 ? COBBLE : STONE;
                    set(x, y, z, b);
                }
            }
        }
    }

    // ---------- praca e fonte ----------

    void plaza() {
        int pr = 15;
        for (int x = -pr; x <= pr; x++) {
            for (int z = -pr; z <= pr; z++) {
                double d = Math.sqrt(x * x + z * z);
                if (d > pr + 0.3) { continue; }
                int[] b;
                if (d > pr - 0.9) { b = SBRICK; }
                else if (d > pr - 2) { b = P_ANDESITE; }
                else if (Math.abs(d - 9) < 0.6) { b = SBRICK_CHISEL; }
                else { int h = Math.floorMod(x * 7 + z * 13 + (x * z), 9); b = h == 0 ? SBRICK_MOSSY : h == 1 ? SBRICK_CRACK : h < 4 ? ANDESITE : SBRICK; }
                set(x, G, z, b);
            }
        }
        reserve(-pr, -pr, pr, pr, 1);
        // Fonte: bacia redonda com agua, pilar de quartzo e luz no topo.
        for (int x = -5; x <= 5; x++) {
            for (int z = -5; z <= 5; z++) {
                double d = Math.sqrt(x * x + z * z);
                if (d <= 4.4) { set(x, G - 1, z, SBRICK); set(x, G, z, WATER); }
                else if (d <= 5.4) { set(x, G, z, SBRICK); set(x, G + 1, z, SBRICK_SLAB); }
            }
        }
        for (int y = G; y <= G + 3; y++) { set(0, y, 0, y == G + 3 ? CHISEL_QUARTZ : QUARTZ); }
        int[][] arms = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] a : arms) { set(a[0], G + 3, a[1], SBRICK_SLAB); set(a[0], G, a[1], QUARTZ); }
        set(0, G + 4, 0, GLOW);
        set(0, G + 5, 0, DARK_SLAB);
        // Bancos (escadas de pinheiro viradas para a fonte) no anel.
        for (int i = 0; i < 8; i++) {
            double a = Math.PI / 4 * i + Math.PI / 8;
            int bx = (int) Math.round(Math.cos(a) * 11), bz = (int) Math.round(Math.sin(a) * 11);
            int dx = Integer.signum(-bx), dz = Integer.signum(-bz);
            // Encosto para fora, assento virado para a fonte.
            set(bx, G + 1, bz, SPRUCE_STAIRS, stairMeta(-dx, 0 == dx ? -dz : 0));
        }
    }

    // ---------- caminhos ----------

    void paths() {
        int[][] dirs = {{0, -1, 34}, {1, 0, 36}, {-1, 0, 36}, {0, 1, 32}};
        for (int[] d : dirs) {
            for (int s = 15; s <= d[2]; s++) {
                for (int o = -3; o <= 3; o++) {
                    int x = d[0] * s + (d[0] == 0 ? o : 0), z = d[1] * s + (d[1] == 0 ? o : 0);
                    int[] b = Math.abs(o) == 3 ? (Math.floorMod(s, 3) == 0 ? MOSSY_COBBLE : COBBLE) : Math.floorMod(s * 5 + o * 3, 7) == 0 ? COARSE : PATH;
                    set(x, G, z, b);
                }
            }
            reserve(d[0] * 15 - 4, d[1] * 15 - 4, d[0] * d[2] + 4, d[1] * d[2] + 4, 0);
        }
    }

    // ---------- predios ----------

    /** Coordenadas locais do predio (u = lado, v = fundo a partir da frente) para o mundo. */
    final class Frame {
        final int fx, fz, bx, bz, rx, rz;
        Frame(int fx, int fz, int bx, int bz) { this.fx = fx; this.fz = fz; this.bx = bx; this.bz = bz; this.rx = -bz; this.rz = bx; }
        int x(int u, int v) { return fx + u * rx + v * bx; }
        int z(int u, int v) { return fz + u * rz + v * bz; }
        void set(int u, int y, int v, int[] b) { LobbyBuilder.this.set(x(u, v), y, z(u, v), b); }
        void set(int u, int y, int v, int id, int meta) { LobbyBuilder.this.set(x(u, v), y, z(u, v), id, meta); }
        /** Meta de escada subindo na direcao local (du, dv). */
        int stair(int du, int dv) { return stairMeta(du * rx + dv * bx, du * rz + dv * bz); }
        /** Tronco deitado ao longo de u ou de v. */
        int[] logAlong(int[] log, boolean alongU) { int wx = alongU ? rx : bx; return new int[] {log[0], log[1] | (wx != 0 ? 4 : 8)}; }
    }

    /**
     * Corpo de casa enxaimel: base de pedra, pilares e vigas de pinheiro, paredes claras, janelas iluminadas
     * e telhado de duas aguas em escadas de tijolo (cumeeira ao longo de v).
     */
    void body(Frame f, int u0, int u1, int v0, int v1, int wallH, boolean openFront, int doorW) {
        int y0 = G + 1;
        for (int u = u0; u <= u1; u++) {
            for (int v = v0; v <= v1; v++) {
                f.set(u, G, v, DARK_PLANK);
                boolean edgeU = u == u0 || u == u1, edgeV = v == v0 || v == v1;
                for (int y = y0; y <= y0 + wallH; y++) {
                    if (!edgeU && !edgeV) { f.set(u, y, v, y == y0 + wallH ? SPRUCE_PLANK : AIR); continue; }
                    boolean corner = edgeU && edgeV;
                    int along = edgeV ? u - u0 : v - v0;
                    boolean post = corner || along % 4 == 0;
                    int[] b;
                    if (y == y0) { b = corner ? SBRICK_CHISEL : SBRICK; }
                    else if (y == y0 + wallH) { b = f.logAlong(SPRUCE_LOG, edgeV); }
                    else if (post) { b = SPRUCE_LOG; }
                    else if (y == y0 + 3 && wallH > 4) { b = f.logAlong(SPRUCE_LOG, edgeV); }
                    else { boolean window = (along % 4 == 2) && (y == y0 + 1 || y == y0 + 2 || (wallH > 5 && (y == y0 + 4 || y == y0 + 5))); b = window ? PANE : WHITE_CLAY; }
                    f.set(u, y, v, b);
                }
            }
        }
        // Luz quente dentro: as janelas brilham como na arte.
        for (int u = u0 + 2; u <= u1 - 2; u += 3) { for (int v = v0 + 2; v <= v1 - 2; v += 3) { f.set(u, y0 + wallH, v, GLOW); } }
        if (openFront) {
            int mid = (u0 + u1) / 2;
            for (int u = mid - doorW / 2; u <= mid + doorW / 2; u++) { for (int y = y0; y <= y0 + 2; y++) { f.set(u, y, v0, AIR); } f.set(u, y0 + 3, v0, f.logAlong(DARK_LOG, true)); }
            f.set(mid - doorW / 2 - 1, y0 + 2, v0 - 1, GLOW);
            f.set(mid + doorW / 2 + 1, y0 + 2, v0 - 1, GLOW);
        }
        roof(f, u0, u1, v0, v1, y0 + wallH + 1);
    }

    /** Telhado de duas aguas: escadas de tijolo subindo dos lados u ate a cumeeira, oitoes de pinheiro com janelinha. */
    void roof(Frame f, int u0, int u1, int v0, int v1, int base) {
        int half = (u1 - u0) / 2 + 1;
        for (int i = 0; i <= half; i++) {
            int y = base + i;
            int ul = u0 - 1 + i, ur = u1 + 1 - i;
            for (int v = v0 - 1; v <= v1 + 1; v++) {
                if (ul < ur) {
                    f.set(ul, y, v, BRICK_STAIRS, f.stair(1, 0));
                    f.set(ur, y, v, BRICK_STAIRS, f.stair(-1, 0));
                    // Oitoes nas pontas, recuados um bloco.
                    if (v == v0 || v == v1) { for (int u = ul + 1; u < ur; u++) { f.set(u, y, v, (u + y) % 5 == 0 && i > 0 ? PANE : SPRUCE_PLANK); } }
                } else if (ul == ur) { f.set(ul, y, v, ORANGE_CLAY); }
            }
            if (ul >= ur - 1) {
                for (int v = v0 - 1; v <= v1 + 1; v++) { if (ul == ur - 1) { f.set(ul, y + 1, v, DARK_SLAB); f.set(ur, y + 1, v, DARK_SLAB); } else if (ul == ur) { f.set(ul, y + 1, v, DARK_SLAB); } }
                break;
            }
        }
    }

    /** Cruz na frente: um oitao virado para a praca (como na arte), com cumeeira ao longo de u. */
    void frontGable(Frame f, int uc, int halfW, int v0, int depth, int wallH) {
        Frame g = new Frame(f.x(uc, v0), f.z(uc, v0), f.bx, f.bz);
        body(g, -halfW, halfW, 0, depth, wallH, false, 0);
    }

    /** Torre redonda com telhado conico de pinheiro. */
    void tower(int cx, int cz, int r, int h) {
        for (int y = G; y <= G + h; y++) {
            for (int x = -r; x <= r; x++) {
                for (int z = -r; z <= r; z++) {
                    double d = Math.sqrt(x * x + z * z);
                    if (d > r + 0.4) { continue; }
                    boolean wall = d > r - 0.6;
                    int[] b = !wall ? (y == G ? DARK_PLANK : y % 5 == 0 ? SPRUCE_PLANK : AIR)
                            : y <= G + 1 ? SBRICK : (y % 5 == 3 && (x == 0 || z == 0)) ? PANE : y % 6 == 0 ? SBRICK_MOSSY : SBRICK;
                    set(cx + x, y, cz + z, b);
                }
            }
        }
        for (int x = -1; x <= 1; x++) { for (int z = -1; z <= 1; z++) { if (Math.abs(x) + Math.abs(z) < 2) { set(cx + x, G + h - 1, cz + z, GLOW); } } }
        int base = G + h + 1;
        for (int i = 0; i <= r + 1; i++) {
            double rr = r + 1 - i;
            for (int x = -r - 1; x <= r + 1; x++) {
                for (int z = -r - 1; z <= r + 1; z++) {
                    double d = Math.sqrt(x * x + z * z);
                    if (d > rr + 0.4 || d < rr - 0.8) { continue; }
                    int dx = Math.abs(x) >= Math.abs(z) ? -Integer.signum(x) : 0, dz = dx == 0 ? -Integer.signum(z) : 0;
                    set(cx + x, base + i, cz + z, rr < 0.5 ? ORANGE_CLAY : new int[] {BRICK_STAIRS, stairMeta(dx, dz)});
                }
            }
        }
        set(cx, base + r + 2, cz, SPRUCE_LOG);
        set(cx, base + r + 3, cz, GLOW);
    }

    /** Ranked: casarao principal ao norte, com duas alas em oitao e torre. */
    void manor(int fx, int fz) {
        Frame f = new Frame(fx, fz, 0, -1);
        body(f, -12, 12, 0, 12, 7, false, 0);
        frontGable(f, -7, 4, -4, 5, 6);
        frontGable(f, 7, 4, -4, 5, 6);
        // Entrada central com arco e escadaria.
        for (int u = -2; u <= 2; u++) { for (int y = G + 1; y <= G + 4; y++) { f.set(u, y, 0, AIR); } f.set(u, G + 5, 0, f.logAlong(DARK_LOG, true)); }
        for (int u = -3; u <= 3; u++) { f.set(u, G + 1, -1, SBRICK_STAIRS, f.stair(0, 1)); f.set(u, G, -2, SBRICK); }
        for (int y = G + 1; y <= G + 4; y++) { f.set(-3, y, -1, SBRICK_CHISEL); f.set(3, y, -1, SBRICK_CHISEL); }
        f.set(-3, G + 5, -1, GLOW); f.set(3, G + 5, -1, GLOW);
        // Interior: estantes e corredor ate o NPC.
        for (int v = 3; v <= 10; v += 7) { for (int u = -10; u <= 10; u++) { if (Math.abs(u) > 2) { f.set(u, G + 1, v, BOOKS); f.set(u, G + 2, v, BOOKS); } } }
        f.set(0, G + 1, 9, gold());
        tower(f.x(13, 10), f.z(13, 10), 3, 15);
        tower(f.x(-13, 10), f.z(-13, 10), 3, 13);
        banners(f, 0, -1, 7, new int[] {35, 4});
        reserve(f.x(-17, -6), f.z(-17, -6), f.x(17, 15), f.z(17, 15), 2);
        spots.add("ranked (NPC na porta do casarao): " + f.x(0, -3) + " " + (G + 1) + " " + f.z(0, -3));
    }

    static int[] gold() { return new int[] {41, 0}; }

    /** PvP e HG: casa media com um oitao na frente. (fx,fz) e o centro da fachada; (bx,bz) aponta para o fundo. */
    void house(int fx, int fz, int bx, int bz, String mode, int[] color) {
        Frame f = new Frame(fx, fz, bx, bz);
        body(f, -8, 8, 0, 11, 5, false, 0);
        frontGable(f, 0, 4, -4, 5, 5);
        // Porta no oitao da frente.
        Frame g = new Frame(f.x(0, -4), f.z(0, -4), bx, bz);
        for (int u = -1; u <= 1; u++) { for (int y = G + 1; y <= G + 3; y++) { g.set(u, y, 0, AIR); } }
        for (int u = -1; u <= 1; u++) { for (int y = G + 1; y <= G + 3; y++) { f.set(u, y, 0, AIR); } }
        g.set(-2, G + 3, -1, GLOW); g.set(2, G + 3, -1, GLOW);
        banners(g, 0, 0, 5, color);
        reserve(f.x(-12, -8), f.z(-12, -8), f.x(12, 14), f.z(12, 14), 2);
        spots.add(mode.toLowerCase() + " (NPC na porta): " + g.x(0, -2) + " " + (G + 1) + " " + g.z(0, -2));
    }

    /** Factions: fortaleza de pedra com muralha baixa e duas torres. */
    void keep(int fx, int fz) {
        Frame f = new Frame(fx, fz, 0, 1);
        for (int u = -10; u <= 10; u++) {
            for (int v = 0; v <= 14; v++) {
                boolean edge = u == -10 || u == 10 || v == 0 || v == 14;
                f.set(u, G, v, edge ? SBRICK : (u + v) % 3 == 0 ? SBRICK_MOSSY : P_ANDESITE);
                if (!edge) { continue; }
                for (int y = G + 1; y <= G + 4; y++) { f.set(u, y, v, y == G + 4 ? ((u + v) % 2 == 0 ? SBRICK : AIR) : (y == G + 2 && (u + v) % 6 == 3 ? SBRICK_CRACK : SBRICK)); }
            }
        }
        for (int u = -2; u <= 2; u++) { for (int y = G + 1; y <= G + 3; y++) { f.set(u, y, 0, AIR); } f.set(u, G + 4, 0, SBRICK_CHISEL); }
        body(f, -6, 6, 4, 12, 6, true, 3);
        tower(f.x(-10, 0), f.z(-10, 0), 2, 11);
        tower(f.x(10, 0), f.z(10, 0), 2, 11);
        banners(f, 0, 0, 6, new int[] {35, 11});
        reserve(f.x(-14, -4), f.z(-14, -4), f.x(14, 18), f.z(14, 18), 2);
        spots.add("factions (NPC no portao): " + f.x(0, -2) + " " + (G + 1) + " " + f.z(0, -2));
    }

    /** Casinha do vilarejo (so decoracao): oitao com telhado, chamine e jardim cercado. */
    void cottage(int fx, int fz, int bx, int bz, int[] accent) {
        Frame f = new Frame(fx, fz, bx, bz);
        body(f, -4, 4, 0, 7, 4, false, 0);
        for (int y = G + 1; y <= G + 2; y++) { f.set(0, y, 0, AIR); }
        f.set(0, G + 3, 0, f.logAlong(DARK_LOG, true));
        f.set(-1, G + 3, -1, GLOW); f.set(1, G + 3, -1, GLOW);
        for (int y = G + 1; y <= G + 10; y++) { f.set(3, y, 5, y > G + 8 ? MOSSY_COBBLE : COBBLE); }
        // Jardim cercado na frente com flores e um banco de feno.
        for (int u = -4; u <= 4; u++) { for (int v = -4; v <= -1; v++) {
            boolean edge = u == -4 || u == 4 || v == -4;
            if (edge && !(u == 0 && v == -4)) { f.set(u, G + 1, v, FENCE_SPRUCE); }
            else if (!edge && (u + v) % 2 == 0 && u != 0) { f.set(u, G + 1, v, (u * v) % 3 == 0 ? new int[] {37, 0} : new int[] {38, Math.floorMod(u * 3 + v, 9)}); }
        } }
        f.set(3, G + 1, -2, HAY);
        f.set(-3, G + 1, 1, accent);
        reserve(f.x(-6, -6), f.z(-6, -6), f.x(6, 10), f.z(6, 10), 1);
    }

    /** Lago com ponte de madeira em arco. */
    void pond(int cx, int cz) {
        for (int x = -8; x <= 8; x++) { for (int z = -8; z <= 8; z++) {
            double d = Math.sqrt(x * x + z * z) + Math.sin(Math.atan2(z, x) * 3) * 0.8;
            if (d <= 6) { set(cx + x, G - 1, cz + z, d < 4 ? new int[] {12, 0} : DIRT); set(cx + x, G, cz + z, WATER); if (d < 4) { set(cx + x, G - 1, cz + z, WATER); set(cx + x, G - 2, cz + z, new int[] {12, 0}); } }
            else if (d <= 7.2) { set(cx + x, G, cz + z, (x + z) % 3 == 0 ? MOSSY_COBBLE : GRAVEL); }
        } }
        for (int x = -7; x <= 7; x++) {
            int h = x == 0 || Math.abs(x) == 1 ? 2 : Math.abs(x) <= 4 ? 1 : 0;
            for (int z = -1; z <= 1; z++) { set(cx + x, G + h, cz + z, SPRUCE_SLAB); if (h > 0) { set(cx + x, G + h - 1, cz + z, AIR); if (h == 2) { set(cx + x, G, cz + z, WATER); } } }
            if (Math.abs(x) <= 5) { set(cx + x, G + h + 1, cz - 2, FENCE_SPRUCE); set(cx + x, G + h + 1, cz + 2, FENCE_SPRUCE); }
        }
        lamp(cx - 8, cz - 2); lamp(cx + 8, cz + 2);
        reserve(cx - 9, cz - 9, cx + 9, cz + 9, 1);
    }

    /** Barracas de feira nas diagonais da praca: balcao de pinheiro e toldo listrado de la. */
    void stalls() {
        int[][] at = {{19, -19}, {-19, -19}, {19, 19}, {-19, 19}};
        int[][] colors = {{14, 0}, {4, 0}, {11, 0}, {5, 0}};
        for (int i = 0; i < 4; i++) {
            int cx = at[i][0], cz = at[i][1];
            for (int x = -2; x <= 2; x++) { for (int z = -2; z <= 2; z++) {
                set(cx + x, G, cz + z, PATH);
                if (Math.abs(x) == 2 && Math.abs(z) == 2) { for (int y = G + 1; y <= G + 3; y++) { set(cx + x, y, cz + z, FENCE_DARK); } }
                set(cx + x, G + 4, cz + z, new int[] {35, (x + 2) % 2 == 0 ? colors[i][0] : 0});
                if (z == -1 && Math.abs(x) <= 1) { set(cx + x, G + 1, cz + z, SPRUCE_SLAB); }
            } }
            set(cx, G + 1, cz + 1, HAY);
            reserve(cx - 4, cz - 4, cx + 4, cz + 4, 0);
        }
    }

    /** Estandartes de la ao lado da entrada, na cor do modo. */
    void banners(Frame f, int uc, int v, int half, int[] color) {
        for (int s = -1; s <= 1; s += 2) {
            int u = uc + s * half;
            for (int y = G + 2; y <= G + 4; y++) { f.set(u, y, v - 1, color); }
            f.set(u, G + 5, v - 1, FENCE_DARK);
        }
    }

    // ---------- iluminacao, jardins e arvores ----------

    void lamp(int x, int z) {
        set(x, G + 1, z, WALL); set(x, G + 2, z, FENCE_DARK); set(x, G + 3, z, FENCE_DARK); set(x, G + 4, z, GLOW); set(x, G + 5, z, DARK_SLAB);
    }

    void lamps() {
        for (int i = 0; i < 8; i++) { double a = Math.PI / 4 * i; lamp((int) Math.round(Math.cos(a) * 14), (int) Math.round(Math.sin(a) * 14)); }
        int[][] dirs = {{0, -1, 34}, {1, 0, 36}, {-1, 0, 36}, {0, 1, 32}};
        for (int[] d : dirs) {
            for (int s = 20; s <= d[2] - 2; s += 7) {
                for (int o = -4; o <= 4; o += 8) { lamp(d[0] * s + (d[0] == 0 ? o : 0), d[1] * s + (d[1] == 0 ? o : 0)); }
            }
        }
    }

    void garden() {
        // Sebe baixa em volta da praca, aberta nos caminhos, com canteiros de flores por fora.
        for (int i = 0; i < 360; i += 2) {
            double a = Math.toRadians(i);
            int x = (int) Math.round(Math.cos(a) * 17), z = (int) Math.round(Math.sin(a) * 17);
            if (Math.abs(x) <= 4 || Math.abs(z) <= 4) { continue; }
            set(x, G + 1, z, OAK_LEAF);
            int fxp = (int) Math.round(Math.cos(a) * 18.5), fzp = (int) Math.round(Math.sin(a) * 18.5);
            if (id(fxp, G, fzp) == 2 && id(fxp, G + 1, fzp) == 0) { set(fxp, G + 1, fzp, (i / 2) % 3 == 0 ? new int[] {37, 0} : new int[] {38, (i / 2) % 9}); }
        }
    }

    void trees() {
        int placed = 0;
        for (int tries = 0; tries < 3000 && placed < 70; tries++) {
            int x = rnd.nextInt(2 * R) - R, z = rnd.nextInt(2 * R) - R;
            double d = Math.sqrt(x * x + z * z);
            if (d < 21 || d > edge(Math.atan2(z, x)) - 4 || reserved.contains(k(x, z))) { continue; }
            int top = G; while (id(x, top + 1, z) != 0) { top++; }
            if (id(x, top, z) != 2) { continue; }
            boolean spruce = d > 48 || rnd.nextInt(3) == 0;
            if (spruce) { spruceTree(x, top + 1, z, 7 + rnd.nextInt(4)); } else { oakTree(x, top + 1, z, 4 + rnd.nextInt(2)); }
            reserve(x, z, x, z, 3);
            placed++;
        }
    }

    void oakTree(int x, int y, int z, int h) {
        for (int i = 0; i < h; i++) { set(x, y + i, z, OAK_LOG); }
        int cy = y + h;
        for (int dx = -3; dx <= 3; dx++) { for (int dy = -2; dy <= 2; dy++) { for (int dz = -3; dz <= 3; dz++) {
            double dd = dx * dx + dy * dy * 1.6 + dz * dz;
            if (dd <= 9.5 && id(x + dx, cy + dy, z + dz) == 0 && rnd.nextInt(12) != 0) { set(x + dx, cy + dy, z + dz, OAK_LEAF); }
        } } }
    }

    void spruceTree(int x, int y, int z, int h) {
        for (int i = 0; i < h; i++) { set(x, y + i, z, SPRUCE_LOG); }
        for (int i = 2; i <= h + 1; i++) {
            int r = Math.max(0, (h + 1 - i) / 2 - ((i % 2 == 0) ? 0 : 1));
            for (int dx = -r; dx <= r; dx++) { for (int dz = -r; dz <= r; dz++) {
                if (Math.abs(dx) + Math.abs(dz) <= r + 1 && id(x + dx, y + i, z + dz) == 0) { set(x + dx, y + i, z + dz, SPRUCE_LEAF); }
            } }
        }
        set(x, y + h + 1, z, SPRUCE_LEAF);
    }
}
