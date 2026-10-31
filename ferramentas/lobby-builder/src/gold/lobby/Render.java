package gold.lobby;

import cn.nukkit.level.Level;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** Previas do lobby sem abrir o jogo: mapa visto de cima e vista isometrica (luz do por do sol). */
final class Render {
    private Render() {}

    static int color(int id, int meta) {
        switch (id) {
            case 1: return meta == 5 || meta == 6 ? 0x8a8a8e : 0x7d7d7d;
            case 2: return 0x6aa843;
            case 3: return meta == 1 ? 0x7a5a3c : 0x8b6340;
            case 4: return 0x777777;
            case 48: return 0x667a5c;
            case 5: return meta == 1 ? 0x6b4f2f : meta == 5 ? 0x45301a : 0xa88654;
            case 17: return meta % 4 == 1 ? 0x3d2b16 : 0x6b5434;
            case 162: return 0x3a2a18;
            case 18: return (meta & 3) == 1 ? 0x2f5a33 : 0x3f8a2a;
            case 161: return 0x2e6b24;
            case 9: case 8: return 0x3f76e4;
            case 45: case 108: return 0xb2563a;
            case 159: return meta == 1 ? 0xd2732e : meta == 0 ? 0xe8dccb : 0xb0806a;
            case 98: return meta == 1 ? 0x6f7d63 : meta == 3 ? 0x9a9a9a : 0x8c8c8c;
            case 44: case 109: return 0x8c8c8c;
            case 198: return 0x9a8a4c;
            case 89: return 0xffd257;
            case 102: return 0xa8d8ee;
            case 139: return 0x7a7a7a;
            case 85: return 0x45301a;
            case 158: return meta == 5 ? 0x45301a : 0x6b4f2f;
            case 134: return 0x6b4f2f;
            case 164: return 0x45301a;
            case 155: return 0xebe5dc;
            case 47: return 0x8a6a3a;
            case 41: return 0xf5d33a;
            case 35: return meta == 14 ? 0xb3312c : meta == 5 ? 0x41ae38 : meta == 4 ? 0xeace3d : meta == 11 ? 0x2e388d : 0xdddddd;
            case 37: return 0xf2e33a;
            case 12: return 0xdbcf8e;
            case 13: return 0x857f7c;
            case 170: return 0xc9a52a;
            case 243: return 0x5a3f22;
            case 38: return 0xd33a2a;
            default: return 0xff00ff;
        }
    }

    static int shade(int rgb, double f) {
        int r = (int) Math.min(255, ((rgb >> 16) & 255) * f), g = (int) Math.min(255, ((rgb >> 8) & 255) * f), b = (int) Math.min(255, (rgb & 255) * f);
        return (r << 16) | (g << 8) | b;
    }

    /** Mapa de cima: bloco mais alto de cada coluna, sombreado pela altura. */
    static void top(Level w, File dir, int r, int ground) throws Exception {
        int s = 6, n = 2 * r + 1;
        BufferedImage img = new BufferedImage(n * s, n * s, BufferedImage.TYPE_INT_RGB);
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                int y = 127; while (y > 0 && w.getBlockIdAt(x, y, z) == 0) { y--; }
                int id = w.getBlockIdAt(x, y, z);
                int c = id == 0 ? 0x1b2340 : shade(color(id, w.getBlockDataAt(x, y, z)), 0.75 + (y - ground) * 0.03);
                for (int i = 0; i < s; i++) { for (int j = 0; j < s; j++) { img.setRGB((x + r) * s + i, (z + r) * s + j, c); } }
            }
        }
        dir.mkdirs();
        ImageIO.write(img, "png", new File(dir, "lobby-cima.png"));
    }

    /** Isometrica vista do sudeste: blocos visiveis desenhados de tras para frente, ceu de por do sol. */
    static void iso(Level w, File dir, int r, int y0, int y1) throws Exception {
        int tw = 8, th = 4, bh = 5;
        int width = (4 * r + 4) * tw / 2 + 40, oyTop = r * th + (y1 - y0) * bh + 30, height = oyTop + r * th + 60;
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int py = 0; py < height; py++) {
            double t = py / (double) height;
            int sky = t < 0.5 ? mix(0x2b2f6e, 0xf08a3a, t * 2) : mix(0xf08a3a, 0x3a2a5a, (t - 0.5) * 2);
            for (int px = 0; px < width; px++) { img.setRGB(px, py, sky); }
        }
        int ox = width / 2, oy = oyTop;
        for (int sum = -2 * r; sum <= 2 * r; sum++) {
            for (int x = -r; x <= r; x++) {
                int z = sum - x;
                if (z < -r || z > r) { continue; }
                for (int y = y0; y <= y1; y++) {
                    int id = w.getBlockIdAt(x, y, z);
                    if (id == 0) { continue; }
                    boolean up = w.getBlockIdAt(x, y + 1, z) == 0 || w.getBlockIdAt(x, y + 1, z) == 9, ex = w.getBlockIdAt(x + 1, y, z) == 0, ez = w.getBlockIdAt(x, y, z + 1) == 0;
                    if (!up && !ex && !ez) { continue; }
                    int c = color(id, w.getBlockDataAt(x, y, z));
                    int sx = ox + (x - z) * tw / 2, sy = oy + (x + z) * th / 2 - (y - y0) * bh;
                    // Topo claro (sol), lado leste quente, lado sul mais escuro.
                    fillTop(img, sx, sy, tw, th, shade(c, 1.12));
                    fillSide(img, sx, sy, tw, th, bh, shade(mix(c, 0xff9a4a, 0.12), 0.85), true);
                    fillSide(img, sx, sy, tw, th, bh, shade(c, 0.6), false);
                    if (id == 89) { glow(img, sx, sy - bh); }
                }
            }
        }
        ImageIO.write(img, "png", new File(dir, "lobby-perspectiva.png"));
    }

    static int mix(int a, int b, double t) {
        int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t), g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t), bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
        return (r << 16) | (g << 8) | bl;
    }

    static void px(BufferedImage img, int x, int y, int c) { if (x >= 0 && y >= 0 && x < img.getWidth() && y < img.getHeight()) { img.setRGB(x, y, c); } }

    static void fillTop(BufferedImage img, int sx, int sy, int tw, int th, int c) {
        for (int j = 0; j < th; j++) { int half = (j < th / 2 ? j + 1 : th - j) * tw / th; for (int i = -half; i < half; i++) { px(img, sx + i, sy - th / 2 + j, c); } }
    }

    static void fillSide(BufferedImage img, int sx, int sy, int tw, int th, int bh, int c, boolean right) {
        for (int i = 0; i < tw / 2; i++) {
            int x = right ? sx + i : sx - i - 1;
            int top = sy + (th / 2) - (i * th / tw);
            for (int j = 0; j < bh; j++) { px(img, x, top + j, c); }
        }
    }

    static void glow(BufferedImage img, int sx, int sy) {
        for (int dx = -6; dx <= 6; dx++) { for (int dy = -6; dy <= 6; dy++) {
            double d = Math.sqrt(dx * dx + dy * dy); if (d > 6) { continue; }
            int x = sx + dx, y = sy + dy; if (x < 0 || y < 0 || x >= img.getWidth() || y >= img.getHeight()) { continue; }
            img.setRGB(x, y, mix(img.getRGB(x, y) & 0xffffff, 0xffd26a, 0.45 * (1 - d / 6)));
        } }
    }
}
