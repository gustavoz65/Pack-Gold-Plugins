package br.goldsuite.api;

import cn.nukkit.Server;
import cn.nukkit.plugin.Plugin;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mensagens de um plugin. Os textos padrao vem do mensagens.yml dentro do jar (ao lado da classe M do plugin) e
 * podem ser trocados em plugins/&lt;Plugin&gt;/mensagens.yml ou no jogo com /painel msg. Cada texto e compilado uma vez
 * (cores, paleta e posicoes dos valores), entao montar uma mensagem custa so juntar pedacos.
 *
 * Formato do arquivo: {@code chave: "texto"}. &amp;a..&amp;f e &amp;k..&amp;r sao cores, &amp;&amp; e um &amp; de verdade,
 * {0} {1}... sao os valores que o plugin preenche (o comentario acima de cada chave diz o que sao) e
 * {primaria} {secundaria} {destaque} {sucesso} {erro} {texto} sao as cores da paleta do /painel.
 */
public final class Messages {
    private static final Map<String, Messages> ATTACHED = new ConcurrentHashMap<>();
    private static final String[] PALETTE = {"primaria", "secundaria", "destaque", "sucesso", "erro", "texto"};
    private static final String[] PALETTE_DEFAULT = {"&6", "&e", "&b", "&a", "&c", "&f"};
    private static final String HEADER =
            "# Mensagens do %s. Edite o texto entre aspas e use /painel mensagens recarregar (ou reinicie).\n"
            + "# Cores: &0-&9 &a-&f, &l negrito, &o italico, &r reseta; && mostra um & de verdade.\n"
            + "# {0} {1}... sao os valores preenchidos pelo plugin (o comentario acima de cada chave mostra quais).\n"
            + "# Paleta do /painel: {primaria} {secundaria} {destaque} {sucesso} {erro} {texto}.\n"
            + "# Apagar uma linha volta ao texto padrao. Comentarios proprios nao sao mantidos quando o /painel msg grava o arquivo.\n\n";

    private final LinkedHashMap<String, String> defaults = new LinkedHashMap<>();
    private final Map<String, String> hints = new HashMap<>();
    private final Map<String, String> custom = new LinkedHashMap<>();
    private volatile Map<String, Object[]> compiled = Collections.emptyMap();
    private volatile String name = "plugin";
    private File file;

    private Messages() {}

