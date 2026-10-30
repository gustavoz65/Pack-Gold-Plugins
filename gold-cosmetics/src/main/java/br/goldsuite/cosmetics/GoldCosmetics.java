package br.goldsuite.cosmetics;

import br.goldsuite.api.*;
import br.goldsuite.cosmetics.Catalog.Cosmetic;
import br.goldsuite.cosmetics.Catalog.Type;
import cn.nukkit.Player;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.entity.EntityDamageByEntityEvent;
import cn.nukkit.event.entity.EntityDamageEvent;
import cn.nukkit.event.player.PlayerChatEvent;
import cn.nukkit.event.player.PlayerDeathEvent;
import cn.nukkit.event.player.PlayerJoinEvent;
import cn.nukkit.event.player.PlayerQuitEvent;
import cn.nukkit.item.Item;
import cn.nukkit.level.Level;
import cn.nukkit.level.particle.*;
import cn.nukkit.level.sound.ExplodeSound;
import cn.nukkit.math.Vector3;
import cn.nukkit.plugin.PluginBase;
import cn.nukkit.scheduler.PluginTask;
import com.google.gson.Gson;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Cosmeticos: pingente e cor do nome no chat, trilha de particulas ao andar e efeito ao matar.
 * Uns se compram com dinheiro do GoldEconomy; outros so se ganham no ranked (GoldPvP libera pelo CosmeticService).
 */
public final class GoldCosmetics extends PluginBase implements Listener, CosmeticService {
    static String PREFIX = "§5[§dCosmeticos§5]§f ";
    static final String STORE = "goldcos-guarda-roupa";
    private static final Gson GSON = new Gson();

    private CoreService core;
    private final Map<Player, Wardrobe> online = new IdentityHashMap<>();
    /** Gravacoes por jogador em fila: liberar, comprar e equipar nunca se atropelam. */
    private final Map<String, CompletableFuture<Void>> chains = new HashMap<>();
    private final Set<String> buying = new HashSet<>();
    /** Ultima posicao de quem tem trilha, para so soltar particulas quando anda. */
    private final Map<Player, double[]> lastPos = new IdentityHashMap<>();
    private int trailTick;
    /** Modelos 3D em pacotes (nuvem e chaveiro) de cada jogador. */
    private final Map<Player, PacketModel> mounts = new IdentityHashMap<>(), keychains = new IdentityHashMap<>();
    /** Precos editaveis no /painel (centavos ou cash). */
    private final Map<String, Long> prices = new HashMap<>();
    private String noBalance, rechargeHint;

    @Override public void onEnable() {
        Settings.prefix(this, PREFIX, v -> PREFIX = v);
        core = getServer().getServiceManager().load(CoreService.class);
        if (core == null) { throw new IllegalStateException("GoldCore precisa estar ativo antes do GoldCosmetics."); }
        getServer().getServiceManager().register(CosmeticService.class, this, this);
        getServer().getPluginManager().registerEvents(this, this);
        for (Player p : getServer().getOnlinePlayers().values()) { load(p); }
        getServer().getScheduler().scheduleRepeatingTask(new PluginTask<GoldCosmetics>(this) { @Override public void onRun(int tick) { trails(); } }, 4);
        getServer().getScheduler().scheduleRepeatingTask(new PluginTask<GoldCosmetics>(this) { @Override public void onRun(int tick) { models(); } }, 1);
        for (Cosmetic c : Catalog.ALL.values()) {
            if (c.forSale()) { Settings.number(this, "preco." + c.id, c.price, "Preco de " + c.name + (c.cash ? " (cash)" : " (centavos: 10000 = $100.00)"), v -> prices.put(c.id, Math.max(1, v))); }
        }
        Settings.text(this, "msg.sem_saldo", "{erro}Saldo insuficiente: {item} custa {preco}.", "Aviso de saldo insuficiente ({item}, {preco})", v -> noBalance = v);
        Settings.text(this, "msg.recarregar", "{destaque}Recarregue seu cash para comprar. Fale com a staff ou use a loja do servidor.", "Como recarregar o cash (itens pagos em cash)", v -> rechargeHint = v);
    }

    long price(Cosmetic c) { return prices.getOrDefault(c.id, c.price); }

    @Override public void onDisable() {
        for (PacketModel m : mounts.values()) { m.remove(getServer()); }
        for (PacketModel m : keychains.values()) { m.remove(getServer()); }
        mounts.clear(); keychains.clear(); online.clear(); lastPos.clear(); buying.clear();
    }

