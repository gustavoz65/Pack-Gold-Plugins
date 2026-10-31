package br.goldsuite.api;

import cn.nukkit.Server;
import cn.nukkit.plugin.Plugin;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

/** Atalhos para os plugins: declara a configuracao e reaplica o valor quando ele muda no painel. */
public final class Settings {
    private Settings() {}

    private static SettingsService service() { return Server.getInstance().getServiceManager().load(SettingsService.class); }

    public static void text(Plugin plugin, String key, String defaultValue, String description, Consumer<String> apply) {
        SettingsService s = service();
        if (s == null) { apply.accept(defaultValue.replace('&', '§')); return; }
        s.define(plugin.getName(), key, SettingsService.Kind.TEXTO, defaultValue, description);
        Runnable update = () -> apply.accept(s.text(plugin.getName(), key));
        update.run();
        s.watch(plugin.getName(), update);
    }

    public static void number(Plugin plugin, String key, long defaultValue, String description, LongConsumer apply) {
        SettingsService s = service();
        if (s == null) { apply.accept(defaultValue); return; }
        s.define(plugin.getName(), key, SettingsService.Kind.NUMERO, String.valueOf(defaultValue), description);
        Runnable update = () -> apply.accept(s.number(plugin.getName(), key));
        update.run();
        s.watch(plugin.getName(), update);
    }

    /** Prefixo das mensagens do plugin, editavel no painel. */
    public static void prefix(Plugin plugin, String defaultValue, Consumer<String> apply) { text(plugin, "prefixo", defaultValue, "Prefixo das mensagens", apply); }

    public static void discord(String source, String message) {
        SettingsService s = service();
        if (s != null) { s.discord(source, message); }
    }

    public static void alert(String source, String message) {
        SettingsService s = service();
        if (s != null) { s.alert(source, message); }
    }
}
