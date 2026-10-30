package br.goldsuite.core;

import cn.nukkit.utils.Logger;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.regex.Pattern;

/**
 * Envio de avisos para um webhook do Discord numa thread propria (nunca trava o servidor).
 * Fila limitada, no maximo uma mensagem por segundo e so URLs oficiais do Discord (sem enviar para qualquer endereco).
 */
final class DiscordWebhook {
    private static final Pattern URL_PATTERN = Pattern.compile("https://(discord\\.com|discordapp\\.com|ptb\\.discord\\.com|canary\\.discord\\.com)/api/webhooks/\\d{5,25}/[A-Za-z0-9_-]{20,100}");
    private final ExecutorService worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(100), r -> {
        Thread t = new Thread(r, "GoldCore-Discord"); t.setDaemon(true); return t;
    }, new ThreadPoolExecutor.DiscardPolicy());
    private final Logger logger;

    DiscordWebhook(Logger logger) { this.logger = logger; }

    static boolean valid(String url) { return url.isEmpty() || URL_PATTERN.matcher(url).matches(); }

    static String json(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': break;
                default: if (c < 0x20) { out.append(' '); } else { out.append(c); }
            }
        }
        return out.append('"').toString();
    }

    void send(String url, String name, String message) {
        if (url.isEmpty() || !valid(url)) { return; }
        String content = message.length() > 1900 ? message.substring(0, 1900) + "..." : message;
        // allowed_mentions vazio: um nome de jogador nunca vira @everyone.
        String body = "{\"username\":" + json(name) + ",\"content\":" + json(content) + ",\"allowed_mentions\":{\"parse\":[]}}";
        worker.execute(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setRequestMethod("POST"); c.setDoOutput(true); c.setConnectTimeout(5000); c.setReadTimeout(5000);
                c.setRequestProperty("Content-Type", "application/json"); c.setRequestProperty("User-Agent", "NukkitGold");
                try (OutputStream o = c.getOutputStream()) { o.write(body.getBytes(StandardCharsets.UTF_8)); }
                int code = c.getResponseCode();
                if (code >= 300) { logger.warning("Discord respondeu " + code + " ao aviso."); }
                c.disconnect();
                Thread.sleep(1000);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            catch (Exception e) { logger.warning("Falha ao avisar no Discord: " + e.getMessage()); }
        });
    }

    void close() { worker.shutdownNow(); }
}
