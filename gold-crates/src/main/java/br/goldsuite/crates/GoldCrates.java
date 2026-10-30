package br.goldsuite.crates;

import br.goldsuite.api.*;
import cn.nukkit.Player;
import cn.nukkit.block.Block;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.block.BlockBreakEvent;
import cn.nukkit.event.player.PlayerInteractEvent;
import cn.nukkit.event.player.PlayerQuitEvent;
import cn.nukkit.item.Item;
import cn.nukkit.level.sound.PopSound;
import cn.nukkit.plugin.PluginBase;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/** Caixas com chaves assinadas (HMAC do GoldCore): nome de item copiado nao abre caixa. */
public final class GoldCrates extends PluginBase implements Listener {
    static final String PREFIX = "§d[§5Caixas§d]§f ";
    static final int KEY_ITEM = 131;
    private CoreService core;
    private PersistentState<CrateState> data;
    /** Admin -> caixa a vincular no proximo toque num bau. */
    private final Map<Player, String> linking = new IdentityHashMap<>();
    private final Map<Player, Integer> lastOpen = new IdentityHashMap<>();

    @Override public void onEnable() {
        core = getServer().getServiceManager().load(CoreService.class);
        if (core == null) { throw new IllegalStateException("GoldCore precisa estar ativo antes do GoldCrates."); }
        data = new PersistentState<>(core.getStore(), "goldcrates", CrateState.class, CrateState::new, CrateState::validate);
        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override public void onDisable() { linking.clear(); lastOpen.clear(); if (data != null) { data.close(); } }

    static String purpose(String crate) { return "goldcrates:" + crate; }

    Item key(String crate, int amount) {
        Item key = Item.get(KEY_ITEM, 0, amount);
        key.setCustomName("§r§dChave: §f" + crate);
        return core.getItemSigner().sign(key, purpose(crate));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void interact(PlayerInteractEvent event) {
        Block b = event.getBlock();
        if (b == null || b.getId() != Block.CHEST || b.level == null || !data.isLoaded()) { return; }
        Player p = event.getPlayer();
        String pos = b.level.getFolderName() + ":" + b.getFloorX() + ":" + b.getFloorY() + ":" + b.getFloorZ();
        String linkTo = linking.remove(p);
        if (linkTo != null) {
            event.setCancelled();
            data.change(s -> { if (!s.crates.containsKey(linkTo)) { throw new IllegalArgumentException("Caixa inexistente."); } s.blocks.put(pos, linkTo); })
                    .whenComplete((v, e) -> p.sendMessage(PREFIX + (e != null ? "§c" + root(e) : "Este bau agora e a caixa " + linkTo + ".")));
            return;
        }
        String crate = data.read(s -> s.blocks.get(pos));
        if (crate == null) { return; }
        event.setCancelled();
        List<CrateState.Reward> rewards = data.read(s -> s.crates.get(crate).rewards);
        Item hand = p.getInventory().getItemInHand();
        if (hand.getId() != KEY_ITEM || !core.getItemSigner().verify(hand, purpose(crate))) { preview(p, crate, rewards); return; }
        int tick = getServer().getTick();
        Integer last = lastOpen.get(p);
        if (last != null && tick - last < 10) { return; }
        lastOpen.put(p, tick);
        if (rewards.isEmpty()) { p.sendMessage(PREFIX + "§cEsta caixa ainda nao tem premios."); return; }
        // Consome a chave antes de entregar.
        hand.setCount(hand.getCount() - 1);
        p.getInventory().setItemInHand(hand.getCount() > 0 ? hand : Item.get(0));
        CrateState.Reward reward = CrateState.roll(rewards, total -> ThreadLocalRandom.current().nextInt(total));
        Item item = reward.item.toItem();
        for (Item rest : p.getInventory().addItem(item.clone())) { p.level.dropItem(p, rest); }
        double chance = CrateState.chance(rewards, reward);
        p.sendMessage(PREFIX + "Voce ganhou §e" + item.getCount() + "x " + item.getName() + "§f! §7(" + String.format(Locale.ROOT, "%.1f", chance) + "%)");
        p.level.addSound(new PopSound(p), p);
        EffectService effects = getServer().getServiceManager().load(EffectService.class);
        if (effects != null) { effects.play(new cn.nukkit.level.Position(b.x + 0.5, b.y + 1, b.z + 0.5, b.level), chance < 5 ? EffectService.Shape.EXPLOSAO : EffectService.Shape.ANEL, 20); }
        if (chance < 5) { getServer().broadcastMessage(PREFIX + "§6" + p.getName() + " tirou " + item.getName() + " (raro!) na caixa " + crate + "!"); }
    }

    private void preview(Player p, String crate, List<CrateState.Reward> rewards) {
        MenuService menus = getServer().getServiceManager().load(MenuService.class);
        Map<Integer, Item> icons = new HashMap<>();
        for (int i = 0; i < rewards.size() && i < MenuService.SIZE; i++) {
            Item icon = rewards.get(i).item.toItem();
            icon.setCustomName("§e" + icon.getCount() + "x " + icon.getName() + "\n§7Chance: " + String.format(Locale.ROOT, "%.1f", CrateState.chance(rewards, rewards.get(i))) + "%");
            icons.put(i, icon);
        }
        p.sendTip("§dSegure a chave da caixa " + crate + " para abrir");
        menus.open(p, "Caixa " + crate + " - premios", icons, (who, slot) -> { });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void breakBlock(BlockBreakEvent event) {
        Block b = event.getBlock();
        if (b.getId() != Block.CHEST || !data.isLoaded()) { return; }
        String pos = b.level.getFolderName() + ":" + b.getFloorX() + ":" + b.getFloorY() + ":" + b.getFloorZ();
        if (data.read(s -> s.blocks.containsKey(pos))) {
            if (!event.getPlayer().hasPermission("goldcrates.admin")) { event.setCancelled(); return; }
            data.change(s -> s.blocks.remove(pos)).thenRun(() -> event.getPlayer().sendMessage(PREFIX + "Caixa desvinculada deste bau."));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR) public void quit(PlayerQuitEvent event) { linking.remove(event.getPlayer()); lastOpen.remove(event.getPlayer()); }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "ajuda" : args[0].toLowerCase(Locale.ROOT);
        try {
            switch (sub) {
                case "criar": {
                    String name = CrateState.name(arg(args, 1));
                    data.change(s -> { if (s.crates.size() >= 50) { throw new IllegalArgumentException("Limite de caixas."); } s.crates.putIfAbsent(name, new CrateState.Crate()); })
                            .whenComplete((v, e) -> sender.sendMessage(PREFIX + (e != null ? "§c" + root(e) : "Caixa " + name + " criada. Use /caixa vincular " + name + " e toque num bau; /caixa premio " + name + " <peso> com o item na mao.")));
                    return true;
                }
                case "vincular": {
                    if (!(sender instanceof Player)) { return true; }
                    linking.put((Player) sender, CrateState.name(arg(args, 1)));
                    sender.sendMessage(PREFIX + "Toque no bau que vai virar a caixa.");
                    return true;
                }
                case "premio": {
                    if (!(sender instanceof Player)) { return true; }
                    String name = CrateState.name(arg(args, 1));
                    int weight = Integer.parseInt(arg(args, 2));
                    Item hand = ((Player) sender).getInventory().getItemInHand();
                    if (hand.getId() == 0) { throw new IllegalArgumentException("Segure o item do premio."); }
                    ItemData item = ItemData.of(hand);
                    data.change(s -> { CrateState.Crate c = s.crates.get(name); if (c == null) { throw new IllegalArgumentException("Caixa inexistente."); } if (c.rewards.size() >= 27) { throw new IllegalArgumentException("Limite de 27 premios."); } c.rewards.add(new CrateState.Reward(item, weight)); })
                            .whenComplete((v, e) -> sender.sendMessage(PREFIX + (e != null ? "§c" + root(e) : "Premio adicionado com peso " + weight + ".")));
                    return true;
                }
                case "limpar": {
                    String name = CrateState.name(arg(args, 1));
                    data.change(s -> { CrateState.Crate c = s.crates.get(name); if (c == null) { throw new IllegalArgumentException("Caixa inexistente."); } c.rewards.clear(); })
                            .whenComplete((v, e) -> sender.sendMessage(PREFIX + (e != null ? "§c" + root(e) : "Premios removidos.")));
                    return true;
                }
                case "apagar": {
                    String name = CrateState.name(arg(args, 1));
                    data.change(s -> { if (s.crates.remove(name) == null) { throw new IllegalArgumentException("Caixa inexistente."); } s.blocks.values().removeIf(name::equals); })
                            .whenComplete((v, e) -> sender.sendMessage(PREFIX + (e != null ? "§c" + root(e) : "Caixa apagada.")));
                    return true;
                }
                case "chave": {
                    Player target = getServer().getPlayerExact(arg(args, 1));
                    if (target == null) { throw new IllegalArgumentException("Jogador offline."); }
                    String name = CrateState.name(arg(args, 2));
                    int amount = args.length > 3 ? Math.max(1, Math.min(64, Integer.parseInt(args[3]))) : 1;
                    if (!data.read(s -> s.crates.containsKey(name))) { throw new IllegalArgumentException("Caixa inexistente."); }
                    for (Item rest : target.getInventory().addItem(key(name, amount))) { target.level.dropItem(target, rest); }
                    target.sendMessage(PREFIX + "Voce recebeu " + amount + " chave(s) da caixa " + name + "!");
                    sender.sendMessage(PREFIX + "Chaves entregues.");
                    getLogger().info(sender.getName() + " deu " + amount + " chave(s) de " + name + " para " + target.getName());
                    return true;
                }
                case "lista": sender.sendMessage(PREFIX + data.read(s -> s.crates.isEmpty() ? "Nenhuma caixa." : String.join(", ", s.crates.keySet()))); return true;
                default:
                    sender.sendMessage(PREFIX + "/caixa criar <nome>, vincular <nome>, premio <nome> <peso>, limpar <nome>, apagar <nome>, chave <jogador> <nome> [qtd], lista");
                    return true;
            }
        } catch (NumberFormatException e) { sender.sendMessage(PREFIX + "§cNumero invalido."); return true; }
        catch (RuntimeException e) { sender.sendMessage(PREFIX + "§c" + root(e)); return true; }
    }

    private static String arg(String[] args, int i) { if (args.length <= i) { throw new IllegalArgumentException("Faltam argumentos. Use /caixa."); } return args[i]; }

    static String root(Throwable e) { while (e.getCause() != null) { e = e.getCause(); } return e.getMessage(); }
}
