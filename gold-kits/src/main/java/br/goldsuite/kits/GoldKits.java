package br.goldsuite.kits;

import br.goldsuite.api.*;
import cn.nukkit.Player;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.item.Item;
import cn.nukkit.plugin.PluginBase;
import java.util.*;

/** Kits com recarga, criados a partir do inventario do admin (AdvancedKits). */
public final class GoldKits extends PluginBase {
    static final String PREFIX = "§a[§2Kits§a]§f ";
    private PersistentState<KitState> data;
    /** Jogadores com resgate gravando; impede dois resgates no mesmo instante. */
    private final Set<String> claiming = new HashSet<>();

    @Override public void onEnable() {
        CoreService core = getServer().getServiceManager().load(CoreService.class);
        if (core == null) { throw new IllegalStateException("GoldCore precisa estar ativo antes do GoldKits."); }
        data = new PersistentState<>(core.getStore(), "goldkits", KitState.class, KitState::new, KitState::validate);
    }

    @Override public void onDisable() { claiming.clear(); if (data != null) { data.close(); } }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) { sender.sendMessage("Use dentro do jogo."); return true; }
        Player p = (Player) sender;
        AuthService auth = getServer().getServiceManager().load(AuthService.class);
        if (auth != null && !auth.isAuthenticated(p)) { p.sendMessage("Autentique-se primeiro."); return true; }
        try {
            if (command.getName().equals("kits") || args.length == 0) { menu(p); return true; }
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "criar": {
                    admin(p);
                    if (args.length < 3) { p.sendMessage(PREFIX + "Uso: /kit criar <nome> <minutos de recarga> [restrito]"); return true; }
                    String name = KitState.name(args[1]);
                    int minutes = Integer.parseInt(args[2]);
                    KitState.KitDef kit = new KitState.KitDef();
                    kit.cooldownMinutes = minutes;
                    kit.restricted = args.length > 3 && args[3].equalsIgnoreCase("restrito");
                    kit.items.addAll(ItemData.snapshot(p.getInventory().getContents(), 36).values());
                    for (Item armor : p.getInventory().getArmorContents()) { if (armor.getId() != 0) { kit.items.add(ItemData.of(armor)); } }
                    if (kit.items.isEmpty()) { throw new IllegalArgumentException("Coloque os itens do kit no seu inventario primeiro."); }
                    save(p, data.change(s -> { if (s.kits.size() >= 54 && !s.kits.containsKey(name)) { throw new IllegalArgumentException("Limite de kits."); } s.kits.put(name, kit); }),
                            "Kit " + name + " salvo com " + kit.items.size() + " itens" + (kit.restricted ? " (exige goldkits.kit." + name + ")" : "") + ".");
                    return true;
                }
                case "apagar": {
                    admin(p);
                    String name = KitState.name(args.length > 1 ? args[1] : "");
                    save(p, data.change(s -> { if (s.kits.remove(name) == null) { throw new IllegalArgumentException("Kit inexistente."); } }), "Kit apagado.");
                    return true;
                }
                default: claim(p, KitState.name(args[0])); return true;
            }
        } catch (NumberFormatException e) { p.sendMessage(PREFIX + "§cNumero invalido."); return true; }
        catch (RuntimeException e) { p.sendMessage(PREFIX + "§c" + root(e)); return true; }
    }

    private void menu(Player p) {
        MenuService menus = getServer().getServiceManager().load(MenuService.class);
        List<String> names = new ArrayList<>();
        Map<Integer, Item> icons = new HashMap<>();
        long now = System.currentTimeMillis();
        String who = p.getName().toLowerCase(Locale.ROOT);
        data.read(s -> {
            int slot = 0;
            for (Map.Entry<String, KitState.KitDef> e : s.kits.entrySet()) {
                if (slot >= MenuService.SIZE) { break; }
                KitState.KitDef kit = e.getValue();
                Item icon = kit.items.get(0).toItem();
                icon.setCount(1);
                Long until = s.cooldowns.get(who + ":" + e.getKey());
                String state = !allowed(p, e.getKey(), kit) ? "§cSem permissao" : until != null && until > now ? "§cDisponivel em " + time(until - now) : "§aDisponivel";
                icon.setCustomName("§e" + e.getKey() + "\n§7" + kit.items.size() + " itens | recarga " + time(kit.cooldownMinutes * 60_000L) + "\n" + state);
                icons.put(slot++, icon);
                names.add(e.getKey());
            }
            return null;
        });
        if (names.isEmpty()) { p.sendMessage(PREFIX + "Nenhum kit criado ainda."); return; }
        menus.open(p, "Kits", icons, (who2, slot) -> { if (slot < names.size()) { try { claim(who2, names.get(slot)); } catch (RuntimeException e) { who2.sendMessage(PREFIX + "§c" + root(e)); } } });
    }

    private void claim(Player p, String name) {
        KitState.KitDef kit = data.read(s -> s.kits.get(name));
        if (kit == null) { throw new IllegalArgumentException("Kit inexistente. Use /kits."); }
        if (!allowed(p, name, kit)) { throw new IllegalArgumentException("Voce nao tem permissao para este kit."); }
        String who = p.getName().toLowerCase(Locale.ROOT), key = who + ":" + name;
        long now = System.currentTimeMillis();
        Long until = data.read(s -> s.cooldowns.get(key));
        if (until != null && until > now) { throw new IllegalArgumentException("Disponivel em " + time(until - now) + "."); }
        int free = 0;
        for (int i = 0; i < 36; i++) { if (p.getInventory().getItem(i).getId() == 0) { free++; } }
        if (free < kit.items.size()) { throw new IllegalArgumentException("Libere " + kit.items.size() + " espacos no inventario."); }
        if (!claiming.add(who)) { return; }
        long next = now + kit.cooldownMinutes * 60_000L;
        // A recarga e gravada antes da entrega: um travamento nunca entrega o kit duas vezes.
        data.change(s -> { s.prune(now); Long current = s.cooldowns.get(key); if (current != null && current > now) { throw new IllegalArgumentException("Kit ja resgatado."); } if (kit.cooldownMinutes > 0) { s.cooldowns.put(key, next); } })
                .whenComplete((v, e) -> {
                    claiming.remove(who);
                    if (!isEnabled() || !p.isOnline()) { return; }
                    if (e != null) { p.sendMessage(PREFIX + "§c" + root(e)); return; }
                    for (ItemData item : kit.items) {
                        Item[] left = p.getInventory().addItem(item.toItem());
                        for (Item rest : left) { p.level.dropItem(p, rest); }
                    }
                    p.sendMessage(PREFIX + "Kit §e" + name + "§f recebido!");
                });
    }

    private static boolean allowed(Player p, String name, KitState.KitDef kit) { return !kit.restricted || p.hasPermission("goldkits.kit." + name) || p.hasPermission("goldkits.admin"); }

    private static void admin(Player p) { if (!p.hasPermission("goldkits.admin")) { throw new IllegalArgumentException("Sem permissao."); } }

    static String time(long millis) {
        long minutes = (millis + 59_999) / 60_000;
        if (minutes == 0) { return "sem recarga"; }
        if (minutes < 60) { return minutes + " min"; }
        if (minutes < 1440) { return (minutes / 60) + " h " + (minutes % 60) + " min"; }
        return (minutes / 1440) + " d " + ((minutes % 1440) / 60) + " h";
    }

    private void save(Player p, java.util.concurrent.CompletableFuture<?> op, String message) {
        op.whenComplete((v, e) -> { if (isEnabled() && p.isOnline()) { p.sendMessage(PREFIX + (e != null ? "§c" + root(e) : message)); } });
    }

    static String root(Throwable e) { while (e.getCause() != null) { e = e.getCause(); } return e.getMessage(); }
}
