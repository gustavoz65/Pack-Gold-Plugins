package br.goldsuite.factions;

import br.goldsuite.api.*;
import br.goldsuite.api.TravelService.Transition;
import br.goldsuite.factions.FactionState.Faction;
import br.goldsuite.factions.FactionState.Role;
import cn.nukkit.Player;
import cn.nukkit.block.Block;
import cn.nukkit.blockentity.BlockEntity;
import cn.nukkit.blockentity.BlockEntityMobSpawner;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.block.BlockBreakEvent;
import cn.nukkit.event.block.BlockPlaceEvent;
import cn.nukkit.event.entity.EntityDamageByEntityEvent;
import cn.nukkit.event.entity.EntityExplodeEvent;
import cn.nukkit.event.entity.EntityLevelChangeEvent;
import cn.nukkit.event.player.PlayerChatEvent;
import cn.nukkit.event.player.PlayerDeathEvent;
import cn.nukkit.event.player.PlayerInteractEvent;
import cn.nukkit.event.player.PlayerJoinEvent;
import cn.nukkit.event.player.PlayerQuitEvent;
import cn.nukkit.level.Level;
import cn.nukkit.level.format.FullChunk;
import cn.nukkit.plugin.PluginBase;
import cn.nukkit.scheduler.PluginTask;
import java.util.*;
import java.util.function.Consumer;

/**
 * Faccoes com cargos, territorio, poder (terras, membros, spawners, abates e mortes com pesos),
 * invasao de borda por faccoes mais fortes, mini mapa no mundo, mapa em texto, home, chat e ranking.
 */
public final class GoldFactions extends PluginBase implements Listener {
    /** Visual proprio, sem escrever "faccao": as mensagens se distinguem das de outros plugins pelo estilo. */
    static String PREFIX = "§4§l»§c» §r§f";
    private static final long INVITE_MS = 120_000;
    private PersistentState<FactionState> data;
    private MiniMap miniMap;
    private boolean mapDirty = true;
    private final Map<String, Object[]> invites = new HashMap<>();
    private final Set<Player> factionChat = Collections.newSetFromMap(new IdentityHashMap<>());
    /** Ultimo chunk de cada jogador, para avisar so quando ele muda de territorio. */
    private final Map<Player, String> lastChunk = new IdentityHashMap<>();

    @Override public void onEnable() {
        Settings.prefix(this, PREFIX, v -> PREFIX = v);
        CoreService core = getServer().getServiceManager().load(CoreService.class);
        if (core == null) { throw new IllegalStateException("GoldCore precisa estar ativo antes do GoldFactions."); }
        data = new PersistentState<>(core.getStore(), "goldfactions", FactionState.class, FactionState::new, FactionState::validate);
        miniMap = new MiniMap(getServer());
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getScheduler().scheduleRepeatingTask(new PluginTask<GoldFactions>(this) { @Override public void onRun(int tick) { territoryTips(); } }, 20);
        // Mapa: logo apos uma mudanca de territorio (no maximo 1x por segundo) e a cada minuto para os nomes.
        getServer().getScheduler().scheduleRepeatingTask(new PluginTask<GoldFactions>(this) { @Override public void onRun(int tick) { if (mapDirty) { renderMap(); } } }, 20);
        getServer().getScheduler().scheduleRepeatingTask(new PluginTask<GoldFactions>(this) { @Override public void onRun(int tick) { mapDirty = true; } }, 20 * 60);
    }

    @Override public void onDisable() { invites.clear(); factionChat.clear(); lastChunk.clear(); if (data != null) { data.close(); } }

    private void renderMap() {
        if (!data.isLoaded()) { return; }
        mapDirty = false;
        data.read(s -> { miniMap.render(s); return null; });
    }

    private String factionOf(String player) { return data.isLoaded() ? data.read(s -> s.members.get(player.toLowerCase(Locale.ROOT))) : null; }

    // ---------- territorio e combate ----------

