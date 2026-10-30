package br.goldsuite.pvp;

import br.goldsuite.api.*;
import cn.nukkit.Player;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.entity.Entity;
import cn.nukkit.entity.mob.EntityCreeper;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.entity.EntityDamageByEntityEvent;
import cn.nukkit.event.entity.EntityDamageEvent;
import cn.nukkit.event.entity.EntityExplodeEvent;
import cn.nukkit.event.player.PlayerCommandPreprocessEvent;
import cn.nukkit.event.player.PlayerDeathEvent;
import cn.nukkit.event.player.PlayerQuitEvent;
import cn.nukkit.item.Item;
import cn.nukkit.plugin.PluginBase;
import java.util.*;

/** Combat log, perfil de PvP e creepers que nao quebram blocos. */
public final class GoldPvP extends PluginBase implements Listener {
    static final String PREFIX = "§4[§cPvP§4]§f ";
    private static final long TAG_MS = 15_000;
    private static final Set<String> ESCAPES = new HashSet<>(Arrays.asList("spawn", "lobby", "home", "casa", "warp", "warps", "tpa", "tpahere", "tpaccept", "tpaceitar", "back", "voltar", "ec", "bau", "enderchest", "hg", "fly", "voar"));
    private PersistentState<Stats> data;
    private final Map<Player, Long> taggedUntil = new IdentityHashMap<>();

    @Override public void onEnable() {
        CoreService core = getServer().getServiceManager().load(CoreService.class);
        if (core == null) { throw new IllegalStateException("GoldCore precisa estar ativo antes do GoldPvP."); }
        data = new PersistentState<>(core.getStore(), "goldpvp", Stats.class, Stats::new, Stats::validate);
        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override public void onDisable() { taggedUntil.clear(); if (data != null) { data.close(); } }

    private boolean tagged(Player p) { Long until = taggedUntil.get(p); return until != null && until > System.currentTimeMillis(); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void damage(EntityDamageEvent event) {
        if (!(event instanceof EntityDamageByEntityEvent) || !(event.getEntity() instanceof Player)) { return; }
        Entity damager = ((EntityDamageByEntityEvent) event).getDamager();
        if (!(damager instanceof Player) || damager == event.getEntity()) { return; }
        long until = System.currentTimeMillis() + TAG_MS;
        for (Player p : new Player[] {(Player) event.getEntity(), (Player) damager}) {
            if (!tagged(p)) { p.sendPopup("§cEm combate! Nao saia por 15 s."); }
            taggedUntil.put(p, until);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void command(PlayerCommandPreprocessEvent event) {
        Player p = event.getPlayer();
        if (!tagged(p) || p.hasPermission("goldpvp.admin")) { return; }
        String m = event.getMessage();
        if (!m.startsWith("/")) { return; }
        int space = m.indexOf(' ');
        String name = (space < 0 ? m.substring(1) : m.substring(1, space)).toLowerCase(Locale.ROOT);
        if (ESCAPES.contains(name.contains(":") ? name.substring(name.indexOf(':') + 1) : name)) {
            event.setCancelled();
            p.sendMessage(PREFIX + "§cVoce esta em combate por mais " + ((taggedUntil.get(p) - System.currentTimeMillis()) / 1000 + 1) + " s.");
        }
    }

    /** Sair em combate larga tudo no chao e conta como morte para quem estava lutando. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void quit(PlayerQuitEvent event) {
        Player p = event.getPlayer();
        boolean wasTagged = tagged(p);
        taggedUntil.remove(p);
        if (!wasTagged || !p.isAlive() || p.hasPermission("goldpvp.admin")) { return; }
        for (Item item : new ArrayList<>(p.getInventory().getContents().values())) { if (item.getId() != 0) { p.level.dropItem(p, item); } }
        for (Item armor : p.getInventory().getArmorContents()) { if (armor.getId() != 0) { p.level.dropItem(p, armor); } }
        p.getInventory().clearAll();
        String name = p.getName().toLowerCase(Locale.ROOT);
        data.change(s -> { int[] st = s.of(name); st[Stats.DEATHS]++; st[Stats.STREAK] = 0; });
        getServer().broadcastMessage(PREFIX + "§e" + p.getName() + "§f saiu em combate e perdeu os itens!");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void death(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        taggedUntil.remove(victim);
        EntityDamageEvent cause = victim.getLastDamageCause();
        if (!(cause instanceof EntityDamageByEntityEvent) || !(((EntityDamageByEntityEvent) cause).getDamager() instanceof Player)) { return; }
        Player killer = (Player) ((EntityDamageByEntityEvent) cause).getDamager();
        if (killer == victim) { return; }
        String k = killer.getName().toLowerCase(Locale.ROOT), v = victim.getName().toLowerCase(Locale.ROOT);
        int[] streak = new int[1];
        data.change(s -> { s.kill(k, v); streak[0] = s.of(k)[Stats.STREAK]; }).thenRun(() -> {
            if (streak[0] >= 5 && streak[0] % 5 == 0) { getServer().broadcastMessage(PREFIX + "§6" + killer.getName() + " esta com " + streak[0] + " abates seguidos!"); }
        });
    }

    /** Creeper ainda causa dano, mas nao destroi construcoes. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void explode(EntityExplodeEvent event) {
        if (event.getEntity() instanceof EntityCreeper) { event.setBlockList(new ArrayList<>()); }
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equals("topkills")) {
            List<Map.Entry<String, int[]>> top = data.read(s -> {
                List<Map.Entry<String, int[]>> list = new ArrayList<>();
                for (Map.Entry<String, int[]> e : s.players.entrySet()) { if (e.getValue()[Stats.KILLS] > 0) { list.add(new AbstractMap.SimpleImmutableEntry<>(e.getKey(), e.getValue().clone())); } }
                return list;
            });
            top.sort((a, b) -> b.getValue()[Stats.KILLS] - a.getValue()[Stats.KILLS]);
            sender.sendMessage(PREFIX + "§6Top abates:");
            for (int i = 0; i < Math.min(10, top.size()); i++) { sender.sendMessage("§e" + (i + 1) + ". §f" + top.get(i).getKey() + " §7- " + top.get(i).getValue()[Stats.KILLS] + " abates"); }
            return true;
        }
        String name = (args.length > 0 ? args[0] : sender.getName()).toLowerCase(Locale.ROOT);
        int[] st = data.read(s -> { int[] x = s.players.get(name); return x == null ? new int[4] : x.clone(); });
        sender.sendMessage(PREFIX + "§6Perfil de " + name);
        sender.sendMessage("§fAbates: §a" + st[Stats.KILLS] + " §fMortes: §c" + st[Stats.DEATHS] + " §fKDR: §e" + Stats.kdr(st));
        sender.sendMessage("§fSequencia atual: §b" + st[Stats.STREAK] + " §fMelhor: §b" + st[Stats.BEST]);
        return true;
    }
}
