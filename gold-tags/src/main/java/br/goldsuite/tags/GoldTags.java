package br.goldsuite.tags;

import br.goldsuite.api.*;
import cn.nukkit.Player;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.player.PlayerJoinEvent;
import cn.nukkit.event.player.PlayerQuitEvent;
import cn.nukkit.item.Item;
import cn.nukkit.plugin.PluginBase;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Selecionar sua tag: cada jogador guarda as tags que ja tem (Jogador, a do cargo enquanto tiver o cargo e as ganhas em
 * eventos) e escolhe qual aparece. Trocar de tag nao muda cargo nem permissao: o VIP que escolhe "Jogador" continua VIP.
 * So os jogadores online ficam em memoria; cada mudanca grava uma linha no SQLite do GoldCore.
 */
public final class GoldTags extends PluginBase implements Listener, TagService {
    static String PREFIX = M.t("tags");
    static final String PLAYERS = "goldtags", CATALOG = "goldtags-catalogo";
    private static final Set<String> RESERVED = new HashSet<>(Arrays.asList("criar", "apagar", "dar", "tirar", "lista", "ver", "automatica", "auto"));
    private CoreService core;
    /** Tags de evento: id -> texto com &. */
    private final Map<String, String> catalog = new TreeMap<>();
    private final Map<String, TagData> online = new HashMap<>();
    private String defaultTag = "&7[Jogador]";

    @Override public void onEnable() {
        M.B.attach(this);
        Settings.prefix(this, PREFIX, v -> PREFIX = v);
        Settings.text(this, "tag_padrao", defaultTag, M.t("padrao_descricao"), v -> defaultTag = v.replace('§', '&'));
        core = getServer().getServiceManager().load(CoreService.class);
        if (core == null) { throw new IllegalStateException("GoldCore"); }
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getServiceManager().register(TagService.class, this, this);
        core.getStore().all(CATALOG).whenComplete((all, e) -> { if (e == null && all != null) { catalog.putAll(all); } });
        for (Player p : getServer().getOnlinePlayers().values()) { load(p); }
    }

    @Override public void onDisable() { online.clear(); }

    private static String key(String name) { return name.toLowerCase(Locale.ROOT); }
    private static String color(String text) { return text.replace('&', '§'); }
    private PermissionService perms() { return getServer().getServiceManager().load(PermissionService.class); }

    @EventHandler(priority = EventPriority.LOW) public void join(PlayerJoinEvent e) { load(e.getPlayer()); }
    @EventHandler(priority = EventPriority.MONITOR) public void quit(PlayerQuitEvent e) { online.remove(key(e.getPlayer().getName())); }

    private void load(Player p) {
        String name = key(p.getName());
        core.getStore().get(PLAYERS, name).whenComplete((value, e) -> {
            if (e == null && p.isOnline()) { online.put(name, TagData.decode(value.orElse(""))); }
        });
    }

    // ---------- TagService (usado pelo GoldPerms no {tag}) ----------

    @Override public String chosen(String player) {
        TagData d = online.get(key(player));
        if (d == null || d.selected.isEmpty()) { return null; }
        String text = textOf(player, d, d.selected, System.currentTimeMillis());
        return text == null ? null : color(text);
    }

    @Override public String fallback() { return color(defaultTag); }

    /** Texto da tag se o jogador ainda tem direito a ela (a do cargo so enquanto tiver o cargo). */
    private String textOf(String player, TagData d, String id, long now) {
        if (id.equals(TagData.DEFAULT_ID)) { return defaultTag; }
        PermissionService perms = perms();
        if (perms != null && id.equals(perms.getGroup(player))) { String g = perms.getGroupTag(player); return g.isEmpty() ? null : g.replace('§', '&'); }
        return d.owns(id, now) ? catalog.get(id) : null;
    }

    /** Todas as tags que o jogador pode usar agora: id -> texto com &. */
    private LinkedHashMap<String, String> owned(String player, TagData d) {
        long now = System.currentTimeMillis();
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        out.put(TagData.DEFAULT_ID, defaultTag);
        PermissionService perms = perms();
        if (perms != null) {
            String group = perms.getGroup(player), tag = perms.getGroupTag(player);
            if (!tag.isEmpty()) { out.put(group, tag.replace('§', '&')); }
        }
        for (String id : d.owned.keySet()) { if (d.owns(id, now) && catalog.containsKey(id)) { out.put(id, catalog.get(id)); } }
        return out;
    }

