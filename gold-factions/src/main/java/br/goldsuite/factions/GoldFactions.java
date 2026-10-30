package br.goldsuite.factions;

import br.goldsuite.api.*;
import br.goldsuite.api.TravelService.Transition;
import br.goldsuite.factions.FactionState.Faction;
import br.goldsuite.factions.FactionState.Role;
import cn.nukkit.Player;
import cn.nukkit.block.Block;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.block.BlockBreakEvent;
import cn.nukkit.event.block.BlockPlaceEvent;
import cn.nukkit.event.entity.EntityDamageByEntityEvent;
import cn.nukkit.event.player.PlayerChatEvent;
import cn.nukkit.event.player.PlayerDeathEvent;
import cn.nukkit.event.player.PlayerInteractEvent;
import cn.nukkit.event.player.PlayerQuitEvent;
import cn.nukkit.level.Level;
import cn.nukkit.plugin.PluginBase;
import java.util.*;
import java.util.function.Consumer;

/** Faccoes com cargos, convites, territorio, home, chat e ranking (FactionsPro e TopFacs). */
public final class GoldFactions extends PluginBase implements Listener {
    static final String PREFIX = "§3[§bFaccao§3]§f ";
    private static final long INVITE_MS = 120_000;
    private PersistentState<FactionState> data;
    /** Convidado -> {faccao, instante}. */
    private final Map<String, Object[]> invites = new HashMap<>();
    private final Set<Player> factionChat = Collections.newSetFromMap(new IdentityHashMap<>());