    private boolean blockedHere(Player p, Level level, int x, int z) {
        if (!data.isLoaded() || p.hasPermission("goldfactions.admin")) { return false; }
        String owner = data.read(s -> s.claims.get(FactionState.chunk(level.getFolderName(), x, z)));
        return owner != null && !owner.equals(factionOf(p.getName()));
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void breakBlock(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (blockedHere(e.getPlayer(), b.level, b.getFloorX(), b.getFloorZ())) { e.setCancelled(); e.getPlayer().sendPopup("§cTerritorio inimigo"); return; }
        if (b.getId() == Block.MONSTER_SPAWNER) { countSpawner(b, -1); }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void place(BlockPlaceEvent e) {
        Block b = e.getBlock();
        if (blockedHere(e.getPlayer(), b.level, b.getFloorX(), b.getFloorZ())) { e.setCancelled(); e.getPlayer().sendPopup("§cTerritorio inimigo"); return; }
        if (b.getId() == Block.MONSTER_SPAWNER) { countSpawner(b, 1); }
    }

    /** Spawners so contam dentro de chunks com dono; a contagem acompanha cada colocacao e quebra. */
    private void countSpawner(Block b, int delta) {
        String key = FactionState.chunk(b.level.getFolderName(), b.getFloorX(), b.getFloorZ());
        if (!data.isLoaded() || !data.read(s -> s.claims.containsKey(key))) { return; }
        data.change(s -> { if (!s.claims.containsKey(key)) { return; } int n = Math.max(0, s.spawners.getOrDefault(key, 0) + delta); if (n == 0) { s.spawners.remove(key); } else { s.spawners.put(key, n); } })
                .thenRun(() -> mapDirty = true);
    }

    /** Spawners destruidos por explosao (TNT, creeper) tambem saem da contagem. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void explode(EntityExplodeEvent e) {
        if (e.getBlockList() == null) { return; }
        for (Block b : e.getBlockList()) { if (b.getId() == Block.MONSTER_SPAWNER && b.level != null) { countSpawner(b, -1); } }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void interact(PlayerInteractEvent e) {
        Block b = e.getBlock();
        if (b == null || b.level == null || b.getId() == 0) { return; }
        if (e.getAction() == PlayerInteractEvent.RIGHT_CLICK_BLOCK && blockedHere(e.getPlayer(), b.level, b.getFloorX(), b.getFloorZ())) { e.setCancelled(); e.getPlayer().sendPopup("§cTerritorio inimigo"); }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void damage(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player) || !(e.getDamager() instanceof Player)) { return; }
        String a = factionOf(e.getDamager().getName()), v = factionOf(e.getEntity().getName());
        if (a != null && a.equals(v)) { e.setCancelled(); ((Player) e.getDamager()).sendPopup("§eMembro da sua faccao"); }
    }

    /** Abates somam e mortes descontam no poder. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void death(PlayerDeathEvent e) {
        String victim = factionOf(e.getEntity().getName());
        String killer = null;
        if (e.getEntity().getLastDamageCause() instanceof EntityDamageByEntityEvent) {
            cn.nukkit.entity.Entity k = ((EntityDamageByEntityEvent) e.getEntity().getLastDamageCause()).getDamager();
            if (k instanceof Player && k != e.getEntity()) { killer = factionOf(k.getName()); }
        }
        if (killer == null && victim == null || (killer != null && killer.equals(victim))) { return; }
        String kf = killer, vf = victim;
        if (kf == null && !(e.getEntity().getLastDamageCause() instanceof EntityDamageByEntityEvent)) { return; }
        data.change(s -> {
            if (kf != null && s.factions.containsKey(kf)) { s.factions.get(kf).kills++; }
            if (vf != null && s.factions.containsKey(vf)) { s.factions.get(vf).deaths++; }
        }).thenRun(() -> mapDirty = true);
    }

    /** Tag da faccao antes do nome no chat; no modo /f chat a mensagem fica so entre os membros. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void chat(PlayerChatEvent e) {
        Player p = e.getPlayer();
        String f = factionOf(p.getName());
        if (f == null) { factionChat.remove(p); return; }
        String tag = data.read(s -> s.factions.get(f).tag);
        if (factionChat.contains(p)) {
            e.setCancelled();
            String msg = "§8«§b" + tag + "§8» §3" + p.getName() + "§7: §b" + e.getMessage();
            for (Player other : getServer().getOnlinePlayers().values()) { if (f.equals(factionOf(other.getName()))) { other.sendMessage(msg); } }
            return;
        }
        e.setFormat(e.getFormat().replace("{%0}", "§8«§b" + tag + "§8»§r {%0}"));
    }

    @EventHandler(priority = EventPriority.MONITOR) public void join(PlayerJoinEvent e) { getServer().getScheduler().scheduleDelayedTask(new PluginTask<GoldFactions>(this) { @Override public void onRun(int tick) { miniMap.showTo(e.getPlayer()); } }, 40); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void level(EntityLevelChangeEvent e) {
        if (!(e.getEntity() instanceof Player)) { return; }
        Player p = (Player) e.getEntity();
        getServer().getScheduler().scheduleDelayedTask(new PluginTask<GoldFactions>(this) { @Override public void onRun(int tick) { miniMap.showTo(p); } }, 40);
    }

    @EventHandler(priority = EventPriority.MONITOR) public void quit(PlayerQuitEvent e) { factionChat.remove(e.getPlayer()); lastChunk.remove(e.getPlayer()); }

    /** Ao cruzar a divisa entre chunks de donos diferentes, mostra de quem e a terra. */
    private void territoryTips() {
        if (!data.isLoaded()) { return; }
        Set<String> worlds = data.read(s -> s.worlds);
        for (Player p : getServer().getOnlinePlayers().values()) {
            if (!p.spawned || (!worlds.isEmpty() && !worlds.contains(p.level.getFolderName()))) { continue; }
            String key = FactionState.chunk(p.level.getFolderName(), p.getFloorX(), p.getFloorZ());
            String owner = data.read(s -> s.claims.get(key));
            String before = lastChunk.put(p, owner == null ? "" : owner);
            if (before == null || before.equals(owner == null ? "" : owner)) { continue; }
            if (owner == null) { p.sendTip("§7Zona livre"); continue; }
            String text = data.read(s -> {
                char c = MiniMap.CHAT[MiniMap.colorIndex(s, owner)];
                return "§" + c + "§l[" + s.factions.get(owner).tag + "] " + owner + "§r §7- poder §f" + s.power(owner);
            });
            p.sendTip(text + (owner.equals(factionOf(p.getName())) ? " §a(sua)" : ""));
        }
    }

    // ---------- comandos ----------

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) { sender.sendMessage("Use dentro do jogo."); return true; }
        Player p = (Player) sender;
        String me = p.getName().toLowerCase(Locale.ROOT);
        String sub = args.length == 0 ? "ajuda" : args[0].toLowerCase(Locale.ROOT);
        try {
            switch (sub) {
                case "criar": {
                    String name = FactionState.name(arg(args, 1)), tag = FactionState.tag(args.length > 2 ? args[2] : name.substring(0, Math.min(4, name.length())));
                    change(p, s -> {
                        if (s.members.containsKey(me)) { throw new IllegalArgumentException("Voce ja tem faccao."); }
                        if (s.factions.containsKey(name)) { throw new IllegalArgumentException("Nome em uso."); }
                        for (Faction f : s.factions.values()) { if (f.tag.equals(tag)) { throw new IllegalArgumentException("Tag em uso."); } }
                        Faction f = new Faction(); f.tag = tag; f.created = System.currentTimeMillis(); f.roster.put(me, Role.LIDER);
                        s.factions.put(name, f); s.members.put(me, name);
                    }, () -> getServer().broadcastMessage(PREFIX + "§e" + p.getName() + " fundou §b" + name + " §8«§b" + tag + "§8»"));
                    return true;
                }
                case "convidar": {
                    Player target = getServer().getPlayerExact(arg(args, 1));
                    if (target == null) { throw new IllegalArgumentException("Jogador offline."); }
                    String f = need(me, Role.OFICIAL);
                    if (factionOf(target.getName()) != null) { throw new IllegalArgumentException("Ele ja tem faccao."); }
                    invites.put(target.getName().toLowerCase(Locale.ROOT), new Object[] {f, System.currentTimeMillis()});
                    target.sendMessage(PREFIX + "Convite de §b" + f + "§f. Use §a/f aceitar§f (2 min).");
                    p.sendMessage(PREFIX + "Convite enviado.");
                    return true;
                }
                case "aceitar": {
                    Object[] inv = invites.remove(me);
                    if (inv == null || System.currentTimeMillis() - (Long) inv[1] > INVITE_MS) { throw new IllegalArgumentException("Nenhum convite valido."); }
                    String f = (String) inv[0];
                    change(p, s -> {
                        Faction x = s.factions.get(f);
                        if (x == null) { throw new IllegalArgumentException("A faccao nao existe mais."); }
                        if (s.members.containsKey(me)) { throw new IllegalArgumentException("Voce ja tem faccao."); }
                        if (x.roster.size() >= FactionState.MAX_MEMBERS) { throw new IllegalArgumentException("Faccao cheia."); }
                        x.roster.put(me, Role.MEMBRO); s.members.put(me, f);
                    }, () -> { broadcast(f, "§a" + p.getName() + " entrou."); mapDirty = true; });
                    return true;
                }
                case "sair": {
                    String f = need(me, Role.MEMBRO);
                    change(p, s -> {
                        Faction x = s.factions.get(f);
                        if (x.roster.get(me) == Role.LIDER) { throw new IllegalArgumentException("O lider passa a lideranca (/f lider) ou desfaz (/f desfazer)."); }
                        x.roster.remove(me); s.members.remove(me);
                    }, () -> { broadcast(f, "§c" + p.getName() + " saiu."); p.sendMessage(PREFIX + "Voce saiu."); mapDirty = true; });
                    return true;
                }
                case "expulsar": {
                    String target = arg(args, 1).toLowerCase(Locale.ROOT), f = need(me, Role.OFICIAL);
                    change(p, s -> {
                        Faction x = s.factions.get(f);
                        Role r = x.roster.get(target);
                        if (r == null) { throw new IllegalArgumentException("Nao e da sua faccao."); }
                        if (r.ordinal() >= x.roster.get(me).ordinal()) { throw new IllegalArgumentException("So pode expulsar cargos abaixo do seu."); }
                        x.roster.remove(target); s.members.remove(target);
                    }, () -> { broadcast(f, "§c" + target + " foi expulso."); mapDirty = true; });
                    return true;
                }
                case "promover": case "rebaixar": {
                    String target = arg(args, 1).toLowerCase(Locale.ROOT), f = need(me, Role.LIDER);
                    Role role = sub.equals("promover") ? Role.OFICIAL : Role.MEMBRO;
                    change(p, s -> { Faction x = s.factions.get(f); if (!x.roster.containsKey(target) || target.equals(me)) { throw new IllegalArgumentException("Membro invalido."); } x.roster.put(target, role); }, () -> broadcast(f, target + " agora e " + role.name().toLowerCase(Locale.ROOT) + "."));
                    return true;
                }
                case "lider": {
                    String target = arg(args, 1).toLowerCase(Locale.ROOT), f = need(me, Role.LIDER);
                    change(p, s -> { Faction x = s.factions.get(f); if (!x.roster.containsKey(target) || target.equals(me)) { throw new IllegalArgumentException("Membro invalido."); } x.roster.put(target, Role.LIDER); x.roster.put(me, Role.OFICIAL); }, () -> broadcast(f, "§6" + target + " e o novo lider."));
                    return true;
                }
                case "desfazer": {
                    String f = need(me, Role.LIDER);
                    change(p, s -> s.remove(f), () -> { getServer().broadcastMessage(PREFIX + "§7" + f + " foi desfeita."); mapDirty = true; });
                    return true;
                }
                case "claim": case "dominar": { claim(p, me); return true; }
                case "unclaim": case "abandonar": {
                    String f = need(me, Role.OFICIAL), key = FactionState.chunk(p.level.getFolderName(), p.getFloorX(), p.getFloorZ());
                    change(p, s -> { if (!f.equals(s.claims.get(key))) { throw new IllegalArgumentException("Este chunk nao e seu."); } s.claims.remove(key); s.spawners.remove(key); }, () -> { p.sendMessage(PREFIX + "Chunk abandonado."); mapDirty = true; });
                    return true;
                }
                case "sethome": {
                    String f = need(me, Role.OFICIAL);
                    String key = FactionState.chunk(p.level.getFolderName(), p.getFloorX(), p.getFloorZ());
                    if (!f.equals(data.read(s -> s.claims.get(key)))) { throw new IllegalArgumentException("A home precisa ficar no seu territorio."); }
                    Destination here = Destination.position(p.level.getFolderName(), p.x, p.y, p.z, (float) p.yaw, (float) p.pitch);
                    change(p, s -> s.factions.get(f).home = here, () -> p.sendMessage(PREFIX + "Home da faccao salva."));
                    return true;
                }
                case "home": {
                    String f = need(me, Role.MEMBRO);
                    Destination home = data.read(s -> s.factions.get(f).home);
                    if (home == null) { throw new IllegalArgumentException("Sem home (/f sethome)."); }
                    getServer().getServiceManager().load(TravelService.class).travel(p, home, Transition.CONTAGEM);
                    return true;
                }
                case "chat": {
                    need(me, Role.MEMBRO);
                    boolean on = factionChat.add(p);
                    if (!on) { factionChat.remove(p); }
                    p.sendMessage(PREFIX + "Chat interno " + (on ? "§aligado" : "§cdesligado") + ".");
                    return true;
                }
                case "info": case "poder": {
                    String f = args.length > 1 ? FactionState.name(args[1]) : factionOf(me);
                    if (f == null) { throw new IllegalArgumentException("Use /f info <faccao>."); }
                    List<String> lines = data.read(s -> {
                        Faction x = s.factions.get(f);
                        if (x == null) { return null; }
                        FactionState.Weights w = s.weights;
                        int terras = s.claimsOf(f), spawners = s.spawnersOf(f);
                        List<String> out = new ArrayList<>();
                        out.add("§" + MiniMap.CHAT[MiniMap.colorIndex(s, f)] + "§l[" + x.tag + "] " + f + " §r§7- poder §f" + s.power(f));
                        out.add("§7Terras §f" + terras + "/" + FactionState.maxClaims(x) + " §8(x" + w.terras + " = " + terras * w.terras + ")");
                        out.add("§7Membros §f" + x.roster.size() + "/" + FactionState.MAX_MEMBERS + " §8(x" + w.membros + " = " + x.roster.size() * w.membros + ")");
                        out.add("§7Spawners §f" + spawners + " §8(x" + w.spawners + " = " + spawners * w.spawners + ")");
                        out.add("§7Abates §a" + x.kills + " §8(x" + w.abates + ")  §7Mortes §c" + x.deaths + " §8(-" + w.mortes + " cada)");
                        StringBuilder roster = new StringBuilder("§f");
                        for (Map.Entry<String, Role> m : x.roster.entrySet()) { roster.append(m.getValue() == Role.LIDER ? "**" : m.getValue() == Role.OFICIAL ? "*" : "").append(m.getKey()).append(' '); }
                        out.add(roster.toString().trim());
                        return out;
                    });
                    if (lines == null) { throw new IllegalArgumentException("Faccao inexistente."); }
                    for (String l : lines) { p.sendMessage(l); }
                    return true;
                }
                case "top": {
                    List<String> top = data.read(s -> {
                        List<String> names = new ArrayList<>(s.factions.keySet());
                        names.sort((a, b) -> Long.compare(s.power(b), s.power(a)));
                        List<String> out = new ArrayList<>();
                        for (int i = 0; i < Math.min(10, names.size()); i++) { String n = names.get(i); out.add("§e" + (i + 1) + ". §" + MiniMap.CHAT[MiniMap.colorIndex(s, n)] + "[" + s.factions.get(n).tag + "] " + n + " §7- poder §f" + s.power(n) + " §8(" + s.claimsOf(n) + " terras)"); }
                        return out;
                    });
                    p.sendMessage(PREFIX + "§6Top por poder:");
                    for (String l : top) { p.sendMessage(l); }
                    return true;
                }
                case "mapa": {
                    if (args.length > 1 && p.hasPermission("goldfactions.admin")) { mapAdmin(p, args); return true; }
                    String mine = factionOf(me);
                    List<String> lines = data.read(s -> MiniMap.ascii(s, p.level.getFolderName(), p.getFloorX() >> 4, p.getFloorZ() >> 4, mine));
                    p.sendMessage(PREFIX + "§7Mapa (norte em cima):");
                    for (String l : lines) { p.sendMessage(l); }
                    return true;
                }
                case "mundo": case "peso": case "recontar": { admin(p, sub, args); return true; }
                default:
                    p.sendMessage(PREFIX + "/f criar <nome> [tag], convidar, aceitar, sair, expulsar, promover, rebaixar, lider, desfazer");
                    p.sendMessage(PREFIX + "/f claim, unclaim, mapa, info, poder, top, sethome, home, chat");
                    if (p.hasPermission("goldfactions.admin")) { p.sendMessage(PREFIX + "Admin: /f mundo <mundo>, peso <fator> <valor>, recontar, mapa definir [raio], mapa centro, mapa remover"); }
                    return true;
            }
        } catch (RuntimeException e) { p.sendMessage(PREFIX + "§c" + root(e)); return true; }
    }

