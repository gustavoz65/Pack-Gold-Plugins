package br.goldsuite.shop;

import br.goldsuite.api.*;
import br.goldsuite.api.EconomyService.Currency;
import br.goldsuite.shop.ShopState.Shop;
import br.goldsuite.shop.ShopState.Type;
import cn.nukkit.Player;
import cn.nukkit.block.Block;
import cn.nukkit.blockentity.BlockEntity;
import cn.nukkit.blockentity.BlockEntityChest;
import cn.nukkit.blockentity.BlockEntitySign;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.block.BlockBreakEvent;
import cn.nukkit.event.block.SignChangeEvent;
import cn.nukkit.event.player.PlayerInteractEvent;
import cn.nukkit.event.player.PlayerQuitEvent;
import cn.nukkit.inventory.BaseInventory;
import cn.nukkit.inventory.PlayerInventory;
import cn.nukkit.item.Item;
import cn.nukkit.math.Vector3;
import cn.nukkit.plugin.PluginBase;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Placas de compra e venda do servidor, lojas de bau de jogadores e /vender. */
public final class GoldShop extends PluginBase implements Listener {
    static String PREFIX = M.t("loja");
    private static final long CONFIRM_MS = 3000;
    private PersistentState<ShopState> data;
    /** Primeiro toque: placa e instante; o segundo toque em ate 3 s confirma. */
    private final Map<Player, Object[]> pending = new IdentityHashMap<>();
    /** Lojas com uma compra em andamento; impede vender o mesmo estoque duas vezes. */
    private final Set<String> busy = new HashSet<>();

