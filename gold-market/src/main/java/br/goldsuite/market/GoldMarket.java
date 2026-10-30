package br.goldsuite.market;

import br.goldsuite.api.*;
import br.goldsuite.api.EconomyService.Currency;
import cn.nukkit.Player;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.item.Item;
import cn.nukkit.plugin.PluginBase;
import cn.nukkit.scheduler.PluginTask;
import java.math.BigDecimal;
import java.util.*;

/** Leilao: anuncios com prazo, compra pelo menu em bau e coleta de itens devolvidos. */
public final class GoldMarket extends PluginBase {
    static final String PREFIX = "§6[§eLeilao§6]§f ";
    private static final int PER_PAGE = 21;
    private PersistentState<MarketState> data;
    private final Set<String> busy = new HashSet<>();

    @Override public void onEnable() {
        CoreService core = getServer().getServiceManager().load(CoreService.class);
        if (core == null) { throw new IllegalStateException("GoldCore precisa estar ativo antes do GoldMarket."); }
        data = new PersistentState<>(core.getStore(), "goldmarket", MarketState.class, MarketState::new, MarketState::validate);
        // Vencimentos verificados a cada 5 minutos, nao a cada abertura de menu.
        getServer().getScheduler().scheduleRepeatingTask(new PluginTask<GoldMarket>(this) {
            @Override public void onRun(int tick) { if (data.isLoaded() && data.read(s -> s.listings.values().stream().anyMatch(l -> System.currentTimeMillis() - l.created > MarketState.DURATION_MS))) { data.change(s -> s.expire(System.currentTimeMillis())); } }
        }, 20 * 300);
    }

    @Override public void onDisable() { busy.clear(); if (data != null) { data.close(); } }

