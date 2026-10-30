package br.goldsuite.vault;

import br.goldsuite.api.*;
import cn.nukkit.Player;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.player.PlayerQuitEvent;
import cn.nukkit.item.Item;
import cn.nukkit.plugin.PluginBase;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.*;

/** Bau pessoal (EnderChest) gravado a cada mudanca, sem ficar em memoria depois de fechado. */
public final class GoldVault extends PluginBase implements Listener {
    static final String PREFIX = "§5[§dBau§5]§f ";
    static final String NAMESPACE = "goldvault";
    private static final Gson GSON = new Gson();
    private static final Type TYPE = new TypeToken<TreeMap<Integer, ItemData>>() { }.getType();
    private CoreService core;
    /** Donos com bau aberto (por qualquer um): um bau nunca fica aberto em duas janelas. */
    private final Set<String> open = new HashSet<>();
    /** Quem esta com qual bau aberto; liberado quando a janela fecha. */
    private final Map<Player, String> viewing = new IdentityHashMap<>();

    @Override public void onEnable() {
        core = getServer().getServiceManager().load(CoreService.class);
        if (core == null) { throw new IllegalStateException("GoldCore precisa estar ativo antes do GoldVault."); }
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getScheduler().scheduleRepeatingTask(new cn.nukkit.scheduler.PluginTask<GoldVault>(this) { @Override public void onRun(int tick) { release(); } }, 20);
    }

    @Override public void onDisable() { open.clear(); viewing.clear(); }

    private void release() {
        if (viewing.isEmpty()) { return; }
        MenuService menus = getServer().getServiceManager().load(MenuService.class);
        viewing.entrySet().removeIf(e -> {
            if (e.getKey().isOnline() && menus != null && menus.isOpen(e.getKey())) { return false; }
            open.remove(e.getValue());
            return true;
        });
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) { sender.sendMessage("Use dentro do jogo."); return true; }
        Player viewer = (Player) sender;
        AuthService auth = getServer().getServiceManager().load(AuthService.class);
        if (auth != null && !auth.isAuthenticated(viewer)) { viewer.sendMessage("Autentique-se primeiro."); return true; }
        String owner = (args.length > 0 && viewer.hasPermission("goldvault.admin") ? args[0] : viewer.getName()).toLowerCase(Locale.ROOT);
        if (!owner.matches("[a-z0-9_]{3,16}")) { viewer.sendMessage(PREFIX + "§cJogador invalido."); return true; }
        if (!open.add(owner)) { viewer.sendMessage(PREFIX + "§cEste bau ja esta aberto."); return true; }
        core.getStore().get(NAMESPACE, owner).whenComplete((value, error) -> {
            if (!isEnabled() || !viewer.isOnline()) { open.remove(owner); return; }
            if (error != null) { open.remove(owner); viewer.sendMessage(PREFIX + "§cNao foi possivel abrir o bau."); return; }
            Map<Integer, Item> items;
            try { items = decode(value.orElse("{}")); }
            catch (RuntimeException invalid) { open.remove(owner); getLogger().warning("Bau corrompido de " + owner + "; nao aberto para nao sobrescrever."); viewer.sendMessage(PREFIX + "§cBau com dados invalidos; fale com a staff."); return; }
            MenuService menus = getServer().getServiceManager().load(MenuService.class);
            boolean shown = menus.openStorage(viewer, owner.equals(viewer.getName().toLowerCase(Locale.ROOT)) ? "Seu bau" : "Bau de " + owner, items, contents -> save(owner, contents));
            if (shown) { viewing.put(viewer, owner); } else { open.remove(owner); }
        });
        return true;
    }

    /** Chamado no tick seguinte a cada mudanca e ao fechar; a fila do GoldCore mantem a ordem das gravacoes. */
    private void save(String owner, Map<Integer, Item> contents) {
        String json = encode(contents);
        core.getStore().put(NAMESPACE, owner, json).whenComplete((v, e) -> { if (e != null) { getLogger().error("Falha ao gravar o bau de " + owner, e); } });
    }

    /** A janela grava o conteudo ao fechar; aqui so libera a trava. */
    @EventHandler(priority = EventPriority.MONITOR) public void quit(PlayerQuitEvent event) {
        String owner = viewing.remove(event.getPlayer());
        if (owner != null) { open.remove(owner); }
    }

    static String encode(Map<Integer, Item> contents) { return GSON.toJson(ItemData.snapshot(contents, MenuService.SIZE), TYPE); }

    static Map<Integer, Item> decode(String json) {
        Map<Integer, ItemData> stored = GSON.fromJson(json, TYPE);
        Map<Integer, Item> items = new HashMap<>();
        if (stored == null) { return items; }
        for (Map.Entry<Integer, ItemData> e : stored.entrySet()) {
            if (e.getKey() == null || e.getKey() < 0 || e.getKey() >= MenuService.SIZE || e.getValue() == null) { throw new IllegalArgumentException("Slot invalido."); }
            e.getValue().validate();
            items.put(e.getKey(), e.getValue().toItem());
        }
        return items;
    }
}