    @Override public void onEnable() {
        M.B.attach(this);
        Settings.prefix(this, PREFIX, v -> PREFIX = v);
        CoreService core = getServer().getServiceManager().load(CoreService.class);
        if (core == null) { throw new IllegalStateException(M.t("goldcore_precisa_estar_ativo_antes_do")); }
        data = new PersistentState<>(core.getStore(), "goldshop", ShopState.class, ShopState::new, ShopState::validate);
        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override public void onDisable() { pending.clear(); busy.clear(); if (data != null) { data.close(); } }

    private EconomyService economy() {
        EconomyService e = getServer().getServiceManager().load(EconomyService.class);
        if (e == null) { throw new IllegalStateException(M.t("goldeconomy_precisa_estar_instalado")); }
        return e;
    }

    // ---------- criar e remover ----------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void sign(SignChangeEvent event) {
        String head = event.getLine(0).trim().toLowerCase(Locale.ROOT);
        Type type = head.equals("[comprar]") ? Type.COMPRAR : head.equals("[vender]") ? Type.VENDER : head.equals("[loja]") ? Type.LOJA : null;
        if (type == null) { return; }
        Player p = event.getPlayer();
        Block b = event.getBlock();
        try {
            if (type != Type.LOJA && !p.hasPermission("goldshop.admin")) { throw new IllegalArgumentException(M.t("apenas_staff_cria_placas_de_compra")); }
            int[] item = ShopState.item(event.getLine(1));
            int amount = ShopState.amount(event.getLine(2));
            long price = ShopState.price(event.getLine(3));
            Shop shop = new Shop();
            shop.type = type; shop.id = item[0]; shop.meta = item[1]; shop.amount = amount; shop.price = price;
            if (type == Type.LOJA) {
                BlockEntityChest chest = chestNear(b);
                if (chest == null) { throw new IllegalArgumentException(M.t("coloque_placa_encostada_ou_em_cima")); }
                shop.owner = p.getName().toLowerCase(Locale.ROOT);
                shop.chestX = chest.getFloorX(); shop.chestY = chest.getFloorY(); shop.chestZ = chest.getFloorZ();
            }
            String key = ShopState.key(b.level.getFolderName(), b.getFloorX(), b.getFloorY(), b.getFloorZ());
            String name = Item.get(shop.id, shop.meta).getName();
            event.setLine(0, type == Type.COMPRAR ? M.t("comprar_2") : type == Type.VENDER ? M.t("vender_2") : "§9[" + p.getName() + "]");
            event.setLine(1, name.length() > 15 ? name.substring(0, 15) : name);
            event.setLine(2, "Qtd: " + amount);
            event.setLine(3, "§2$ " + ShopState.money(price));
            data.change(s -> { if (s.shops.size() >= 5000) { throw new IllegalArgumentException(M.t("limite_de_lojas")); } s.shops.put(key, shop); })
                    .whenComplete((v, e) -> { if (p.isOnline()) { p.sendMessage(PREFIX + (e != null ? "§c" + root(e) : M.t("loja_criada_toque_duas_vezes_na"))); } });
        } catch (RuntimeException e) { event.setLine(0, M.t("erro")); p.sendMessage(PREFIX + M.t("formato_comprar_vender_loja_item_quantidade", root(e))); }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void breakBlock(BlockBreakEvent event) {
        Block b = event.getBlock();
        if (!data.isLoaded()) { return; }
        Player p = event.getPlayer();
        String key = ShopState.key(b.level.getFolderName(), b.getFloorX(), b.getFloorY(), b.getFloorZ());
        Shop shop = data.read(s -> s.shops.get(key));
        if (shop != null) {
            if (!canManage(p, shop)) { event.setCancelled(); p.sendPopup(M.t("loja_de_outro_jogador")); return; }
            data.change(s -> s.shops.remove(key)).thenRun(() -> { if (p.isOnline()) { p.sendMessage(PREFIX + M.t("loja_removida")); } });
            return;
        }
        // O baú de estoque so pode ser quebrado pelo dono.
        if (b.getId() == Block.CHEST) {
            String world = b.level.getFolderName();
            Shop owner = data.read(s -> { for (Map.Entry<String, Shop> e : s.shops.entrySet()) { Shop x = e.getValue(); if (x.type == Type.LOJA && x.chestX == b.getFloorX() && x.chestY == b.getFloorY() && x.chestZ == b.getFloorZ() && e.getKey().startsWith(world + ":")) { return x; } } return null; });
            if (owner != null && !canManage(p, owner)) { event.setCancelled(); p.sendPopup(M.t("estoque_de_outra_loja")); }
        }
    }

    private static boolean canManage(Player p, Shop shop) { return p.hasPermission("goldshop.admin") || (shop.type == Type.LOJA && shop.owner.equals(p.getName().toLowerCase(Locale.ROOT))); }

    /** Estoque da loja do jogador: so o dono abre o bau. */
    private boolean protectedChest(Player p, Block b) {
        String world = b.level.getFolderName(), name = p.getName().toLowerCase(Locale.ROOT);
        return !p.hasPermission("goldshop.admin") && data.read(s -> {
            for (Map.Entry<String, Shop> e : s.shops.entrySet()) {
                Shop x = e.getValue();
                if (x.type == Type.LOJA && x.chestX == b.getFloorX() && x.chestY == b.getFloorY() && x.chestZ == b.getFloorZ() && e.getKey().startsWith(world + ":") && !x.owner.equals(name)) { return true; }
            }
            return false;
        });
    }

    // ---------- uso ----------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void interact(PlayerInteractEvent event) {
        Block b = event.getBlock();
        if (b == null || b.level == null || !data.isLoaded()) { return; }
        int id = b.getId();
        Player p = event.getPlayer();
        if (id == Block.CHEST && event.getAction() == PlayerInteractEvent.RIGHT_CLICK_BLOCK && protectedChest(p, b)) { event.setCancelled(); p.sendPopup(M.t("estoque_de_outra_loja")); return; }
        if (id != Block.SIGN_POST && id != Block.WALL_SIGN) { return; }
        String key = ShopState.key(b.level.getFolderName(), b.getFloorX(), b.getFloorY(), b.getFloorZ());
        Shop shop = data.read(s -> s.shops.get(key));
        if (shop == null) { return; }
        event.setCancelled();
        Item item = Item.get(shop.id, shop.meta, shop.amount);
        String what = shop.amount + "x " + item.getName() + " por $" + ShopState.money(shop.price);
        if (shop.type == Type.LOJA && shop.owner.equals(p.getName().toLowerCase(Locale.ROOT))) {
            BaseInventory chest = chestAt(b, shop);
            p.sendMessage(PREFIX + M.t("sua_loja_estoque", what, (chest == null ? M.t("bau_sumiu") : count(chest, shop.id, shop.meta))));
            return;
        }
        long now = System.currentTimeMillis();
        Object[] last = pending.get(p);
        if (last == null || !key.equals(last[0]) || now - (Long) last[1] > CONFIRM_MS) {
            pending.put(p, new Object[] {key, now});
            p.sendTip(M.t("toque_de_novo_para_confirmar", (shop.type == Type.VENDER ? M.t("vender") : M.t("comprar")), what));
            return;
        }
        pending.remove(p);
        if (!busy.add(key)) { return; }
        CompletableFuture<String> result;
        try {
            switch (shop.type) {
                case COMPRAR: result = buyFromServer(p, shop, item); break;
                case VENDER: result = sellToServer(p, item, shop.price); break;
                default: result = buyFromPlayer(p, shop, item, chestAt(b, shop)); break;
            }
        } catch (RuntimeException e) { result = PersistentState.failed(e); }
        result.whenComplete((message, error) -> { busy.remove(key); if (p.isOnline()) { p.sendMessage(PREFIX + (error != null ? "§c" + root(error) : message)); } });
    }