    /**
     * Todo tick (o cliente puxa os modelos para baixo se ficarem sem atualizacao): nuvem e chaveiro de quem tem e esta no lobby (mundo padrao). Fora do lobby, em espectador
     * ou com TPS abaixo de 15 os modelos somem; nada disso existe no servidor, so em pacotes.
     */
    private void models() {
        boolean healthy = getServer().getTicksPerSecond() >= 15;
        for (Map.Entry<Player, Wardrobe> e : online.entrySet()) {
            Player p = e.getKey();
            boolean show = healthy && p.spawned && p.level != null && p.level == getServer().getDefaultLevel() && p.getGamemode() != Player.SPECTATOR;
            String mount = show ? e.getValue().equipped(Type.MONTARIA) : null, badge = show ? e.getValue().equipped(Type.PINGENTE) : null;
            sync(mounts, p, mount, () -> PacketModel.cloud(p));
            Cosmetic c = badge == null ? null : Catalog.ALL.get(badge);
            sync(keychains, p, badge, () -> PacketModel.keychain(p, c.icon, c.meta));
        }
    }

    private void sync(Map<Player, PacketModel> map, Player p, String id, java.util.function.Supplier<PacketModel> make) {
        PacketModel m = map.get(p);
        if (id == null) { if (m != null) { m.remove(getServer()); map.remove(p); } return; }
        if (m == null) { m = make.get(); map.put(p, m); }
        m.update(getServer());
    }

    static String key(String name) { return name.toLowerCase(Locale.ROOT); }

    // ---------- dados ----------

    /** Encadeia a operacao depois das anteriores do mesmo jogador. */
    private <T> CompletableFuture<T> serial(String name, java.util.function.Supplier<CompletableFuture<T>> op) {
        CompletableFuture<Void> before = chains.getOrDefault(name, CompletableFuture.completedFuture(null));
        CompletableFuture<T> result = before.handle((v, e) -> null).thenCompose(v -> op.get());
        CompletableFuture<Void> tail = result.handle((v, e) -> null);
        chains.put(name, tail);
        tail.thenRun(() -> { if (chains.get(name) == tail) { chains.remove(name); } });
        return result;
    }

    private CompletableFuture<Wardrobe> read(String name) {
        return core.getStore().get(STORE, name).thenApply(v -> {
            if (!v.isPresent()) { return new Wardrobe(); }
            try { return GSON.fromJson(v.get(), Wardrobe.class).clean(); } catch (RuntimeException bad) { return new Wardrobe(); }
        });
    }

    private Player onlinePlayer(String name) {
        for (Player p : online.keySet()) { if (key(p.getName()).equals(name)) { return p; } }
        return null;
    }

    private void load(Player p) {
        String name = key(p.getName());
        serial(name, () -> read(name)).whenComplete((w, e) -> { if (isEnabled() && p.isOnline()) { online.put(p, e == null ? w : new Wardrobe()); } });
    }

    /** Muda o guarda-roupa (cache se online, banco sempre) em ordem; so confirma depois de gravar. */
    private CompletableFuture<Boolean> modify(String name, java.util.function.Function<Wardrobe, Boolean> change) {
        return serial(name, () -> {
            Player p = onlinePlayer(name);
            CompletableFuture<Wardrobe> source = p != null && online.get(p) != null ? CompletableFuture.completedFuture(online.get(p)) : read(name);
            return source.thenCompose(w -> {
                Wardrobe copy = GSON.fromJson(GSON.toJson(w), Wardrobe.class).clean();
                boolean result = change.apply(copy);
                return core.getStore().put(STORE, name, GSON.toJson(copy)).thenApply(v -> {
                    Player now = onlinePlayer(name);
                    if (now != null) { online.put(now, copy); }
                    return result;
                });
            });
        });
    }

    @Override public CompletableFuture<Boolean> unlock(String player, String id) {
        if (!Catalog.ALL.containsKey(id)) { CompletableFuture<Boolean> f = new CompletableFuture<>(); f.completeExceptionally(new IllegalArgumentException("Cosmetico inexistente: " + id)); return f; }
        String name = key(player);
        Player p = onlinePlayer(name);
        // Ja tem: responde na hora, sem gravar (o GoldPvP pergunta a cada partida).
        if (p != null && online.get(p) != null && online.get(p).owned.contains(id)) { return CompletableFuture.completedFuture(false); }
        return modify(name, w -> w.owned.add(id));
    }

    @Override public boolean exists(String id) { return Catalog.ALL.containsKey(id); }

