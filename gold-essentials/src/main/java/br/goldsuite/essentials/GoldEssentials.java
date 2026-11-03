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
import cn.nukkit.item.Item;
import cn.nukkit.level.Location;
import cn.nukkit.plugin.PluginBase;
import com.google.gson.Gson;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Homes, warps, spawn, tpa, back, fly e tpall. */
public final class GoldEssentials extends PluginBase implements Listener {
    static String PREFIX = M.t("gold");
    private static final String HOMES = "goldessentials-homes";
    private static final long TPA_TIMEOUT_MS = 60_000;
    private static final Gson GSON = new Gson();
    static final String PREFS = "goldessentials-pref", SHOPS = "goldessentials-loja";
    /** Bits das preferencias (como o OPreferencias): quem desligou nao recebe. */
    static final int NO_TELL = 1, NO_TPA = 2, NO_COINS = 4;
    static final String[][] PREF_COMMANDS = {{"tell", "msg", "w", "whisper"}, {"tpa", "tpahere", "etpa"}, {"pagar", "pay"}};
    /** Preferencias de quem esta online (so de quem mudou algo; o resto e 0). */
    private final Map<Player, Integer> prefs = new IdentityHashMap<>();
    private final Set<Player> nightVision = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<Player, Long> luzAt = new IdentityHashMap<>(), shopAt = new IdentityHashMap<>();

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
        M.B.attach(this);
        Settings.prefix(this, PREFIX, v -> PREFIX = v);
        core = getServer().getServiceManager().load(CoreService.class);
        if (core == null) { throw new IllegalStateException(M.t("goldcore_precisa_estar_ativo_antes_do")); }
        places = new PersistentState<>(core.getStore(), "goldessentials", Places.class, Places::new, Places::validate);
        Settings.number(this, "luz.espera", 0, "Segundos entre um /luz e outro (0 = sem espera)", v -> luzDelay = Math.max(0, v));
        Settings.number(this, "loja.espera", 30, "Segundos entre um /loja e outro", v -> shopCooldown = Math.max(0, v));
        Settings.text(this, "mundovip.sem_vip", "&cOpa, este e o mundo VIP. Para acessa-lo adquira um VIP no nosso site.", "Mensagem para quem nao e VIP (| quebra a linha)", v -> vipOnly = v);
        getServer().getPluginManager().registerEvents(this, this);
        for (Player player : getServer().getOnlinePlayers().values()) { loadHomes(player); }
    }

    @Override public void onDisable() {
        homes.clear(); back.clear(); requests.clear();
        if (places != null) { places.close(); }
    }

    // ---------- eventos ----------

    @EventHandler(priority = EventPriority.MONITOR) public void join(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        loadHomes(p);
        core.getStore().get(PREFS, p.getName().toLowerCase(Locale.ROOT)).thenAccept(v -> { if (v.isPresent() && p.isOnline()) { try { prefs.put(p, Integer.parseInt(v.get())); } catch (NumberFormatException ignored) { } } });
    }

    /** Pedido de tell/tpa/pagamento para quem desligou essa funcao no /config: barrado antes do comando. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void preferences(cn.nukkit.event.player.PlayerCommandPreprocessEvent e) {
        if (prefs.isEmpty()) { return; }
        String[] parts = e.getMessage().substring(1).trim().split("\\s+");
        if (parts.length < 2) { return; }
        String cmd = parts[0].toLowerCase(Locale.ROOT);
        if (cmd.contains(":")) { cmd = cmd.substring(cmd.indexOf(':') + 1); }
        for (int bit = 0; bit < PREF_COMMANDS.length; bit++) {
            if (!Arrays.asList(PREF_COMMANDS[bit]).contains(cmd)) { continue; }
            Player target = getServer().getPlayerExact(parts[1]);
            if (target == null || target == e.getPlayer() || e.getPlayer().hasPermission("goldessentials.admin")) { return; }
            if ((prefs.getOrDefault(target, 0) & (1 << bit)) != 0) {
                e.setCancelled();
                e.getPlayer().sendMessage(PREFIX + M.t("esta_com_desativado", target.getName(), (bit == 0 ? M.t("as_mensagens_privadas") : bit == 1 ? M.t("os_pedidos_de_teleporte") : M.t("recebimento_de_coins"))));
            }
            return;
        }
    }

    /** /config: liga e desliga quem pode mandar tell, tpa e coins para voce. */
    private void config(Player p) {
        MenuService menus = getServer().getServiceManager().load(MenuService.class);
        if (menus == null) { return; }
        int mask = prefs.getOrDefault(p, 0);
        String[] names = {M.t("mensagens_privadas_tell"), M.t("pedidos_de_teleporte_tpa"), M.t("recebimento_de_coins_2")};
        int[] icons = {339, 368, 266};
        Map<Integer, Item> menu = new HashMap<>();
        for (int i = 0; i < 3; i++) {
            boolean on = (mask & (1 << i)) == 0;
            Item icon = Item.get(icons[i], 0, 1); icon.setCustomName(M.t("outros_jogadores_te_mandar_isso", names[i], (on ? M.t("podem") : M.t("nao_podem"))));
            Item state = Item.get(35, on ? 5 : 14, 1); state.setCustomName(M.t("toque_para", (on ? M.t("ativado") : M.t("desativado")), (on ? M.t("desativar") : M.t("ativar"))));
            menu.put(11 + i * 2, icon); menu.put(20 + i * 2, state);
        }
        menus.open(p, M.t("preferencias"), menu, (who, slot) -> {
            int bit = slot == 11 || slot == 20 ? 0 : slot == 13 || slot == 22 ? 1 : slot == 15 || slot == 24 ? 2 : -1;
            if (bit < 0) { return; }
            int next = prefs.getOrDefault(who, 0) ^ (1 << bit);
            if (next == 0) { prefs.remove(who); } else { prefs.put(who, next); }
            core.getStore().put(PREFS, who.getName().toLowerCase(Locale.ROOT), String.valueOf(next));
            who.sendMessage(PREFIX + ((next & (1 << bit)) == 0 ? M.t("funcao_ativada") : M.t("funcao_desativada")));
            config(who);
        });
    }

    /** /luz: visao noturna enquanto estiver ligada (reLuz). */
    private void luz(Player p) {
        long now = System.currentTimeMillis();
        if (luzDelay > 0 && !p.hasPermission("goldessentials.admin") && now - luzAt.getOrDefault(p, 0L) < luzDelay * 1000L) { throw new IllegalArgumentException(M.t("aguarde_para_usar_de_novo")); }
        luzAt.put(p, now);
        if (nightVision.remove(p)) { p.removeEffect(cn.nukkit.potion.Effect.NIGHT_VISION); p.sendMessage(PREFIX + M.t("lanterna_desligada")); return; }
        nightVision.add(p);
        p.addEffect(cn.nukkit.potion.Effect.getEffect(cn.nukkit.potion.Effect.NIGHT_VISION).setDuration(20 * 60 * 60 * 24).setVisible(false));
        p.sendMessage(PREFIX + M.t("lanterna_ligada_aproveite"));
    }

    /** /setloja e /loja <jogador> (T_SetLoja): cada jogador com permissao marca a propria loja. */
    private void setShop(Player p, boolean remove) {
        String key = p.getName().toLowerCase(Locale.ROOT);
        CompletableFuture<Void> op = remove ? core.getStore().delete(SHOPS, key) : core.getStore().put(SHOPS, key, GSON.toJson(here(p)));
        op.whenComplete((v, e) -> getServer().getScheduler().scheduleTask(() -> { if (p.isOnline()) { p.sendMessage(PREFIX + (e != null ? M.t("nao_foi_possivel_gravar") : remove ? M.t("loja_removida") : M.t("loja_marcada_aqui_outros_chegam_com", p.getName()))); } }));
    }

    private void goShop(Player p, String owner) {
        long now = System.currentTimeMillis();
        if (!p.hasPermission("goldessentials.instantaneo") && now - shopAt.getOrDefault(p, 0L) < shopCooldown * 1000L) { throw new IllegalArgumentException(M.t("aguarde_para_ir_outra_loja", ((shopCooldown * 1000L - (now - shopAt.get(p))) / 1000 + 1))); }
        String key = owner.toLowerCase(Locale.ROOT);
        if (!key.matches("[a-z0-9_]{3,16}")) { throw new IllegalArgumentException(M.t("jogador_invalido")); }
        core.getStore().get(SHOPS, key).whenComplete((v, e) -> getServer().getScheduler().scheduleTask(() -> {
            if (!p.isOnline()) { return; }
            if (e != null || v == null || !v.isPresent()) { p.sendMessage(PREFIX + M.t("este_jogador_nao_tem_loja")); return; }
            Destination d;
            try { d = GSON.fromJson(v.get(), Destination.class); d.validate(); } catch (RuntimeException bad) { p.sendMessage(PREFIX + M.t("loja_invalida")); return; }
            shopAt.put(p, System.currentTimeMillis());
            p.sendMessage(PREFIX + M.t("indo_para_loja_de", key));
            go(p, d);
        }));
    }

    private void vipWorld(Player p, String[] args) {
        if (args.length > 0 && p.hasPermission("goldessentials.admin")) {
            boolean remove = args[0].equalsIgnoreCase("remover");
            Destination d = remove ? null : here(p);
            places.change(s -> s.vipWorld = d).whenComplete((v, e) -> p.sendMessage(PREFIX + (e != null ? M.t("nao_foi_possivel_gravar") : remove ? M.t("mundo_vip_removido") : M.t("mundo_vip_definido_aqui"))));
            return;
        }
        if (!p.hasPermission("goldessentials.mundovip")) { for (String line : vipOnly.split("\\|")) { p.sendMessage(line.replace('&', '\u00a7')); } return; }
        Destination d = places.isLoaded() ? places.read(s -> s.vipWorld) : null;
        if (d == null) { throw new IllegalArgumentException(M.t("mundo_vip_ainda_nao_foi_definido")); }
        p.sendMessage(PREFIX + M.t("seja_bem_vindo_ao_mundo_vip"));
        go(p, d);
    }

    private long luzDelay, shopCooldown = 30;
    private String vipOnly = M.t("copa_este_mundo_vip_para_acessa");

    @EventHandler(priority = EventPriority.MONITOR) public void quit(PlayerQuitEvent event) {
        Player p = event.getPlayer();
        homes.remove(p); back.remove(p); requests.remove(p); prefs.remove(p); nightVision.remove(p); luzAt.remove(p); shopAt.remove(p);
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
        if (!(sender instanceof Player)) { sender.sendMessage(M.t("use_dentro_do_jogo")); return true; }
        Player p = (Player) sender;
        AuthService auth = getServer().getServiceManager().load(AuthService.class);
        if (auth != null && !auth.isAuthenticated(p)) { p.sendMessage(M.t("autentique_se_primeiro")); return true; }
        try {
            switch (command.getName()) {
                case "config": config(p); return true;
                case "luz": luz(p); return true;
                case "setloja": setShop(p, args.length > 0 && args[0].equalsIgnoreCase("remover")); return true;
                case "loja": if (args.length == 0) { p.sendMessage(PREFIX + M.t("use_loja_jogador_para_marcar_sua")); return true; } goShop(p, args[0]); return true;
                case "mundovip": vipWorld(p, args); return true;
                case "sethome": setHome(p, args.length > 0 ? args[0] : "casa"); return true;
                case "home": home(p, args.length > 0 ? args[0] : null); return true;
                case "delhome": delHome(p, args.length > 0 ? args[0] : "casa"); return true;
                case "warp": warp(p, args.length > 0 ? args[0] : null); return true;
                case "setwarp": { String name = Places.name(args.length > 0 ? args[0] : ""); Destination here = here(p); save(p, places.change(s -> { if (s.warps.size() >= Places.MAX_WARPS && !s.warps.containsKey(name)) { throw new IllegalArgumentException(M.t("limite_de_warps")); } s.warps.put(name, here); }), "Warp " + name + " salva."); return true; }
                case "delwarp": { String name = Places.name(args.length > 0 ? args[0] : ""); save(p, places.change(s -> { if (s.warps.remove(name) == null) { throw new IllegalArgumentException(M.t("warp_inexistente")); } }), M.t("warp_removida")); return true; }
                case "spawn": { Location spawn = spawnLocation(); go(p, spawn == null ? Destination.world(getServer().getDefaultLevel().getFolderName()) : here(spawn)); return true; }
                case "setspawn": { Destination here = here(p); save(p, places.change(s -> s.spawn = here), M.t("spawn_do_servidor_salvo")); getServer().getDefaultLevel().setSpawnLocation(p); return true; }
                case "tpa": case "tpahere": request(p, args, command.getName().equals("tpahere")); return true;
                case "tpaccept": answer(p, true); return true;
                case "tpdeny": answer(p, false); return true;
                case "back": {
                    Location last = back.get(p);
                    if (last == null || last.level == null) { throw new IllegalArgumentException(M.t("nenhum_local_para_voltar")); }
                    go(p, here(last));
                    return true;
                }
                case "fly": {
                    Player target = args.length > 0 && p.hasPermission("goldessentials.fly.outros") ? online(args[0]) : p;
                    boolean on = !target.getAdventureSettings().canFly();
                    target.setAllowFlight(on);
                    target.sendMessage(PREFIX + M.t("voo", (on ? M.t("ligado") : M.t("desligado"))));
                    if (target != p) { p.sendMessage(PREFIX + M.t("voo_de", target.getName(), (on ? M.t("ligado_2") : M.t("desligado_2")))); }
                    return true;
                }
                case "tpall": {
                    int moved = 0;
                    for (Player other : getServer().getOnlinePlayers().values()) { if (other != p && other.spawned) { other.teleport(p.getLocation()); moved++; } }
                    p.sendMessage(PREFIX + M.t("jogador_es_trazidos", moved));
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
        if (h == null) { throw new IllegalArgumentException(M.t("suas_homes_ainda_estao_carregando_tente")); }
        return h;
    }

    private void setHome(Player p, String text) {
        String name = Places.name(text);
        Places.Homes current = homesOf(p);
        int max = maxHomes(p);
        if (!current.homes.containsKey(name) && current.homes.size() >= max) { throw new IllegalArgumentException(M.t("limite_de_homes_use_delhome", max)); }
        Places.Homes next = copy(current);
        next.homes.put(name, here(p));
        persistHomes(p, next, "Home " + name + " salva.");
    }

    private void delHome(Player p, String text) {
        String name = Places.name(text);
        Places.Homes next = copy(homesOf(p));
        if (next.homes.remove(name) == null) { throw new IllegalArgumentException(M.t("home_inexistente")); }
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
            p.sendMessage(PREFIX + (h.homes.isEmpty() ? M.t("nenhuma_home_use_sethome_nome") : M.t("homes", String.join(", ", h.homes.keySet()))));
            return;
        }
        Destination d = h.homes.get(Places.name(text));
        if (d == null) { throw new IllegalArgumentException(M.t("home_inexistente_suas_homes", String.join(", ", h.homes.keySet()))); }
        go(p, d);
    }

    private void warp(Player p, String text) {
        if (text == null) { p.sendMessage(PREFIX + M.t("warps", places.<String>read(s -> s.warps.isEmpty() ? "nenhuma" : String.join(", ", s.warps.keySet())))); return; }
        String name = Places.name(text);
        Destination d = places.read(s -> s.warps.get(name));
        if (d == null) { throw new IllegalArgumentException(M.t("warp_inexistente")); }
        if (!p.hasPermission("goldessentials.warp." + name) && !p.hasPermission("goldessentials.warp.todas")) { throw new IllegalArgumentException(M.t("sem_permissao_para_esta_warp")); }
        go(p, d);
    }

    private void request(Player p, String[] args, boolean here) {
        if (args.length == 0) { throw new IllegalArgumentException(M.t("uso_jogador", (here ? M.t("tpahere") : M.t("tpa")))); }
        Player target = online(args[0]);
        if (target == p) { throw new IllegalArgumentException(M.t("voce_nao_pode_enviar_para_si")); }
        requests.put(target, new Request(p, here));
        p.sendMessage(PREFIX + M.t("pedido_enviado_para_expira_em_60", target.getName()));
        target.sendMessage(PREFIX + M.t("tpaccept_ou_tpdeny", p.getName(), (here ? M.t("quer_que_voce_va_ate_ele") : M.t("quer_ir_ate_voce"))));
    }

    private void answer(Player p, boolean accept) {
        Request r = requests.remove(p);
        if (r == null || !r.from.isOnline() || System.currentTimeMillis() - r.at > TPA_TIMEOUT_MS) { throw new IllegalArgumentException(M.t("nenhum_pedido_pendente")); }
        if (!accept) { r.from.sendMessage(PREFIX + M.t("recusou_pedido", p.getName())); p.sendMessage(PREFIX + M.t("pedido_recusado")); return; }
        Player traveler = r.here ? p : r.from, target = r.here ? r.from : p;
        r.from.sendMessage(PREFIX + M.t("aceitou", p.getName()));
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
        if (p == null) { throw new IllegalArgumentException(M.t("jogador_offline")); }
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
