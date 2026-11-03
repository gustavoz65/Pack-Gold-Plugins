package br.goldsuite.enchant;

import br.goldsuite.api.*;
import br.goldsuite.api.EconomyService.Currency;
import cn.nukkit.Player;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.item.Item;
import cn.nukkit.item.enchantment.Enchantment;
import cn.nukkit.plugin.PluginBase;
import java.math.BigDecimal;
import java.util.*;

/** Encantador (Encantar, Mago): sobe um nivel por vez, pago com dinheiro. */
public final class GoldEnchant extends PluginBase {
    static String PREFIX = M.t("encantar");
    private PersistentState<Prices> data;
    private final Set<String> busy = new HashSet<>();

    /** Centavos por nivel de cada encantamento oferecido. */
    public static final class Prices {
        public Map<Integer, Long> perLevel = defaults();
        static Map<Integer, Long> defaults() {
            Map<Integer, Long> m = new TreeMap<>();
            long[][] t = {{Enchantment.ID_DAMAGE_ALL, 50000}, {Enchantment.ID_PROTECTION_ALL, 40000}, {Enchantment.ID_EFFICIENCY, 30000}, {Enchantment.ID_DURABILITY, 30000},
                    {Enchantment.ID_KNOCKBACK, 25000}, {Enchantment.ID_FIRE_ASPECT, 60000}, {Enchantment.ID_BOW_POWER, 40000}, {Enchantment.ID_BOW_FLAME, 60000},
                    {Enchantment.ID_BOW_INFINITY, 150000}, {Enchantment.ID_FORTUNE_DIGGING, 80000}, {Enchantment.ID_SILK_TOUCH, 100000}, {Enchantment.ID_PROTECTION_FALL, 20000}, {Enchantment.ID_THORNS, 50000}};
            for (long[] e : t) { m.put((int) e[0], e[1]); }
            return m;
        }
        static void validate(Prices p) {
            if (p == null || p.perLevel == null || p.perLevel.size() > 27) { throw new IllegalArgumentException(M.t("precos_invalidos")); }
            for (Map.Entry<Integer, Long> e : p.perLevel.entrySet()) { if (e.getKey() == null || Enchantment.get(e.getKey()) == null || e.getValue() == null || e.getValue() < 1 || e.getValue() > 1_000_000_000_00L) { throw new IllegalArgumentException(M.t("preco_invalido")); } }
        }
    }

    @Override public void onEnable() {
        M.B.attach(this);
        Settings.prefix(this, PREFIX, v -> PREFIX = v);
        CoreService core = getServer().getServiceManager().load(CoreService.class);
        if (core == null) { throw new IllegalStateException(M.t("goldcore_precisa_estar_ativo_antes_do")); }
        data = new PersistentState<>(core.getStore(), "goldenchant", Prices.class, Prices::new, Prices::validate);
    }

    @Override public void onDisable() { busy.clear(); if (data != null) { data.close(); } }

    static int level(Item item, int id) { Enchantment e = item.getEnchantment(id); return e == null ? 0 : e.getLevel(); }

