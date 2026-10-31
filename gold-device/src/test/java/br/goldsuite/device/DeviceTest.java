package br.goldsuite.device;

import br.goldsuite.api.DeviceService.Platform;
import java.util.HashMap;
import java.util.Map;

public final class DeviceTest {
    public static void main(String[] args) {
        Map<String, String> m = new HashMap<>();
        check(Platforms.detect(m) == Platform.DESCONHECIDO, "Sem dados deveria ser desconhecido.");
        m.put("DeviceOS", "7"); check(Platforms.detect(m) == Platform.WINDOWS10, "DeviceOS 7 = Windows 10.");
        m.put("DeviceOS", "1"); check(Platforms.detect(m) == Platform.ANDROID, "DeviceOS 1 = Android.");
        m.put("DeviceOS", "2"); check(Platforms.detect(m) == Platform.IOS && Platform.IOS.mobile(), "DeviceOS 2 = iOS.");
        m.clear(); m.put("DeviceModel", "SAMSUNG SM-G960F"); check(Platforms.detect(m) == Platform.ANDROID, "Modelo Android.");
        m.clear(); m.put("Gold.XUID", "2535412345678901"); m.put("Gold.ChainLength", "3"); check(Platforms.detect(m) == Platform.WINDOWS10, "0.15 com conta Xbox = Windows 10.");
        m.clear(); m.put("Gold.XUID", ""); m.put("Gold.ChainLength", "1"); check(Platforms.detect(m) == Platform.DESCONHECIDO, "0.15 sem conta Xbox fica para o padrao do painel.");
        m.put("DeviceOS", "1"); m.put("Gold.XUID", "123"); check(Platforms.detect(m) == Platform.ANDROID, "DeviceOS vale mais que a conta Xbox.");
        check(Platforms.parse("pc") == Platform.WINDOWS10 && Platforms.parse("Celular") == Platform.ANDROID, "Parse errado.");
        try { Platforms.parse("xbox"); throw new AssertionError("Aceitou plataforma invalida."); } catch (IllegalArgumentException expected) { }

        ClickLimiter c = new ClickLimiter();
        long t = 10_000;
        int ok = 0;
        for (int i = 0; i < 30; i++) { if (c.allow(t + i * 20, 12)) { ok++; } }
        check(ok == 12, "Deveria aceitar 12 hits no mesmo segundo, aceitou " + ok);
        check(c.blocked(t + 600) == 18, "Hits barrados contados errado.");
        check(c.allow(t + 2000, 12), "Depois de 1 s os hits voltam a valer.");
        ClickLimiter human = new ClickLimiter();
        for (int i = 0; i < 100; i++) { check(human.allow(t + i * 100L, 12), "10 CPS humano barrado."); }
        System.out.println("GoldDevice: plataformas, limite de CPS e contagem de autoclick aprovados.");
    }
    private static void check(boolean c, String m) { if (!c) { throw new AssertionError(m); } }
}