    @EventHandler(priority = EventPriority.MONITOR) public void join(PlayerJoinEvent e) { load(e.getPlayer()); }
    @EventHandler(priority = EventPriority.MONITOR) public void quit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        online.remove(p); lastPos.remove(p);
        PacketModel m = mounts.remove(p); if (m != null) { m.remove(getServer()); }
        m = keychains.remove(p); if (m != null) { m.remove(getServer()); }
        for (PacketModel other : mounts.values()) { other.forget(p); }
        for (PacketModel other : keychains.values()) { other.forget(p); }
    }

    private Cosmetic equipped(Player p, Type type) {
        Wardrobe w = online.get(p);
        String id = w == null ? null : w.equipped(type);
        return id == null ? null : Catalog.ALL.get(id);
    }

    // ---------- efeitos ----------

    /** Pingente antes do nome e cor do nome no chat. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void chat(PlayerChatEvent e) {
        Cosmetic color = equipped(e.getPlayer(), Type.COR);
        if (color == null) { return; }
        e.setFormat(e.getFormat().replace("{%0}", color.display + "{%0}§r"));
    }

    /** A cada 4 ticks, uma particula nos pes de quem tem trilha e se mexeu. Desliga sozinho com TPS baixo. */
    private void trails() {
        if (online.isEmpty() || getServer().getTicksPerSecond() < 16) { return; }
        trailTick++;
        for (Map.Entry<Player, Wardrobe> e : online.entrySet()) {
            Player p = e.getKey();
            String id = e.getValue().equipped(Type.TRILHA);
            if (id == null || !p.spawned || p.getGamemode() == Player.SPECTATOR || p.level == null) { continue; }
            double[] last = lastPos.get(p);
            if (last != null && Math.abs(last[0] - p.x) + Math.abs(last[1] - p.y) + Math.abs(last[2] - p.z) < 0.15) { continue; }
            lastPos.put(p, new double[] {p.x, p.y, p.z});
            Vector3 at = new Vector3(p.x, p.y + 0.15, p.z);
            p.level.addParticle(trail(Catalog.ALL.get(id).display, at));
        }
    }

    private Particle trail(String kind, Vector3 at) {
        switch (kind) {
            case "fumaca": return new SmokeParticle(at);
            case "agua": return new SplashParticle(at);
            case "redstone": return new RedstoneParticle(at);
            case "encantada": return new EnchantParticle(at.add(0, 0.5, 0));
            case "portal": return new PortalParticle(at);
            case "coracoes": return new HeartParticle(at.add(0, 0.3, 0));
            case "chamas": return new FlameParticle(at);
            default: return trailTick % 2 == 0 ? new FlameParticle(at) : new HappyVillagerParticle(at.add(0, 0.3, 0));
        }
    }

    /** Efeito no lugar da morte da vitima, tocado pelo assassino. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void death(PlayerDeathEvent e) {
        EntityDamageEvent cause = e.getEntity().getLastDamageCause();
        if (!(cause instanceof EntityDamageByEntityEvent) || !(((EntityDamageByEntityEvent) cause).getDamager() instanceof Player)) { return; }
        Player killer = (Player) ((EntityDamageByEntityEvent) cause).getDamager();
        Cosmetic effect = equipped(killer, Type.ABATE);
        Level level = e.getEntity().level;
        if (effect == null || killer == e.getEntity() || level == null) { return; }
        Vector3 c = new Vector3(e.getEntity().x, e.getEntity().y, e.getEntity().z);
        switch (effect.display) {
            case "explosao":
                level.addParticle(new HugeExplodeParticle(c.add(0, 1, 0)));
                level.addSound(new ExplodeSound(c));
                break;
            case "lava": for (int i = 0; i < 12; i++) { level.addParticle(new LavaParticle(c.add(Math.random() - 0.5, 0.5 + Math.random(), Math.random() - 0.5))); } break;
            case "coracoes": ring(level, c.add(0, 1.2, 0), 1.0, 10, true); break;
            default: ring(level, c.add(0, 2.2, 0), 0.6, 12, false); ring(level, c.add(0, 0.2, 0), 1.2, 16, false); break;
        }
    }

    private static void ring(Level level, Vector3 c, double r, int points, boolean hearts) {
        for (int i = 0; i < points; i++) {
            double a = Math.PI * 2 * i / points;
            Vector3 at = c.add(Math.cos(a) * r, 0, Math.sin(a) * r);
            level.addParticle(hearts ? new HeartParticle(at) : new FlameParticle(at));
        }
    }

    // ---------- menus ----------

    /** Explica o menu em um icone: tocar para usar, tocar de novo para tirar, loja pede confirmacao. */
    private static Item help() {
        return icon(323, 0, "§eComo usar\n§a1. §fToque num cosmetico que voce tem para §aUSAR\n§c2. §fToque no que esta §a[USANDO] §fpara §cTIRAR\n§63. §fItens da loja: toque e confirme a compra\n§74. §fBloqueados mostram como ganhar");
    }

    @Override public void openMenu(Player p) {
        Wardrobe w = online.get(p);
        if (w == null) { p.sendMessage(PREFIX + "Carregando, tente de novo."); return; }
        Map<Integer, Item> icons = new HashMap<>();
        icons.put(4, help());
        int slot = 10;
        for (Type type : Type.values()) {
            Cosmetic on = equipped(p, type);
            int have = 0; for (Cosmetic c : Catalog.of(type)) { if (w.owned.contains(c.id)) { have++; } }
            Item item = icon(type.icon, type.meta, "§d§l" + type.title + "\n§7Voce tem " + have + " de " + Catalog.of(type).size()
                    + "\n" + (on == null ? "§7Usando: nenhum" : "§aUsando: §f" + on.name) + "\n§eToque para abrir");
            icons.put(slot, on == null ? item : PacketModel.glint(item));
            slot += 2;
        }
        boolean any = !w.equipped.isEmpty();
        if (any) { icons.put(22, icon(35, 14, "§c§lTirar tudo\n§7Para de usar todos os cosmeticos")); }
        menus().open(p, "Cosmeticos", icons, (who, s) -> {
            if (s >= 10 && s <= 18 && s % 2 == 0) { category(who, Type.values()[(s - 10) / 2]); }
            else if (s == 22 && any) { takeOffAll(who); }
        });
    }

    private void takeOffAll(Player who) {
        modify(key(who.getName()), x -> { boolean had = !x.equipped.isEmpty(); x.equipped.clear(); return had; })
                .whenComplete((v, e) -> { if (who.isOnline()) { who.sendMessage(PREFIX + (e != null ? "§c" + root(e) : "Voce tirou todos os cosmeticos.")); } });
    }

    private void category(Player p, Type type) {
        Wardrobe w = online.get(p);
        if (w == null) { return; }
        List<Cosmetic> list = Catalog.of(type);
        Map<Integer, Item> icons = new HashMap<>();
        String on = w.equipped(type);
        for (int i = 0; i < list.size() && i < 18; i++) {
            Cosmetic c = list.get(i);
            boolean using = c.id.equals(on), owned = w.owned.contains(c.id);
            String state = using ? "§a§l[USANDO]\n§cToque para TIRAR"
                    : owned ? "§e[VOCE TEM]\n§aToque para USAR"
                    : c.forSale() ? "§6[A VENDA] " + Catalog.price(c, price(c)) + "\n§fToque para comprar"
                    : "§c[BLOQUEADO]\n§7Como ganhar: " + c.source;
            String preview = type == Type.PINGENTE ? "§7Chaveiro 3D ao seu lado (lobby)" : type == Type.COR ? "§7No chat: " + c.display + p.getName()
                    : type == Type.MONTARIA ? "§7Modelo 3D que te acompanha (lobby)" : type == Type.TRILHA ? "§7Particulas por onde voce anda" : "§7Efeito onde sua vitima cai";
            Item item = icon(c.icon, c.meta, (using ? "§a" : owned ? "§f" : "§7") + c.name + "\n" + preview + "\n" + state);
            // So o que esta em uso brilha: fica facil achar.
            icons.put(i, using ? PacketModel.glint(item) : item);
        }
        Cosmetic current = on == null ? null : Catalog.ALL.get(on);
        icons.put(18, help());
        if (current != null) { icons.put(22, icon(35, 14, "§c§lTirar " + current.name + "\n§7Para de usar este " + type.title.toLowerCase(Locale.ROOT))); }
        icons.put(26, icon(262, 0, "§fVoltar"));
        menus().open(p, type.title, icons, (who, slot) -> {
            if (slot == 26) { openMenu(who); return; }
            String name = key(who.getName());
            if (slot == 22 && current != null) {
                modify(name, x -> x.equipped.remove(type.name()) != null).whenComplete((v, e) -> { if (who.isOnline()) { who.sendMessage(PREFIX + (e != null ? "§c" + root(e) : "Voce tirou " + current.name + ".")); category(who, type); } });
                return;
            }
            if (slot >= list.size() || slot >= 18) { return; }
            Cosmetic c = list.get(slot);
            Wardrobe now = online.get(who);
            if (now != null && now.owned.contains(c.id)) {
                modify(name, x -> x.toggle(c)).whenComplete((equippedNow, e) -> {
                    if (!who.isOnline()) { return; }
                    who.sendMessage(PREFIX + (e != null ? "§c" + root(e) : equippedNow ? "§aVoce esta usando " + c.name + ". §7Toque nele de novo para tirar." : "Voce tirou " + c.name + "."));
                    category(who, type);
                });
            } else if (c.forSale()) { confirm(who, c); }
            else { who.sendMessage(PREFIX + "§c" + c.name + " e exclusivo. Como ganhar: " + c.source + "."); }
        });
    }

    private void confirm(Player p, Cosmetic c) {
        Map<Integer, Item> icons = new HashMap<>();
        icons.put(11, icon(35, 5, "§aComprar " + c.name + "\n§f" + Catalog.price(c, price(c))));
        icons.put(13, icon(c.icon, c.meta, "§f" + c.name + "\n§7" + c.type.title));
        icons.put(15, icon(35, 14, "§cCancelar"));
        menus().open(p, "Confirmar compra", icons, (who, slot) -> { if (slot == 11) { buy(who, c); } else { category(who, c.type); } });
    }

    /** Debita, grava o cosmetico e so entao confirma; se a gravacao falhar, devolve o dinheiro. */
    private void buy(Player p, Cosmetic c) {
        EconomyService economy = getServer().getServiceManager().load(EconomyService.class);
        String name = key(p.getName());
        if (economy == null) { p.sendMessage(PREFIX + "§cLoja indisponivel (GoldEconomy desligado)."); return; }
        if (!buying.add(name)) { p.sendMessage(PREFIX + "§cCompra em andamento."); return; }
        Wardrobe w = online.get(p);
        if (w != null && w.owned.contains(c.id)) { buying.remove(name); p.sendMessage(PREFIX + "Voce ja tem."); return; }
        EconomyService.Currency currency = c.cash ? EconomyService.Currency.CASH : EconomyService.Currency.MONEY;
        long cost = price(c);
        if (economy.balance(name, currency) < cost) {
            buying.remove(name);
            p.sendMessage(PREFIX + noBalance.replace("{item}", c.name).replace("{preco}", Catalog.price(c, cost)));
            if (c.cash) { p.sendMessage(PREFIX + rechargeHint); }
            return;
        }
        boolean[] paid = new boolean[1];
        economy.debit(name, currency, cost)
                .thenCompose(v -> { paid[0] = true; return modify(name, x -> x.owned.add(c.id) && x.toggle(c)); })
                .whenComplete((v, e) -> {
                    buying.remove(name);
                    if (e != null) {
                        if (paid[0]) { economy.credit(name, currency, cost); }
                        if (p.isOnline()) { p.sendMessage(PREFIX + "§cCompra nao concluida: " + root(e) + (paid[0] ? " (dinheiro devolvido)" : "")); }
                        return;
                    }
                    // Nao reabre o menu aqui: o cliente ainda fecha a janela de confirmacao e o HUD podia sumir.
                    if (p.isOnline()) { p.sendMessage(PREFIX + "§aVoce comprou e equipou " + c.name + "! §7(/cosmeticos para trocar)"); }
                });
    }

    // ---------- comandos ----------

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length >= 3 && args[0].equalsIgnoreCase("dar") && sender.hasPermission("goldcosmetics.admin")) {
            String id = args[2].toLowerCase(Locale.ROOT);
            if (!exists(id)) { sender.sendMessage(PREFIX + "§cId inexistente. Use /cosmeticos lista."); return true; }
            unlock(args[1], id).whenComplete((fresh, e) -> sender.sendMessage(PREFIX + (e != null ? "§c" + root(e) : fresh ? "Entregue." : "Ele ja tinha.")));
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("tirar") && sender instanceof Player) { takeOffAll((Player) sender); return true; }
        if (args.length >= 1 && args[0].equalsIgnoreCase("lista")) {
            for (Type t : Type.values()) {
                StringBuilder line = new StringBuilder("§d" + t.title + ":§f");
                for (Cosmetic c : Catalog.of(t)) { line.append(' ').append(c.id); }
                sender.sendMessage(line.toString());
            }
            return true;
        }
        if (sender instanceof Player) { openMenu((Player) sender); } else { sender.sendMessage("Use /cosmeticos lista ou /cosmeticos dar <jogador> <id>."); }
        return true;
    }

    private MenuService menus() { return getServer().getServiceManager().load(MenuService.class); }
    private static Item icon(int id, int meta, String name) { Item item = Item.get(id, meta, 1); item.setCustomName(name); return item; }
    static String root(Throwable e) { while (e.getCause() != null) { e = e.getCause(); } return e.getMessage(); }
}
