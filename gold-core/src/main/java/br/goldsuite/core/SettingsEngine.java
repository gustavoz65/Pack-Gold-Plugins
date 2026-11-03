package br.goldsuite.core;

import br.goldsuite.api.MenuService;
import br.goldsuite.api.PersistentState;
import br.goldsuite.api.SettingsService;
import cn.nukkit.Player;
import cn.nukkit.command.CommandSender;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.player.PlayerChatEvent;
import cn.nukkit.event.player.PlayerQuitEvent;
import cn.nukkit.item.Item;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Painel de configuracao de toda a GoldSuite (/painel, so OP): paleta, prefixos, mensagens e valores dos plugins,
 * mais o webhook do Discord. Valores ficam no SQLite do GoldCore; os plugins so declaram padrao e descricao.
 */
final class SettingsEngine implements SettingsService, Listener {
    static final String SERVER = "Servidor";
    static final String[] PALETTE = {"primaria", "secundaria", "destaque", "sucesso", "erro", "texto"};
    private static final String[] PALETTE_DEFAULT = {"&6", "&e", "&b", "&a", "&c", "&f"};
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
    static final Pattern COLOR = Pattern.compile("(&[0-9a-fk-or]){1,3}");
    private static final int PER_PAGE = 18;

    /** O que fica gravado: valores mudados no painel (o resto usa o padrao) e o webhook. */
    public static final class State {
        public Map<String, String> values = new TreeMap<>();
        public String webhook = "";

        static void validate(State s) {
            if (s == null || s.values == null || s.values.size() > 4000 || s.webhook == null || !DiscordWebhook.valid(s.webhook)) { throw new IllegalArgumentException(M.t("configuracao_invalida")); }
            for (Map.Entry<String, String> e : s.values.entrySet()) {
                if (!KEY.matcher(e.getKey().replace(':', '.')).matches() || e.getValue() == null || e.getValue().length() > 256) { throw new IllegalArgumentException(M.t("valor_invalido", e.getKey())); }
            }
        }
    }

    static final class Def {
        final String plugin, key, def, description; final Kind kind;
        Def(String plugin, String key, Kind kind, String def, String description) { this.plugin = plugin; this.key = key; this.kind = kind; this.def = def; this.description = description; }
        String id() { return SettingsEngine.id(plugin, key); }
    }

    static String id(String plugin, String key) { return plugin + ":" + key; }

    private final GoldCore core;
    private final PersistentState<State> data;
    private final DiscordWebhook discord;
    private final Map<String, Def> defs = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private final Map<String, List<Runnable>> watchers = new HashMap<>();
    /** Quem esta digitando um valor no chat: jogador -> {id, prazo}. */
    private final Map<Player, Object[]> typing = new IdentityHashMap<>();

    SettingsEngine(GoldCore core) {
        this.core = core;
        this.discord = new DiscordWebhook(core.getLogger());
        this.data = new PersistentState<>(core, "goldcore-config", State.class, State::new, State::validate);
        for (int i = 0; i < PALETTE.length; i++) { define(SERVER, "cor." + PALETTE[i], Kind.TEXTO, PALETTE_DEFAULT[i], "Cor " + PALETTE[i] + " da paleta (ex.: &6 ou &6&l)"); }
        define(SERVER, "alerta.prefixo", Kind.TEXTO, "{erro}[Alerta]", "Prefixo dos alertas para a staff");
        define(SERVER, "discord.nome", Kind.TEXTO, "Nukkit Gold", "Nome do bot nos avisos do Discord");
        data.ready().thenRun(this::changedAll);
    }

    void close() { discord.close(); data.close(); }

    // ---------- servico ----------

    @Override public void define(String plugin, String key, Kind kind, String defaultValue, String description) {
        if (!KEY.matcher(key).matches() || !KEY.matcher(plugin).matches()) { throw new IllegalArgumentException(M.t("chave_invalida", plugin, key)); }
        defs.put(id(plugin, key), new Def(plugin, key, kind, defaultValue, description));
    }

