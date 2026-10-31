package br.goldsuite.device;

import br.goldsuite.api.DeviceService.Platform;
import java.util.Locale;
import java.util.Map;

/** Descobre a plataforma pelos dados do login. Clientes 1.x mandam DeviceOS; no 0.15 a conta Xbox separa o Windows 10 do celular. */
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
        // O 0.15 nao manda o sistema. Nessa versao so o Windows 10 Edition entra com conta Xbox Live (cadeia de login
        // assinada, com XUID); o Pocket Edition do celular entra sem conta. Essas chaves sao gravadas pelo servidor.
        if (!login.getOrDefault("Gold.XUID", "").isEmpty() || parseInt(login.get("Gold.ChainLength")) > 1) { return Platform.WINDOWS10; }
        return Platform.DESCONHECIDO;
    }

    private static int parseInt(String v) { try { return v == null ? 0 : Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return 0; } }

    /** Resumo do que o login mostrou, para conferir a deteccao no console. */
    static String evidence(Map<String, String> login) {
        String os = login.get("DeviceOS");
        return os != null ? "DeviceOS " + os : "conta Xbox " + (login.getOrDefault("Gold.XUID", "").isEmpty() ? "nao" : "sim") + ", cadeia " + login.getOrDefault("Gold.ChainLength", "?");
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