    private CompletableFuture<String> buyFromServer(Player p, Shop shop, Item item) {
        if (!p.getInventory().canAddItem(item)) { throw new IllegalArgumentException(M.t("inventario_cheio")); }
        String who = p.getName();
        return economy().debit(who, Currency.MONEY, shop.price).thenApply(v -> {
            if (!p.isOnline()) { economy().credit(who, Currency.MONEY, shop.price); throw new IllegalStateException(M.t("jogador_saiu_valor_devolvido")); }
            give(p, item.clone());
            return M.t("comprou_itens", shop.amount, item.getName());
        });
    }

    /** Itens saem do inventario antes do credito; se o credito falhar, voltam. */
    private CompletableFuture<String> sellToServer(Player p, Item item, long price) {
        if (count(p.getInventory(), item.getId(), item.getDamage()) < item.getCount()) { throw new IllegalArgumentException(M.t("voce_nao_tem", item.getCount(), item.getName())); }
        p.getInventory().removeItem(item.clone());
        return economy().credit(p.getName(), Currency.MONEY, price).handle((v, error) -> {
            if (error != null) { if (p.isOnline()) { give(p, item.clone()); } throw new IllegalStateException(root(error)); }
            return M.t("vendeu_itens_placa", item.getCount(), item.getName(), ShopState.money(price));
        });
    }

    /** Estoque sai do bau antes da cobranca; se ela falhar, volta para o bau. */
    private CompletableFuture<String> buyFromPlayer(Player p, Shop shop, Item item, BaseInventory chest) {
        if (chest == null) { throw new IllegalArgumentException(M.t("bau_desta_loja_nao_existe_mais")); }
        if (count(chest, shop.id, shop.meta) < shop.amount) { throw new IllegalArgumentException(M.t("loja_sem_estoque")); }
        if (!p.getInventory().canAddItem(item)) { throw new IllegalArgumentException(M.t("inventario_cheio")); }
        if (shop.owner.equals(p.getName().toLowerCase(Locale.ROOT))) { throw new IllegalArgumentException(M.t("loja_propria")); }
        chest.removeItem(item.clone());
        return economy().transfer(p.getName(), shop.owner, Currency.MONEY, shop.price).handle((v, error) -> {
            if (error != null) {
                for (Item rest : chest.addItem(item.clone())) { p.level.dropItem(new Vector3(shop.chestX + 0.5, shop.chestY + 1, shop.chestZ + 0.5), rest); }
                throw new IllegalStateException(root(error));
            }
            if (p.isOnline()) { give(p, item.clone()); } else { for (Item rest : chest.addItem(item.clone())) { p.level.dropItem(new Vector3(shop.chestX + 0.5, shop.chestY + 1, shop.chestZ + 0.5), rest); } }
            Player owner = getServer().getPlayerExact(shop.owner);
            if (owner != null) { owner.sendMessage(PREFIX + M.t("comprou_da_sua_loja", p.getName(), shop.amount, item.getName(), ShopState.money(shop.price))); }
            return M.t("comprou_itens_de", shop.amount, item.getName(), shop.owner);
        });
    }