    private String raw(String id) {
        Def d = defs.get(id);
        String saved = data.isLoaded() ? data.read(s -> s.values.get(id)) : null;
        return saved != null ? saved : d == null ? "" : d.def;
    }

    /** Aplica a paleta ({primaria} etc.) e troca & por §. */
    String format(String text) {
        String out = text;
        if (out.indexOf('{') >= 0) { for (String p : PALETTE) { out = out.replace("{" + p + "}", raw(id(SERVER, "cor." + p))); } }
        return out.replace('&', '§');
    }

    @Override public String text(String plugin, String key) { return format(raw(id(plugin, key))); }

    @Override public long number(String plugin, String key) {
        try { return Long.parseLong(raw(id(plugin, key)).trim()); } catch (NumberFormatException e) { Def d = defs.get(id(plugin, key)); return d == null ? 0 : Long.parseLong(d.def); }
    }

    @Override public void watch(String plugin, Runnable onChange) { watchers.computeIfAbsent(plugin.toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(onChange); }

    private void changed(String plugin) {
        // A paleta e do servidor inteiro: muda-la reaplica tudo.
        if (plugin.equalsIgnoreCase(SERVER)) { changedAll(); return; }
        for (Runnable r : watchers.getOrDefault(plugin.toLowerCase(Locale.ROOT), Collections.emptyList())) { safe(r); }
    }

    private void changedAll() { for (List<Runnable> list : watchers.values()) { for (Runnable r : list) { safe(r); } } }

    private void safe(Runnable r) { try { r.run(); } catch (RuntimeException e) { core.getLogger().warning("Falha ao reaplicar configuracao: " + e.getMessage()); } }

    @Override public void alert(String source, String message) {
        String line = text(SERVER, "alerta.prefixo") + " §7" + source + ": §f" + message;
        for (Player p : core.getServer().getOnlinePlayers().values()) { if (p.hasPermission("goldcore.alertas")) { p.sendMessage(line); } }
        core.getLogger().warning("[Alerta] " + source + ": " + strip(message));
        String hook = data.isLoaded() ? data.read(s -> s.webhook) : "";
        if (!hook.isEmpty()) { discord.send(hook, strip(text(SERVER, "discord.nome")), "**" + strip(source) + "**: " + strip(message)); }
    }

    @Override public void discord(String source, String message) {
        String hook = data.isLoaded() ? data.read(s -> s.webhook) : "";
        if (!hook.isEmpty()) { discord.send(hook, strip(text(SERVER, "discord.nome")), "**" + strip(source) + "**: " + strip(message)); }
    }

    /** Chaves de API (nome com "chave") nunca aparecem inteiras no painel. */
    static boolean secret(Def d) { return d.key.contains("chave"); }

    String shown(Def d) {
        String v = raw(d.id());
        if (!secret(d)) { return format(v); }
        return v.isEmpty() ? "(vazia)" : v.length() <= 8 ? "****" : v.substring(0, 4) + "****" + v.substring(v.length() - 4);
    }

    static String strip(String text) { return text.replaceAll("§.", ""); }

    /** Confere o valor pelo tipo antes de gravar. */
    static String check(Def d, String value) {
        String v = value.trim();
        if (d.kind == Kind.NUMERO) {
            long n;
            try { n = Long.parseLong(v); } catch (NumberFormatException e) { throw new IllegalArgumentException(M.t("use_um_numero_inteiro")); }
            if (n < 0 || n > 1_000_000_000_000L) { throw new IllegalArgumentException(M.t("numero_fora_do_limite")); }
            return String.valueOf(n);
        }
        if (d.plugin.equals(SERVER) && d.key.startsWith("cor.") && !COLOR.matcher(v).matches()) { throw new IllegalArgumentException(M.t("cor_invalida_use_0_9_opcionalmente")); }
        if (v.isEmpty() || v.length() > 200) { throw new IllegalArgumentException(M.t("texto_de_1_200_caracteres")); }
        return v;
    }

    /** Grava (ou volta ao padrao com null) e reaplica no plugin dono. */
    void set(String id, String value, Consumer<String> done) {
        Def d = defs.get(id);
        if (d == null) { done.accept(M.t("configuracao_inexistente", id)); return; }
        String v;
        try { v = value == null ? null : check(d, value); } catch (IllegalArgumentException e) { done.accept("§c" + e.getMessage()); return; }
        data.change(s -> { if (v == null || v.equals(d.def)) { s.values.remove(d.id()); } else { s.values.put(d.id(), v); } }).whenComplete((x, e) -> {
            if (e != null) { done.accept(M.t("nao_foi_possivel_gravar", GoldCore.root(e))); return; }
            changed(d.plugin);
            done.accept("§a" + d.plugin + " / " + d.key + " = §r" + shown(d));
        });
    }

    // ---------- painel ----------

    private MenuService menus() { return core.getServer().getServiceManager().load(MenuService.class); }
    private static Item icon(int id, int meta, String name) { Item i = Item.get(id, meta, 1); i.setCustomName(name); return i; }

    private List<String> plugins() {
        TreeSet<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (Def d : defs.values()) { if (!d.plugin.equals(SERVER)) { names.add(d.plugin); } }
        List<String> out = new ArrayList<>(); out.add(SERVER); out.addAll(names);
        return out;
    }

    void open(Player p) {
        List<String> list = plugins();
        Map<Integer, Item> icons = new HashMap<>();
        for (int i = 0; i < list.size() && i < 21; i++) {
            String name = list.get(i);
            int count = 0; for (Def d : defs.values()) { if (d.plugin.equals(name)) { count++; } }
            icons.put(i, name.equals(SERVER) ? icon(351, 14, M.t("paleta_servidor_opcoes", count)) : icon(339, 0, "§e" + name + "\n§7" + count + " opcoes"));
        }
        boolean hook = data.isLoaded() && !data.read(s -> s.webhook).isEmpty();
        icons.put(22, icon(388, 0, M.t("discord_avisos_da_staff", (hook ? M.t("ligado") : M.t("desligado")))));
        menus().open(p, M.t("painel_do_servidor"), icons, (who, slot) -> {
            if (slot == 22) { discordMenu(who); }
            else if (slot < list.size() && slot < 21) { page(who, list.get(slot), 0); }
        });
    }

    private void page(Player p, String plugin, int page) {
        List<Def> list = new ArrayList<>();
        for (Def d : defs.values()) { if (d.plugin.equals(plugin)) { list.add(d); } }
        Map<Integer, Item> icons = new HashMap<>();
        int pages = Math.max(1, (list.size() + PER_PAGE - 1) / PER_PAGE);
        for (int i = 0; i < PER_PAGE && page * PER_PAGE + i < list.size(); i++) {
            Def d = list.get(page * PER_PAGE + i);
            boolean custom = data.isLoaded() && data.read(s -> s.values.containsKey(d.id()));
            icons.put(i, icon(d.kind == Kind.NUMERO ? 371 : d.key.startsWith("cor.") ? 351 : 339, d.key.startsWith("cor.") ? 14 : 0,
                    M.t("atual_toque_para_editar", d.key, (custom ? M.t("alterado") : ""), d.description, shown(d))));
        }
        if (page > 0) { icons.put(18, icon(262, 0, M.t("pagina_anterior"))); }
        if (page < pages - 1) { icons.put(26, icon(262, 0, M.t("proxima_pagina"))); }
        icons.put(22, icon(262, 0, M.t("voltar")));
        menus().open(p, plugin + " (" + (page + 1) + "/" + pages + ")", icons, (who, slot) -> {
            if (slot == 18 && page > 0) { page(who, plugin, page - 1); }
            else if (slot == 26 && page < pages - 1) { page(who, plugin, page + 1); }
            else if (slot == 22) { open(who); }
            else if (slot < PER_PAGE && page * PER_PAGE + slot < list.size()) { ask(who, list.get(page * PER_PAGE + slot).id()); }
        });
    }

    private void ask(Player p, String id) {
        Def d = defs.get(id);
        typing.put(p, new Object[] {id, System.currentTimeMillis() + 90_000});
        p.sendMessage(M.t("painel_digite_no_chat_novo_valor", d.plugin, d.key));
        p.sendMessage(M.t("atual_padrao", (secret(d) ? shown(d) : raw(id).replace('§', '&')), d.def));
        p.sendMessage(M.t("use_para_cores_primaria_secundaria_destaque"));
    }

    private void discordMenu(Player p) {
        Map<Integer, Item> icons = new HashMap<>();
        icons.put(11, icon(339, 0, M.t("definir_webhook_cole_url_do_webhook")));
        icons.put(13, icon(388, 0, M.t("enviar_teste")));
        icons.put(15, icon(35, 14, M.t("desligar")));
        icons.put(22, icon(262, 0, M.t("voltar")));
        menus().open(p, M.t("discord"), icons, (who, slot) -> {
            if (slot == 11) { typing.put(who, new Object[] {"#webhook", System.currentTimeMillis() + 90_000}); who.sendMessage(M.t("painel_cole_no_chat_url_do")); }
            else if (slot == 13) { alert("Painel", M.t("teste_de_aviso_enviado_por", who.getName())); who.sendMessage(M.t("painel_teste_enviado")); }
            else if (slot == 15) { setWebhook("", who::sendMessage); }
            else if (slot == 22) { open(who); }
        });
    }

    void setWebhook(String url, Consumer<String> done) {
        String u = url.trim();
        if (!DiscordWebhook.valid(u)) { done.accept(M.t("url_invalida_precisa_ser_https_discord")); return; }
        data.change(s -> s.webhook = u).whenComplete((v, e) -> done.accept(e != null ? M.t("nao_foi_possivel_gravar_2") : u.isEmpty() ? M.t("painel_discord_desligado") : M.t("painel_webhook_salvo_use_teste_no")));
    }

    /** O valor digitado no chat vai para a configuracao, nunca para o chat publico. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void chat(PlayerChatEvent e) {
        Object[] t = typing.get(e.getPlayer());
        if (t == null) { return; }
        e.setCancelled();
        typing.remove(e.getPlayer());
        Player p = e.getPlayer();
        String msg = e.getMessage();
        if ((Long) t[1] < System.currentTimeMillis() || msg.equalsIgnoreCase("cancelar") || !p.hasPermission("goldcore.painel")) { p.sendMessage(M.t("edicao_cancelada")); return; }
        String id = (String) t[0];
        if (id.equals("#webhook")) { setWebhook(msg, p::sendMessage); return; }
        set(id, msg.equalsIgnoreCase("padrao") ? null : msg, r -> { if (p.isOnline()) { p.sendMessage(M.t("painel", r)); page(p, defs.get(id).plugin, 0); } });
    }

    @EventHandler(priority = EventPriority.MONITOR) public void quit(PlayerQuitEvent e) { typing.remove(e.getPlayer()); }

    /** /painel [set <plugin> <chave> <valor>|ver <plugin>|discord <url|off|teste>] */
    boolean command(CommandSender sender, String[] args) {
        if (!sender.hasPermission("goldcore.painel")) { sender.sendMessage(M.t("sem_permissao")); return true; }
        if (args.length == 0) { if (sender instanceof Player) { open((Player) sender); } else { sender.sendMessage(M.t("painel_ver_plugin_set_plugin_chave")); } return true; }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "ver": {
                String plugin = args.length > 1 ? args[1] : SERVER;
                for (Def d : defs.values()) { if (d.plugin.equalsIgnoreCase(plugin)) { sender.sendMessage("§e" + d.key + " §7= §r" + shown(d) + " §8(" + d.description + ")"); } }
                return true;
            }
            case "set": {
                if (args.length < 4) { sender.sendMessage(M.t("uso_painel_set_plugin_chave_valor")); return true; }
                String value = String.join(" ", Arrays.copyOfRange(args, 3, args.length));
                Def d = defs.get(id(args[1], args[2]));
                set(d == null ? id(args[1], args[2]) : d.id(), value.equalsIgnoreCase("padrao") ? null : value, sender::sendMessage);
                return true;
            }
            case "discord": {
                if (args.length < 2) { sender.sendMessage(M.t("uso_painel_discord_url_off_teste")); return true; }
                if (args[1].equalsIgnoreCase("teste")) { alert("Painel", M.t("teste_de_aviso_enviado_por", sender.getName())); return true; }
                setWebhook(args[1].equalsIgnoreCase("off") ? "" : args[1], sender::sendMessage);
                return true;
            }
            case "msg": case "mensagem": messages(sender, args); return true;
            case "mensagens": {
                if (args.length > 1 && args[1].equalsIgnoreCase("recarregar")) {
                    int n = 0;
                    for (br.goldsuite.api.Messages m : br.goldsuite.api.Messages.all()) { m.reload(); n++; }
                    sender.sendMessage(M.t("msg_recarregadas", n));
                } else { messages(sender, new String[] {"msg"}); }
                return true;
            }
            default: sender.sendMessage(M.t("painel_painel_ver_plugin_painel_set")); return true;
        }
    }

