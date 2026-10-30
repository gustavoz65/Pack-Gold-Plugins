package br.goldsuite.essentials;

import br.goldsuite.api.*;
import br.goldsuite.api.TravelService.Transition;
import cn.nukkit.Player;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.player.*;
import cn.nukkit.level.Location;
import cn.nukkit.plugin.PluginBase;
import com.google.gson.Gson;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Homes, warps, spawn, tpa, back, fly e tpall. */
public final class GoldEssentials extends PluginBase implements Listener {
    static String PREFIX = "§6[§eGold§6]§f ";
    private static final String HOMES = "goldessentials-homes";
    private static final long TPA_TIMEOUT_MS = 60_000;
    private static final Gson GSON = new Gson();

    private CoreService core;
    private PersistentState<Places> places;
    /** Homes so dos jogadores online; carregadas ao entrar, descartadas ao sair. */
    private final Map<Player, Places.Homes> homes = new IdentityHashMap<>();
    private final Map<Player, Location> back = new IdentityHashMap<>();
    /** Destino -> pedido pendente (um por destino; o mais novo substitui). */
    private final Map<Player, Request> requests = new IdentityHashMap<>();

    private static final class Request {
        final Player from; final boolean here; final long at = System.currentTimeMillis();
        Request(Player from, boolean here) { this.from = from; this.here = here; }
    }

    @Override public void onEnable() {
        Settings.prefix(this, PREFIX, v -> PREFIX = v);
        core = getServer().getServiceManager().load(CoreService.class);
        if (core == null) { throw new IllegalStateException("GoldCore precisa estar ativo antes do GoldEssentials."); }
        places = new PersistentState<>(core.getStore(), "goldessentials", Places.class, Places::new, Places::validate);
        getServer().getPluginManager().registerEvents(this, this);
        for (Player player : getServer().getOnlinePlayers().values()) { loadHomes(player); }
    }

    @Override public void onDisable() {
        homes.clear(); back.clear(); requests.clear();
        if (places != null) { places.close(); }
    }

    // ---------- eventos ----------

