package br.goldsuite.core;

import cn.nukkit.Server;
import cn.nukkit.event.server.ServerCommandEvent;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Vigia do console. O leitor do Nukkit (jline) morre com StringIndexOutOfBoundsException quando o log
 * escreve no meio de uma linha digitada; a thread "Console" some e o painel para de aceitar comandos.
 * A cada 5 s confere se ela esta viva; se morreu, le o stdin por uma thread simples (sem jline).
 */
final class ConsoleGuard {
    private final GoldCore plugin;
    private Thread original;
    private volatile Thread backup;
    private volatile boolean ended;

    ConsoleGuard(GoldCore plugin) { this.plugin = plugin; }

    void start() {
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if ("Console".equals(t.getName())) { this.original = t; break; }
        }
        if (this.original == null) { return; }
        plugin.getServer().getScheduler().scheduleDelayedRepeatingTask(this::check, 100, 100);
    }

    private void check() {
        if (ended || !plugin.isEnabled() || original.isAlive() || (backup != null && backup.isAlive())) { return; }
        plugin.getLogger().warning(M.t("console_reaberto"));
        Thread t = new Thread(this::read, "Console-Gold");
        t.setDaemon(true);
        this.backup = t;
        t.start();
    }

    private void read() {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null && plugin.isEnabled()) {
                String cmd = line.trim();
                while (cmd.startsWith("/")) { cmd = cmd.substring(1).trim(); }
                if (cmd.isEmpty()) { continue; }
                String c = cmd;
                plugin.getServer().getScheduler().scheduleTask(() -> {
                    Server s = Server.getInstance();
                    ServerCommandEvent ev = new ServerCommandEvent(s.getConsoleSender(), c);
                    s.getPluginManager().callEvent(ev);
                    if (!ev.isCancelled()) { s.dispatchCommand(ev.getSender(), ev.getCommand()); }
                });
            }
            ended = true;
        } catch (Exception e) {
            plugin.getLogger().warning(M.t("console_reaberto") + " " + e);
        }
    }
}