    private String usingNow(String player, TagData d) {
        String c = chosen(player);
        return c != null ? c : M.t("automatica");
    }

    // ---------- comando ----------

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (sender.hasPermission("goldtags.admin") && RESERVED.contains(sub) && !sub.startsWith("auto")) {
            try { admin(sender, sub, args); } catch (RuntimeException e) { sender.sendMessage(PREFIX + "§c" + e.getMessage()); }
            return true;
        }
        if (!(sender instanceof Player)) { sender.sendMessage(M.t("use_dentro_do_jogo")); return true; }
        Player p = (Player) sender;
        TagData d = online.get(key(p.getName()));
        if (d == null) { p.sendMessage(PREFIX + M.t("carregando")); return true; }
        if (sub.isEmpty()) { list(p, d); openMenu(p, d); return true; }
        if (sub.startsWith("auto")) { select(p, d, ""); return true; }
        String typed = String.join(" ", args);
        for (Map.Entry<String, String> e : owned(p.getName(), d).entrySet()) {
            if (e.getKey().equalsIgnoreCase(typed) || TagData.plain(e.getValue()).equalsIgnoreCase(typed)) { select(p, d, e.getKey()); return true; }
        }
        p.sendMessage(PREFIX + M.t("nao_tem", typed));
        return true;
    }

    private void list(Player p, TagData d) {
        StringBuilder names = new StringBuilder();
        for (String text : owned(p.getName(), d).values()) { names.append(names.length() == 0 ? "" : "§7, ").append(color(text)); }
        p.sendMessage(PREFIX + M.t("suas_tags", names.toString(), usingNow(p.getName(), d)));
        p.sendMessage(PREFIX + M.t("dica"));
    }

    private void select(Player p, TagData d, String id) {
        d.selected = id;
        save(key(p.getName()), d).whenComplete((v, e) -> {
            if (!p.isOnline()) { return; }
            if (e != null) { p.sendMessage(PREFIX + M.t("falha")); return; }
            p.sendMessage(PREFIX + (id.isEmpty() ? M.t("voltou_automatica") : M.t("voce_usa", color(textOf(p.getName(), d, id, System.currentTimeMillis())))));
        });
    }

    private CompletableFuture<Void> save(String name, TagData d) {
        d.prune(System.currentTimeMillis());
        return core.getStore().put(PLAYERS, name, d.encode());
    }

    /** Menu para o celular: cada tag e um botao, a usada aparece marcada; o ultimo slot volta para a automatica. */
    private void openMenu(Player p, TagData d) {
        MenuService menus = getServer().getServiceManager().load(MenuService.class);
        if (menus == null) { return; }
        List<String> ids = new ArrayList<>();
        Map<Integer, Item> icons = new HashMap<>();
        String current = d.selected;
        for (Map.Entry<String, String> e : owned(p.getName(), d).entrySet()) {
            if (ids.size() >= 25) { break; }
            Item tag = Item.get(421, 0, 1);
            tag.setCustomName(e.getKey().equals(current) ? M.t("menu_usando", color(e.getValue())) : M.t("menu_tocar", color(e.getValue())));
            icons.put(ids.size(), tag);
            ids.add(e.getKey());
        }
        Item auto = Item.get(340, 0, 1);
        auto.setCustomName(current.isEmpty() ? M.t("menu_usando", M.t("menu_automatica")) : M.t("menu_automatica"));
        icons.put(26, auto);
        menus.open(p, M.t("menu_titulo"), icons, (who, slot) -> {
            TagData now = online.get(key(who.getName()));
            if (now == null) { return; }
            if (slot == 26) { select(who, now, ""); }
            else if (slot >= 0 && slot < ids.size()) { select(who, now, ids.get(slot)); }
        });
    }

    // ---------- staff ----------

    private void admin(CommandSender sender, String sub, String[] args) {
        switch (sub) {
            case "criar": {
                if (args.length < 3) { sender.sendMessage(PREFIX + M.t("uso_admin")); return; }
                String id = TagData.id(args[1]);
                if (RESERVED.contains(id) || id.equals(TagData.DEFAULT_ID)) { throw new IllegalArgumentException(M.t("reservado")); }
                String text = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
                // Vale na hora (um /tag dar logo em seguida ja acha a tag); se a gravacao falhar, volta como estava.
                String before = catalog.put(id, text);
                core.getStore().put(CATALOG, id, text).whenComplete((v, e) -> {
                    if (e != null) { if (before == null) { catalog.remove(id); } else { catalog.put(id, before); } sender.sendMessage(PREFIX + M.t("falha")); return; }
                    sender.sendMessage(PREFIX + M.t("criada", id, color(text)));
                });
                return;
            }
            case "apagar": {
                String id = TagData.id(args.length > 1 ? args[1] : "");
                if (!catalog.containsKey(id)) { throw new IllegalArgumentException(M.t("inexistente")); }
                String before = catalog.remove(id);
                core.getStore().delete(CATALOG, id).whenComplete((v, e) -> {
                    if (e != null) { catalog.put(id, before); sender.sendMessage(PREFIX + M.t("falha")); return; }
                    sender.sendMessage(PREFIX + M.t("apagada", id));
                });
                return;
            }
            case "dar": case "tirar": {
                if (args.length < 3) { sender.sendMessage(PREFIX + M.t("uso_admin")); return; }
                String name = key(args[1]);
                if (!name.matches("[a-z0-9_]{3,16}")) { throw new IllegalArgumentException(M.t("jogador_invalido")); }
                String id = TagData.id(args[2]);
                if (!catalog.containsKey(id)) { throw new IllegalArgumentException(M.t("inexistente")); }
                boolean give = sub.equals("dar");
                int days = give && args.length > 3 ? Integer.parseInt(args[3]) : 0;
                long until = days <= 0 ? 0 : System.currentTimeMillis() + days * 86_400_000L;
                String period = days <= 0 ? M.t("para_sempre") : M.t("por_dias", days);
                modify(name, d -> { if (give) { d.owned.put(id, until); } else { d.owned.remove(id); if (d.selected.equals(id)) { d.selected = ""; } } })
                        .whenComplete((v, e) -> {
                            if (e != null) { sender.sendMessage(PREFIX + M.t("falha")); return; }
                            sender.sendMessage(PREFIX + (give ? M.t("deu", name, color(catalog.get(id)), period) : M.t("tirou", name, color(catalog.get(id)))));
                            Player target = getServer().getPlayerExact(name);
                            if (give && target != null) { target.sendMessage(PREFIX + M.t("ganhou", color(catalog.get(id)), period)); }
                        });
                return;
            }
            case "lista": {
                sender.sendMessage(M.t("catalogo_titulo"));
                if (catalog.isEmpty()) { sender.sendMessage(M.t("catalogo_vazio")); }
                for (Map.Entry<String, String> e : catalog.entrySet()) { sender.sendMessage(M.t("catalogo_linha", e.getKey(), color(e.getValue()))); }
                return;
            }
            case "ver": {
                String name = key(args.length > 1 ? args[1] : "");
                if (!name.matches("[a-z0-9_]{3,16}")) { throw new IllegalArgumentException(M.t("jogador_invalido")); }
                read(name).whenComplete((d, e) -> {
                    if (e != null) { sender.sendMessage(PREFIX + M.t("falha")); return; }
                    StringBuilder names = new StringBuilder();
                    for (String text : owned(name, d).values()) { names.append(names.length() == 0 ? "" : "§7, ").append(color(text)); }
                    String sel = d.selected.isEmpty() ? M.t("automatica") : String.valueOf(textOf(name, d, d.selected, System.currentTimeMillis()));
                    sender.sendMessage(M.t("ver", name, names.toString(), color(sel)));
                });
                return;
            }
            default: sender.sendMessage(PREFIX + M.t("uso_admin"));
        }
    }

    /** Dados de qualquer jogador: do cache se online, do banco se offline. */
    private CompletableFuture<TagData> read(String name) {
        TagData d = online.get(name);
        if (d != null) { return CompletableFuture.completedFuture(d); }
        return core.getStore().get(PLAYERS, name).thenApply(v -> TagData.decode(v.orElse("")));
    }

    private CompletableFuture<Void> modify(String name, java.util.function.Consumer<TagData> change) {
        return read(name).thenCompose(d -> { change.accept(d); return save(name, d); });
    }
}