    @Override public void onEnable() {
        CoreService core = getServer().getServiceManager().load(CoreService.class);
        if (core == null) { throw new IllegalStateException("GoldCore precisa estar ativo antes do GoldFactions."); }
        data = new PersistentState<>(core.getStore(), "goldfactions", FactionState.class, FactionState::new, FactionState::validate);
        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override public void onDisable() { invites.clear(); factionChat.clear(); if (data != null) { data.close(); } }

    private String factionOf(String player) { return data.isLoaded() ? data.read(s -> s.members.get(player.toLowerCase(Locale.ROOT))) : null; }

    // ---------- territorio e combate ----------

    private boolean blockedHere(Player p, Level level, int x, int z) {
        if (!data.isLoaded() || p.hasPermission("goldfactions.admin")) { return false; }
        String owner = data.read(s -> s.claims.get(FactionState.chunk(level.getFolderName(), x, z)));
        return owner != null && !owner.equals(factionOf(p.getName()));
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void breakBlock(BlockBreakEvent e) { Block b = e.getBlock(); if (blockedHere(e.getPlayer(), b.level, b.getFloorX(), b.getFloorZ())) { e.setCancelled(); e.getPlayer().sendPopup("§cTerritorio de outra faccao"); } }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void place(BlockPlaceEvent e) { Block b = e.getBlock(); if (blockedHere(e.getPlayer(), b.level, b.getFloorX(), b.getFloorZ())) { e.setCancelled(); e.getPlayer().sendPopup("§cTerritorio de outra faccao"); } }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void interact(PlayerInteractEvent e) {
        Block b = e.getBlock();
        if (b == null || b.level == null || b.getId() == 0) { return; }
        if (blockedHere(e.getPlayer(), b.level, b.getFloorX(), b.getFloorZ()) && e.getAction() == PlayerInteractEvent.RIGHT_CLICK_BLOCK) { e.setCancelled(); e.getPlayer().sendPopup("§cTerritorio de outra faccao"); }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void damage(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player) || !(e.getDamager() instanceof Player)) { return; }
        String a = factionOf(e.getDamager().getName()), v = factionOf(e.getEntity().getName());
        if (a != null && a.equals(v)) { e.setCancelled(); ((Player) e.getDamager()).sendPopup("§eAliado da sua faccao"); }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void death(PlayerDeathEvent e) {
        if (!(e.getEntity().getLastDamageCause() instanceof EntityDamageByEntityEvent)) { return; }
        cn.nukkit.entity.Entity killer = ((EntityDamageByEntityEvent) e.getEntity().getLastDamageCause()).getDamager();
        if (!(killer instanceof Player)) { return; }
        String f = factionOf(killer.getName());
        if (f != null && !f.equals(factionOf(e.getEntity().getName()))) { data.change(s -> { Faction x = s.factions.get(f); if (x != null) { x.kills++; } }); }
    }

    /** Tag da faccao antes do nome; no modo /f chat a mensagem vai so para a faccao. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void chat(PlayerChatEvent e) {
        Player p = e.getPlayer();
        String f = factionOf(p.getName());
        if (f == null) { factionChat.remove(p); return; }
        String tag = data.read(s -> s.factions.get(f).tag);
        if (factionChat.contains(p)) {
            e.setCancelled();
            String msg = "§b[F] §3" + p.getName() + "§f: " + e.getMessage();
            for (Player other : getServer().getOnlinePlayers().values()) { if (f.equals(factionOf(other.getName()))) { other.sendMessage(msg); } }
            return;
        }
        e.setFormat(e.getFormat().replace("{%0}", "§8[§b" + tag + "§8]§r {%0}"));
    }

    @EventHandler(priority = EventPriority.MONITOR) public void quit(PlayerQuitEvent e) { factionChat.remove(e.getPlayer()); }

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
                    }, () -> getServer().broadcastMessage(PREFIX + "§e" + p.getName() + " fundou a faccao §b" + name + " [" + tag + "]"));
                    return true;
                }
                case "convidar": {
                    Player target = getServer().getPlayerExact(arg(args, 1));
                    if (target == null) { throw new IllegalArgumentException("Jogador offline."); }
                    String f = need(me, Role.OFICIAL);
                    if (factionOf(target.getName()) != null) { throw new IllegalArgumentException("Ele ja tem faccao."); }
                    invites.put(target.getName().toLowerCase(Locale.ROOT), new Object[] {f, System.currentTimeMillis()});
                    target.sendMessage(PREFIX + "Convite da faccao §b" + f + "§f. Use §a/f aceitar§f (2 min).");
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
                    }, () -> broadcast(f, "§a" + p.getName() + " entrou na faccao."));
                    return true;
                }
                case "sair": {
                    String f = need(me, Role.MEMBRO);
                    change(p, s -> {
                        Faction x = s.factions.get(f);
                        if (x.roster.get(me) == Role.LIDER) { throw new IllegalArgumentException("O lider passa a lideranca (/f lider) ou desfaz (/f desfazer)."); }
                        x.roster.remove(me); s.members.remove(me);
                    }, () -> { broadcast(f, "§c" + p.getName() + " saiu da faccao."); p.sendMessage(PREFIX + "Voce saiu."); });
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
                    }, () -> broadcast(f, "§c" + target + " foi expulso."));
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
                    change(p, s -> s.remove(f), () -> getServer().broadcastMessage(PREFIX + "A faccao " + f + " foi desfeita."));
                    return true;
                }
                case "claim": case "dominar": {
                    String f = need(me, Role.OFICIAL), key = FactionState.chunk(p.level.getFolderName(), p.getFloorX(), p.getFloorZ());
                    change(p, s -> {
                        String owner = s.claims.get(key);
                        if (owner != null) { throw new IllegalArgumentException(owner.equals(f) ? "Este chunk ja e seu." : "Chunk da faccao " + owner + "."); }
                        Faction x = s.factions.get(f);
                        if (s.claimsOf(f) >= FactionState.maxClaims(x)) { throw new IllegalArgumentException("Limite de " + FactionState.maxClaims(x) + " chunks (cresce com membros)."); }
                        s.claims.put(key, f);
                    }, () -> p.sendMessage(PREFIX + "Chunk dominado."));
                    return true;
                }
                case "unclaim": case "abandonar": {
                    String f = need(me, Role.OFICIAL), key = FactionState.chunk(p.level.getFolderName(), p.getFloorX(), p.getFloorZ());
                    change(p, s -> { if (!f.equals(s.claims.get(key))) { throw new IllegalArgumentException("Este chunk nao e seu."); } s.claims.remove(key); }, () -> p.sendMessage(PREFIX + "Chunk abandonado."));
                    return true;
                }
                case "sethome": {
                    String f = need(me, Role.OFICIAL);
                    Destination here = Destination.position(p.level.getFolderName(), p.x, p.y, p.z, (float) p.yaw, (float) p.pitch);
                    change(p, s -> s.factions.get(f).home = here, () -> p.sendMessage(PREFIX + "Home da faccao salva."));
                    return true;
                }
                case "home": {
                    String f = need(me, Role.MEMBRO);
                    Destination home = data.read(s -> s.factions.get(f).home);
                    if (home == null) { throw new IllegalArgumentException("A faccao nao tem home (/f sethome)."); }
                    getServer().getServiceManager().load(TravelService.class).travel(p, home, Transition.CONTAGEM);
                    return true;
                }
                case "chat": {
                    need(me, Role.MEMBRO);
                    boolean on = factionChat.add(p);
                    if (!on) { factionChat.remove(p); }
                    p.sendMessage(PREFIX + "Chat da faccao " + (on ? "§aligado" : "§cdesligado") + ".");
                    return true;
                }
                case "info": {
                    String f = args.length > 1 ? FactionState.name(args[1]) : factionOf(me);
                    if (f == null) { throw new IllegalArgumentException("Use /f info <faccao>."); }
                    List<String> lines = data.read(s -> {
                        Faction x = s.factions.get(f);
                        if (x == null) { return null; }
                        List<String> out = new ArrayList<>();
                        out.add("§b" + f + " [" + x.tag + "] §7- " + x.roster.size() + "/" + FactionState.MAX_MEMBERS + " membros, " + s.claimsOf(f) + "/" + FactionState.maxClaims(x) + " chunks, " + x.kills + " abates");
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
                        List<Map.Entry<String, Faction>> list = new ArrayList<>(s.factions.entrySet());
                        list.sort((a, b) -> b.getValue().kills - a.getValue().kills);
                        List<String> out = new ArrayList<>();
                        for (int i = 0; i < Math.min(10, list.size()); i++) { out.add("§e" + (i + 1) + ". §b" + list.get(i).getKey() + " [" + list.get(i).getValue().tag + "] §7- " + list.get(i).getValue().kills + " abates, " + list.get(i).getValue().roster.size() + " membros"); }
                        return out;
                    });
                    p.sendMessage(PREFIX + "§6Top faccoes:");
                    for (String l : top) { p.sendMessage(l); }
                    return true;
                }
                default:
                    p.sendMessage(PREFIX + "/f criar <nome> [tag], convidar, aceitar, sair, expulsar, promover, rebaixar, lider, desfazer");
                    p.sendMessage(PREFIX + "/f claim, unclaim, sethome, home, chat, info [faccao], top");
                    return true;
            }
        } catch (RuntimeException e) { p.sendMessage(PREFIX + "§c" + root(e)); return true; }
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
