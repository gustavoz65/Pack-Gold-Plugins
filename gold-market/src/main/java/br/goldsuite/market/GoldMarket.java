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
    static String PREFIX = M.t("leilao");
    private static final int PER_PAGE = 21;
    private PersistentState<MarketState> data;
    private final Set<String> busy = new HashSet<>();

    @Override public void onEnable() {
        M.B.attach(this);
        Settings.prefix(this, PREFIX, v -> PREFIX = v);
        CoreService core = getServer().getServiceManager().load(CoreService.class);
        if (core == null) { throw new IllegalStateException(M.t("goldcore_precisa_estar_ativo_antes_do")); }
        data = new PersistentState<>(core.getStore(), "goldmarket", MarketState.class, MarketState::new, MarketState::validate);
        // Vencimentos verificados a cada 5 minutos, nao a cada abertura de menu.
        getServer().getScheduler().scheduleRepeatingTask(new PluginTask<GoldMarket>(this) {
            @Override public void onRun(int tick) { if (data.isLoaded() && data.read(s -> s.listings.values().stream().anyMatch(l -> System.currentTimeMillis() - l.created > MarketState.DURATION_MS))) { data.change(s -> s.expire(System.currentTimeMillis())); } }
        }, 20 * 300);
    }

    @Override public void onDisable() { busy.clear(); if (data != null) { data.close(); } }

    private EconomyService economy() {
        EconomyService e = getServer().getServiceManager().load(EconomyService.class);
        if (e == null) { throw new IllegalStateException(M.t("goldeconomy_precisa_estar_instalado")); }
        return e;
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) { sender.sendMessage(M.t("use_dentro_do_jogo")); return true; }
        Player p = (Player) sender;
        AuthService auth = getServer().getServiceManager().load(AuthService.class);
        if (auth != null && !auth.isAuthenticated(p)) { p.sendMessage(M.t("autentique_se_primeiro")); return true; }
        String sub = args.length == 0 ? "ver" : args[0].toLowerCase(Locale.ROOT);
        try {
            switch (sub) {
                case "vender": sell(p, args); return true;
                case "coletar": collect(p); return true;
                case "meus": mine(p); return true;
                case "cancelar": cancel(p, args); return true;
                case "ver": menu(p, 0); return true;
                default:
                    p.sendMessage(PREFIX + M.t("leilao_ver_anuncios_vender_preco_meus"));
                    return true;
            }
        } catch (RuntimeException e) { p.sendMessage(PREFIX + "§c" + root(e)); return true; }
    }

    /** O item sai da mao antes de gravar; se a gravacao falhar, volta. */
    private void sell(Player p, String[] args) {
        if (args.length < 2) { throw new IllegalArgumentException(M.t("uso_leilao_vender_preco_com_item")); }
        long price = price(args[1]);
        Item hand = p.getInventory().getItemInHand();
        if (hand.getId() == 0) { throw new IllegalArgumentException(M.t("segure_item_que_quer_anunciar")); }
        String name = p.getName().toLowerCase(Locale.ROOT);
        if (!busy.add(name)) { return; }
        ItemData item = ItemData.of(hand);
        int slot = p.getInventory().getHeldItemIndex();
        p.getInventory().setItem(slot, Item.get(0));
        long[] id = new long[1];
        data.change(s -> {
            if (s.listings.size() >= MarketState.MAX_LISTINGS) { throw new IllegalArgumentException(M.t("leilao_esta_cheio")); }
            if (s.count(name) >= MarketState.PER_PLAYER) { throw new IllegalArgumentException(M.t("voce_ja_tem_anuncios", MarketState.PER_PLAYER)); }
            MarketState.Listing l = new MarketState.Listing();
            l.seller = name; l.item = item; l.price = price; l.created = System.currentTimeMillis();
            id[0] = s.nextId++;
            s.listings.put(id[0], l);
        }).whenComplete((v, e) -> {
            busy.remove(name);
            if (e != null) { give(p, item.toItem()); p.sendMessage(PREFIX + "§c" + root(e)); return; }
            if (p.isOnline()) { p.sendMessage(PREFIX + M.t("anuncio_criado_por_vence_em_48", id[0], money(price))); }
            getServer().broadcastMessage(PREFIX + M.t("anunciou_por_leilao", p.getName(), item.count, item.toItem().getName(), money(price)));
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
        if (all.isEmpty()) { p.sendMessage(PREFIX + M.t("nenhum_anuncio_venda_com_leilao_vender")); return; }
        int pages = (all.size() + PER_PAGE - 1) / PER_PAGE, current = Math.max(0, Math.min(page, pages - 1));
        Map<Integer, Item> icons = new HashMap<>();
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < PER_PAGE && current * PER_PAGE + i < all.size(); i++) {
            Map.Entry<Long, MarketState.Listing> e = all.get(current * PER_PAGE + i);
            Item icon = e.getValue().item.toItem();
            icon.setCustomName(M.t("vendedor_toque_para_comprar", icon.getCount(), icon.getName(), money(e.getValue().price), e.getValue().seller));
            icons.put(i, icon);
            ids.add(e.getKey());
        }
        if (current > 0) { icons.put(21, named(Item.get(262), M.t("pagina_anterior"))); }
        if (current < pages - 1) { icons.put(26, named(Item.get(262), M.t("proxima_pagina"))); }
        MenuService menus = getServer().getServiceManager().load(MenuService.class);
        menus.open(p, M.t("leilao_2", (current + 1), pages), icons, (who, slot) -> {
            if (slot == 21 && current > 0) { menu(who, current - 1); return; }
            if (slot == 26 && current < pages - 1) { menu(who, current + 1); return; }
            if (slot < ids.size()) { confirm(who, ids.get(slot)); }
        });
    }

    /** Segunda tela com Confirmar/Cancelar: no celular um toque errado nao compra nada. */
    private void confirm(Player p, long id) {
        MarketState.Listing l = data.read(s -> s.listings.get(id));
        if (l == null) { p.sendMessage(PREFIX + M.t("este_anuncio_ja_foi_vendido")); return; }
        Map<Integer, Item> icons = new HashMap<>();
        Item shown = l.item.toItem();
        icons.put(13, shown);
        icons.put(11, named(Item.get(35, 5, 1), M.t("confirmar_compra", money(l.price))));
        icons.put(15, named(Item.get(35, 14, 1), M.t("cancelar")));
        getServer().getServiceManager().load(MenuService.class).open(p, M.t("comprar_por", money(l.price)), icons, (who, slot) -> { if (slot == 11) { buy(who, id); } });
    }

    /** O anuncio sai do leilao antes da cobranca (ninguem compra duas vezes) e volta se o pagamento falhar. */
    private void buy(Player p, long id) {
        String buyer = p.getName().toLowerCase(Locale.ROOT);
        if (!busy.add(buyer)) { return; }
        MarketState.Listing[] taken = new MarketState.Listing[1];
        data.change(s -> {
            taken[0] = s.listings.remove(id);
            if (taken[0] == null) { throw new IllegalArgumentException(M.t("este_anuncio_ja_foi_vendido_2")); }
            if (taken[0].seller.equals(buyer)) { s.listings.put(id, taken[0]); throw new IllegalArgumentException(M.t("use_leilao_cancelar_para_retirar_seu", id)); }
        }).thenCompose(v -> economy().transfer(buyer, taken[0].seller, Currency.MONEY, taken[0].price).exceptionally(error -> {
            data.change(s -> s.listings.put(id, taken[0]));
            throw new IllegalStateException(root(error));
        })).whenComplete((v, e) -> {
            busy.remove(buyer);
            if (e != null) { if (p.isOnline()) { p.sendMessage(PREFIX + "§c" + root(e)); } return; }
            Item item = taken[0].item.toItem();
            if (p.isOnline()) { give(p, item); p.sendMessage(PREFIX + M.t("comprou_por", item.getCount(), item.getName(), money(taken[0].price))); }
            else { data.change(s -> s.giveBack(buyer, taken[0].item)); }
            Player seller = getServer().getPlayerExact(taken[0].seller);
            if (seller != null) { seller.sendMessage(PREFIX + M.t("comprou_seu_por", p.getName(), item.getName(), money(taken[0].price))); }
        });
    }

    private void cancel(Player p, String[] args) {
        if (args.length < 2) { throw new IllegalArgumentException(M.t("uso_leilao_cancelar_id_veja_em")); }
        long id = Long.parseLong(args[1]);
        String name = p.getName().toLowerCase(Locale.ROOT);
        data.change(s -> {
            MarketState.Listing l = s.listings.get(id);
            if (l == null || (!l.seller.equals(name) && !p.hasPermission("goldmarket.admin"))) { throw new IllegalArgumentException(M.t("anuncio_nao_encontrado")); }
            s.listings.remove(id);
            s.giveBack(l.seller, l.item);
        }).whenComplete((v, e) -> p.sendMessage(PREFIX + (e != null ? "§c" + root(e) : M.t("anuncio_cancelado_pegue_item_com_leilao"))));
    }

    private void mine(Player p) {
        String name = p.getName().toLowerCase(Locale.ROOT);
        List<String> lines = data.read(s -> {
            List<String> out = new ArrayList<>();
            for (Map.Entry<Long, MarketState.Listing> e : s.listings.entrySet()) { if (e.getValue().seller.equals(name)) { out.add("§e#" + e.getKey() + " §f" + e.getValue().item.count + "x " + e.getValue().item.toItem().getName() + " §a$" + money(e.getValue().price)); } }
            List<?> back = s.returns.get(name);
            if (back != null && !back.isEmpty()) { out.add(M.t("item_ns_para_coletar_leilao_coletar", back.size())); }
            return out;
        });
        if (lines.isEmpty()) { p.sendMessage(PREFIX + M.t("voce_nao_tem_anuncios")); return; }
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
            if (list == null || list.isEmpty()) { throw new IllegalArgumentException(M.t("nada_para_coletar")); }
            while (!list.isEmpty() && taken.size() < room) { taken.add(list.remove(0)); }
            if (list.isEmpty()) { s.returns.remove(name); }
            if (taken.isEmpty()) { throw new IllegalArgumentException(M.t("libere_espaco_no_inventario")); }
        }).whenComplete((v, e) -> {
            busy.remove(name);
            if (e != null) { p.sendMessage(PREFIX + "§c" + root(e)); return; }
            for (ItemData d : taken) { give(p, d.toItem()); }
            p.sendMessage(PREFIX + M.t("item_ns_coletado", taken.size()));
        });
    }

    private static void give(Player p, Item item) { for (Item rest : p.getInventory().addItem(item)) { p.level.dropItem(p, rest); } }
    private static Item named(Item item, String name) { item.setCustomName(name); return item; }
    static String money(long cents) { return BigDecimal.valueOf(cents, 2).toPlainString(); }

    static long price(String text) {
        String t = text.replace("$", "").replace(",", ".").trim();
        if (!t.matches("[0-9]{1,11}(\\.[0-9]{1,2})?")) { throw new IllegalArgumentException(M.t("preco_invalido_ex_100_ou_99")); }
        long cents = new BigDecimal(t).movePointRight(2).longValueExact();
        if (cents < 1 || cents > 1_000_000_000_00L) { throw new IllegalArgumentException(M.t("preco_fora_do_limite")); }
        return cents;
    }

    static String root(Throwable e) { while (e.getCause() != null) { e = e.getCause(); } return e.getMessage(); }
}
