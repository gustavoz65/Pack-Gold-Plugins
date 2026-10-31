package br.goldsuite.keys;

import br.goldsuite.api.*;
import br.goldsuite.keys.KeyState.Key;
import br.goldsuite.keys.KeyState.Order;
import br.goldsuite.keys.KeyState.Product;
import cn.nukkit.Player;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.plugin.PluginBase;
import cn.nukkit.scheduler.PluginTask;
import com.google.gson.Gson;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Keys: um codigo unico libera cash, dinheiro, cosmeticos e VIP. Ciclo: pedido (PENDENTE) -> pago (PAGA, com validade)
 * -> resgatado uma unica vez (RESGATADA). So o hash do codigo fica no banco. Pedidos vao para a staff e para o Discord;
 * a confirmacao do Pix e feita pela staff (/key pago) ate o pagamento automatico ser ligado.
 */
public final class GoldKeys extends PluginBase {
    static String PREFIX = "§6[§eKeys§6]§f ";
    static final String KEYS = "goldkeys-chave";
    private static final Gson GSON = new Gson();

    private CoreService core;
    private PersistentState<KeyState> data;
    /** Codigos sendo resgatados agora: o mesmo codigo nunca e processado duas vezes ao mesmo tempo. */
    private final Set<String> busy = new HashSet<>();
    private final Map<String, long[]> wrong = new HashMap<>();
    private long validityHours = 72, pendingHours = 48, maxPending = 3;
    private String pixMessage;

    @Override public void onEnable() {
        Settings.prefix(this, PREFIX, v -> PREFIX = v);
        Settings.number(this, "validade_horas", 72, "Horas que uma key paga fica valida para resgate", v -> validityHours = Math.max(1, v));
        Settings.number(this, "pedido_horas", 48, "Horas que um pedido espera o pagamento antes de cancelar", v -> pendingHours = Math.max(1, v));
        Settings.number(this, "pedidos_por_jogador", 3, "Pedidos abertos ao mesmo tempo por jogador", v -> maxPending = Math.max(1, v));
        Settings.text(this, "msg.pix", "{destaque}Pague pelo Pix e mande o comprovante no Discord com o numero do pedido.", "Como pagar (chave Pix, link do Discord...)", v -> pixMessage = v);
        core = getServer().getServiceManager().load(CoreService.class);
        if (core == null) { throw new IllegalStateException("GoldCore precisa estar ativo antes do GoldKeys."); }
        data = new PersistentState<>(core.getStore(), "goldkeys", KeyState.class, KeyState::new, KeyState::validate);
        // Pedidos sem pagamento vencem sozinhos (a cada 10 min, so o mapa pequeno de pendentes).
        getServer().getScheduler().scheduleRepeatingTask(new PluginTask<GoldKeys>(this) { @Override public void onRun(int tick) { expireOrders(); } }, 20 * 600);
    }

    @Override public void onDisable() { busy.clear(); wrong.clear(); if (data != null) { data.close(); } }

    static String key(String name) { return name.toLowerCase(Locale.ROOT); }

    private CompletableFuture<Key> load(String hash) {
        return core.getStore().get(KEYS, hash).thenApply(v -> v.isPresent() ? GSON.fromJson(v.get(), Key.class) : null);
    }

    private CompletableFuture<Void> save(String hash, Key k) { return core.getStore().put(KEYS, hash, GSON.toJson(k)); }

    private void expireOrders() {
        if (!data.isLoaded()) { return; }
        long limit = System.currentTimeMillis() - pendingHours * 3_600_000L;
        List<String> old = data.read(s -> { List<String> out = new ArrayList<>(); for (Map.Entry<String, Order> e : s.pending.entrySet()) { if (e.getValue().created < limit) { out.add(e.getKey()); } } return out; });
        if (old.isEmpty()) { return; }
        data.change(s -> { for (String id : old) { Order o = s.pending.remove(id); if (o != null) { cancelKey(o.keyHash); } } });
    }

    private void cancelKey(String hash) {
        load(hash).thenCompose(k -> { if (k == null || !KeyState.PENDENTE.equals(k.status)) { return CompletableFuture.completedFuture(null); } k.status = KeyState.CANCELADA; return save(hash, k); });
    }