    /** Dominar terra livre ou invadir a borda de uma faccao mais fraca. */
    private void claim(Player p, String me) {
        String f = need(me, Role.OFICIAL), world = p.level.getFolderName();
        int cx = p.getFloorX() >> 4, cz = p.getFloorZ() >> 4;
        String key = world + ":" + cx + ":" + cz;
        Set<String> worlds = data.read(s -> s.worlds);
        if (!worlds.isEmpty() && !worlds.contains(world)) { throw new IllegalArgumentException("Terras so podem ser dominadas em: " + String.join(", ", worlds)); }
        int spawners = spawnersIn(p.level, cx, cz);
        String[] victim = new String[1];
        change(p, s -> {
            String owner = s.claims.get(key);
            Faction x = s.factions.get(f);
            if (owner != null) {
                if (owner.equals(f)) { throw new IllegalArgumentException("Este chunk ja e seu."); }
                long mine = s.power(f), theirs = s.power(owner);
                if (mine <= theirs) { throw new IllegalArgumentException("Territorio de " + owner + " (poder " + theirs + "). Seu poder (" + mine + ") precisa ser maior para invadir."); }
                if (!s.border(world, cx, cz, owner)) { throw new IllegalArgumentException("So a borda do territorio inimigo pode ser invadida."); }
                victim[0] = owner;
            }
            if (s.claimsOf(f) >= FactionState.maxClaims(x)) { throw new IllegalArgumentException("Limite de " + FactionState.maxClaims(x) + " chunks (cresce com membros)."); }
            s.claims.put(key, f);
            if (spawners > 0) { s.spawners.put(key, spawners); } else { s.spawners.remove(key); }
        }, () -> {
            mapDirty = true;
            if (victim[0] == null) { p.sendMessage(PREFIX + "Chunk dominado" + (spawners > 0 ? " com " + spawners + " spawner(s)!" : ".")); return; }
            getServer().broadcastMessage(PREFIX + "§c" + f + " invadiu terras de " + victim[0] + "!");
        });
    }

