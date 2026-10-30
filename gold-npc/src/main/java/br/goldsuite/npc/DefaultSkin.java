package br.goldsuite.npc;

import cn.nukkit.entity.data.Skin;

/** Skin neutra (64x32) usada so quando a skin salva de um NPC nao e encontrada; criada uma unica vez. */
final class DefaultSkin {
    private static Skin skin;
    private DefaultSkin() { }
    static Skin get() {
        if (skin == null) {
            byte[] pixels = new byte[Skin.SINGLE_SKIN_SIZE];
            byte[] color = {(byte) 0x8d, (byte) 0x6e, (byte) 0x63, (byte) 0xff};
            for (int i = 0; i < pixels.length; i += 4) { System.arraycopy(color, 0, pixels, i, 4); }
            skin = new Skin(pixels, Skin.MODEL_STEVE);
        }
        return skin;
    }
}