    /** Preco do proximo nivel: o valor base vezes o nivel. */
    static long cost(long perLevel, int nextLevel) { return Math.multiplyExact(perLevel, nextLevel); }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) { sender.sendMessage(M.t("use_dentro_do_jogo")); return true; }
        Player p = (Player) sender;
        try {
            if (args.length >= 3 && args[0].equalsIgnoreCase("preco") && p.hasPermission("goldenchant.admin")) {
                int id = Integer.parseInt(args[1]);
                long cents = new BigDecimal(args[2].replace(",", ".")).movePointRight(2).longValueExact();
                data.change(s -> s.perLevel.put(id, cents)).whenComplete((v, e) -> p.sendMessage(PREFIX + (e != null ? "§c" + root(e) : M.t("preco_atualizado"))));
                return true;
            }
            if (args.length >= 2 && p.hasPermission("goldenchant.admin")) {
                Enchantment e = Enchantment.getEnchantment(Integer.parseInt(args[0]));
                e.setLevel(Integer.parseInt(args[1]), false);
                Item hand = p.getInventory().getItemInHand();
                if (hand.getId() == 0) { throw new IllegalArgumentException(M.t("segure_um_item")); }
                hand.addEnchantment(e);
                p.getInventory().setItemInHand(hand);
                p.sendMessage(PREFIX + M.t("aplicado", e.getName(), args[1]));
                return true;
            }
            menu(p);
            return true;
        } catch (ArithmeticException | NumberFormatException e) { p.sendMessage(PREFIX + M.t("numero_invalido")); return true; }
        catch (RuntimeException e) { p.sendMessage(PREFIX + "§c" + root(e)); return true; }
    }

    private void menu(Player p) {
        Item hand = p.getInventory().getItemInHand();
        if (hand.getId() == 0) { throw new IllegalArgumentException(M.t("segure_item_que_quer_encantar")); }
        Map<Integer, Item> icons = new HashMap<>();
        List<Integer> ids = new ArrayList<>();
        for (Map.Entry<Integer, Long> e : data.read(s -> new ArrayList<>(s.perLevel.entrySet()))) {
            Enchantment ench = Enchantment.getEnchantment(e.getKey());
            if (ench == null || !ench.canEnchant(hand)) { continue; }
            int now = level(hand, e.getKey());
            Item icon = Item.get(403, 0, 1);
            String state = now >= ench.getMaxLevel() ? M.t("nivel_maximo") : M.t("nivel", (now + 1), money(cost(e.getValue(), now + 1)));
            icon.setCustomName("§d" + ench.getName() + " §7(" + now + "/" + ench.getMaxLevel() + ")\n" + state);
            icons.put(ids.size(), icon);
            ids.add(e.getKey());
        }
        if (ids.isEmpty()) { throw new IllegalArgumentException(M.t("nenhum_encantamento_serve_para_este_item")); }
        int slot = p.getInventory().getHeldItemIndex();
        getServer().getServiceManager().load(MenuService.class).open(p, M.t("encantar_2", hand.getName()), icons, (who, index) -> { if (index < ids.size()) { buy(who, slot, ids.get(index)); } });
    }

    /** Cobra antes; se o item da mao mudou enquanto a cobranca gravava, devolve o dinheiro. */
    private void buy(Player p, int slot, int id) {
        EconomyService economy = getServer().getServiceManager().load(EconomyService.class);
        if (economy == null) { p.sendMessage(PREFIX + M.t("goldeconomy_precisa_estar_instalado")); return; }
        Item before = p.getInventory().getItem(slot).clone();
        Enchantment ench = Enchantment.getEnchantment(id);
        int next = level(before, id) + 1;
        if (next > ench.getMaxLevel()) { p.sendMessage(PREFIX + M.t("ja_esta_no_nivel_maximo")); return; }
        long price = cost(data.read(s -> s.perLevel.get(id)), next);
        String name = p.getName().toLowerCase(Locale.ROOT);
        if (!busy.add(name)) { return; }
        economy.debit(p.getName(), Currency.MONEY, price).whenComplete((v, e) -> {
            busy.remove(name);
            if (e != null) { if (p.isOnline()) { p.sendMessage(PREFIX + "§c" + root(e)); } return; }
            Item current = p.isOnline() ? p.getInventory().getItem(slot) : null;
            if (current == null || current.getId() != before.getId() || current.getDamage() != before.getDamage() || level(current, id) != next - 1) {
                economy.credit(p.getName(), Currency.MONEY, price);
                if (p.isOnline()) { p.sendMessage(PREFIX + M.t("item_mudou_valor_devolvido")); }
                return;
            }
            ench.setLevel(next, false);
            current.addEnchantment(ench);
            p.getInventory().setItem(slot, current);
            p.sendMessage(PREFIX + M.t("aplicado_por", ench.getName(), next, money(price)));
        });
    }

    static String money(long cents) { return BigDecimal.valueOf(cents, 2).toPlainString(); }
    static String root(Throwable e) { while (e.getCause() != null) { e = e.getCause(); } return e.getMessage(); }
}