    // ---------- comandos ----------

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!data.isLoaded()) { sender.sendMessage(PREFIX + "Carregando, tente em instantes."); return true; }
        String sub = args.length == 0 ? "ajuda" : args[0].toLowerCase(Locale.ROOT);
        boolean admin = sender.hasPermission("goldkeys.admin");
        try {
            switch (sub) {
                case "produtos": listProducts(sender); return true;
                case "comprar": order(sender, arg(args, 1)); return true;
                case "resgatar": redeem(sender, args.length > 1 ? String.join("", Arrays.copyOfRange(args, 1, args.length)) : ""); return true;
                case "pago": if (admin) { paid(sender, arg(args, 1).toUpperCase(Locale.ROOT)); return true; } break;
                case "cancelar": if (admin) { cancel(sender, arg(args, 1).toUpperCase(Locale.ROOT)); return true; } break;
                case "pedidos": if (admin) { listOrders(sender); return true; } break;
                case "gerar": if (admin) { generate(sender, arg(args, 1), args.length > 2 ? Integer.parseInt(args[2]) : 1); return true; } break;
                case "produto": if (admin) { product(sender, args); return true; } break;
                default: break;
            }
            sender.sendMessage(PREFIX + "/key produtos, /key comprar <produto>, /key resgatar <codigo>");
            if (admin) { sender.sendMessage(PREFIX + "Staff: /key pedidos, /key pago <pedido>, /key cancelar <pedido>, /key gerar <produto> [qtd], /key produto criar <id> <preco> <nome> | <id> cash|dinheiro|cosmetico|vip|remover ..."); }
        } catch (NumberFormatException e) { sender.sendMessage(PREFIX + "§cNumero invalido."); }
        catch (RuntimeException e) { sender.sendMessage(PREFIX + "§c" + e.getMessage()); }
        return true;
    }

    private void listProducts(CommandSender sender) {
        Map<String, Product> products = data.read(s -> new TreeMap<>(s.products));
        if (products.isEmpty()) { sender.sendMessage(PREFIX + "Nenhum produto a venda ainda."); return; }
        sender.sendMessage(PREFIX + "§6Produtos:");
        for (Map.Entry<String, Product> e : products.entrySet()) {
            sender.sendMessage("§e" + e.getKey() + " §f- " + e.getValue().name + " §a" + KeyState.price(e.getValue().priceCents) + " §7(" + e.getValue().rewards() + ")");
        }
        sender.sendMessage("§7Compre com /key comprar <produto>");
    }

    /** Pedido do jogador: cria a key PENDENTE e mostra o codigo; ela so passa a valer quando a staff confirmar o pagamento. */
    private void order(CommandSender sender, String productId) {
        if (!(sender instanceof Player)) { throw new IllegalArgumentException("Use dentro do jogo."); }
        Player p = (Player) sender;
        String id = productId.toLowerCase(Locale.ROOT), name = key(p.getName());
        Product product = data.read(s -> s.products.get(id));
        if (product == null) { throw new IllegalArgumentException("Produto inexistente. Veja /key produtos."); }
        long open = data.read(s -> s.pending.values().stream().filter(o -> o.player.equals(name)).count());
        if (open >= maxPending) { throw new IllegalArgumentException("Voce ja tem " + open + " pedidos esperando pagamento."); }
        String code = Codes.newCode(), hash = Codes.hash(code);
        String orderId = data.read(s -> { String o; do { o = Codes.newOrder(); } while (s.pending.containsKey(o)); return o; });
        Key k = new Key();
        k.product = id; k.owner = name; k.order = orderId; k.status = KeyState.PENDENTE; k.created = System.currentTimeMillis();
        save(hash, k).thenCompose(v -> data.change(s -> { Order o = new Order(); o.player = name; o.product = id; o.keyHash = hash; o.created = k.created; s.pending.put(orderId, o); }))
                .whenComplete((v, e) -> {
                    if (e != null) { if (p.isOnline()) { p.sendMessage(PREFIX + "§cNao foi possivel criar o pedido."); } return; }
                    Settings.alert("Loja", "Pedido #" + orderId + ": " + p.getName() + " quer " + product.name + " (" + KeyState.price(product.priceCents) + "). Confirme o Pix com /key pago " + orderId);
                    if (!p.isOnline()) { return; }
                    p.sendMessage(PREFIX + "§aPedido §e#" + orderId + "§a criado: " + product.name + " por " + KeyState.price(product.priceCents) + ".");
                    p.sendMessage(PREFIX + "Sua key: §e§l" + code + " §7(guarde! so vale depois do pagamento)");
                    p.sendMessage(PREFIX + pixMessage);
                    p.sendMessage(PREFIX + "Depois de confirmado, use §e/key resgatar " + code);
                });
    }

    private void paid(CommandSender sender, String orderId) {
        Order o = data.read(s -> s.pending.get(orderId));
        if (o == null) { throw new IllegalArgumentException("Pedido #" + orderId + " nao esta aguardando pagamento."); }
        load(o.keyHash).thenCompose(k -> {
            if (k == null || !KeyState.PENDENTE.equals(k.status)) { throw new IllegalStateException("A key do pedido nao esta pendente."); }
            k.status = KeyState.PAGA; k.paidAt = System.currentTimeMillis(); k.expires = k.paidAt + validityHours * 3_600_000L;
            return save(o.keyHash, k);
        }).thenCompose(v -> data.change(s -> s.pending.remove(orderId))).whenComplete((v, e) -> {
            if (e != null) { sender.sendMessage(PREFIX + "§c" + root(e)); return; }
            sender.sendMessage(PREFIX + "Pedido #" + orderId + " pago. A key de " + o.player + " vale por " + validityHours + " h.");
            Settings.alert("Loja", "Pedido #" + orderId + " de " + o.player + " confirmado por " + sender.getName());
            Player buyer = getServer().getPlayerExact(o.player);
            if (buyer != null) { buyer.sendMessage(PREFIX + "§aPagamento do pedido #" + orderId + " confirmado! §fUse /key resgatar <sua key> (vale " + validityHours + " h)."); }
        });
    }

    private void cancel(CommandSender sender, String orderId) {
        Order o = data.read(s -> s.pending.get(orderId));
        if (o == null) { throw new IllegalArgumentException("Pedido inexistente."); }
        data.change(s -> s.pending.remove(orderId)).whenComplete((v, e) -> { cancelKey(o.keyHash); sender.sendMessage(PREFIX + "Pedido #" + orderId + " cancelado."); });
    }

    private void listOrders(CommandSender sender) {
        Map<String, Order> pending = data.read(s -> new TreeMap<>(s.pending));
        if (pending.isEmpty()) { sender.sendMessage(PREFIX + "Nenhum pedido aguardando pagamento."); return; }
        for (Map.Entry<String, Order> e : pending.entrySet()) {
            long min = (System.currentTimeMillis() - e.getValue().created) / 60_000;
            sender.sendMessage("§e#" + e.getKey() + " §f" + e.getValue().player + " - " + e.getValue().product + " §7(ha " + min + " min)");
        }
    }

    /** Keys de presente (sem dono, ja pagas): os codigos aparecem so para quem gerou. */
    private void generate(CommandSender sender, String productId, int amount) {
        String id = productId.toLowerCase(Locale.ROOT);
        if (data.read(s -> !s.products.containsKey(id))) { throw new IllegalArgumentException("Produto inexistente."); }
        if (amount < 1 || amount > 20) { throw new IllegalArgumentException("Gere de 1 a 20 por vez."); }
        for (int i = 0; i < amount; i++) {
            String code = Codes.newCode();
            Key k = new Key();
            k.product = id; k.status = KeyState.PAGA; k.created = k.paidAt = System.currentTimeMillis(); k.expires = k.paidAt + validityHours * 3_600_000L;
            save(Codes.hash(code), k).whenComplete((v, e) -> sender.sendMessage(e != null ? PREFIX + "§cFalha ao gravar uma key." : PREFIX + "§e" + code + " §7(" + id + ", " + validityHours + " h)"));
        }
    }

    /** Resgate: confere tudo, marca RESGATADA no banco e so depois entrega (nunca entrega duas vezes). */
    private void redeem(CommandSender sender, String typed) {
        if (!(sender instanceof Player)) { throw new IllegalArgumentException("Use dentro do jogo."); }
        Player p = (Player) sender;
        String name = key(p.getName());
        long now = System.currentTimeMillis();
        long[] w = wrong.computeIfAbsent(name, k -> new long[2]);
        if (now - w[1] > 60_000) { w[0] = 0; w[1] = now; }
        if (w[0] >= 5) { throw new IllegalArgumentException("Muitas keys invalidas. Aguarde um minuto."); }
        String code = Codes.normalize(typed);
        if (code == null) { w[0]++; throw new IllegalArgumentException("Formato de key invalido (GOLD-XXXXX-XXXXX-XXXXX-XXXXX)."); }
        String hash = Codes.hash(code);
        if (!busy.add(hash)) { throw new IllegalArgumentException("Essa key ja esta sendo resgatada."); }
        Product[] product = new Product[1];
        load(hash).thenCompose(k -> {
            String problem = k == null ? "Key inexistente." : KeyState.RESGATADA.equals(k.status) ? "Essa key ja foi usada." : KeyState.CANCELADA.equals(k.status) ? "Essa key foi cancelada."
                    : KeyState.PENDENTE.equals(k.status) ? "O pagamento dessa key ainda nao foi confirmado." : k.expires < now ? "Essa key venceu." : k.owner != null && !k.owner.equals(name) ? "Essa key pertence a outro jogador." : null;
            if (problem == null) { product[0] = data.read(s -> s.products.get(k.product)); if (product[0] == null) { problem = "O produto dessa key nao existe mais. Fale com a staff."; } }
            if (problem != null) { if (k == null) { w[0]++; } throw new IllegalStateException(problem); }
            k.status = KeyState.RESGATADA; k.redeemedBy = name; k.redeemedAt = now;
            return save(hash, k);
        }).whenComplete((v, e) -> {
            busy.remove(hash);
            if (e != null) { if (p.isOnline()) { p.sendMessage(PREFIX + "§c" + root(e)); } return; }
            deliver(p, name, product[0]);
        });
    }

    private void deliver(Player p, String name, Product product) {
        List<String> failed = new ArrayList<>();
        EconomyService eco = getServer().getServiceManager().load(EconomyService.class);
        if (product.cash > 0) { if (eco == null) { failed.add("cash"); } else { eco.credit(name, EconomyService.Currency.CASH, product.cash); } }
        if (product.moneyCents > 0) { if (eco == null) { failed.add("dinheiro"); } else { eco.credit(name, EconomyService.Currency.MONEY, product.moneyCents); } }
        CosmeticService cos = getServer().getServiceManager().load(CosmeticService.class);
        for (String c : product.cosmetics) { if (cos == null || !cos.exists(c)) { failed.add(c); } else { cos.unlock(name, c); } }
        if (product.vipGroup != null) {
            PermissionService perms = getServer().getServiceManager().load(PermissionService.class);
            if (perms == null) { failed.add("vip"); } else { perms.setGroup(name, product.vipGroup, product.vipDays > 0 ? System.currentTimeMillis() + product.vipDays * 86_400_000L : 0); }
        }
        Settings.alert("Loja", name + " resgatou " + product.name + (failed.isEmpty() ? "" : " - FALHOU entregar: " + failed));
        if (p.isOnline()) { p.sendMessage(PREFIX + "§aKey resgatada! §fVoce recebeu: " + product.rewards() + (failed.isEmpty() ? "" : " §c(avise a staff: " + failed + ")")); }
    }

    /** /key produto criar <id> <preco> <nome...> | /key produto <id> cash <n> | dinheiro <valor> | cosmetico <id> | vip <grupo> <dias> | remover */
    private void product(CommandSender sender, String[] args) {
        String a1 = arg(args, 1).toLowerCase(Locale.ROOT);
        if (a1.equals("criar")) {
            String id = arg(args, 2).toLowerCase(Locale.ROOT);
            long price = Math.round(Double.parseDouble(arg(args, 3).replace(',', '.')) * 100);
            String nome = String.join(" ", Arrays.copyOfRange(args, Math.min(4, args.length), args.length));
            change(sender, s -> { Product p = new Product(); p.name = nome.isEmpty() ? id : nome; p.priceCents = price; s.products.put(id, p); }, "Produto " + id + " criado. Configure o que ele entrega: /key produto " + id + " cash|dinheiro|cosmetico|vip");
            return;
        }
        String what = arg(args, 2).toLowerCase(Locale.ROOT);
        change(sender, s -> {
            Product p = s.products.get(a1);
            if (p == null) { throw new IllegalArgumentException("Produto inexistente."); }
            switch (what) {
                case "cash": p.cash = Long.parseLong(arg(args, 3)); break;
                case "dinheiro": p.moneyCents = Math.round(Double.parseDouble(arg(args, 3).replace(',', '.')) * 100); break;
                case "cosmetico": { String c = arg(args, 3).toLowerCase(Locale.ROOT); if (!p.cosmetics.remove(c)) { p.cosmetics.add(c); } break; }
                case "vip": p.vipGroup = arg(args, 3).toLowerCase(Locale.ROOT); p.vipDays = args.length > 4 ? Integer.parseInt(args[4]) : 0; break;
                case "remover": s.products.remove(a1); break;
                default: throw new IllegalArgumentException("Use cash, dinheiro, cosmetico, vip ou remover.");
            }
        }, "Produto " + a1 + " atualizado.");
    }

    private void change(CommandSender sender, java.util.function.Consumer<KeyState> mutation, String ok) {
        data.change(mutation).whenComplete((v, e) -> sender.sendMessage(PREFIX + (e != null ? "§c" + root(e) : ok)));
    }

    private static String arg(String[] args, int i) { if (args.length <= i) { throw new IllegalArgumentException("Faltam argumentos. Use /key."); } return args[i]; }
    static String root(Throwable e) { while (e.getCause() != null) { e = e.getCause(); } return e.getMessage(); }
}
