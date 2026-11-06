package teste;
import cn.nukkit.command.*;
import cn.nukkit.plugin.*;
import cn.nukkit.permission.Permission;
import java.io.*;
import java.lang.reflect.Field;
import java.util.*;
public class Dump extends PluginBase {
    static Object f(Object o, String n) throws Exception { Class<?> c = o.getClass(); while (c != null) { try { Field x = c.getDeclaredField(n); x.setAccessible(true); return x.get(o); } catch (NoSuchFieldException e) { c = c.getSuperclass(); } } throw new RuntimeException(n); }
    static String j(String s) { return s == null ? "null" : "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "") + "\""; }
    @Override public void onEnable() {
        getServer().getScheduler().scheduleDelayedTask(() -> {
            try (PrintWriter w = new PrintWriter(new OutputStreamWriter(new FileOutputStream("dump.json"), "UTF-8"))) {
                w.println("{\"settings\":[");
                Object eng = f(getServer().getPluginManager().getPlugin("GoldCore"), "settings");
                Map<?, ?> defs = (Map<?, ?>) f(eng, "defs"); boolean first = true;
                for (Object d : defs.values()) { w.println((first ? "" : ",") + "{\"plugin\":" + j((String) f(d, "plugin")) + ",\"key\":" + j((String) f(d, "key")) + ",\"kind\":" + j(String.valueOf(f(d, "kind"))) + ",\"def\":" + j((String) f(d, "def")) + ",\"desc\":" + j((String) f(d, "description")) + "}"); first = false; }
                w.println("],\"commands\":["); first = true;
                for (Plugin p : getServer().getPluginManager().getPlugins().values()) {
                    for (Map.Entry<String, Object> e : p.getDescription().getCommands().entrySet()) {
                        Command c = getServer().getCommandMap().getCommand(e.getKey());
                        if (c == null) continue;
                        w.println((first ? "" : ",") + "{\"plugin\":" + j(p.getName()) + ",\"name\":" + j(e.getKey()) + ",\"desc\":" + j(c.getDescription()) + ",\"usage\":" + j(c.getUsage()) + ",\"perm\":" + j(c.getPermission()) + ",\"aliases\":" + j(String.join(",", c.getAliases())) + "}"); first = false;
                    }
                }
                w.println("],\"perms\":["); first = true;
                for (Plugin p : getServer().getPluginManager().getPlugins().values()) {
                    for (Permission pe : p.getDescription().getPermissions()) { w.println((first ? "" : ",") + "{\"plugin\":" + j(p.getName()) + ",\"name\":" + j(pe.getName()) + ",\"desc\":" + j(pe.getDescription()) + ",\"def\":" + j(pe.getDefault()) + "}"); first = false; }
                }
                w.println("],\"placeholders\":["); first = true;
                Object ph = getServer().getServiceManager().load(br.goldsuite.api.PlaceholderService.class);
                for (Object k : ((Map<?, ?>) f(ph, "values")).keySet()) { w.println((first ? "" : ",") + j(String.valueOf(k))); first = false; }
                w.println("]}");
            } catch (Exception e) { getLogger().error("dump", e); }
            getLogger().info("FIM_TESTE");
        }, 100);
    }
}
