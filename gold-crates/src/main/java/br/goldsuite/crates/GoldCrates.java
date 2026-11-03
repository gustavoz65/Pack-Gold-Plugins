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
import cn.nukkit.event.block.BlockPlaceEvent;
import cn.nukkit.event.player.PlayerItemHeldEvent;
import cn.nukkit.event.player.PlayerInteractEvent;
import cn.nukkit.event.player.PlayerQuitEvent;
import cn.nukkit.item.Item;
import cn.nukkit.level.sound.PopSound;
import cn.nukkit.plugin.PluginBase;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/** Caixas com chaves assinadas (HMAC do GoldCore): nome de item copiado nao abre caixa. */
public final class GoldCrates extends PluginBase implements Listener {
    static String PREFIX = M.t("caixas");
    static final int KEY_ITEM = 131;
    private CoreService core;
    private PersistentState<CrateState> data;
    /** Admin -> caixa a vincular no proximo toque num bau. */
    private final Map<Player, String> linking = new IdentityHashMap<>();
    private final Map<Player, Integer> lastOpen = new IdentityHashMap<>();
    private final Map<Player, Integer> lastHeld = new IdentityHashMap<>();
    static final int BOX_ITEM = 54;
    private double rare = 5, veryRare = 2.5;
    private boolean announce = true;
    private String announceText = M.t("6_jogador_cganhou_um_item_raridade");

    @Override public void onEnable() {
        M.B.attach(this);
        Settings.prefix(this, PREFIX, v -> PREFIX = v);
        core = getServer().getServiceManager().load(CoreService.class);
        if (core == null) { throw new IllegalStateException(M.t("goldcore_precisa_estar_ativo_antes_do")); }
        data = new PersistentState<>(core.getStore(), "goldcrates", CrateState.class, CrateState::new, CrateState::validate);
        getServer().getPluginManager().registerEvents(this, this);
        Settings.number(this, "misteriosa.raro", 50, "Chance maxima (em decimos de %) para o premio ser RARO (50 = 5%)", v -> rare = v / 10.0);
        Settings.number(this, "misteriosa.muito_raro", 25, "Chance maxima (em decimos de %) para o premio ser MUITO RARO, com raio (25 = 2,5%)", v -> veryRare = v / 10.0);
        Settings.number(this, "misteriosa.anunciar", 1, "1 = anuncia para todos quando alguem tira um premio raro", v -> announce = v == 1);
        Settings.text(this, "misteriosa.mensagem", "&6{jogador} &cganhou um item {raridade} &cna caixa {caixa}!", "Anuncio do premio raro ({jogador}, {raridade}, {caixa}, {premio})", v -> announceText = v);
    }

    @Override public void onDisable() { linking.clear(); lastOpen.clear(); lastHeld.clear(); if (data != null) { data.close(); } }

    // ---------- caixas misteriosas (item que se abre sozinho, como o yCaixasMisteriosas) ----------

    static String boxPurpose(String crate) { return "goldcrates-caixa:" + crate; }

    String display(String crate) { return data.read(s -> { CrateState.Crate c = s.crates.get(crate); return c == null || c.display.isEmpty() ? crate : c.display; }).replace('&', '\u00a7'); }

    /** Caixa misteriosa: baú assinado (renomear um bau na bigorna nao vale). */
    Item box(String crate, int amount) {
        Item box = Item.get(BOX_ITEM, 0, amount);
        box.setCustomName(M.t("caixa_misteriosa_selecione_para_abrir", display(crate)));
        return core.getItemSigner().sign(box, boxPurpose(crate));
    }

    /** Nome da caixa se o item for uma caixa misteriosa valida, senao null. */
    String boxOf(Item item) {
        if (item == null || item.getId() != BOX_ITEM || !item.hasCustomName() || !data.isLoaded()) { return null; }
        for (String crate : data.read(s -> new ArrayList<>(s.crates.keySet()))) { if (core.getItemSigner().verify(item, boxPurpose(crate))) { return crate; } }
        return null;
    }

