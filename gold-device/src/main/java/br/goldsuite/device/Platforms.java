package br.goldsuite.device;

import br.goldsuite.api.DeviceService.Platform;
import java.util.Locale;
import java.util.Map;

/** Descobre a plataforma pelos dados do login. Clientes 1.x mandam DeviceOS; o 0.15 quase nunca, entao vale o padrao do painel. */
final class Platforms {
    private Platforms() {}

    static Platform detect(Map<String, String> login) {
        String os = login.get("DeviceOS");
        if (os != null) {
            switch (os.trim()) {
                case "1": case "4": return Platform.ANDROID;       // Android, FireOS
                case "2": case "3": return Platform.IOS;           // iOS (3 = macOS no Bedrock, tratado como Apple)
                case "7": case "8": return Platform.WINDOWS10;     // Windows 10 / Win32
                case "": break;
                default: return Platform.OUTRO;
            }
        }
        String model = login.getOrDefault("DeviceModel", "").toLowerCase(Locale.ROOT);
        if (model.contains("iphone") || model.contains("ipad")) { return Platform.IOS; }
        if (!model.isEmpty() && !model.contains("windows")) { return Platform.ANDROID; }
        if (model.contains("windows")) { return Platform.WINDOWS10; }
        return Platform.DESCONHECIDO;
    }

    static Platform parse(String text) {
        switch (text.toLowerCase(Locale.ROOT)) {
            case "windows": case "win10": case "pc": return Platform.WINDOWS10;
            case "android": case "mobile": case "celular": return Platform.ANDROID;
            case "ios": case "iphone": return Platform.IOS;
            default: throw new IllegalArgumentException("Use windows, android ou ios.");
        }
    }

    static String label(Platform p) {
        switch (p) {
            case WINDOWS10: return "Windows 10";
            case ANDROID: return "Android";
            case IOS: return "iOS";
            case OUTRO: return "Outra";
            default: return "Desconhecida";
        }
    }
}