    @EventHandler(priority = EventPriority.MONITOR) public void join(PlayerJoinEvent event) { loadHomes(event.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR) public void quit(PlayerQuitEvent event) {
        Player p = event.getPlayer();
        homes.remove(p); back.remove(p); requests.remove(p);
        requests.values().removeIf(r -> r.from == p);
    }

    @EventHandler(priority = EventPriority.MONITOR) public void death(PlayerDeathEvent event) { back.put(event.getEntity(), event.getEntity().getLocation()); }

    /** /back volta ao ultimo ponto antes de um teleporte longo ou da morte. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void teleport(PlayerTeleportEvent event) {
        Location from = event.getFrom(), to = event.getTo();
        if (from.level != to.level || from.distanceSquared(to) > 25) { back.put(event.getPlayer(), from); }
    }

    /** Spawn salvo pelo /setspawn tambem vale ao renascer. */
    @EventHandler(priority = EventPriority.LOW) public void respawn(PlayerRespawnEvent event) {
        Location spawn = spawnLocation();
        if (spawn != null && event.getRespawnPosition().level == getServer().getDefaultLevel()) { event.setRespawnPosition(spawn); }
    }

    private void loadHomes(Player player) {
        String key = player.getName().toLowerCase(Locale.ROOT);
        core.getStore().get(HOMES, key).whenComplete((value, error) -> {
            if (!isEnabled() || !player.isOnline()) { return; }
            Places.Homes loaded = new Places.Homes();
            if (error == null && value.isPresent()) {
                try { loaded = GSON.fromJson(value.get(), Places.Homes.class); Places.validate(loaded, 64); }
                catch (RuntimeException invalid) { getLogger().warning("Homes invalidas de " + key + "; ignoradas."); loaded = new Places.Homes(); }
            }
            homes.put(player, loaded);
        });
    }

    // ---------- comandos ----------

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) { sender.sendMessage("Use dentro do jogo."); return true; }
        Player p = (Player) sender;
        AuthService auth = getServer().getServiceManager().load(AuthService.class);
        if (auth != null && !auth.isAuthenticated(p)) { p.sendMessage("Autentique-se primeiro."); return true; }
        try {
            switch (command.getName()) {
                case "sethome": setHome(p, args.length > 0 ? args[0] : "casa"); return true;
                case "home": home(p, args.length > 0 ? args[0] : null); return true;
                case "delhome": delHome(p, args.length > 0 ? args[0] : "casa"); return true;
                case "warp": warp(p, args.length > 0 ? args[0] : null); return true;
                case "setwarp": { String name = Places.name(args.length > 0 ? args[0] : ""); Destination here = here(p); save(p, places.change(s -> { if (s.warps.size() >= Places.MAX_WARPS && !s.warps.containsKey(name)) { throw new IllegalArgumentException("Limite de warps."); } s.warps.put(name, here); }), "Warp " + name + " salva."); return true; }
                case "delwarp": { String name = Places.name(args.length > 0 ? args[0] : ""); save(p, places.change(s -> { if (s.warps.remove(name) == null) { throw new IllegalArgumentException("Warp inexistente."); } }), "Warp removida."); return true; }
                case "spawn": { Location spawn = spawnLocation(); go(p, spawn == null ? Destination.world(getServer().getDefaultLevel().getFolderName()) : here(spawn)); return true; }
                case "setspawn": { Destination here = here(p); save(p, places.change(s -> s.spawn = here), "Spawn do servidor salvo."); getServer().getDefaultLevel().setSpawnLocation(p); return true; }
                case "tpa": case "tpahere": request(p, args, command.getName().equals("tpahere")); return true;
                case "tpaccept": answer(p, true); return true;
                case "tpdeny": answer(p, false); return true;
                case "back": {
                    Location last = back.get(p);
                    if (last == null || last.level == null) { throw new IllegalArgumentException("Nenhum local para voltar."); }
                    go(p, here(last));
                    return true;
                }
                case "fly": {
                    Player target = args.length > 0 && p.hasPermission("goldessentials.fly.outros") ? online(args[0]) : p;
                    boolean on = !target.getAdventureSettings().canFly();
                    target.setAllowFlight(on);
                    target.sendMessage(PREFIX + "Voo " + (on ? "§aligado" : "§cdesligado"));
                    if (target != p) { p.sendMessage(PREFIX + "Voo de " + target.getName() + (on ? " ligado." : " desligado.")); }
                    return true;
                }
                case "tpall": {
                    int moved = 0;
                    for (Player other : getServer().getOnlinePlayers().values()) { if (other != p && other.spawned) { other.teleport(p.getLocation()); moved++; } }
                    p.sendMessage(PREFIX + moved + " jogador(es) trazidos.");
                    return true;
                }
                default: return false;
            }
        } catch (RuntimeException error) { p.sendMessage(PREFIX + "§c" + root(error)); return true; }
    }

    private int maxHomes(Player p) {
        for (int n = 20; n > 1; n--) { if (p.hasPermission("goldessentials.homes." + n)) { return n; } }
        return p.hasPermission("goldessentials.homes.vip") ? 5 : 2;
    }

    private Places.Homes homesOf(Player p) {
        Places.Homes h = homes.get(p);
        if (h == null) { throw new IllegalArgumentException("Suas homes ainda estao carregando; tente de novo."); }
        return h;
    }

    private void setHome(Player p, String text) {
        String name = Places.name(text);
        Places.Homes current = homesOf(p);
        int max = maxHomes(p);
        if (!current.homes.containsKey(name) && current.homes.size() >= max) { throw new IllegalArgumentException("Limite de " + max + " homes. Use /delhome."); }
        Places.Homes next = copy(current);
        next.homes.put(name, here(p));
        persistHomes(p, next, "Home " + name + " salva.");
    }

    private void delHome(Player p, String text) {
        String name = Places.name(text);
        Places.Homes next = copy(homesOf(p));
        if (next.homes.remove(name) == null) { throw new IllegalArgumentException("Home inexistente."); }
        persistHomes(p, next, "Home " + name + " removida.");
    }

    /** Publica so depois de gravar; uma falha de disco mantem as homes anteriores. */
    private void persistHomes(Player p, Places.Homes next, String message) {
        Places.validate(next, 64);
        save(p, core.getStore().put(HOMES, p.getName().toLowerCase(Locale.ROOT), GSON.toJson(next)), message, () -> { if (p.isOnline()) { homes.put(p, next); } });
    }

    private void home(Player p, String text) {
        Places.Homes h = homesOf(p);
        if (text == null) {
            if (h.homes.size() == 1) { go(p, h.homes.values().iterator().next()); return; }
            p.sendMessage(PREFIX + (h.homes.isEmpty() ? "Nenhuma home. Use /sethome [nome]." : "Homes: " + String.join(", ", h.homes.keySet())));
            return;
        }
        Destination d = h.homes.get(Places.name(text));
        if (d == null) { throw new IllegalArgumentException("Home inexistente. Suas homes: " + String.join(", ", h.homes.keySet())); }
        go(p, d);
    }

    private void warp(Player p, String text) {
        if (text == null) { p.sendMessage(PREFIX + "Warps: " + places.read(s -> s.warps.isEmpty() ? "nenhuma" : String.join(", ", s.warps.keySet()))); return; }
        String name = Places.name(text);
        Destination d = places.read(s -> s.warps.get(name));
        if (d == null) { throw new IllegalArgumentException("Warp inexistente."); }
        if (!p.hasPermission("goldessentials.warp." + name) && !p.hasPermission("goldessentials.warp.todas")) { throw new IllegalArgumentException("Sem permissao para esta warp."); }
        go(p, d);
    }

    private void request(Player p, String[] args, boolean here) {
        if (args.length == 0) { throw new IllegalArgumentException("Uso: /" + (here ? "tpahere" : "tpa") + " <jogador>"); }
        Player target = online(args[0]);
        if (target == p) { throw new IllegalArgumentException("Voce nao pode enviar para si mesmo."); }
        requests.put(target, new Request(p, here));
        p.sendMessage(PREFIX + "Pedido enviado para " + target.getName() + ". Expira em 60 s.");
        target.sendMessage(PREFIX + "§e" + p.getName() + (here ? " quer que voce va ate ele." : " quer ir ate voce.") + " §a/tpaccept §fou §c/tpdeny");
    }

    private void answer(Player p, boolean accept) {
        Request r = requests.remove(p);
        if (r == null || !r.from.isOnline() || System.currentTimeMillis() - r.at > TPA_TIMEOUT_MS) { throw new IllegalArgumentException("Nenhum pedido pendente."); }
        if (!accept) { r.from.sendMessage(PREFIX + p.getName() + " recusou o pedido."); p.sendMessage(PREFIX + "Pedido recusado."); return; }
        Player traveler = r.here ? p : r.from, target = r.here ? r.from : p;
        r.from.sendMessage(PREFIX + p.getName() + " aceitou.");
        go(traveler, here(target));
    }

    /** Teleporte de jogador: contagem de 3 s parado; admins vao na hora. */
    private void go(Player p, Destination d) {
        TravelService travel = getServer().getServiceManager().load(TravelService.class);
        Transition t = p.hasPermission("goldessentials.instantaneo") ? Transition.INSTANTANEA : Transition.CONTAGEM;
        travel.travel(p, d, t).whenComplete((ok, error) -> { if (error != null && p.isOnline()) { p.sendMessage(PREFIX + "§c" + root(error)); } });
    }

    private Location spawnLocation() {
        if (!places.isLoaded()) { return null; }
        Destination d = places.read(s -> s.spawn);
        if (d == null || getServer().getLevelByName(d.target) == null) { return null; }
        return new Location(d.x, d.y, d.z, d.yaw, d.pitch, getServer().getLevelByName(d.target));
    }

    private Player online(String name) {
        Player p = getServer().getPlayerExact(name);
        if (p == null) { p = getServer().getPlayer(name); }
        if (p == null) { throw new IllegalArgumentException("Jogador offline."); }
        return p;
    }

    private static Destination here(Player p) { return here(p.getLocation()); }
    private static Destination here(Location l) { return Destination.position(l.level.getFolderName(), l.x, l.y, l.z, (float) l.yaw, (float) l.pitch); }
    private static Places.Homes copy(Places.Homes h) { Places.Homes c = new Places.Homes(); c.homes.putAll(h.homes); return c; }

    private void save(Player p, CompletableFuture<?> op, String message) { save(p, op, message, null); }
    private void save(Player p, CompletableFuture<?> op, String message, Runnable then) {
        op.whenComplete((v, error) -> {
            if (!isEnabled()) { return; }
            if (error != null) { if (p.isOnline()) { p.sendMessage(PREFIX + "§c" + root(error)); } return; }
            if (then != null) { then.run(); }
            if (p.isOnline()) { p.sendMessage(PREFIX + message); }
        });
    }

    static String root(Throwable e) { while (e.getCause() != null) { e = e.getCause(); } return e.getMessage(); }
}
