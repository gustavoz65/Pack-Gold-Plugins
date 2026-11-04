package br.goldsuite.staff;

import br.goldsuite.api.*;
import br.goldsuite.api.event.PlayerAuthEvent;
import cn.nukkit.Player;
import cn.nukkit.command.CommandSender;
import cn.nukkit.event.*;
import cn.nukkit.event.player.*;
import cn.nukkit.item.Item;
import cn.nukkit.utils.Config;
import java.util.*;

/** Livro adaptado a menus de 27 slots e chat do protocolo 84. Sem pacotes de livros inexistentes. */
final class Welcome implements Listener {
    private final GoldStaff plugin;
    private final Set<Player> shown = Collections.newSetFromMap(new IdentityHashMap<>());
    private Config config;
    private boolean enabled, hideJoin;
    private String ip, support;
    private List<String> pages;
    Welcome(GoldStaff plugin) { this.plugin = plugin; plugin.saveResource("config.yml", false); reload(); }
    void reload() {
        Config next = new Config(new java.io.File(plugin.getDataFolder(), "config.yml"), Config.YAML);
        List<String> nextPages = next.getStringList("boasvindas.paginas");
        if (nextPages.size() > 12) { throw new IllegalArgumentException(M.t("boasvindas_config_invalida")); }
        for (String page : nextPages) { if (page.length() > 3000) { throw new IllegalArgumentException(M.t("boasvindas_config_invalida")); } }
        String cmd = next.getString("boasvindas.suporte", "suporte").replaceFirst("^/", "");
        if (!cmd.matches("[a-zA-Z0-9_:-]+( [^\\r\\n]{1,100})?")) { throw new IllegalArgumentException(M.t("boasvindas_config_invalida")); }
        config = next; pages = nextPages; support = cmd;
        enabled = next.getBoolean("boasvindas.ativo", true);
        hideJoin = next.getBoolean("boasvindas.ocultar_entrada", true);
        ip = next.getString("boasvindas.ip", "seuservidor.com");
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void join(PlayerJoinEvent e) {
        if (hideJoin) { e.setJoinMessage(""); }
        if (plugin.getServer().getServiceManager().load(AuthService.class) == null) { schedule(e.getPlayer(), 0); }
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void auth(PlayerAuthEvent e) { schedule(e.getPlayer(), 0); }
    @EventHandler public void quit(PlayerQuitEvent e) { shown.remove(e.getPlayer()); }
    private void schedule(Player p, int attempt) {
        if (!enabled || shown.contains(p) || attempt > 10) { return; }
        plugin.getServer().getScheduler().scheduleDelayedTask(new cn.nukkit.scheduler.PluginTask<GoldStaff>(plugin) {
            @Override public void onRun(int tick) {
                if (!p.isOnline() || !plugin.isEnabled() || shown.contains(p)) { return; }
                AuthService auth = plugin.getServer().getServiceManager().load(AuthService.class);
                if (auth != null && !auth.isAuthenticated(p)) { return; }
                MenuService menus = plugin.getServer().getServiceManager().load(MenuService.class);
                if (!p.spawned || menus == null || menus.isOpen(p)) { schedule(p, attempt + 1); return; }
                shown.add(p); open(p, 0);
            }
        }, 20);
    }
    private String page(Player p, int index) {
        String text = pages.isEmpty() ? M.t(index == 0 ? "boasvindas_p1" : index == 1 ? "boasvindas_p2" : "boasvindas_p3") : pages.get(index);
        return text.replace('&', '§').replace("%player%", p.getName()).replace("{ip}", ip).replace("{suporte}", "/" + support);
    }
    void open(Player p, int index) {
        AuthService auth = plugin.getServer().getServiceManager().load(AuthService.class);
        if (auth != null && !auth.isAuthenticated(p)) { return; }
        int count = pages.isEmpty() ? 3 : pages.size();
        int selected = Math.max(0, Math.min(count - 1, index));
        String text = page(p, selected);
        for (String line : text.split("\\n")) { p.sendMessage(line); }
        MenuService menus = plugin.getServer().getServiceManager().load(MenuService.class);
        if (menus == null) { return; }
        Map<Integer, Item> icons = new HashMap<>();
        icons.put(13, icon(340, M.t("boasvindas_pagina", selected + 1, count), text));
        if (selected > 0) { icons.put(18, icon(262, M.t("boasvindas_anterior"), "")); }
        if (selected + 1 < count) { icons.put(26, icon(262, M.t("boasvindas_proxima"), "")); }
        icons.put(22, icon(339, M.t("boasvindas_suporte"), "/" + support));
        menus.open(p, M.t("boasvindas_titulo"), icons, (who, slot) -> {
            if (slot == 18 && selected > 0) { open(who, selected - 1); }
            else if (slot == 26 && selected + 1 < count) { open(who, selected + 1); }
            else if (slot == 22) {
                // Mesmo caminho do chat: preserva bloqueios e autenticacao dos outros plugins.
                PlayerCommandPreprocessEvent e = new PlayerCommandPreprocessEvent(who, "/" + support);
                plugin.getServer().getPluginManager().callEvent(e);
                if (!e.isCancelled()) { plugin.getServer().dispatchCommand(who, e.getMessage().replaceFirst("^/", "")); }
            }
        });
    }
    static Item icon(int id, String name, String lore) {
        Item it = Item.get(id, 0, 1); it.setCustomName(name);
        if (!lore.isEmpty()) { it.setCustomName(name + "\n" + lore); } return it;
    }
    boolean command(CommandSender sender, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("welcomebook.admin")) { sender.sendMessage(M.t("punicao_sem_permissao")); return true; }
            try { reload(); sender.sendMessage(M.t("boasvindas_recarregado")); }
            catch (RuntimeException error) { sender.sendMessage(M.t("boasvindas_config_invalida")); }
        } else if (sender instanceof Player) { open((Player) sender, 0); }
        return true;
    }
    void close() { shown.clear(); }
}

