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
    static String PREFIX = M.t("kits");
    private PersistentState<KitState> data;
    /** Jogadores com resgate gravando; impede dois resgates no mesmo instante. */
    private final Set<String> claiming = new HashSet<>();

    @Override public void onEnable() {
        M.B.attach(this);
        Settings.prefix(this, PREFIX, v -> PREFIX = v);
        CoreService core = getServer().getServiceManager().load(CoreService.class);
        if (core == null) { throw new IllegalStateException(M.t("goldcore_precisa_estar_ativo_antes_do")); }
        data = new PersistentState<>(core.getStore(), "goldkits", KitState.class, KitState::new, KitState::validate);
    }

    @Override public void onDisable() { claiming.clear(); if (data != null) { data.close(); } }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) { sender.sendMessage(M.t("use_dentro_do_jogo")); return true; }
        Player p = (Player) sender;
        AuthService auth = getServer().getServiceManager().load(AuthService.class);
        if (auth != null && !auth.isAuthenticated(p)) { p.sendMessage(M.t("autentique_se_primeiro")); return true; }
        try {
            if (command.getName().equals("kits") || args.length == 0) { menu(p); return true; }
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "criar": {
                    admin(p);
                    if (args.length < 3) { p.sendMessage(PREFIX + M.t("uso_kit_criar_nome_minutos_de")); return true; }
                    String name = KitState.name(args[1]);
                    int minutes = Integer.parseInt(args[2]);
                    KitState.KitDef kit = new KitState.KitDef();
                    kit.cooldownMinutes = minutes;
                    kit.restricted = args.length > 3 && args[3].equalsIgnoreCase("restrito");
                    kit.items.addAll(ItemData.snapshot(p.getInventory().getContents(), 36).values());
                    for (Item armor : p.getInventory().getArmorContents()) { if (armor.getId() != 0) { kit.items.add(ItemData.of(armor)); } }
                    if (kit.items.isEmpty()) { throw new IllegalArgumentException(M.t("coloque_os_itens_do_kit_no")); }
                    save(p, data.change(s -> { if (s.kits.size() >= 54 && !s.kits.containsKey(name)) { throw new IllegalArgumentException(M.t("limite_de_kits")); } s.kits.put(name, kit); }),
                            M.t("kit_salvo_com_itens", name, kit.items.size(), (kit.restricted ? M.t("exige_goldkits_kit", name) : "")));
                    return true;
                }
                case "apagar": {
                    admin(p);
                    String name = KitState.name(args.length > 1 ? args[1] : "");
                    save(p, data.change(s -> { if (s.kits.remove(name) == null) { throw new IllegalArgumentException(M.t("kit_inexistente")); } }), M.t("kit_apagado"));
                    return true;
                }
                default: claim(p, KitState.name(args[0])); return true;
            }
        } catch (NumberFormatException e) { p.sendMessage(PREFIX + M.t("numero_invalido")); return true; }
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
                String state = !allowed(p, e.getKey(), kit) ? M.t("sem_permissao_2") : until != null && until > now ? M.t("disponivel_em_2", time(until - now)) : M.t("disponivel");
                icon.setCustomName(M.t("itens_recarga", e.getKey(), kit.items.size(), time(kit.cooldownMinutes * 60_000L), state));
                icons.put(slot++, icon);
                names.add(e.getKey());
            }
            return null;
        });
        if (names.isEmpty()) { p.sendMessage(PREFIX + M.t("nenhum_kit_criado_ainda")); return; }
        menus.open(p, M.t("kits_2"), icons, (who2, slot) -> { if (slot < names.size()) { try { claim(who2, names.get(slot)); } catch (RuntimeException e) { who2.sendMessage(PREFIX + "§c" + root(e)); } } });
    }

    private void claim(Player p, String name) {
        KitState.KitDef kit = data.read(s -> s.kits.get(name));
        if (kit == null) { throw new IllegalArgumentException(M.t("kit_inexistente_use_kits")); }
        if (!allowed(p, name, kit)) { throw new IllegalArgumentException(M.t("voce_nao_tem_permissao_para_este")); }
        String who = p.getName().toLowerCase(Locale.ROOT), key = who + ":" + name;
        long now = System.currentTimeMillis();
        Long until = data.read(s -> s.cooldowns.get(key));
        if (until != null && until > now) { throw new IllegalArgumentException(M.t("disponivel_em", time(until - now))); }
        int free = 0;
        for (int i = 0; i < 36; i++) { if (p.getInventory().getItem(i).getId() == 0) { free++; } }
        if (free < kit.items.size()) { throw new IllegalArgumentException(M.t("libere_espacos_no_inventario", kit.items.size())); }
        if (!claiming.add(who)) { return; }
        long next = now + kit.cooldownMinutes * 60_000L;
        // A recarga e gravada antes da entrega: um travamento nunca entrega o kit duas vezes.
        data.change(s -> { s.prune(now); Long current = s.cooldowns.get(key); if (current != null && current > now) { throw new IllegalArgumentException(M.t("kit_ja_resgatado")); } if (kit.cooldownMinutes > 0) { s.cooldowns.put(key, next); } })
                .whenComplete((v, e) -> {
                    claiming.remove(who);
                    if (!isEnabled() || !p.isOnline()) { return; }
                    if (e != null) { p.sendMessage(PREFIX + "§c" + root(e)); return; }
                    for (ItemData item : kit.items) {
                        Item[] left = p.getInventory().addItem(item.toItem());
                        for (Item rest : left) { p.level.dropItem(p, rest); }
                    }
                    p.sendMessage(PREFIX + M.t("kit_recebido", name));
                });
    }

    private static boolean allowed(Player p, String name, KitState.KitDef kit) { return !kit.restricted || p.hasPermission("goldkits.kit." + name) || p.hasPermission("goldkits.admin"); }

    private static void admin(Player p) { if (!p.hasPermission("goldkits.admin")) { throw new IllegalArgumentException(M.t("sem_permissao")); } }

    static String time(long millis) {
        long minutes = (millis + 59_999) / 60_000;
        if (minutes == 0) { return M.t("sem_recarga"); }
        if (minutes < 60) { return minutes + " min"; }
        if (minutes < 1440) { return (minutes / 60) + " h " + (minutes % 60) + " min"; }
        return (minutes / 1440) + " d " + ((minutes % 1440) / 60) + " h";
    }

    private void save(Player p, java.util.concurrent.CompletableFuture<?> op, String message) {
        op.whenComplete((v, e) -> { if (isEnabled() && p.isOnline()) { p.sendMessage(PREFIX + (e != null ? "§c" + root(e) : message)); } });
    }

    static String root(Throwable e) { while (e.getCause() != null) { e = e.getCause(); } return e.getMessage(); }
}