    /** Selecionar a caixa na hotbar abre a confirmacao (no 0.15 tocar no ar com um bloco nao chega ao servidor). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void held(PlayerItemHeldEvent event) {
        Player p = event.getPlayer();
        String crate = boxOf(event.getItem());
        if (crate == null) { lastHeld.put(p, p.getInventory().getHeldItemIndex()); return; }
        Integer before = lastHeld.get(p);
        int slot = p.getInventory().getHeldItemIndex(), back = before == null || before == slot ? (slot == 0 ? 1 : 0) : before;
        confirm(p, crate);
        getServer().getScheduler().scheduleDelayedTask(() -> {
            if (!isEnabled() || !p.isOnline() || boxOf(p.getInventory().getItemInHand()) == null) { return; }
            p.getInventory().setHeldItemIndex(back);
            p.getInventory().sendHeldItem(p);
        }, 2);
    }

    /** Tocar com a caixa na mao (num bloco) abre direto; ela nunca vira um bau no chao. */
    @EventHandler(priority = EventPriority.LOW)
    public void useBox(PlayerInteractEvent event) {
        if (event.getAction() == PlayerInteractEvent.PHYSICAL) { return; }
        String crate = boxOf(event.getPlayer().getInventory().getItemInHand());
        if (crate == null) { return; }
        event.setCancelled();
        open(event.getPlayer(), crate);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void placeBox(BlockPlaceEvent event) { if (boxOf(event.getItem()) != null) { event.setCancelled(); } }

    private void confirm(Player p, String crate) {
        MenuService menus = getServer().getServiceManager().load(MenuService.class);
        if (menus == null) { return; }
        Map<Integer, Item> icons = new HashMap<>();
        Item a = Item.get(35, 5, 1); a.setCustomName(M.t("abrir_abre_1_caixa", display(crate))); icons.put(11, a);
        Item c = Item.get(BOX_ITEM, 0, 1); c.setCustomName(M.t("caixa", display(crate))); icons.put(13, c);
        Item n = Item.get(35, 14, 1); n.setCustomName(M.t("cancelar")); icons.put(15, n);
        Item v = Item.get(339, 0, 1); v.setCustomName(M.t("ver_os_premios_chance_de_cada")); icons.put(22, v);
        menus.open(p, M.t("deseja_abrir_caixa"), icons, (who, slot) -> {
            if (slot == 11) { open(who, crate); }
            else if (slot == 22) { preview(who, crate, data.read(s -> s.crates.containsKey(crate) ? s.crates.get(crate).rewards : Collections.<CrateState.Reward>emptyList())); }
        });
    }

    /** Consome uma caixa do inventario (conferindo a assinatura de novo) e entrega o premio. */
    void open(Player p, String crate) {
        int tick = getServer().getTick();
        Integer last = lastOpen.get(p);
        if (last != null && tick - last < 10) { return; }
        lastOpen.put(p, tick);
        List<CrateState.Reward> rewards = data.read(s -> s.crates.containsKey(crate) ? s.crates.get(crate).rewards : Collections.<CrateState.Reward>emptyList());
        if (rewards.isEmpty()) { p.sendMessage(PREFIX + M.t("esta_caixa_ainda_nao_tem_premios")); return; }
        int slot = -1;
        for (Map.Entry<Integer, Item> e : p.getInventory().getContents().entrySet()) { if (crate.equals(boxOf(e.getValue()))) { slot = e.getKey(); break; } }
        if (slot < 0) { p.sendMessage(PREFIX + M.t("voce_nao_tem_essa_caixa")); return; }
        Item stack = p.getInventory().getItem(slot);
        stack.setCount(stack.getCount() - 1);
        p.getInventory().setItem(slot, stack.getCount() > 0 ? stack : Item.get(0));
        CrateState.Reward reward = CrateState.roll(rewards, total -> ThreadLocalRandom.current().nextInt(total));
        Item item = reward.item.toItem();
        String prize = reward.commandOnly ? item.getName() : item.getCount() + "x " + item.getName();
        if (!reward.commandOnly) { for (Item rest : p.getInventory().addItem(item.clone())) { p.level.dropItem(p, rest); } }
        if (!reward.command.isEmpty()) { getServer().dispatchCommand(getServer().getConsoleSender(), reward.command.replace("{jogador}", p.getName()).replace("<jogador>", p.getName())); }
        double chance = CrateState.chance(rewards, reward);
        String rarity = CrateState.rarity(chance, rare, veryRare);
        p.sendMessage(PREFIX + M.t("voce_ganhou", prize, String.format(Locale.ROOT, "%.1f", chance), (rarity != null ? ", " + rarity : "")));
        p.level.addSound(new PopSound(p), p);
        if (rarity != null) {
            if (announce) {
                getServer().broadcastMessage(announceText.replace('&', '\u00a7').replace("{jogador}", p.getName()).replace("{raridade}", (rarity.equals(M.t("raro")) ? "\u00a75" : "\u00a74") + rarity)
                        .replace("{caixa}", display(crate)).replace("{premio}", prize));
            }
            if (rarity.equals(M.t("muito_raro"))) { lightning(p); }
            EffectService effects = getServer().getServiceManager().load(EffectService.class);
            if (effects != null) { effects.play(p, EffectService.Shape.EXPLOSAO, 20); }
        }
    }

    /** So o visual do raio (sem fogo nem dano), para quem esta por perto. */
    private void lightning(Player p) {
        cn.nukkit.network.protocol.AddEntityPacket pk = new cn.nukkit.network.protocol.AddEntityPacket();
        pk.eid = cn.nukkit.entity.Entity.entityCount++;
        pk.type = 93; pk.x = (float) p.x; pk.y = (float) p.y; pk.z = (float) p.z;
        pk.metadata = new cn.nukkit.entity.data.EntityMetadata();
        cn.nukkit.network.protocol.RemoveEntityPacket rm = new cn.nukkit.network.protocol.RemoveEntityPacket();
        rm.eid = pk.eid;
        List<Player> near = new ArrayList<>();
        for (Player o : p.level.getPlayers().values()) { if (o.distanceSquared(p) < 64 * 64) { near.add(o); o.dataPacket(pk); } }
        getServer().getScheduler().scheduleDelayedTask(() -> { for (Player o : near) { if (o.isOnline()) { o.dataPacket(rm); } } }, 20);
    }

    static String purpose(String crate) { return "goldcrates:" + crate; }

    Item key(String crate, int amount) {
        Item key = Item.get(KEY_ITEM, 0, amount);
        key.setCustomName(M.t("chave", crate));
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
            data.change(s -> { if (!s.crates.containsKey(linkTo)) { throw new IllegalArgumentException(M.t("caixa_inexistente")); } s.blocks.put(pos, linkTo); })
                    .whenComplete((v, e) -> p.sendMessage(PREFIX + (e != null ? "§c" + root(e) : M.t("este_bau_agora_caixa", linkTo))));
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
        if (rewards.isEmpty()) { p.sendMessage(PREFIX + M.t("esta_caixa_ainda_nao_tem_premios")); return; }
        // Consome a chave antes de entregar.
        hand.setCount(hand.getCount() - 1);
        p.getInventory().setItemInHand(hand.getCount() > 0 ? hand : Item.get(0));
        CrateState.Reward reward = CrateState.roll(rewards, total -> ThreadLocalRandom.current().nextInt(total));
        Item item = reward.item.toItem();
        for (Item rest : p.getInventory().addItem(item.clone())) { p.level.dropItem(p, rest); }
        double chance = CrateState.chance(rewards, reward);
        p.sendMessage(PREFIX + M.t("voce_ganhou_2", item.getCount(), item.getName(), String.format(Locale.ROOT, "%.1f", chance)));
        p.level.addSound(new PopSound(p), p);
        EffectService effects = getServer().getServiceManager().load(EffectService.class);
        if (effects != null) { effects.play(new cn.nukkit.level.Position(b.x + 0.5, b.y + 1, b.z + 0.5, b.level), chance < 5 ? EffectService.Shape.EXPLOSAO : EffectService.Shape.ANEL, 20); }
        if (chance < 5) { getServer().broadcastMessage(PREFIX + M.t("tirou_raro_na_caixa", p.getName(), item.getName(), crate)); }
    }

    private void preview(Player p, String crate, List<CrateState.Reward> rewards) {
        MenuService menus = getServer().getServiceManager().load(MenuService.class);
        Map<Integer, Item> icons = new HashMap<>();
        for (int i = 0; i < rewards.size() && i < MenuService.SIZE; i++) {
            Item icon = rewards.get(i).item.toItem();
            icon.setCustomName(M.t("chance", icon.getCount(), icon.getName(), String.format(Locale.ROOT, "%.1f", CrateState.chance(rewards, rewards.get(i)))));
            icons.put(i, icon);
        }
        p.sendTip(M.t("segure_chave_da_caixa_para_abrir", crate));
        menus.open(p, M.t("caixa_premios", crate), icons, (who, slot) -> { });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void breakBlock(BlockBreakEvent event) {
        Block b = event.getBlock();
        if (b.getId() != Block.CHEST || !data.isLoaded()) { return; }
        String pos = b.level.getFolderName() + ":" + b.getFloorX() + ":" + b.getFloorY() + ":" + b.getFloorZ();
        if (data.read(s -> s.blocks.containsKey(pos))) {
            if (!event.getPlayer().hasPermission("goldcrates.admin")) { event.setCancelled(); return; }
            data.change(s -> s.blocks.remove(pos)).thenRun(() -> event.getPlayer().sendMessage(PREFIX + M.t("caixa_desvinculada_deste_bau")));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR) public void quit(PlayerQuitEvent event) { linking.remove(event.getPlayer()); lastOpen.remove(event.getPlayer()); lastHeld.remove(event.getPlayer()); }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "ajuda" : args[0].toLowerCase(Locale.ROOT);
        try {
            switch (sub) {
                case "criar": {
                    String name = CrateState.name(arg(args, 1));
                    data.change(s -> { if (s.crates.size() >= 50) { throw new IllegalArgumentException(M.t("limite_de_caixas")); } s.crates.putIfAbsent(name, new CrateState.Crate()); })
                            .whenComplete((v, e) -> sender.sendMessage(PREFIX + (e != null ? "§c" + root(e) : M.t("caixa_criada_use_caixa_vincular_toque", name, name, name))));
                    return true;
                }
                case "vincular": {
                    if (!(sender instanceof Player)) { return true; }
                    linking.put((Player) sender, CrateState.name(arg(args, 1)));
                    sender.sendMessage(PREFIX + M.t("toque_no_bau_que_vai_virar"));
                    return true;
                }
                case "premio": {
                    if (!(sender instanceof Player)) { return true; }
                    String name = CrateState.name(arg(args, 1));
                    int weight = Integer.parseInt(arg(args, 2));
                    Item hand = ((Player) sender).getInventory().getItemInHand();
                    if (hand.getId() == 0) { throw new IllegalArgumentException(M.t("segure_item_do_premio")); }
                    ItemData item = ItemData.of(hand);
                    data.change(s -> { CrateState.Crate c = s.crates.get(name); if (c == null) { throw new IllegalArgumentException(M.t("caixa_inexistente")); } if (c.rewards.size() >= 27) { throw new IllegalArgumentException(M.t("limite_de_27_premios")); } c.rewards.add(new CrateState.Reward(item, weight)); })
                            .whenComplete((v, e) -> sender.sendMessage(PREFIX + (e != null ? "§c" + root(e) : M.t("premio_adicionado_com_peso", weight))));
                    return true;
                }
                case "limpar": {
                    String name = CrateState.name(arg(args, 1));
                    data.change(s -> { CrateState.Crate c = s.crates.get(name); if (c == null) { throw new IllegalArgumentException(M.t("caixa_inexistente")); } c.rewards.clear(); })
                            .whenComplete((v, e) -> sender.sendMessage(PREFIX + (e != null ? "§c" + root(e) : M.t("premios_removidos"))));
                    return true;
                }
                case "apagar": {
                    String name = CrateState.name(arg(args, 1));
                    data.change(s -> { if (s.crates.remove(name) == null) { throw new IllegalArgumentException(M.t("caixa_inexistente")); } s.blocks.values().removeIf(name::equals); })
                            .whenComplete((v, e) -> sender.sendMessage(PREFIX + (e != null ? "§c" + root(e) : M.t("caixa_apagada"))));
                    return true;
                }
                case "chave": {
                    Player target = getServer().getPlayerExact(arg(args, 1));
                    if (target == null) { throw new IllegalArgumentException(M.t("jogador_offline")); }
                    String name = CrateState.name(arg(args, 2));
                    int amount = args.length > 3 ? Math.max(1, Math.min(64, Integer.parseInt(args[3]))) : 1;
                    if (!data.read(s -> s.crates.containsKey(name))) { throw new IllegalArgumentException(M.t("caixa_inexistente")); }
                    for (Item rest : target.getInventory().addItem(key(name, amount))) { target.level.dropItem(target, rest); }
                    target.sendMessage(PREFIX + M.t("voce_recebeu_chave_da_caixa", amount, name));
                    sender.sendMessage(PREFIX + M.t("chaves_entregues"));
                    getLogger().info(sender.getName() + " deu " + amount + " chave(s) de " + name + " para " + target.getName());
                    return true;
                }
                case "misteriosa": case "dar": {
                    if (!sender.hasPermission("goldcrates.admin")) { throw new IllegalArgumentException(M.t("sem_permissao")); }
                    Player target = getServer().getPlayerExact(arg(args, 1));
                    if (target == null) { throw new IllegalArgumentException(M.t("jogador_offline")); }
                    String name = CrateState.name(arg(args, 2));
                    int amount = args.length > 3 ? Math.max(1, Math.min(64, Integer.parseInt(args[3]))) : 1;
                    if (!data.read(s -> s.crates.containsKey(name))) { throw new IllegalArgumentException(M.t("caixa_inexistente")); }
                    for (Item rest : target.getInventory().addItem(box(name, amount))) { target.level.dropItem(target, rest); }
                    target.sendMessage(PREFIX + M.t("voce_recebeu_caixa_misteriosa_selecione_na", amount, display(name)));
                    sender.sendMessage(PREFIX + M.t("caixas_entregues"));
                    getLogger().info(sender.getName() + " deu " + amount + " caixa(s) misteriosa(s) " + name + " para " + target.getName());
                    return true;
                }
                case "nome": {
                    String name = CrateState.name(arg(args, 1));
                    String display = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
                    if (display.isEmpty() || display.length() > 40) { throw new IllegalArgumentException(M.t("nome_de_1_40_caracteres")); }
                    data.change(s -> { CrateState.Crate c = s.crates.get(name); if (c == null) { throw new IllegalArgumentException(M.t("caixa_inexistente")); } c.display = display; })
                            .whenComplete((v, e) -> sender.sendMessage(PREFIX + (e != null ? "\u00a7c" + root(e) : M.t("nome_da_caixa", display.replace('&', '\u00a7')))));
                    return true;
                }
                case "premiocomando": {
                    if (!(sender instanceof Player)) { return true; }
                    String name = CrateState.name(arg(args, 1));
                    int weight = Integer.parseInt(arg(args, 2));
                    String prizeName = arg(args, 3).replace('_', ' ');
                    String cmd = CrateState.command(String.join(" ", Arrays.copyOfRange(args, 4, args.length)));
                    Item hand = ((Player) sender).getInventory().getItemInHand();
                    Item icon = hand.getId() == 0 ? Item.get(339, 0, 1) : hand.clone();
                    icon.setCustomName(prizeName.replace('&', '\u00a7'));
                    ItemData item = ItemData.of(icon);
                    data.change(s -> { CrateState.Crate c = s.crates.get(name); if (c == null) { throw new IllegalArgumentException(M.t("caixa_inexistente")); } if (c.rewards.size() >= 27) { throw new IllegalArgumentException(M.t("limite_de_27_premios")); }
                                CrateState.Reward r = new CrateState.Reward(item, Math.max(1, Math.min(10000, weight))); r.command = cmd; r.commandOnly = true; c.rewards.add(r); })
                            .whenComplete((v, e) -> sender.sendMessage(PREFIX + (e != null ? "\u00a7c" + root(e) : M.t("premio_de_comando_adicionado", cmd))));
                    return true;
                }
                case "lista": sender.sendMessage(PREFIX + data.read(s -> s.crates.isEmpty() ? M.t("nenhuma_caixa") : String.join(", ", s.crates.keySet()))); return true;
                default:
                    sender.sendMessage(PREFIX + M.t("caixa_criar_nome_nome_caixa_nome"));
                    return true;
            }
        } catch (NumberFormatException e) { sender.sendMessage(PREFIX + M.t("numero_invalido")); return true; }
        catch (RuntimeException e) { sender.sendMessage(PREFIX + "§c" + root(e)); return true; }
    }

    private static String arg(String[] args, int i) { if (args.length <= i) { throw new IllegalArgumentException(M.t("faltam_argumentos_use_caixa")); } return args[i]; }

    static String root(Throwable e) { while (e.getCause() != null) { e = e.getCause(); } return e.getMessage(); }
}
