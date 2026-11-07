package br.goldsuite.npc;

import cn.nukkit.entity.data.Skin;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Skins geradas por codigo (64x32, modelo Steve): rosto, cabelo, camisa de uma cor, calca e sapatos.
 * Servem para NPCs criados pelo console (sem jogador para copiar a skin) e como reserva quando a skin salva some.
 */
final class DefaultSkin {
    /** Cor da camisa por nome (o que se digita em /npc skin cor <cor>). */
    static final Map<String, Integer> COLORS;
    static {
        Map<String, Integer> c = new LinkedHashMap<>();
        c.put("vermelho", 0xC0392B); c.put("azul", 0x2E64C8); c.put("verde", 0x27AE60); c.put("amarelo", 0xE6B800);
        c.put("laranja", 0xE67E22); c.put("roxo", 0x8E44AD); c.put("rosa", 0xE84393); c.put("ciano", 0x16A6B6);
        c.put("branco", 0xECF0F1); c.put("preto", 0x2C2C2C); c.put("cinza", 0x7F8C8D); c.put("dourado", 0xD4A017);
        COLORS = Collections.unmodifiableMap(c);
    }
    private static Skin fallback;
    private DefaultSkin() { }

    /** Reserva: camisa azul. Criada uma vez. */
    static Skin get() {
        if (fallback == null) { fallback = uniform(0x2E64C8); }
        return fallback;
    }

    static int color(String name) {
        Integer rgb = COLORS.get(name == null ? "" : name.toLowerCase(Locale.ROOT));
        if (rgb == null) { throw new IllegalArgumentException(M.t("cores_de_skin", String.join(", ", COLORS.keySet()))); }
        return rgb;
    }

    /** Skin com a camisa da cor pedida (RGB). */
    static Skin uniform(int shirt) {
        byte[] px = new byte[Skin.SINGLE_SKIN_SIZE];
        int skinTone = 0xC69C6D, hair = 0x3B2A1A, pants = 0x2B3A67, shoes = 0x3A3A3A, sleeve = darker(shirt);
        // Cabeca (x 0-31, y 0-15): pele, com o cabelo no topo e na faixa de cima de todas as faces.
        fill(px, 0, 0, 32, 16, skinTone);
        fill(px, 8, 0, 8, 8, hair);                      // topo da cabeca
        for (int face = 0; face < 4; face++) { fill(px, face * 8, 8, 8, 2, hair); }
        fill(px, 24, 8, 8, 8, hair);                     // nuca
        // Olhos e boca na frente (8,8).
        set(px, 9, 12, 0xFFFFFF); set(px, 10, 12, 0x3B5BA8); set(px, 13, 12, 0x3B5BA8); set(px, 14, 12, 0xFFFFFF);
        fill(px, 11, 14, 2, 1, 0x8A5A44);
        // Camada do chapeu (x 32-63, y 0-15): transparente.
        fill(px, 32, 0, 32, 16, -1);
        // Pernas (x 0-15, y 16-31): calca, sapato nas 2 ultimas linhas.
        fill(px, 0, 16, 16, 16, pants);
        fill(px, 0, 30, 16, 2, shoes);
        fill(px, 8, 16, 4, 4, shoes);                    // sola
        // Corpo (x 16-39, y 16-31): camisa.
        fill(px, 16, 16, 24, 16, shirt);
        // Bracos (x 40-55, y 16-31): manga nas 4 linhas de cima, maos de pele.
        fill(px, 40, 16, 16, 16, skinTone);
        fill(px, 40, 16, 16, 8, sleeve);
        // Resto da textura (x 56-63, y 16-31) nao e usado: transparente.
        fill(px, 56, 16, 8, 16, -1);
        return new Skin(px, Skin.MODEL_STEVE);
    }

    private static int darker(int rgb) {
        int r = (rgb >> 16 & 0xFF) * 4 / 5, g = (rgb >> 8 & 0xFF) * 4 / 5, b = (rgb & 0xFF) * 4 / 5;
        return r << 16 | g << 8 | b;
    }

    /** rgb = -1 deixa transparente. */
    private static void fill(byte[] px, int x0, int y0, int w, int h, int rgb) {
        for (int y = y0; y < y0 + h; y++) { for (int x = x0; x < x0 + w; x++) { set(px, x, y, rgb); } }
    }

    private static void set(byte[] px, int x, int y, int rgb) {
        int i = (y * 64 + x) * 4;
        if (rgb < 0) { px[i] = 0; px[i + 1] = 0; px[i + 2] = 0; px[i + 3] = 0; return; }
        px[i] = (byte) (rgb >> 16); px[i + 1] = (byte) (rgb >> 8); px[i + 2] = (byte) rgb; px[i + 3] = (byte) 0xFF;
    }
}