    /**
     * Carrega os textos padrao do mensagens.yml que fica no mesmo pacote da classe e, no servidor, ja aplica
     * plugins/&lt;plugin&gt;/mensagens.yml (assim ate nomes de itens e kits montados na inicializacao seguem o arquivo).
     */
    public static Messages resource(Class<?> anchor, String plugin) {
        Messages m = new Messages();
        m.name = plugin;
        try (InputStream in = anchor.getResourceAsStream("mensagens.yml")) {
            if (in != null) { parse(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)), m.defaults, m.hints); }
        } catch (IOException ignored) { }
        Server server = server();
        if (server != null && server.getPluginPath() != null) {
            m.file = new File(new File(server.getPluginPath(), plugin), "mensagens.yml");
            ATTACHED.put(plugin.toLowerCase(Locale.ROOT), m);
            synchronized (m) { m.load(); }
        } else {
            m.compile();
        }
        return m;
    }

    /** Chamado no onEnable: usa a pasta real do plugin e passa a seguir a paleta do painel. */
    public synchronized void attach(Plugin plugin) {
        File real = new File(plugin.getDataFolder(), "mensagens.yml");
        boolean moved = file == null || !real.getAbsoluteFile().equals(file.getAbsoluteFile());
        this.name = plugin.getName();
        this.file = real;
        ATTACHED.put(name.toLowerCase(Locale.ROOT), this);
        if (moved) { load(); } else { compile(); }
        SettingsService s = settings();
        if (s != null) { s.watch(name, this::compile); }
    }

    private static Server server() {
        try { return Server.getInstance(); } catch (RuntimeException | NoClassDefFoundError e) { return null; }
    }

    /** Releitura do arquivo (depois de editar na mao). */
    public synchronized void reload() { if (file != null) { load(); } }

    private void load() {
        custom.clear();
        try {
            if (file.isFile()) {
                Map<String, String> read = new LinkedHashMap<>();
                try (BufferedReader r = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) { parse(r, read, null); }
                custom.putAll(read);
                List<String> missing = new ArrayList<>();
                for (String k : defaults.keySet()) { if (!read.containsKey(k)) { missing.add(k); } }
                if (!missing.isEmpty()) {
                    StringBuilder add = new StringBuilder("\n# Mensagens novas desta versao\n");
                    for (String k : missing) { entry(add, k); }
                    Files.write(file.toPath(), add.toString().getBytes(StandardCharsets.UTF_8), java.nio.file.StandardOpenOption.APPEND);
                }
            } else {
                save();
            }
        } catch (IOException | RuntimeException e) {
            Server server = server();
            if (server != null) server.getLogger().warning("[" + name + "] mensagens.yml ilegivel, usando os textos padrao: " + e.getMessage());
            custom.clear();
        }
        compile();
    }

    /** Texto montado com cores e valores. */
    public String t(String key, Object... args) {
        Object[] parts = compiled.get(key);
        if (parts == null) { return key; }
        if (parts.length == 1 && parts[0] instanceof String) { return (String) parts[0]; }
        StringBuilder sb = new StringBuilder(64);
        for (Object p : parts) {
            if (p instanceof String) { sb.append((String) p); }
            else { int i = (Integer) p; sb.append(args != null && i < args.length ? String.valueOf(args[i]) : "{" + i + "}"); }
        }
        return sb.toString();
    }

    // ---------- painel ----------

    public String pluginName() { return name; }
    public Set<String> keys() { return Collections.unmodifiableSet(defaults.keySet()); }
    public boolean has(String key) { return defaults.containsKey(key); }
    /** Texto como esta no arquivo (com &amp;), ou o padrao. */
    public synchronized String raw(String key) { String c = custom.get(key); return c != null ? c : defaults.get(key); }
    public String defaultRaw(String key) { return defaults.get(key); }
    public String hint(String key) { return hints.get(key); }
    public synchronized boolean customized(String key) { return custom.containsKey(key) && !custom.get(key).equals(defaults.get(key)); }

    /** Troca um texto (null volta ao padrao) e grava o arquivo. */
    public synchronized void set(String key, String value) throws IOException {
        if (!defaults.containsKey(key)) { throw new IllegalArgumentException(key); }
        if (value == null || value.equals(defaults.get(key))) { custom.remove(key); } else { custom.put(key, value); }
        if (file != null) { save(); }
        compile();
    }

    public static Messages of(String plugin) { return plugin == null ? null : ATTACHED.get(plugin.toLowerCase(Locale.ROOT)); }

    public static List<Messages> all() {
        List<Messages> l = new ArrayList<>(ATTACHED.values());
        l.sort(Comparator.comparing(Messages::pluginName, String.CASE_INSENSITIVE_ORDER));
        return l;
    }

    // ---------- arquivo ----------

    private void save() throws IOException {
        File dir = file.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) { throw new IOException("sem pasta " + dir); }
        StringBuilder sb = new StringBuilder(String.format(HEADER, name));
        for (String k : defaults.keySet()) { entry(sb, k); }
        for (Map.Entry<String, String> e : custom.entrySet()) {
            if (!defaults.containsKey(e.getKey())) { sb.append(e.getKey()).append(": ").append(quote(e.getValue())).append('\n'); }
        }
        File tmp = new File(file.getPath() + ".tmp");
        Files.write(tmp.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    private void entry(StringBuilder sb, String k) {
        String h = hints.get(k);
        if (h != null) { sb.append("# ").append(h).append('\n'); }
        String c = custom.get(k);
        sb.append(k).append(": ").append(quote(c != null ? c : defaults.get(k))).append('\n');
    }

    static String quote(String v) { return '"' + v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + '"'; }

    /** Le "chave: valor" (aspas opcionais); o comentario logo acima vira a dica da chave. */
    static void parse(BufferedReader r, Map<String, String> into, Map<String, String> hintsInto) throws IOException {
        String line, hint = null;
        int n = 0;
        while ((line = r.readLine()) != null) {
            n++;
            String t = line.trim();
            if (t.isEmpty()) { hint = null; continue; }
            if (t.startsWith("#")) { hint = t.substring(1).trim(); continue; }
            int c = t.indexOf(':');
            if (c <= 0) { throw new IllegalArgumentException("linha " + n + " sem 'chave: texto'"); }
            String key = t.substring(0, c).trim();
            String v = t.substring(c + 1).trim();
            if (v.startsWith("\"")) { v = unquote(v, n); }
            else if (v.startsWith("'") && v.endsWith("'") && v.length() >= 2) { v = v.substring(1, v.length() - 1).replace("''", "'"); }
            into.put(key, v);
            if (hintsInto != null && hint != null) { hintsInto.put(key, hint); }
            hint = null;
        }
    }

    private static String unquote(String v, int line) {
        StringBuilder sb = new StringBuilder(v.length());
        for (int i = 1; i < v.length(); i++) {
            char ch = v.charAt(i);
            if (ch == '\\' && i + 1 < v.length()) {
                char nx = v.charAt(++i);
                sb.append(nx == 'n' ? '\n' : nx);
            } else if (ch == '"') {
                return sb.toString();
            } else { sb.append(ch); }
        }
        throw new IllegalArgumentException("linha " + line + ": aspas sem fechar");
    }

    // ---------- compilacao ----------

    private void compile() {
        String[] palette = palette();
        Map<String, Object[]> out = new HashMap<>(defaults.size() * 2);
        for (String k : defaults.keySet()) {
            String c;
            synchronized (this) { c = custom.get(k); }
            out.put(k, compile(c != null ? c : defaults.get(k), palette));
        }
        compiled = out;
    }

    static Object[] compile(String raw, String[] palette) {
        if (raw.indexOf('{') >= 0) { for (int i = 0; i < PALETTE.length; i++) { raw = raw.replace("{" + PALETTE[i] + "}", palette[i]); } }
        List<Object> parts = new ArrayList<>(4);
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (ch == '&' && i + 1 < raw.length()) {
                char nx = raw.charAt(i + 1);
                if (nx == '&') { sb.append('&'); i++; continue; }
                if ("0123456789abcdefklmnorABCDEFKLMNOR".indexOf(nx) >= 0) { sb.append('§').append(Character.toLowerCase(nx)); i++; continue; }
            }
            if (ch == '{') {
                int end = raw.indexOf('}', i);
                if (end > i + 1 && end - i <= 4) {
                    String num = raw.substring(i + 1, end);
                    boolean digits = true;
                    for (int j = 0; j < num.length(); j++) { if (!Character.isDigit(num.charAt(j))) { digits = false; break; } }
                    if (digits) {
                        if (sb.length() > 0) { parts.add(sb.toString()); sb.setLength(0); }
                        parts.add(Integer.parseInt(num));
                        i = end;
                        continue;
                    }
                }
            }
            sb.append(ch);
        }
        if (sb.length() > 0 || parts.isEmpty()) { parts.add(sb.toString()); }
        return parts.toArray();
    }

    /** Cores da paleta ja com §, vindas do painel (ou as padrao fora do servidor). */
    private static String[] palette() {
        String[] out = new String[PALETTE.length];
        SettingsService s = settings();
        for (int i = 0; i < PALETTE.length; i++) {
            String v = null;
            if (s != null) { try { v = s.text("Servidor", "cor." + PALETTE[i]); } catch (RuntimeException ignored) { } }
            out[i] = v != null && !v.isEmpty() ? v.replace('§', '&') : PALETTE_DEFAULT[i];
        }
        return out;
    }

    private static SettingsService settings() {
        Server server = server();
        try { return server == null || server.getServiceManager() == null ? null : server.getServiceManager().load(SettingsService.class); }
        catch (RuntimeException e) { return null; }
    }
}
