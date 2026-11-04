package br.goldsuite.skywars;

import cn.nukkit.Player;
import cn.nukkit.entity.Entity;
import cn.nukkit.entity.projectile.EntityProjectile;
import cn.nukkit.event.*;
import cn.nukkit.event.block.*;
import cn.nukkit.event.entity.*;
import cn.nukkit.item.Item;
import cn.nukkit.potion.Effect;
import java.util.*;

final class Perks implements Listener {
    private final GoldSkyWars plugin;
    private final Random random = new Random();
    Perks(GoldSkyWars plugin) { this.plugin = plugin; }
    private int level(Player p, String perk) { return plugin.progress.loaded(p) ? plugin.progress.perk(p, perk) : 0; }
    private boolean active(Player p) { GoldSkyWars.Game g = plugin.inGame.get(p); return g != null && g.phase == GoldSkyWars.Phase.JOGANDO && g.players.containsKey(p); }
    void start(Player p) {
        int speed = level(p, "speedboost"), resistance = level(p, "resistanceboost");
        if (speed > 0) { effect(p, Effect.SPEED, 5 + speed * 5); }
        if (resistance > 0) { effect(p, Effect.DAMAGE_RESISTANCE, 5 + resistance * 5); }
    }
    void kill(Player p) {
        if (level(p, "bulldozer") > 0) { effect(p, Effect.STRENGTH, 5); }
        if (level(p, "juggernaut") > 0) { effect(p, Effect.REGENERATION, 4); }
        if (random.nextInt(100) < level(p, "savior") * 20) { effect(p, Effect.ABSORPTION, 4); }
        if (random.nextInt(100) < level(p, "nourishment") * 20) { p.getFoodData().setLevel(20); }
        if (level(p, "knowledge") > 0) { p.setExperience(p.getExperience(), p.getExperienceLevel() + 2); }
    }
    private void effect(Player p, int id, int seconds) { p.addEffect(Effect.getEffect(id).setDuration(seconds * 20).setAmplifier(0)); }
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void damage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player) || !active((Player)e.getEntity())) { return; }
        Player p = (Player)e.getEntity(); int reduction = 0;
        switch (e.getCause()) {
            case EntityDamageEvent.CAUSE_FALL: reduction = level(p, "fallreduction") * 10; break;
            case EntityDamageEvent.CAUSE_FIRE: case EntityDamageEvent.CAUSE_FIRE_TICK: case EntityDamageEvent.CAUSE_LAVA: reduction = level(p, "firedamagereduction") * 7; break;
            case EntityDamageEvent.CAUSE_PROJECTILE: reduction = level(p, "shotdamagereduction") * 5; break;
            default: break;
        }
        if (reduction > 0) { e.setDamage(e.getDamage() * (100 - reduction) / 100f); }
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void arrow(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof EntityProjectile) || !(e.getEntity() instanceof Player)) { return; }
        EntityProjectile projectile = (EntityProjectile)e.getDamager();
        if (!(projectile.shootingEntity instanceof Player) || projectile.getNetworkId() != 80) { return; }
        Player p = (Player)projectile.shootingEntity;
        if (!active(p) || plugin.inGame.get(p) != plugin.inGame.get((Player)e.getEntity())) { return; }
        if (random.nextInt(100) < level(p, "arrowrecovery") * 8) {
            for (Item item : p.getInventory().addItem(Item.get(262, 0, 1))) { p.level.dropItem(p, item); }
        }
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void launch(ProjectileLaunchEvent e) {
        EntityProjectile projectile = e.getEntity();
        if (projectile.shootingEntity instanceof Player && projectile.getNetworkId() == 80) {
            Player p = (Player)projectile.shootingEntity;
            if (active(p) && random.nextInt(100) < level(p, "blazingarrows") * 3) { projectile.setOnFire(10); }
        }
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void place(BlockPlaceEvent e) {
        GoldSkyWars.Game g = plugin.inGame.get(e.getPlayer());
        if (g != null) { g.placedOres.add(IslandScan.pack(e.getBlock().getFloorX(), e.getBlock().getFloorY(), e.getBlock().getFloorZ())); }
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void mine(BlockBreakEvent e) {
        Player p = e.getPlayer(); GoldSkyWars.Game g = plugin.inGame.get(p);
        if (!active(p) || g == null) { return; }
        int id = e.getBlock().getId();
        long pos = IslandScan.pack(e.getBlock().getFloorX(), e.getBlock().getFloorY(), e.getBlock().getFloorZ());
        if (g.placedOres.contains(pos) || !(id == 56 || id == 15 || id == 16 || id == 14 || id == 21 || id == 73 || id == 74)) { return; }
        if (random.nextInt(100) < level(p, "miningexpertise") * 12) {
            Item[] original = e.getDrops(), doubled = new Item[original.length * 2];
            for (int i = 0; i < original.length; i++) { doubled[i] = original[i]; doubled[i + original.length] = original[i].clone(); }
            e.setDrops(doubled);
        }
    }
}