    /** Conta spawners no chunk carregado (o jogador esta nele ao dominar). */
    private static int spawnersIn(Level level, int cx, int cz) {
        FullChunk chunk = level.getChunk(cx, cz, false);
        if (chunk == null) { return 0; }
        int n = 0;
        for (BlockEntity tile : chunk.getBlockEntities().values()) { if (tile instanceof BlockEntityMobSpawner) { n++; } }
        return n;
    }

    private void mapAdmin(Player p, String[] args) {
        String what = args[1].toLowerCase(Locale.ROOT);
        if (what.equals("remover")) { change(p, s -> s.map = null, () -> { mapDirty = true; p.sendMessage(PREFIX + "Mini mapa desligado (os blocos ficam onde estao)."); }); return; }
        if (what.equals("centro")) {
            int cx = p.getFloorX() >> 4, cz = p.getFloorZ() >> 4;
            String world = p.level.getFolderName();
            change(p, s -> { if (s.map == null) { throw new IllegalArgumentException("Defina o mapa antes (/f mapa definir)."); } s.map.source = world; s.map.sourceCx = cx; s.map.sourceCz = cz; },
                    () -> { mapDirty = true; p.sendMessage(PREFIX + "O mapa agora mostra " + world + " centrado no seu chunk."); });
            return;
        }
        if (!what.equals("definir")) { p.sendMessage(PREFIX + "Uso: /f mapa definir [raio 4-24] | centro | remover"); return; }
        int radius = args.length > 2 ? Integer.parseInt(args[2]) : 12;
        FactionState.MiniMap map = new FactionState.MiniMap();
        map.world = p.level.getFolderName(); map.x = p.getFloorX(); map.y = p.getFloorY() - 1; map.z = p.getFloorZ(); map.radius = radius;
        map.source = data.read(s -> s.worlds.isEmpty() ? map.world : s.worlds.iterator().next());
        Level source = getServer().getLevelByName(map.source);
        if (source != null) { map.sourceCx = source.getSafeSpawn().getFloorX() >> 4; map.sourceCz = source.getSafeSpawn().getFloorZ() >> 4; }
        change(p, s -> s.map = map, () -> { mapDirty = true; p.sendMessage(PREFIX + "Mini mapa de " + (radius * 2 + 1) + "x" + (radius * 2 + 1) + " no chao sob voce, mostrando " + map.source + ". Use /f mapa centro para mudar o centro."); });
    }

