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

    @Override public void onEnable() {
        Settings.prefix(this, PREFIX, v -> PREFIX = v);
        core = getServer().getServiceManager().load(CoreService.class);
        if (core == null) { throw new IllegalStateException("GoldCore precisa estar ativo antes do GoldCosmetics."); }
        getServer().getServiceManager().register(CosmeticService.class, this, this);
        getServer().getPluginManager().registerEvents(this, this);
        for (Player p : getServer().getOnlinePlayers().values()) { load(p); }
        getServer().getScheduler().scheduleRepeatingTask(new PluginTask<GoldCosmetics>(this) { @Override public void onRun(int tick) { trails(); } }, 4);
    }

    @Override public void onDisable() { online.clear(); lastPos.clear(); buying.clear(); }

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
    @EventHandler(priority = EventPriority.MONITOR) public void quit(PlayerQuitEvent e) { online.remove(e.getPlayer()); lastPos.remove(e.getPlayer()); }

    private Cosmetic equipped(Player p, Type type) {
        Wardrobe w = online.get(p);
        String id = w == null ? null : w.equipped(type);
        return id == null ? null : Catalog.ALL.get(id);
    }

    // ---------- efeitos ----------

    /** Pingente antes do nome e cor do nome no chat. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void chat(PlayerChatEvent e) {
        Cosmetic badge = equipped(e.getPlayer(), Type.PINGENTE), color = equipped(e.getPlayer(), Type.COR);
        if (badge == null && color == null) { return; }
        e.setFormat(e.getFormat().replace("{%0}", (badge == null ? "" : badge.display + "§r ") + (color == null ? "" : color.display) + "{%0}§r"));
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

    @Override public void openMenu(Player p) {
        Wardrobe w = online.get(p);
        if (w == null) { p.sendMessage(PREFIX + "Carregando, tente de novo."); return; }
        Map<Integer, Item> icons = new HashMap<>();
        int slot = 10;
        for (Type type : Type.values()) {
            Cosmetic on = equipped(p, type);
            int have = 0; for (Cosmetic c : Catalog.of(type)) { if (w.owned.contains(c.id)) { have++; } }
            icons.put(slot, icon(type.icon, type.meta, "§d" + type.title + "\n§7Voce tem " + have + "/" + Catalog.of(type).size() + "\n§7Equipado: §f" + (on == null ? "nenhum" : on.name)));
            slot += 2;
        }
        menus().open(p, "Cosmeticos", icons, (who, s) -> { if (s >= 10 && s <= 16 && s % 2 == 0) { category(who, Type.values()[(s - 10) / 2]); } });
    }

    private void category(Player p, Type type) {
        Wardrobe w = online.get(p);
        if (w == null) { return; }
        List<Cosmetic> list = Catalog.of(type);
        Map<Integer, Item> icons = new HashMap<>();
        String on = w.equipped(type);
        for (int i = 0; i < list.size() && i < 18; i++) {
            Cosmetic c = list.get(i);
            String state = c.id.equals(on) ? "§a[EQUIPADO] toque para tirar" : w.owned.contains(c.id) ? "§eToque para equipar"
                    : c.forSale() ? "§6Comprar por " + Catalog.price(c.price) : "§cBloqueado\n§7" + c.source;
            String preview = type == Type.PINGENTE ? "\n§7No chat: " + c.display + " §f" + p.getName() : type == Type.COR ? "\n§7No chat: " + c.display + p.getName() : "";
            icons.put(i, icon(c.icon, c.meta, "§f" + c.name + preview + "\n" + state));
        }
        icons.put(22, icon(325, 0, "§cTirar " + type.title.toLowerCase(Locale.ROOT)));
        icons.put(26, icon(262, 0, "§fVoltar"));
        menus().open(p, type.title, icons, (who, slot) -> {
            if (slot == 26) { openMenu(who); return; }
            String name = key(who.getName());
            if (slot == 22) { modify(name, x -> x.equipped.remove(type.name()) != null).whenComplete((v, e) -> { if (who.isOnline()) { who.sendMessage(PREFIX + "Pronto."); category(who, type); } }); return; }
            if (slot >= list.size() || slot >= 18) { return; }
            Cosmetic c = list.get(slot);
            Wardrobe now = online.get(who);
            if (now != null && now.owned.contains(c.id)) {
                modify(name, x -> x.toggle(c)).whenComplete((equippedNow, e) -> {
                    if (!who.isOnline()) { return; }
                    who.sendMessage(PREFIX + (e != null ? "§c" + root(e) : equippedNow ? "§a" + c.name + " equipado." : c.name + " removido."));
                    category(who, type);
                });
            } else if (c.forSale()) { confirm(who, c); }
            else { who.sendMessage(PREFIX + "§c" + c.name + " e exclusivo. " + c.source + "."); category(who, type); }
        });
    }

    private void confirm(Player p, Cosmetic c) {
        Map<Integer, Item> icons = new HashMap<>();
        icons.put(11, icon(35, 5, "§aComprar " + c.name + "\n§f" + Catalog.price(c.price)));
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
        if (economy.balance(name, EconomyService.Currency.MONEY) < c.price) { buying.remove(name); p.sendMessage(PREFIX + "§cDinheiro insuficiente: custa " + Catalog.price(c.price) + "."); return; }
        boolean[] paid = new boolean[1];
        economy.debit(name, EconomyService.Currency.MONEY, c.price)
                .thenCompose(v -> { paid[0] = true; return modify(name, x -> x.owned.add(c.id) && x.toggle(c)); })
                .whenComplete((v, e) -> {
                    buying.remove(name);
                    if (e != null) {
                        if (paid[0]) { economy.credit(name, EconomyService.Currency.MONEY, c.price); }
                        if (p.isOnline()) { p.sendMessage(PREFIX + "§cCompra nao concluida: " + root(e) + (paid[0] ? " (dinheiro devolvido)" : "")); }
                        return;
                    }
                    if (p.isOnline()) { p.sendMessage(PREFIX + "§aVoce comprou e equipou " + c.name + "!"); category(p, c.type); }
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