    // ---------- /vender ----------

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        try {
            if (command.getName().equals("comprar")) { store(sender, args); return true; }
            if (command.getName().equals("preco")) {
                if (args.length < 2) { sender.sendMessage(PREFIX + M.t("uso_preco_id_meta_valor_por")); return true; }
                int[] item = ShopState.item(args[0]);
                String key = item[0] + ":" + item[1];
                if (args[1].equalsIgnoreCase("remover")) { data.change(s -> s.sellPrices.remove(key)).thenRun(() -> sender.sendMessage(PREFIX + M.t("preco_removido"))); return true; }
                long price = ShopState.price(args[1]);
                data.change(s -> s.sellPrices.put(key, price)).whenComplete((v, e) -> sender.sendMessage(PREFIX + (e != null ? "§c" + root(e) : M.t("por_unidade", Item.get(item[0], item[1]).getName(), ShopState.money(price)))));
                return true;
            }
            if (!(sender instanceof Player)) { return true; }
            Player p = (Player) sender;
            String who = p.getName().toLowerCase(Locale.ROOT);
            if (!busy.add("vender:" + who)) { return true; }
            boolean all = args.length > 0 && args[0].equalsIgnoreCase("tudo");
            Map<Integer, Item> sold = new LinkedHashMap<>();
            long total = 0;
            PlayerInventory inv = p.getInventory();
            for (int slot = 0; slot < 36; slot++) {
                if (!all && slot != inv.getHeldItemIndex()) { continue; }
                Item it = inv.getItem(slot);
                if (it.getId() == 0 || it.hasCompoundTag()) { continue; }
                Long unit = data.read(s -> s.sellPrices.get(it.getId() + ":" + it.getDamage()));
                if (unit == null) { continue; }
                sold.put(slot, it.clone());
                total = Math.addExact(total, Math.multiplyExact(unit, it.getCount()));
            }
            if (sold.isEmpty() || total <= 0) { busy.remove("vender:" + who); throw new IllegalArgumentException(all ? M.t("nada_vendavel_no_inventario") : M.t("item_da_mao_nao_tem_preco")); }
            for (int slot : sold.keySet()) { inv.setItem(slot, Item.get(0)); }
            long value = total;
            int items = sold.values().stream().mapToInt(Item::getCount).sum();
            economy().credit(p.getName(), Currency.MONEY, value).whenComplete((v, error) -> {
                busy.remove("vender:" + who);
                if (error != null) { if (p.isOnline()) { for (Item it : sold.values()) { give(p, it); } p.sendMessage(PREFIX + "§c" + root(error)); } return; }
                if (p.isOnline()) { p.sendMessage(PREFIX + M.t("vendeu_itens_por", items, ShopState.money(value))); }
            });
            return true;
        } catch (ArithmeticException e) { busy.remove("vender:" + sender.getName().toLowerCase(Locale.ROOT)); sender.sendMessage(PREFIX + M.t("valor_alto_demais")); return true; }
        catch (RuntimeException e) { sender.sendMessage(PREFIX + "§c" + root(e)); return true; }
    }

    @EventHandler(priority = EventPriority.MONITOR) public void quit(PlayerQuitEvent event) { pending.remove(event.getPlayer()); }

    // ---------- /comprar: loja do servidor em menu ----------

    /** Posicoes das categorias no menu principal (duas fileiras centrais) e dos botoes. */
    private static final int[] CATEGORY_SLOTS = {1, 2, 3, 4, 5, 6, 7, 10, 11, 12, 13, 14, 15, 16};
    private static final int BACK = 18, BALANCE = 22, SELL_ALL = 26;

    private void store(CommandSender sender, String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (!sub.isEmpty() && !sub.equals("menu")) { storeAdmin(sender, sub, args); return; }
        if (!(sender instanceof Player)) { sender.sendMessage(PREFIX + M.t("uso_comprar_admin")); return; }
        categories((Player) sender);
    }

    private MenuService menus() { return getServer().getServiceManager().load(MenuService.class); }

    private void categories(Player p) {
        Map<String, ShopState.Category> catalog = data.read(s -> new LinkedHashMap<>(s.catalog));
        if (catalog.isEmpty()) { p.sendMessage(PREFIX + M.t("loja_do_servidor_vazia")); return; }
        Map<Integer, Item> icons = new HashMap<>();
        Map<Integer, String> bySlot = new HashMap<>();
        int i = 0;
        for (Map.Entry<String, ShopState.Category> e : catalog.entrySet()) {
            if (i >= CATEGORY_SLOTS.length) { break; }
            ShopState.Category c = e.getValue();
            icons.put(CATEGORY_SLOTS[i], named(Item.get(c.iconId, c.iconMeta, 1), M.t("categoria_icone", (c.title.isEmpty() ? e.getKey() : c.title).replace('&', '§'), c.offers.size())));
            bySlot.put(CATEGORY_SLOTS[i], e.getKey());
            i++;
        }
        icons.put(SELL_ALL, named(Item.get(266, 0, 1), M.t("botao_vender_tudo")));
        menus().open(p, M.t("titulo_loja_servidor"), icons, (who, slot) -> {
            if (slot == SELL_ALL) { getServer().dispatchCommand(who, "vender tudo"); return; }
            String key = bySlot.get(slot);
            if (key != null) { offers(who, key); }
        });
    }

    static final int PAGE = 18, PREV = 20, NEXT = 24;

    private void offers(Player p, String key) { offers(p, key, 0); }

    /** Itens da categoria, 18 por pagina; tocar num item abre a escolha de quantidade. */
    private void offers(Player p, String key, int page) {
        ShopState.Category c = data.read(s -> s.catalog.get(key));
        if (c == null) { categories(p); return; }
        List<ShopState.Offer> list = new ArrayList<>(c.offers);
        int pages = Math.max(1, (list.size() + PAGE - 1) / PAGE);
        int pg = Math.max(0, Math.min(page, pages - 1));
        Map<Integer, Item> icons = new HashMap<>();
        for (int i = 0; i < PAGE && pg * PAGE + i < list.size(); i++) {
            ShopState.Offer o = list.get(pg * PAGE + i);
            Item it = Item.get(o.id, o.meta, Math.max(1, Math.min(64, o.amount)));
            icons.put(i, named(it, M.t("oferta_icone", o.amount, it.getName(), ShopState.money(o.price))));
        }
        icons.put(BACK, named(Item.get(262, 0, 1), M.t("botao_voltar")));
        if (pg > 0) { icons.put(PREV, named(Item.get(262, 0, 1), M.t("pagina_anterior", pg, pages))); }
        if (pg + 1 < pages) { icons.put(NEXT, named(Item.get(262, 0, 1), M.t("proxima_pagina", pg + 2, pages))); }
        icons.put(BALANCE, named(Item.get(339, 0, 1), M.t("botao_saldo", "$ " + ShopState.money(balance(p)))));
        icons.put(SELL_ALL, named(Item.get(266, 0, 1), M.t("botao_vender_tudo")));
        String title = (c.title.isEmpty() ? key : c.title).replace('&', '§');
        menus().open(p, title, icons, (who, slot) -> {
            if (slot == BACK) { categories(who); return; }
            if (slot == PREV && pg > 0) { offers(who, key, pg - 1); return; }
            if (slot == NEXT && pg + 1 < pages) { offers(who, key, pg + 1); return; }
            if (slot == SELL_ALL) { getServer().dispatchCommand(who, "vender tudo"); return; }
            int index = pg * PAGE + slot;
            if (slot < 0 || slot >= PAGE || index >= list.size()) { return; }
            quantity(who, key, pg, list.get(index));
        });
    }

    private long balance(Player p) { EconomyService eco = getServer().getServiceManager().load(EconomyService.class); return eco == null ? 0 : eco.balance(p.getName(), Currency.MONEY); }

    /** Preco de n unidades a partir do preco do pacote (arredonda o centavo para cima). */
    static long cost(ShopState.Offer o, int n) { return (o.price * n + o.amount - 1) / o.amount; }

    /** Escolha da quantidade: 1, 16 ou 64 (o que couber na pilha do item). */
    private void quantity(Player p, String key, int page, ShopState.Offer o) {
        Item base = Item.get(o.id, o.meta, 1);
        int stack = Math.max(1, base.getMaxStackSize());
        int[] options = stack >= 64 ? new int[] {1, 16, 64} : stack >= 16 ? new int[] {1, 16} : new int[] {1};
        int[] slots = {11, 13, 15};
        Map<Integer, Item> icons = new HashMap<>();
        for (int i = 0; i < options.length; i++) {
            icons.put(slots[i], named(Item.get(o.id, o.meta, options[i]), M.t("quantidade_icone", options[i], base.getName(), ShopState.money(cost(o, options[i])))));
        }
        icons.put(BACK, named(Item.get(262, 0, 1), M.t("botao_voltar")));
        icons.put(BALANCE, named(Item.get(339, 0, 1), M.t("botao_saldo", "$ " + ShopState.money(balance(p)))));
        menus().open(p, M.t("titulo_quantidade"), icons, (who, slot) -> {
            if (slot == BACK) { offers(who, key, page); return; }
            for (int i = 0; i < options.length; i++) { if (slot == slots[i]) { confirm(who, key, page, o, options[i]); return; } }
        });
    }

    /** Confirmar ou cancelar; confirmar compra e volta para a mesma pagina, para continuar comprando. */
    private void confirm(Player p, String key, int page, ShopState.Offer o, int n) {
        Item base = Item.get(o.id, o.meta, Math.min(64, n));
        long total = cost(o, n);
        Map<Integer, Item> icons = new HashMap<>();
        icons.put(13, named(base, M.t("confirmar_item", n, Item.get(o.id, o.meta).getName(), ShopState.money(total))));
        icons.put(11, named(Item.get(35, 5, 1), M.t("botao_confirmar_compra", ShopState.money(total))));
        icons.put(15, named(Item.get(35, 14, 1), M.t("botao_cancelar_compra")));
        icons.put(BALANCE, named(Item.get(339, 0, 1), M.t("botao_saldo", "$ " + ShopState.money(balance(p)))));
        menus().open(p, M.t("titulo_confirmar"), icons, (who, slot) -> {
            if (slot == 15) { offers(who, key, page); return; }
            if (slot != 11) { return; }
            String busyKey = "comprar:" + who.getName().toLowerCase(Locale.ROOT);
            if (!busy.add(busyKey)) { return; }
            CompletableFuture<String> result;
            try {
                Shop shop = new Shop(); shop.id = o.id; shop.meta = o.meta; shop.amount = n; shop.price = total;
                result = buyFromServer(who, shop, Item.get(o.id, o.meta, n));
            } catch (RuntimeException e) { result = PersistentState.failed(e); }
            result.whenComplete((message, error) -> {
                busy.remove(busyKey);
                if (!who.isOnline()) { return; }
                who.sendMessage(PREFIX + (error != null ? "§c" + root(error) : message));
            });
            offers(who, key, page);
        });
    }

    private static Item named(Item it, String name) { it.setCustomName(name); return it; }

    /** Administracao da loja (console ou goldshop.admin). */
    private void storeAdmin(CommandSender sender, String sub, String[] a) {
        if (!sender.hasPermission("goldshop.admin")) { sender.sendMessage(PREFIX + M.t("uso_comprar")); return; }
        switch (sub) {
            case "categoria": {
                if (a.length < 3) { throw new IllegalArgumentException(M.t("uso_comprar_admin")); }
                String key = ShopState.category(a[1]);
                int[] icon = ShopState.item(a[2]);
                String title = a.length > 3 ? String.join(" ", Arrays.copyOfRange(a, 3, a.length)) : "";
                if (title.length() > 40) { throw new IllegalArgumentException(M.t("titulo_longo")); }
                data.change(s -> {
                    ShopState.Category c = s.catalog.get(key);
                    if (c == null) { if (s.catalog.size() >= ShopState.MAX_CATEGORIES) { throw new IllegalArgumentException(M.t("limite_de_categorias")); } c = new ShopState.Category(); s.catalog.put(key, c); }
                    c.iconId = icon[0]; c.iconMeta = icon[1]; c.title = title;
                }).whenComplete((v, e) -> sender.sendMessage(PREFIX + (e != null ? "§c" + root(e) : M.t("categoria_salva", key))));
                return;
            }
            case "add": {
                if (a.length < 5) { throw new IllegalArgumentException(M.t("uso_comprar_admin")); }
                String key = ShopState.category(a[1]);
                int[] item = ShopState.item(a[2]);
                int amount = ShopState.amount(a[3]);
                long price = ShopState.price(a[4]);
                data.change(s -> {
                    ShopState.Category c = s.catalog.get(key);
                    if (c == null) { throw new IllegalArgumentException(M.t("categoria_inexistente", key)); }
                    if (c.offers.size() >= ShopState.MAX_OFFERS) { throw new IllegalArgumentException(M.t("limite_de_ofertas")); }
                    ShopState.Offer o = new ShopState.Offer(); o.id = item[0]; o.meta = item[1]; o.amount = amount; o.price = price;
                    c.offers.add(o);
                }).whenComplete((v, e) -> sender.sendMessage(PREFIX + (e != null ? "§c" + root(e) : M.t("oferta_adicionada", amount, Item.get(item[0], item[1]).getName(), ShopState.money(price), key))));
                return;
            }
            case "remover": {
                if (a.length < 3) { throw new IllegalArgumentException(M.t("uso_comprar_admin")); }
                String key = ShopState.category(a[1]);
                int n = Integer.parseInt(a[2]);
                data.change(s -> { ShopState.Category c = s.catalog.get(key); if (c == null || n < 1 || n > c.offers.size()) { throw new IllegalArgumentException(M.t("oferta_inexistente")); } c.offers.remove(n - 1); })
                        .whenComplete((v, e) -> sender.sendMessage(PREFIX + (e != null ? "§c" + root(e) : M.t("oferta_removida"))));
                return;
            }
            case "apagar": {
                String key = ShopState.category(a.length > 1 ? a[1] : "");
                data.change(s -> { if (s.catalog.remove(key) == null) { throw new IllegalArgumentException(M.t("categoria_inexistente", key)); } })
                        .whenComplete((v, e) -> sender.sendMessage(PREFIX + (e != null ? "§c" + root(e) : M.t("categoria_apagada", key))));
                return;
            }
            case "lista": {
                data.read(s -> {
                    if (s.catalog.isEmpty()) { sender.sendMessage(PREFIX + M.t("loja_do_servidor_vazia")); }
                    for (Map.Entry<String, ShopState.Category> e : s.catalog.entrySet()) {
                        sender.sendMessage(M.t("categoria_linha", e.getKey(), e.getValue().title.replace('&', '§'), e.getValue().offers.size()));
                        int i = 1;
                        for (ShopState.Offer o : e.getValue().offers) { sender.sendMessage(M.t("oferta_linha", i++, o.amount, Item.get(o.id, o.meta).getName(), ShopState.money(o.price))); }
                    }
                    return null;
                });
                return;
            }
            default: sender.sendMessage(PREFIX + M.t("uso_comprar_admin"));
        }
    }

    // ---------- utilitarios ----------

    private static void give(Player p, Item item) { for (Item rest : p.getInventory().addItem(item)) { p.level.dropItem(p, rest); } }

    private static int count(BaseInventory inv, int id, int meta) {
        int n = 0;
        for (Item it : inv.getContents().values()) { if (it.getId() == id && it.getDamage() == meta && !it.hasCompoundTag()) { n += it.getCount(); } }
        return n;
    }

    private static BlockEntityChest chestNear(Block sign) {
        int[][] around = {{0, -1, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int[] d : around) {
            BlockEntity tile = sign.level.getBlockEntity(new Vector3(sign.x + d[0], sign.y + d[1], sign.z + d[2]));
            if (tile instanceof BlockEntityChest) { return (BlockEntityChest) tile; }
        }
        return null;
    }

    private static BaseInventory chestAt(Block sign, Shop shop) {
        BlockEntity tile = sign.level.getBlockEntity(new Vector3(shop.chestX, shop.chestY, shop.chestZ));
        return tile instanceof BlockEntityChest ? ((BlockEntityChest) tile).getRealInventory() : null;
    }

    static String root(Throwable e) { while (e.getCause() != null) { e = e.getCause(); } return e.getMessage(); }
}