    private void admin(Player p, String sub, String[] args) {
        if (!p.hasPermission("goldfactions.admin")) { throw new IllegalArgumentException("Sem permissao."); }
        switch (sub) {
            case "mundo": {
                String world = Destination.world(arg(args, 1)).target;
                boolean[] on = new boolean[1];
                change(p, s -> { on[0] = s.worlds.add(world); if (!on[0]) { s.worlds.remove(world); } }, () -> p.sendMessage(PREFIX + "Mundos de faccoes: " + data.read(s -> s.worlds.isEmpty() ? "todos" : String.join(", ", s.worlds))));
                return;
            }
            case "peso": {
                String factor = arg(args, 1).toLowerCase(Locale.ROOT);
                int value = Integer.parseInt(arg(args, 2));
                change(p, s -> {
                    FactionState.Weights w = s.weights;
                    switch (factor) {
                        case "terras": w.terras = value; break;
                        case "membros": w.membros = value; break;
                        case "spawners": w.spawners = value; break;
                        case "abates": w.abates = value; break;
                        case "mortes": w.mortes = value; break;
                        default: throw new IllegalArgumentException("Fatores: terras, membros, spawners, abates, mortes.");
                    }
                }, () -> { mapDirty = true; FactionState.Weights w = data.read(s -> s.weights); p.sendMessage(PREFIX + "Pesos: terras " + w.terras + ", membros " + w.membros + ", spawners " + w.spawners + ", abates " + w.abates + ", mortes -" + w.mortes); });
                return;
            }
            default: {
                // Recontagem dos spawners nos chunks com dono que estao carregados.
                Map<String, Integer> counted = new HashMap<>();
                for (String key : data.read(s -> new ArrayList<>(s.claims.keySet()))) {
                    String[] parts = key.split(":");
                    Level level = getServer().getLevelByName(parts[0]);
                    if (level == null || level.getChunk(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), false) == null) { continue; }
                    counted.put(key, spawnersIn(level, Integer.parseInt(parts[1]), Integer.parseInt(parts[2])));
                }
                change(p, s -> { for (Map.Entry<String, Integer> e : counted.entrySet()) { if (!s.claims.containsKey(e.getKey())) { continue; } if (e.getValue() == 0) { s.spawners.remove(e.getKey()); } else { s.spawners.put(e.getKey(), e.getValue()); } } },
                        () -> { mapDirty = true; p.sendMessage(PREFIX + counted.size() + " chunks carregados recontados."); });
            }
        }
    }

    /** Faccao do jogador, exigindo um cargo minimo. */
    private String need(String player, Role min) {
        String f = factionOf(player);
        if (f == null) { throw new IllegalArgumentException("Voce nao tem faccao. Use /f criar."); }
        Role r = data.read(s -> s.factions.get(f).roster.get(player));
        if (r == null || r.ordinal() < min.ordinal()) { throw new IllegalArgumentException("Seu cargo nao permite."); }
        return f;
    }

    private void broadcast(String faction, String message) {
        for (Player p : getServer().getOnlinePlayers().values()) { if (faction.equals(factionOf(p.getName()))) { p.sendMessage(PREFIX + message); } }
    }

    private void change(Player p, Consumer<FactionState> mutation, Runnable success) {
        data.change(mutation).whenComplete((v, e) -> { if (!isEnabled()) { return; } if (e != null) { if (p.isOnline()) { p.sendMessage(PREFIX + "§c" + root(e)); } } else { success.run(); } });
    }

    private static String arg(String[] args, int i) { if (args.length <= i) { throw new IllegalArgumentException("Faltam argumentos. Use /f."); } return args[i]; }
    static String root(Throwable e) { while (e.getCause() != null) { e = e.getCause(); } return e.getMessage(); }
}