    /** /painel msg [plugin] [chave|busca] [texto|padrao|pagina]: busca e edita as mensagens dos plugins. */
    private void messages(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(M.t("msg_lista_titulo"));
            for (br.goldsuite.api.Messages m : br.goldsuite.api.Messages.all()) {
                int changed = 0;
                for (String k : m.keys()) { if (m.customized(k)) { changed++; } }
                sender.sendMessage(M.t("msg_lista_linha", m.pluginName(), m.keys().size(), changed));
            }
            sender.sendMessage(M.t("msg_lista_uso"));
            return;
        }
        br.goldsuite.api.Messages m = br.goldsuite.api.Messages.of(args[1]);
        if (m == null) { sender.sendMessage(M.t("msg_plugin_invalido", args[1])); return; }
        if (args.length >= 3 && m.has(args[2])) {
            String key = args[2];
            if (args.length == 3) {
                sender.sendMessage(M.t("msg_chave", m.pluginName(), key));
                sender.sendMessage(M.t("msg_atual", m.raw(key)));
                if (m.customized(key)) { sender.sendMessage(M.t("msg_padrao", m.defaultRaw(key))); }
                if (m.hint(key) != null) { sender.sendMessage(M.t("msg_valores", m.hint(key))); }
                sender.sendMessage(M.t("msg_editar_uso", m.pluginName(), key));
                return;
            }
            String value = String.join(" ", Arrays.copyOfRange(args, 3, args.length));
            try {
                m.set(key, value.equalsIgnoreCase("padrao") ? null : value);
                sender.sendMessage(M.t("msg_salva", key));
                sender.sendMessage(m.t(key, "{0}", "{1}", "{2}", "{3}", "{4}", "{5}"));
            } catch (java.io.IOException | RuntimeException e) { sender.sendMessage(M.t("msg_falha_salvar", e.getMessage())); }
            return;
        }
        String term = args.length >= 3 ? args[2].toLowerCase(Locale.ROOT) : "";
        int page = 1;
        if (args.length >= 4) { try { page = Math.max(1, Integer.parseInt(args[3])); } catch (NumberFormatException ignored) { } }
        else if (args.length == 3 && args[2].matches("\\d+")) { page = Integer.parseInt(args[2]); term = ""; }
        List<String> found = new ArrayList<>();
        for (String k : m.keys()) {
            if (term.isEmpty() || k.contains(term) || m.raw(k).toLowerCase(Locale.ROOT).contains(term)) { found.add(k); }
        }
        int per = 8, pages = Math.max(1, (found.size() + per - 1) / per);
        page = Math.min(page, pages);
        sender.sendMessage(M.t("msg_busca_titulo", m.pluginName(), found.size(), page, pages));
        for (int i = (page - 1) * per; i < Math.min(found.size(), page * per); i++) {
            String k = found.get(i), raw = m.raw(k);
            sender.sendMessage(M.t("msg_busca_linha", k, raw.length() > 60 ? raw.substring(0, 60) + "..." : raw));
        }
        if (page < pages) { sender.sendMessage(M.t("msg_busca_mais", m.pluginName(), term.isEmpty() ? "" : term + " ", page + 1)); }
    }
}
