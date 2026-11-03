package br.goldsuite.core;

import br.goldsuite.api.PlaceholderService;
import cn.nukkit.Player;
import cn.nukkit.Server;
import cn.nukkit.plugin.Plugin;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/** Registro das chaves {x}. Modelos compilados guardam so referencias: nada e alocado por chave a cada render. */
final class PlaceholderRegistry implements PlaceholderService {
    private final Map<String, Function<Player, String>> values = new ConcurrentHashMap<>();

    PlaceholderRegistry() {
        Plugin none = null;
        register(none, "nome", Player::getName);
        register(none, "online", p -> String.valueOf(Server.getInstance().getOnlinePlayers().size()));
        register(none, "max", p -> String.valueOf(Server.getInstance().getMaxPlayers()));
        register(none, "mundo", p -> p.level == null ? "" : p.level.getFolderName());
        register(none, "x", p -> String.valueOf(p.getFloorX()));
        register(none, "y", p -> String.valueOf(p.getFloorY()));
        register(none, "z", p -> String.valueOf(p.getFloorZ()));
        register(none, "dir", p -> direction(p.yaw));
        register(none, "ping", p -> String.valueOf(p.getPing()));
    }

    static String direction(double yaw) {
        String[] dirs = {"S", "SO", "O", "NO", "N", "NE", "L", "SE"};
        return dirs[(int) Math.floorMod(Math.round(((yaw % 360) + 360) % 360 / 45.0), 8)];
    }

    @Override public void register(Plugin owner, String key, Function<Player, String> value) {
        if (key == null || !key.matches("[a-z0-9_]{1,32}") || value == null) { throw new IllegalArgumentException(M.t("chave_de_placeholder_invalida", key)); }
        values.put(key, value);
    }

    @Override public Template compile(String text) {
        List<Object> parts = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        int i = 0;
        StringBuilder lit = new StringBuilder();
        while (i < text.length()) {
            char c = text.charAt(i);
            int end = c == '{' ? text.indexOf('}', i + 1) : -1;
            String key = end > i ? text.substring(i + 1, end) : null;
            if (key != null && key.matches("[a-z0-9_]{1,32}")) {
                if (lit.length() > 0) { parts.add(lit.toString()); lit.setLength(0); }
                parts.add(new String[] {key});
                keys.add(key);
                i = end + 1;
            } else { lit.append(c); i++; }
        }
        if (lit.length() > 0) { parts.add(lit.toString()); }
        Object[] compiled = parts.toArray();
        return new Template() {
            @Override public String render(Player p) {
                StringBuilder out = new StringBuilder(64);
                for (Object part : compiled) {
                    if (part instanceof String) { out.append((String) part); continue; }
                    String k = ((String[]) part)[0];
                    Function<Player, String> f = values.get(k);
                    if (f == null) { out.append('{').append(k).append('}'); continue; }
                    try { String v = f.apply(p); out.append(v == null ? "" : v); } catch (RuntimeException e) { out.append('?'); }
                }
                return out.toString();
            }
            @Override public boolean uses(String key) { return keys.contains(key); }
        };
    }
}
