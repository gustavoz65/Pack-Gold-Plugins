package br.goldsuite.breaker;

import br.goldsuite.api.*;
import br.goldsuite.api.EconomyService.Currency;
import cn.nukkit.Player;
import cn.nukkit.block.Block;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.entity.EntityExplodeEvent;
import cn.nukkit.item.Item;
import cn.nukkit.level.Level;
import cn.nukkit.level.Position;
import cn.nukkit.math.Vector3;
import cn.nukkit.plugin.PluginBase;
import java.math.BigDecimal;
import java.util.*;

/** Obsidiana e bedrock quebram com varias explosoes, /reparar pago e /enviar. */
public final class GoldBreaker extends PluginBase implements Listener {
    static final String PREFIX = "§7[§8Breaker§7]§f ";
    static final int OBSIDIAN_HITS = 3, BEDROCK_HITS = 10, BEDROCK_FLOOR = 4;
    /** Centavos por ponto de durabilidade reparado. */
    static final long REPAIR_CENTS_PER_POINT = 50;
    /** "mundo:x:y:z" -> explosoes recebidas. Limitado: os mais antigos saem primeiro. */
    private final LinkedHashMap<String, Integer> hits = new LinkedHashMap<String, Integer>(256, 0.75f, false) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Integer> eldest) { return size() > 20000; }
    };
    private final Set<String> busy = new HashSet<>();

    @Override public void onEnable() { getServer().getPluginManager().registerEvents(this, this); }
    @Override public void onDisable() { hits.clear(); busy.clear(); }

    /** Explosoes nao listam blocos resistentes; procura obsidiana e bedrock num raio de 2 do centro. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void explode(EntityExplodeEvent event) {
        Position c = event.getPosition();
        if (c == null || c.level == null) { return; }
        Level level = c.level;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (dx * dx + dy * dy + dz * dz > 6) { continue; }
                    Vector3 pos = new Vector3(c.getFloorX() + dx, c.getFloorY() + dy, c.getFloorZ() + dz);
                    int id = level.getBlock(pos).getId();
                    int needed = needed(id, pos.getFloorY());
                    if (needed == 0) { continue; }
                    String key = level.getFolderName() + ":" + pos.getFloorX() + ":" + pos.getFloorY() + ":" + pos.getFloorZ();
                    int count = hits.merge(key, 1, Integer::sum);
                    if (count >= needed) {
                        hits.remove(key);
                        level.setBlock(pos, Block.get(Block.AIR));
                        if (id == Block.OBSIDIAN) { level.dropItem(pos.add(0.5, 0.5, 0.5), Item.get(Block.OBSIDIAN, 0, 1)); }
                    }
                }
            }
        }
    }

    /** Explosoes necessarias para quebrar o bloco; 0 = nao quebra. A bedrock do fundo do mundo nunca quebra. */
    static int needed(int id, int y) {
        if (id == Block.OBSIDIAN) { return OBSIDIAN_HITS; }
        if (id == Block.BEDROCK && y > BEDROCK_FLOOR) { return BEDROCK_HITS; }
        return 0;
    }

    static long repairCost(Item item) { return item.getMaxDurability() > 0 && item.getDamage() > 0 ? item.getDamage() * REPAIR_CENTS_PER_POINT : 0; }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) { sender.sendMessage("Use dentro do jogo."); return true; }
        Player p = (Player) sender;
        try {
            if (command.getName().equals("enviar")) { send(p, args); return true; }
            repair(p, args.length > 0 && args[0].equalsIgnoreCase("tudo"));
            return true;
        } catch (RuntimeException e) { p.sendMessage(PREFIX + "§c" + root(e)); return true; }
    }

    private void repair(Player p, boolean all) {
        if (all && !p.hasPermission("goldbreaker.reparar.tudo")) { throw new IllegalArgumentException("Reparar tudo e exclusivo de VIP."); }
        List<Integer> slots = new ArrayList<>();
        long total = 0;
        for (int slot = 0; slot < 36; slot++) {
            if (!all && slot != p.getInventory().getHeldItemIndex()) { continue; }
            long cost = repairCost(p.getInventory().getItem(slot));
            if (cost > 0) { slots.add(slot); total += cost; }
        }
        boolean armor = all;
        Item[] worn = p.getInventory().getArmorContents();
        if (armor) { for (Item a : worn) { total += repairCost(a); } }
        if (total == 0) { throw new IllegalArgumentException(all ? "Nada para reparar." : "O item da mao nao precisa de reparo."); }
        EconomyService economy = getServer().getServiceManager().load(EconomyService.class);
        boolean free = p.hasPermission("goldbreaker.reparar.gratis") || economy == null;
        String name = p.getName().toLowerCase(Locale.ROOT);
        if (!busy.add(name)) { return; }
        long price = total;
        Runnable apply = () -> {
            for (int slot : slots) { Item it = p.getInventory().getItem(slot); it.setDamage(0); p.getInventory().setItem(slot, it); }
            if (armor) { Item[] now = p.getInventory().getArmorContents(); for (int i = 0; i < now.length; i++) { if (repairCost(now[i]) > 0) { now[i].setDamage(0); p.getInventory().setArmorItem(i, now[i]); } } }
            p.sendMessage(PREFIX + "Reparado" + (free ? "." : " por $" + BigDecimal.valueOf(price, 2).toPlainString() + "."));
        };
        if (free) { busy.remove(name); apply.run(); return; }
        economy.debit(p.getName(), Currency.MONEY, price).whenComplete((v, e) -> {
            busy.remove(name);
            if (!p.isOnline()) { if (e == null) { economy.credit(p.getName(), Currency.MONEY, price); } return; }
            if (e != null) { p.sendMessage(PREFIX + "§c" + root(e) + " (custa $" + BigDecimal.valueOf(price, 2).toPlainString() + ")"); return; }
            apply.run();
        });
    }

    private void send(Player p, String[] args) {
        if (args.length < 1) { throw new IllegalArgumentException("Uso: /enviar <jogador> (com o item na mao)"); }
        Player target = getServer().getPlayerExact(args[0]);
        if (target == null) { target = getServer().getPlayer(args[0]); }
        if (target == null || target == p) { throw new IllegalArgumentException("Jogador offline ou invalido."); }
        int slot = p.getInventory().getHeldItemIndex();
        Item hand = p.getInventory().getItem(slot);
        if (hand.getId() == 0) { throw new IllegalArgumentException("Segure o item que quer enviar."); }
        if (!target.getInventory().canAddItem(hand)) { throw new IllegalArgumentException(target.getName() + " esta com o inventario cheio."); }
        p.getInventory().setItem(slot, Item.get(0));
        target.getInventory().addItem(hand.clone());
        p.sendMessage(PREFIX + "Enviou " + hand.getCount() + "x " + hand.getName() + " para " + target.getName() + ".");
        target.sendMessage(PREFIX + p.getName() + " te enviou " + hand.getCount() + "x " + hand.getName() + ".");
    }

    static String root(Throwable e) { while (e.getCause() != null) { e = e.getCause(); } return e.getMessage(); }
}