    private EconomyService economy() {
        EconomyService e = getServer().getServiceManager().load(EconomyService.class);
        if (e == null) { throw new IllegalStateException("O GoldEconomy precisa estar instalado."); }
        return e;
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) { sender.sendMessage("Use dentro do jogo."); return true; }
        Player p = (Player) sender;
        AuthService auth = getServer().getServiceManager().load(AuthService.class);
        if (auth != null && !auth.isAuthenticated(p)) { p.sendMessage("Autentique-se primeiro."); return true; }
        String sub = args.length == 0 ? "ver" : args[0].toLowerCase(Locale.ROOT);
        try {
            switch (sub) {
                case "vender": sell(p, args); return true;
                case "coletar": collect(p); return true;
                case "meus": mine(p); return true;
                case "cancelar": cancel(p, args); return true;
                case "ver": menu(p, 0); return true;
                default:
                    p.sendMessage(PREFIX + "/leilao (ver anuncios), vender <preco>, meus, cancelar <id>, coletar");
                    return true;
            }
        } catch (RuntimeException e) { p.sendMessage(PREFIX + "§c" + root(e)); return true; }
    }

    /** O item sai da mao antes de gravar; se a gravacao falhar, volta. */
    private void sell(Player p, String[] args) {
        if (args.length < 2) { throw new IllegalArgumentException("Uso: /leilao vender <preco> (com o item na mao)"); }
        long price = price(args[1]);
        Item hand = p.getInventory().getItemInHand();
        if (hand.getId() == 0) { throw new IllegalArgumentException("Segure o item que quer anunciar."); }
        String name = p.getName().toLowerCase(Locale.ROOT);
        if (!busy.add(name)) { return; }
        ItemData item = ItemData.of(hand);
        int slot = p.getInventory().getHeldItemIndex();
        p.getInventory().setItem(slot, Item.get(0));
        long[] id = new long[1];
        data.change(s -> {
            if (s.listings.size() >= MarketState.MAX_LISTINGS) { throw new IllegalArgumentException("O leilao esta cheio."); }
            if (s.count(name) >= MarketState.PER_PLAYER) { throw new IllegalArgumentException("Voce ja tem " + MarketState.PER_PLAYER + " anuncios."); }
            MarketState.Listing l = new MarketState.Listing();
            l.seller = name; l.item = item; l.price = price; l.created = System.currentTimeMillis();
            id[0] = s.nextId++;
            s.listings.put(id[0], l);
        }).whenComplete((v, e) -> {
            busy.remove(name);
            if (e != null) { give(p, item.toItem()); p.sendMessage(PREFIX + "§c" + root(e)); return; }
            if (p.isOnline()) { p.sendMessage(PREFIX + "Anuncio #" + id[0] + " criado por $" + money(price) + ". Vence em 48 h."); }
            getServer().broadcastMessage(PREFIX + "§e" + p.getName() + "§f anunciou " + item.count + "x " + item.toItem().getName() + " por §a$" + money(price) + "§f. /leilao");
        });
    }

    private void menu(Player p, int page) {
        List<Map.Entry<Long, MarketState.Listing>> all = data.read(s -> {
            long now = System.currentTimeMillis();
            List<Map.Entry<Long, MarketState.Listing>> list = new ArrayList<>();
            for (Map.Entry<Long, MarketState.Listing> e : s.listings.entrySet()) { if (now - e.getValue().created <= MarketState.DURATION_MS) { list.add(e); } }
            Collections.reverse(list);
            return list;
        });
        if (all.isEmpty()) { p.sendMessage(PREFIX + "Nenhum anuncio. Venda com /leilao vender <preco>."); return; }
        int pages = (all.size() + PER_PAGE - 1) / PER_PAGE, current = Math.max(0, Math.min(page, pages - 1));
        Map<Integer, Item> icons = new HashMap<>();
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < PER_PAGE && current * PER_PAGE + i < all.size(); i++) {
            Map.Entry<Long, MarketState.Listing> e = all.get(current * PER_PAGE + i);
            Item icon = e.getValue().item.toItem();
            icon.setCustomName("§e" + icon.getCount() + "x " + icon.getName() + "\n§a$" + money(e.getValue().price) + "\n§7Vendedor: " + e.getValue().seller + "\n§fToque para comprar");
            icons.put(i, icon);
            ids.add(e.getKey());
        }
        if (current > 0) { icons.put(21, named(Item.get(262), "§f< Pagina anterior")); }
        if (current < pages - 1) { icons.put(26, named(Item.get(262), "§fProxima pagina >")); }
        MenuService menus = getServer().getServiceManager().load(MenuService.class);
        menus.open(p, "Leilao (" + (current + 1) + "/" + pages + ")", icons, (who, slot) -> {
            if (slot == 21 && current > 0) { menu(who, current - 1); return; }
            if (slot == 26 && current < pages - 1) { menu(who, current + 1); return; }
            if (slot < ids.size()) { confirm(who, ids.get(slot)); }
        });
    }

    /** Segunda tela com Confirmar/Cancelar: no celular um toque errado nao compra nada. */
    private void confirm(Player p, long id) {
        MarketState.Listing l = data.read(s -> s.listings.get(id));
        if (l == null) { p.sendMessage(PREFIX + "§cEste anuncio ja foi vendido."); return; }
        Map<Integer, Item> icons = new HashMap<>();
        Item shown = l.item.toItem();
        icons.put(13, shown);
        icons.put(11, named(Item.get(35, 5, 1), "§aConfirmar compra\n§f$" + money(l.price)));
        icons.put(15, named(Item.get(35, 14, 1), "§cCancelar"));
        getServer().getServiceManager().load(MenuService.class).open(p, "Comprar por $" + money(l.price) + "?", icons, (who, slot) -> { if (slot == 11) { buy(who, id); } });
    }

    /** O anuncio sai do leilao antes da cobranca (ninguem compra duas vezes) e volta se o pagamento falhar. */
    private void buy(Player p, long id) {
        String buyer = p.getName().toLowerCase(Locale.ROOT);
        if (!busy.add(buyer)) { return; }
        MarketState.Listing[] taken = new MarketState.Listing[1];
        data.change(s -> {
            taken[0] = s.listings.remove(id);
            if (taken[0] == null) { throw new IllegalArgumentException("Este anuncio ja foi vendido."); }
            if (taken[0].seller.equals(buyer)) { s.listings.put(id, taken[0]); throw new IllegalArgumentException("Use /leilao cancelar " + id + " para retirar seu anuncio."); }
        }).thenCompose(v -> economy().transfer(buyer, taken[0].seller, Currency.MONEY, taken[0].price).exceptionally(error -> {
            data.change(s -> s.listings.put(id, taken[0]));
            throw new IllegalStateException(root(error));
        })).whenComplete((v, e) -> {
            busy.remove(buyer);
            if (e != null) { if (p.isOnline()) { p.sendMessage(PREFIX + "§c" + root(e)); } return; }
            Item item = taken[0].item.toItem();
            if (p.isOnline()) { give(p, item); p.sendMessage(PREFIX + "Comprou " + item.getCount() + "x " + item.getName() + " por $" + money(taken[0].price) + "."); }
            else { data.change(s -> s.giveBack(buyer, taken[0].item)); }
            Player seller = getServer().getPlayerExact(taken[0].seller);
            if (seller != null) { seller.sendMessage(PREFIX + "§a" + p.getName() + " comprou seu " + item.getName() + " por $" + money(taken[0].price) + "!"); }
        });
    }

    private void cancel(Player p, String[] args) {
        if (args.length < 2) { throw new IllegalArgumentException("Uso: /leilao cancelar <id> (veja em /leilao meus)"); }
        long id = Long.parseLong(args[1]);
        String name = p.getName().toLowerCase(Locale.ROOT);
        data.change(s -> {
            MarketState.Listing l = s.listings.get(id);
            if (l == null || (!l.seller.equals(name) && !p.hasPermission("goldmarket.admin"))) { throw new IllegalArgumentException("Anuncio nao encontrado."); }
            s.listings.remove(id);
            s.giveBack(l.seller, l.item);
        }).whenComplete((v, e) -> p.sendMessage(PREFIX + (e != null ? "§c" + root(e) : "Anuncio cancelado. Pegue o item com /leilao coletar.")));
    }

    private void mine(Player p) {
        String name = p.getName().toLowerCase(Locale.ROOT);
        List<String> lines = data.read(s -> {
            List<String> out = new ArrayList<>();
            for (Map.Entry<Long, MarketState.Listing> e : s.listings.entrySet()) { if (e.getValue().seller.equals(name)) { out.add("§e#" + e.getKey() + " §f" + e.getValue().item.count + "x " + e.getValue().item.toItem().getName() + " §a$" + money(e.getValue().price)); } }
            List<?> back = s.returns.get(name);
            if (back != null && !back.isEmpty()) { out.add("§d" + back.size() + " item(ns) para coletar: /leilao coletar"); }
            return out;
        });
        if (lines.isEmpty()) { p.sendMessage(PREFIX + "Voce nao tem anuncios."); return; }
        for (String l : lines) { p.sendMessage(l); }
    }

    /** Remove da lista antes de entregar; o que nao couber continua guardado. */
    private void collect(Player p) {
        String name = p.getName().toLowerCase(Locale.ROOT);
        if (!busy.add(name)) { return; }
        int free = 0;
        for (int i = 0; i < 36; i++) { if (p.getInventory().getItem(i).getId() == 0) { free++; } }
        int room = free;
        List<ItemData> taken = new ArrayList<>();
        data.change(s -> {
            List<ItemData> list = s.returns.get(name);
            if (list == null || list.isEmpty()) { throw new IllegalArgumentException("Nada para coletar."); }
            while (!list.isEmpty() && taken.size() < room) { taken.add(list.remove(0)); }
            if (list.isEmpty()) { s.returns.remove(name); }
            if (taken.isEmpty()) { throw new IllegalArgumentException("Libere espaco no inventario."); }
        }).whenComplete((v, e) -> {
            busy.remove(name);
            if (e != null) { p.sendMessage(PREFIX + "§c" + root(e)); return; }
            for (ItemData d : taken) { give(p, d.toItem()); }
            p.sendMessage(PREFIX + taken.size() + " item(ns) coletado(s).");
        });
    }

    private static void give(Player p, Item item) { for (Item rest : p.getInventory().addItem(item)) { p.level.dropItem(p, rest); } }
    private static Item named(Item item, String name) { item.setCustomName(name); return item; }
    static String money(long cents) { return BigDecimal.valueOf(cents, 2).toPlainString(); }

    static long price(String text) {
        String t = text.replace("$", "").replace(",", ".").trim();
        if (!t.matches("[0-9]{1,11}(\\.[0-9]{1,2})?")) { throw new IllegalArgumentException("Preco invalido (ex.: 100 ou 99,90)."); }
        long cents = new BigDecimal(t).movePointRight(2).longValueExact();
        if (cents < 1 || cents > 1_000_000_000_00L) { throw new IllegalArgumentException("Preco fora do limite."); }
        return cents;
    }

    static String root(Throwable e) { while (e.getCause() != null) { e = e.getCause(); } return e.getMessage(); }
}
