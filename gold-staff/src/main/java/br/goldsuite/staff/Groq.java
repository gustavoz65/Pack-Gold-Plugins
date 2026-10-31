package br.goldsuite.staff;

import com.google.gson.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.*;

/**
 * Cliente da API de chat da Groq (formato OpenAI). Roda em threads proprias com fila limitada:
 * nunca trava o servidor, e com a fila cheia a triagem cai no modo sem IA.
 */
final class Groq {
    static final String DEFAULT_URL = "https://api.groq.com/openai/v1/chat/completions";
    static final String DEFAULT_MODEL = "openai/gpt-oss-120b";

    private final ExecutorService pool = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(40), r -> {
        Thread t = new Thread(r, "GoldStaff-Groq"); t.setDaemon(true); return t;
    }, new ThreadPoolExecutor.AbortPolicy());
    volatile String key = "", model = DEFAULT_MODEL, url = DEFAULT_URL;

    boolean enabled() { return !key.isEmpty(); }

    /** So aceita https, ou http no proprio computador (testes). */
    static boolean validUrl(String u) { return u.startsWith("https://") || u.startsWith("http://127.0.0.1:") || u.startsWith("http://localhost:"); }

    /** Envia a conversa e devolve o objeto JSON que o modelo respondeu. */
    CompletableFuture<JsonObject> ask(List<JsonObject> messages) {
        String k = key, m = model, u = url;
        JsonObject body = new JsonObject();
        body.addProperty("model", m);
        JsonArray list = new JsonArray();
        for (JsonObject msg : messages) { list.add(msg); }
        body.add("messages", list);
        body.addProperty("temperature", 0.3);
        body.addProperty("max_completion_tokens", 900);
        JsonObject format = new JsonObject();
        format.addProperty("type", "json_object");
        body.add("response_format", format);
        // Os modelos gpt-oss raciocinam antes de responder; pouco raciocinio basta para triagem e economiza tempo.
        if (m.startsWith("openai/gpt-oss")) { body.addProperty("reasoning_effort", "low"); }
        String payload = body.toString();
        CompletableFuture<JsonObject> out = new CompletableFuture<>();
        try {
            pool.execute(() -> {
                try { out.complete(parse(post(u, k, payload))); } catch (Throwable e) { out.completeExceptionally(e); }
            });
        } catch (RejectedExecutionException e) { out.completeExceptionally(new IOException("Muitas triagens ao mesmo tempo.")); }
        return out;
    }

    private static String post(String u, String k, String payload) throws IOException {
        if (!validUrl(u)) { throw new IOException("Endereco da IA invalido."); }
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        try {
            c.setRequestMethod("POST"); c.setDoOutput(true); c.setConnectTimeout(6000); c.setReadTimeout(25000);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Authorization", "Bearer " + k);
            c.setRequestProperty("User-Agent", "NukkitGold");
            try (OutputStream o = c.getOutputStream()) { o.write(payload.getBytes(StandardCharsets.UTF_8)); }
            int code = c.getResponseCode();
            InputStream in = code >= 300 ? c.getErrorStream() : c.getInputStream();
            String text = read(in);
            if (code == 401) { throw new IOException("Chave da Groq recusada (401). Confira no /painel."); }
            if (code >= 300) { throw new IOException("IA respondeu " + code + ": " + TicketState.clean(text, 160)); }
            return text;
        } finally { c.disconnect(); }
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) { return ""; }
        try (InputStream s = in) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = s.read(buf)) > 0) { b.write(buf, 0, n); if (b.size() > 256 * 1024) { throw new IOException("Resposta da IA grande demais."); } }
            return new String(b.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /** Extrai choices[0].message.content e le o JSON de dentro (tolera texto em volta das chaves). */
    static JsonObject parse(String response) throws IOException {
        try {
            JsonObject root = new JsonParser().parse(response).getAsJsonObject();
            String content = root.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message").get("content").getAsString();
            int a = content.indexOf('{'), b = content.lastIndexOf('}');
            if (a < 0 || b <= a) { throw new IOException("IA nao respondeu em JSON."); }
            return new JsonParser().parse(content.substring(a, b + 1)).getAsJsonObject();
        } catch (RuntimeException e) { throw new IOException("Resposta da IA ilegivel."); }
    }

    static String str(JsonObject o, String field) { JsonElement e = o.get(field); return e == null || e.isJsonNull() ? "" : e.isJsonPrimitive() ? e.getAsString() : e.toString(); }
    static boolean bool(JsonObject o, String field) { JsonElement e = o.get(field); try { return e != null && e.isJsonPrimitive() && (e.getAsJsonPrimitive().isBoolean() ? e.getAsBoolean() : e.getAsString().equalsIgnoreCase("true")); } catch (RuntimeException x) { return false; } }

    static JsonObject message(String role, String content) {
        JsonObject o = new JsonObject();
        o.addProperty("role", role);
        o.addProperty("content", content);
        return o;
    }

    void close() { pool.shutdownNow(); }
}
