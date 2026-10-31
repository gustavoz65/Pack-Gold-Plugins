package br.goldsuite.api;

import cn.nukkit.Player;

/** Plataforma de cada jogador e o modo leve (/fps). Plugins de efeitos consultam lowEffects antes de mandar particulas e modelos. */
public interface DeviceService {
    enum Platform { WINDOWS10, ANDROID, IOS, OUTRO, DESCONHECIDO;
        public boolean mobile() { return this == ANDROID || this == IOS; }
    }

    Platform platform(Player player);
    /** /fps ligado: sem particulas decorativas, trilhas, modelos 3D e animacoes de viagem. */
    boolean lowEffects(Player player);

    /** Atalho seguro: false se o GoldDevice nao estiver instalado. */
    static boolean low(Player player) {
        DeviceService s = cn.nukkit.Server.getInstance().getServiceManager().load(DeviceService.class);
        return s != null && s.lowEffects(player);
    }
}
