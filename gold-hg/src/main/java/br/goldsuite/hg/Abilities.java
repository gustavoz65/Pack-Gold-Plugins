package br.goldsuite.hg;

import br.goldsuite.api.EffectService;
import cn.nukkit.Player;
import cn.nukkit.block.Block;
import cn.nukkit.entity.Entity;
import cn.nukkit.entity.projectile.EntityProjectile;
import cn.nukkit.entity.projectile.EntitySnowball;
import cn.nukkit.entity.weather.EntityLightning;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.block.BlockBreakEvent;
import cn.nukkit.event.block.BlockPlaceEvent;
import cn.nukkit.event.entity.EntityDamageByEntityEvent;
import cn.nukkit.event.entity.EntityDamageEvent;
import cn.nukkit.event.entity.ProjectileHitEvent;
import cn.nukkit.event.player.*;
import cn.nukkit.item.Item;
import cn.nukkit.item.enchantment.Enchantment;
import cn.nukkit.level.Explosion;
import cn.nukkit.level.Level;
import cn.nukkit.level.Location;
import cn.nukkit.level.Position;
import cn.nukkit.level.generator.object.tree.ObjectTree;
import cn.nukkit.level.particle.CriticalParticle;
import cn.nukkit.level.sound.ExplodeSound;
import cn.nukkit.level.sound.PopSound;
import cn.nukkit.math.NukkitRandom;
import cn.nukkit.math.Vector3;
import cn.nukkit.nbt.tag.CompoundTag;
import cn.nukkit.nbt.tag.DoubleTag;
import cn.nukkit.nbt.tag.FloatTag;
import cn.nukkit.nbt.tag.ListTag;
import cn.nukkit.potion.Effect;
import cn.nukkit.scheduler.PluginTask;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Eventos da partida, habilidades dos kits e sopa. Todo handler sai cedo quando o jogador nao esta numa
 * partida (consulta O(1) em mapa de identidade); nada aqui acessa disco.
 */
final class Abilities implements Listener {
    private static final int SOUP = 282, BOWL = 281;
    private static final Set<Integer> SWORDS = new HashSet<>(Arrays.asList(268, 272, 267, 276, 283));
    private static final Set<Integer> AXES = new HashSet<>(Arrays.asList(271, 275, 258, 286, 279));
    private static final Set<Integer> LOGS = new HashSet<>(Arrays.asList(17, 162));
    private static final Set<Integer> VEINS = new HashSet<>(Arrays.asList(14, 15, 16));

    private final GoldHG plugin;
    private final Map<Player, Long> cooldown = new IdentityHashMap<>();
    private final Map<Player, Long> fallSafeUntil = new IdentityHashMap<>();
    private final Set<Player> kangarooAir = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Player> meteor = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<Player, Player> lastHit = new IdentityHashMap<>();
    private final Map<Player, Long> lastHitAt = new IdentityHashMap<>();
    private final Map<Player, int[]> blink = new IdentityHashMap<>();
    private final Map<Player, double[]> spy = new IdentityHashMap<>();
    private final Map<Player, Long> phantomUntil = new IdentityHashMap<>();
    private final Map<Player, Long> invulnerableUntil = new IdentityHashMap<>();
    private final Map<Player, Integer> kills = new IdentityHashMap<>();
    private final Map<Player, Integer> lastSoupTick = new IdentityHashMap<>();
    private final Map<String, Player> mines = new HashMap<>();
    private final Map<Player, Duel> duels = new IdentityHashMap<>();

    Abilities(GoldHG plugin) { this.plugin = plugin; }

    void clear() {
        for (Duel duel : new ArrayList<>(duels.values())) { duel.finish(null); }
        cooldown.clear(); fallSafeUntil.clear(); kangarooAir.clear(); meteor.clear(); lastHit.clear(); lastHitAt.clear(); blink.clear();
        spy.clear(); phantomUntil.clear(); invulnerableUntil.clear(); kills.clear(); lastSoupTick.clear(); mines.clear();
    }

    void forget(Player p) {
        Duel duel = duels.get(p);
        if (duel != null) { duel.finish(duel.other(p)); }
        cooldown.remove(p); fallSafeUntil.remove(p); kangarooAir.remove(p); meteor.remove(p); lastHit.remove(p); lastHitAt.remove(p); blink.remove(p);
        spy.remove(p); phantomUntil.remove(p); invulnerableUntil.remove(p); kills.remove(p); lastSoupTick.remove(p);
        mines.values().removeIf(owner -> owner == p);
        lastHit.values().removeIf(target -> target == p);
    }

    void onStart(Player p, Kit kit) {
        if (kit == Kit.TANQUE) { p.addEffect(Effect.getEffect(Effect.DAMAGE_RESISTANCE).setDuration(20 * 60 * 30).setVisible(false)); }
        if (kit == Kit.BLINK) { blink.put(p, new int[] {3, 0}); }
        if (kit == Kit.ESPECIALISTA) { p.setExperience(0, 3); }
    }

    /** Chamado pela tarefa de 1 s para cada jogador vivo. */
    void everySecond(Player p, Kit kit) {
        long now = System.currentTimeMillis();
        switch (kit) {
            case BLINK: {
                int[] charges = blink.get(p);
                if (charges != null && charges[0] < 3 && --charges[1] <= 0) { charges[0] = 3; p.sendPopup("§aBlink recarregado"); }
                break;
            }
            case FANTASMA: {
                Long until = phantomUntil.get(p);
                if (until != null && now >= until) { phantomUntil.remove(p); p.setAllowFlight(false); fallSafeUntil.put(p, now + 4000); p.sendPopup("§cVoo acabou"); }
                break;
            }
            case ESPIAO: {
                double[] last = spy.computeIfAbsent(p, k -> new double[] {p.x, p.y, p.z, 0});
                boolean still = p.isSneaking() && Math.abs(p.x - last[0]) < 0.05 && Math.abs(p.z - last[2]) < 0.05;
                last[0] = p.x; last[1] = p.y; last[2] = p.z;
                if (still) { if (++last[3] >= 3) { p.addEffect(Effect.getEffect(Effect.INVISIBILITY).setDuration(40).setVisible(false)); } }
                else { if (last[3] >= 3) { p.removeEffect(Effect.INVISIBILITY); } last[3] = 0; }
                break;
            }
            default:
        }
        Duel duel = duels.get(p);
        if (duel != null && duel.first == p && now - duel.started > 120_000) { duel.finish(null); }
    }

    // ---------- sopa ----------

    private boolean soupAllowed(Player p) {
        Game game = plugin.inGame.get(p);
        if (game != null) { return !game.waiting(); }
        return plugin.data.isLoaded() && plugin.data.read(s -> s.soupWorlds.contains(p.level.getFolderName()));
    }

    /** Cura na hora. Limite de 1 sopa a cada 3 ticks: macro nao ganha de quem joga no touch. */
    private boolean drink(Player p, int slot) {
        int tick = plugin.getServer().getTick();
        Integer last = lastSoupTick.get(p);
        if (last != null && tick - last < 3) { return false; }
        if (p.getHealth() >= p.getMaxHealth()) { p.sendPopup("§7Vida cheia"); return false; }
        HgState.Rules rules = plugin.rules();
        lastSoupTick.put(p, tick);
        p.setHealth(Math.min(p.getMaxHealth(), p.getHealth() + rules.soupHeal));
        p.getInventory().setItem(slot, rules.soupBowl ? Item.get(BOWL, 0, 1) : Item.get(0));
        p.level.addSound(new PopSound(p), p);
        return true;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void held(PlayerItemHeldEvent event) {
        Player p = event.getPlayer();
        if (event.getItem().getId() != SOUP || !soupAllowed(p)) { return; }
        String name = HgState.player(p.getName());
        if (plugin.data.read(s -> s.soupOnSelect.contains(name))) { drink(p, event.getInventorySlot()); }
    }

    // ---------- toque com item ----------

    @EventHandler(priority = EventPriority.LOW)
    public void interact(PlayerInteractEvent event) {
        Player p = event.getPlayer();
        Item hand = p.getInventory().getItemInHand();
        if (event.getAction() == PlayerInteractEvent.PHYSICAL) { mine(p, event.getBlock()); return; }
        // Sopa em qualquer toque: no ar, num bloco ou segurando.
        if (hand.getId() == SOUP && soupAllowed(p)) { drink(p, p.getInventory().getHeldItemIndex()); event.setCancelled(); return; }
        Game game = plugin.inGame.get(p);
        if (game == null) { return; }
        if (game.waiting()) {
            event.setCancelled();
            if (hand.getId() == GoldHG.SELECTOR) { plugin.openKits(p, 0); }
            return;
        }
        Kit kit = plugin.kit(p);
        long now = System.currentTimeMillis();
        boolean block = event.getAction() == PlayerInteractEvent.RIGHT_CLICK_BLOCK || event.getAction() == PlayerInteractEvent.LEFT_CLICK_BLOCK;
        switch (kit) {
            case CANGURU:
                if (hand.getId() != Kit.RABBIT_FOOT || !ready(p, 350)) { return; }
                if (p.isOnGround()) { kangarooAir.remove(p); launch(p, 1.1, 0.95); }
                else if (kangarooAir.add(p)) { launch(p, 0.8, 0.7); }
                fallSafeUntil.put(p, now + 6000);
                event.setCancelled();
                return;
            case GRAPPLER: {
                if (hand.getId() != Kit.ROD || !ready(p, 1500)) { return; }
                Block target = targetBlock(p, 30);
                if (target == null) { p.sendPopup("§7Mire num bloco"); return; }
                Vector3 pull = new Vector3(target.x + 0.5 - p.x, target.y + 1 - p.y, target.z + 0.5 - p.z);
                double length = pull.length();
                p.setMotion(new Vector3(pull.x / length * 1.7, Math.min(1.6, pull.y / length * 1.7 + 0.45), pull.z / length * 1.7));
                fallSafeUntil.put(p, now + 3000);
                event.setCancelled();
                return;
            }
            case PESCADOR: {
                if (hand.getId() != Kit.ROD) { return; }
                Player target = targetPlayer(p, game, 20);
                if (target == null || plugin.kit(target) == Kit.NEO || !cooldownOk(p, 10_000)) { return; }
                Vector3 dir = p.getDirectionVector();
                target.teleport(new Location(p.x + dir.x * 1.5, p.y, p.z + dir.z * 1.5, (float) (p.yaw + 180), 0, p.level));
                target.sendMessage(GoldHG.PREFIX + "§cVoce foi fisgado por " + p.getName() + "!");
                event.setCancelled();
                return;
            }
            case FLASH: {
                if (hand.getId() != Kit.REDSTONE_TORCH || !cooldownOk(p, 15_000)) { return; }
                Block target = targetBlock(p, 40);
                if (target == null) { cooldown.remove(p); p.sendPopup("§7Mire num bloco"); return; }
                lightning(p.level, p.x, p.y, p.z);
                p.teleport(new Location(target.x + 0.5, target.y + 1, target.z + 0.5, (float) p.yaw, (float) p.pitch, p.level));
                p.getFoodData().setLevel(Math.max(0, p.getFoodData().getLevel() - 3));
                event.setCancelled();
                return;
            }
            case BLINK: {
                if (hand.getId() != Kit.REDSTONE || !ready(p, 300)) { return; }
                int[] charges = blink.get(p);
                if (charges == null || charges[0] <= 0) { p.sendPopup("§cSem cargas"); return; }
                Vector3 dir = p.getDirectionVector();
                for (double distance = 6; distance >= 1; distance -= 0.5) {
                    Vector3 to = new Vector3(p.x + dir.x * distance, p.y + Math.max(0, dir.y * distance), p.z + dir.z * distance);
                    if (free(p.level, to)) {
                        p.teleport(new Location(to.x, to.y, to.z, (float) p.yaw, (float) p.pitch, p.level));
                        if (--charges[0] == 0) { charges[1] = 15; }
                        p.sendPopup("§dBlink §f" + charges[0] + "/3");
                        break;
                    }
                }
                event.setCancelled();
                return;
            }
            case FORJADOR: {
                if (hand.getId() != Kit.COAL || !ready(p, 800)) { return; }
                double yaw = Math.toRadians(p.yaw);
                double dx = -Math.sin(yaw), dz = Math.cos(yaw);
                int built = 0;
                for (int i = 1; i <= 5; i++) {
                    Vector3 pos = new Vector3(Math.floor(p.x + dx * i), p.getFloorY() - 1, Math.floor(p.z + dz * i));
                    if (p.level.getBlock(pos).getId() == 0) { p.level.setBlock(pos, Block.get(Block.COBBLESTONE)); built++; }
                }
                if (built > 0) { hand.setCount(hand.getCount() - 1); p.getInventory().setItemInHand(hand.getCount() > 0 ? hand : Item.get(0)); }
                event.setCancelled();
                return;
            }
            case METEORO:
                if (hand.getId() != Kit.MAGMA_CREAM || !cooldownOk(p, 20_000)) { return; }
                launch(p, 1.4, 1.3);
                meteor.add(p);
                event.setCancelled();
                return;
            case THOR: {
                if (hand.getId() != Kit.WOODEN_AXE || !block || !cooldownOk(p, 8000)) { return; }
                Block target = event.getBlock();
                lightning(p.level, target.x + 0.5, target.y + 1, target.z + 0.5);
                if (target.getId() != Block.BEDROCK) { p.level.setBlock(target, Block.get(Block.AIR)); }
                Vector3 above = new Vector3(target.x, target.y + 1, target.z);
                if (p.level.getBlock(above).getId() == 0) { p.level.setBlock(above, Block.get(Block.FIRE)); }
                for (Player other : game.players.keySet()) {
                    if (other != p && other.level == p.level && other.distanceSquared(target) <= 9) {
                        other.attack(new EntityDamageByEntityEvent(p, other, EntityDamageEvent.CAUSE_LIGHTNING, 4f, 0.2f));
                        other.setOnFire(4);
                    }
                }
                event.setCancelled();
                return;
            }
            case FANTASMA:
                if (hand.getId() != Kit.FEATHER || !cooldownOk(p, 60_000)) { return; }
                p.setAllowFlight(true);
                phantomUntil.put(p, now + 5000);
                p.sendPopup("§bVoando por 5 s!");
                event.setCancelled();
                return;
            default:
        }
    }

    // ---------- dano ----------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void damage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player)) { return; }
        Player victim = (Player) event.getEntity();
        Player attacker = null;
        if (event instanceof EntityDamageByEntityEvent) {
            Entity damager = ((EntityDamageByEntityEvent) event).getDamager();
            if (damager instanceof Player) { attacker = (Player) damager; }
        }
        Game gv = plugin.inGame.get(victim);
        Game ga = attacker == null ? null : plugin.inGame.get(attacker);
        if (gv == null) { if (ga != null) { event.setCancelled(); } return; }
        if (attacker != null && ga != gv) { event.setCancelled(); return; }
        int cause = event.getCause();
        if (gv.protectedPhase() && cause != EntityDamageEvent.CAUSE_VOID) { event.setCancelled(); return; }
        long now = System.currentTimeMillis();
        Long safe = invulnerableUntil.get(victim);
        if (safe != null && now < safe && cause != EntityDamageEvent.CAUSE_VOID) { event.setCancelled(); return; }
        Duel duel = duels.get(victim);
        if (duel != null && attacker != null && duel.other(victim) != attacker) { event.setCancelled(); return; }

        float damage = event.getDamage();
        Kit kv = plugin.kit(victim);
        boolean melee = attacker != null && cause == EntityDamageEvent.CAUSE_ENTITY_ATTACK;
        switch (kv) {
            case MAGMA: if (cause == EntityDamageEvent.CAUSE_FIRE || cause == EntityDamageEvent.CAUSE_FIRE_TICK || cause == EntityDamageEvent.CAUSE_LAVA) { event.setCancelled(); victim.setOnFire(0); return; } break;
            case THOR: if (cause == EntityDamageEvent.CAUSE_LIGHTNING || cause == EntityDamageEvent.CAUSE_FIRE || cause == EntityDamageEvent.CAUSE_FIRE_TICK) { event.setCancelled(); return; } break;
            case TANQUE: if (cause == EntityDamageEvent.CAUSE_BLOCK_EXPLOSION || cause == EntityDamageEvent.CAUSE_ENTITY_EXPLOSION) { event.setCancelled(); return; } break;
            case NEO: if (cause == EntityDamageEvent.CAUSE_PROJECTILE) { event.setCancelled(); return; } break;
            case TARTARUGA: if (victim.isSneaking()) { damage = Math.min(damage, 1f); } break;
            case BOXER: damage = Math.max(0.5f, damage - 1f); break;
            case AQUILES:
                if (melee) {
                    int held = attacker.getInventory().getItemInHand().getId();
                    if (SWORDS.contains(held)) { damage /= 3f; } else if (held == 0 || AXES.contains(held)) { damage *= 1.5f; }
                }
                break;
            default:
        }
        if (cause == EntityDamageEvent.CAUSE_FALL) {
            Long fallSafe = fallSafeUntil.get(victim);
            if (meteor.remove(victim)) { impact(victim, gv); event.setCancelled(); return; }
            if (fallSafe != null && now < fallSafe) { event.setCancelled(); return; }
            if (kv == Kit.STOMPER) { damage = stomp(victim, gv, damage); }
        }
        if (melee) {
            Kit ka = plugin.kit(attacker);
            Item hand = attacker.getInventory().getItemInHand();
            if (hand.getId() == SOUP && soupAllowed(attacker)) { drink(attacker, attacker.getInventory().getHeldItemIndex()); }
            ThreadLocalRandom random = ThreadLocalRandom.current();
            switch (ka) {
                case BOXER: if (hand.getId() == 0) { damage = Math.max(damage, 6f); } break;
                case CRITICO: if (random.nextInt(5) == 0) { damage *= 1.5f; victim.level.addParticle(new CriticalParticle(victim.add(0, 1.2, 0)), victim.level.getPlayers().values()); } break;
                case TARTARUGA: if (attacker.isSneaking()) { damage = Math.min(damage, 2f); } break;
                case VIPER: if (random.nextInt(3) == 0) { victim.addEffect(Effect.getEffect(Effect.POISON).setDuration(80)); } break;
                case SNAIL: if (random.nextInt(3) == 0) { victim.addEffect(Effect.getEffect(Effect.SLOWNESS).setDuration(80)); } break;
                case MAGMA: if (random.nextInt(3) == 0) { victim.setOnFire(4); } break;
                case CEIFADOR: if (hand.getId() == Kit.WOODEN_HOE) { victim.addEffect(Effect.getEffect(Effect.WITHER).setDuration(80)); } break;
                case MONGE:
                    if (hand.getId() == Kit.STICK && cooldownOk(attacker, 15_000)) {
                        int heldSlot = victim.getInventory().getHeldItemIndex();
                        int other = random.nextInt(36);
                        Item a = victim.getInventory().getItem(heldSlot), b = victim.getInventory().getItem(other);
                        victim.getInventory().setItem(heldSlot, b); victim.getInventory().setItem(other, a);
                        victim.sendMessage(GoldHG.PREFIX + "§cUm monge baguncou seu inventario!");
                    }
                    break;
                case HULK:
                    if (hand.getId() == 0 && kv != Kit.NEO && cooldownOk(attacker, 12_000)) {
                        Vector3 dir = attacker.getDirectionVector();
                        victim.setMotion(new Vector3(dir.x * 2.2, 1.4, dir.z * 2.2));
                        victim.sendMessage(GoldHG.PREFIX + "§cO Hulk te arremessou!");
                    }
                    break;
                case GLADIADOR:
                    if (hand.getId() == Kit.IRON_BLOCK && kv != Kit.NEO && !duels.containsKey(attacker) && !duels.containsKey(victim) && cooldownOk(attacker, 30_000)) {
                        new Duel(attacker, victim).start();
                        event.setCancelled();
                        return;
                    }
                    break;
                default:
            }
            lastHit.put(attacker, victim); lastHitAt.put(attacker, now);
            lastHit.put(victim, attacker); lastHitAt.put(victim, now);
            if ((kv == Kit.ANCORA || ka == Kit.ANCORA) && event instanceof EntityDamageByEntityEvent) { ((EntityDamageByEntityEvent) event).setKnockBack(0f); }
        } else if (kv == Kit.ANCORA && event instanceof EntityDamageByEntityEvent) {
            ((EntityDamageByEntityEvent) event).setKnockBack(0f);
        }
        if (damage != event.getDamage()) { event.setDamage(damage); }
    }

    /** Repassa a queda para quem esta embaixo; o Stomper fica com no maximo 2 coracoes. */
    private float stomp(Player stomper, Game game, float damage) {
        boolean hit = false;
        for (Player other : game.players.keySet()) {
            if (other == stomper || other.level != stomper.level) { continue; }
            double dx = other.x - stomper.x, dz = other.z - stomper.z, dy = stomper.y - other.y;
            if (dx * dx + dz * dz <= 9 && dy >= -1 && dy <= 3) {
                float hurt = other.isSneaking() ? Math.min(damage, 2f) : damage;
                other.attack(new EntityDamageByEntityEvent(stomper, other, EntityDamageEvent.CAUSE_ENTITY_ATTACK, hurt, 0.4f));
                other.sendMessage(GoldHG.PREFIX + "§cVoce foi esmagado por " + stomper.getName() + "!");
                hit = true;
            }
        }
        if (hit) { stomper.level.addSound(new ExplodeSound(stomper), stomper.level.getPlayers().values()); }
        return Math.min(damage, 4f);
    }

    /** Queda do Meteor: dano, fogo e cratera pequena; o proprio Meteor nao se fere. */
    private void impact(Player p, Game game) {
        EffectService effects = plugin.getServer().getServiceManager().load(EffectService.class);
        if (effects != null) { effects.play(p, EffectService.Shape.EXPLOSAO, 12); }
        p.level.addSound(new ExplodeSound(p), p.level.getPlayers().values());
        for (Player other : game.players.keySet()) {
            if (other != p && other.level == p.level && other.distanceSquared(p) <= 16) {
                other.attack(new EntityDamageByEntityEvent(p, other, EntityDamageEvent.CAUSE_ENTITY_EXPLOSION, 6f, 0.8f));
                other.setOnFire(5);
            }
        }
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                Vector3 pos = new Vector3(p.getFloorX() + dx, p.getFloorY() - 1, p.getFloorZ() + dz);
                int id = p.level.getBlock(pos).getId();
                if (dx * dx + dz * dz <= 4 && id != 0 && id != Block.BEDROCK && id != Block.CHEST) { p.level.setBlock(pos, Block.get(Block.AIR)); }
            }
        }
    }

    // ---------- morte, volta e conexao ----------

    @EventHandler(priority = EventPriority.HIGH)
    public void death(PlayerDeathEvent event) {
        Player dead = event.getEntity();
        Game game = plugin.inGame.get(dead);
        if (game == null || game.waiting()) { return; }
        Player killer = null;
        EntityDamageEvent cause = dead.getLastDamageCause();
        if (cause instanceof EntityDamageByEntityEvent && ((EntityDamageByEntityEvent) cause).getDamager() instanceof Player) { killer = (Player) ((EntityDamageByEntityEvent) cause).getDamager(); }
        event.setDeathMessage("");
        plugin.returning.add(dead);
        String message = killer != null ? "§f" + dead.getName() + " §7foi morto por §c" + killer.getName() : "§f" + dead.getName() + " §7morreu";
        if (killer != null && plugin.inGame.get(killer) == game) { rewardKill(killer, dead); }
        plugin.eliminate(dead, game, message);
    }

    private void rewardKill(Player killer, Player dead) {
        int count = kills.merge(killer, 1, Integer::sum);
        switch (plugin.kit(killer)) {
            case TANQUE: {
                Explosion explosion = new Explosion(new Position(dead.x, dead.y, dead.z, dead.level), 3, killer);
                explosion.explodeA(); explosion.explodeB();
                break;
            }
            case BARBARO: {
                for (Map.Entry<Integer, Item> slot : killer.getInventory().getContents().entrySet()) {
                    if (!SWORDS.contains(slot.getValue().getId())) { continue; }
                    Item sword = Item.get(slot.getValue().getId(), slot.getValue().getDamage(), 1);
                    sword.setCustomName("§rEspada do Barbaro");
                    sword.addEnchantment(Kit.enchant(Enchantment.ID_DAMAGE_ALL, Math.min(4, count)));
                    killer.getInventory().setItem(slot.getKey(), sword);
                    killer.sendPopup("§6Afiacao " + Math.min(4, count) + "!");
                    break;
                }
                break;
            }
            case ESPECIALISTA: killer.setExperience(killer.getExperience(), killer.getExperienceLevel() + 3); break;
            default:
        }
    }

    @EventHandler public void respawn(PlayerRespawnEvent event) {
        if (plugin.returning.remove(event.getPlayer())) { event.setRespawnPosition(plugin.lobby().getSafeSpawn()); }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void quit(PlayerQuitEvent event) {
        Player p = event.getPlayer();
        plugin.returning.remove(p);
        plugin.kits.remove(p);
        Game game = plugin.inGame.get(p);
        if (game == null) { forget(p); return; }
        // Quem sai no meio da partida volta sem o loot (e sem o seletor de kits).
        String name = HgState.player(p.getName());
        plugin.data.change(s -> s.pendingClear.add(name));
        if (game.waiting()) { game.players.remove(p); plugin.inGame.remove(p); forget(p); }
        else { plugin.eliminate(p, game, "§f" + p.getName() + " §7desconectou"); }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void join(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        String name = HgState.player(p.getName());
        if (!plugin.data.isLoaded()) { return; }
        boolean pending = plugin.data.read(s -> s.pendingClear.contains(name));
        if (!pending && !plugin.isArena(p.level)) { return; }
        plugin.getServer().getScheduler().scheduleDelayedTask(new PluginTask<GoldHG>(plugin) {
            @Override public void onRun(int tick) {
                if (!p.isOnline() || plugin.inGame.containsKey(p)) { return; }
                if (pending) { plugin.strip(p); plugin.data.change(s -> s.pendingClear.remove(name)); p.sendMessage(GoldHG.PREFIX + "Voce saiu no meio do HG; os itens da partida foram removidos."); }
                if (plugin.isArena(p.level)) { p.teleport(plugin.lobby().getSafeSpawn()); }
            }
        }, 20);
    }

    // ---------- movimento ----------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void move(PlayerMoveEvent event) {
        Player p = event.getPlayer();
        Game game = plugin.inGame.get(p);
        if (game == null) { return; }
        if (game.waiting()) {
            if (plugin.isArena(p.level)) {
                Location from = event.getFrom(), to = event.getTo();
                double dx = to.x - from.x, dz = to.z - from.z;
                if (dx * dx + dz * dz > 0.0001) { event.setTo(new Location(from.x, from.y, from.z, to.yaw, to.pitch, from.level)); }
            }
            return;
        }
        Kit kit = plugin.kit(p);
        if (kit == Kit.CANGURU && p.isOnGround()) { kangarooAir.remove(p); }
        else if (kit == Kit.ARANHA && p.isSneaking()) {
            double yaw = Math.toRadians(p.yaw);
            Vector3 front = new Vector3(Math.floor(p.x - Math.sin(yaw) * 0.8), p.getFloorY() + 1, Math.floor(p.z + Math.cos(yaw) * 0.8));
            if (p.level.getBlock(front).isSolid()) { p.setMotion(new Vector3(0, 0.3, 0)); }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void sneak(PlayerToggleSneakEvent event) {
        Player p = event.getPlayer();
        if (!event.isSneaking() || plugin.kit(p) != Kit.NINJA) { return; }
        Game game = plugin.inGame.get(p);
        if (game == null || !game.pvp()) { return; }
        Player target = lastHit.get(p);
        Long at = lastHitAt.get(p);
        if (target == null || at == null || System.currentTimeMillis() - at > 15_000 || plugin.inGame.get(target) != game || target.level != p.level || target.distanceSquared(p) > 40 * 40 || plugin.kit(target) == Kit.NEO) { return; }
        if (!cooldownOk(p, 10_000)) { return; }
        Vector3 dir = target.getDirectionVector();
        p.teleport(new Location(target.x - dir.x * 1.3, target.y, target.z - dir.z * 1.3, (float) target.yaw, 0, target.level));
    }

    // ---------- blocos ----------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void breakBlock(BlockBreakEvent event) {
        Player p = event.getPlayer();
        Game game = plugin.inGame.get(p);
        if (game == null) { if (plugin.isArena(p.level) && !p.hasPermission("goldhg.admin")) { event.setCancelled(); } return; }
        if (game.waiting()) { event.setCancelled(); return; }
        Block block = event.getBlock();
        Kit kit = plugin.kit(p);
        int held = p.getInventory().getItemInHand().getId();
        if (kit == Kit.LENHADOR && LOGS.contains(block.getId()) && AXES.contains(held)) {
            for (int y = 1; y <= 32; y++) {
                Vector3 pos = new Vector3(block.x, block.y + y, block.z);
                Block above = p.level.getBlock(pos);
                if (!LOGS.contains(above.getId())) { break; }
                p.level.setBlock(pos, Block.get(Block.AIR));
                p.level.dropItem(pos, Item.get(above.getId(), above.getDamage() & 3, 1));
            }
        } else if (kit == Kit.MINERADOR && VEINS.contains(block.getId())) {
            Deque<Vector3> queue = new ArrayDeque<>();
            Set<String> seen = new HashSet<>();
            queue.add(block);
            int broken = 0;
            while (!queue.isEmpty() && broken < 16) {
                Vector3 at = queue.poll();
                for (int[] d : new int[][] {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}}) {
                    Vector3 pos = new Vector3(at.x + d[0], at.y + d[1], at.z + d[2]);
                    if (!seen.add(pos.getFloorX() + ":" + pos.getFloorY() + ":" + pos.getFloorZ())) { continue; }
                    Block ore = p.level.getBlock(pos);
                    if (ore.getId() != block.getId()) { continue; }
                    for (int[] drop : ore.getDrops(p.getInventory().getItemInHand())) { p.level.dropItem(pos, Item.get(drop[0], drop[1], drop[2])); }
                    p.level.setBlock(pos, Block.get(Block.AIR));
                    queue.add(pos);
                    if (++broken >= 16) { break; }
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void place(BlockPlaceEvent event) {
        Player p = event.getPlayer();
        Game game = plugin.inGame.get(p);
        if (game == null) { if (plugin.isArena(p.level) && !p.hasPermission("goldhg.admin")) { event.setCancelled(); } return; }
        if (game.waiting()) { event.setCancelled(); return; }
        Block block = event.getBlock();
        Kit kit = plugin.kit(p);
        int id = block.getId();
        if (kit == Kit.GLADIADOR && id == Kit.IRON_BLOCK) { event.setCancelled(); return; }
        if (kit == Kit.ENDERMAGE && id == 120) { event.setCancelled(); endermage(p, game, block); return; }
        if (kit == Kit.DEMOLIDOR && (id == 70 || id == 72) && p.level.getBlock(new Vector3(block.x, block.y - 1, block.z)).getId() == Block.GRAVEL) {
            mines.put(key(p.level, block), p);
            p.sendPopup("§cMina armada");
            return;
        }
        if (kit == Kit.CULTIVADOR) {
            Level level = p.level;
            int x = block.getFloorX(), y = block.getFloorY(), z = block.getFloorZ(), meta = block.getDamage();
            plugin.getServer().getScheduler().scheduleDelayedTask(new PluginTask<GoldHG>(plugin) {
                @Override public void onRun(int tick) {
                    int now = level.getBlock(new Vector3(x, y, z)).getId();
                    if (id == Block.SAPLING && now == Block.SAPLING) { level.setBlock(new Vector3(x, y, z), Block.get(Block.AIR)); ObjectTree.growTree(level, x, y, z, new NukkitRandom(), meta & 7); }
                    else if ((id == 59 || id == 141 || id == 142) && now == id) { level.setBlock(new Vector3(x, y, z), Block.get(id, 7)); }
                }
            }, 1);
        }
    }

    /** Placa sobre cascalho do Demoman explode quando um inimigo pisa. */
    private void mine(Player p, Block plate) {
        if (plate == null) { return; }
        Player owner = mines.get(key(p.level, plate));
        if (owner == null || owner == p) { return; }
        Game game = plugin.inGame.get(p);
        if (game == null || game.waiting() || plugin.inGame.get(owner) != game) { return; }
        mines.remove(key(p.level, plate));
        Explosion explosion = new Explosion(new Position(plate.x + 0.5, plate.y, plate.z + 0.5, p.level), 2.5, owner);
        explosion.explodeA(); explosion.explodeB();
    }

    /** Puxa quem estiver acima ou abaixo do portal (torres e buracos) para o combate. */
    private void endermage(Player mage, Game game, Block portal) {
        if (!cooldownOk(mage, 10_000)) { return; }
        List<Player> pulled = new ArrayList<>();
        for (Player other : game.players.keySet()) {
            if (other == mage || other.level != mage.level || plugin.kit(other) == Kit.NEO) { continue; }
            double dx = other.x - (portal.x + 0.5), dz = other.z - (portal.z + 0.5), dy = Math.abs(other.y - portal.y);
            if (dx * dx + dz * dz <= 2.25 && dy >= 3) { pulled.add(other); }
        }
        if (pulled.isEmpty()) { mage.sendPopup("§7Ninguem acima ou abaixo do portal"); cooldown.remove(mage); return; }
        long until = System.currentTimeMillis() + 5000;
        Location target = new Location(portal.x + 0.5, portal.y + 1, portal.z + 0.5, 0, 0, mage.level);
        pulled.add(mage);
        for (Player player : pulled) {
            player.teleport(target);
            invulnerableUntil.put(player, until);
            player.sendMessage(GoldHG.PREFIX + "§5Endermage! 5 s de invencibilidade.");
        }
    }

    // ---------- projeteis ----------

    @EventHandler(priority = EventPriority.MONITOR)
    public void projectile(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof EntitySnowball)) { return; }
        EntityProjectile ball = (EntityProjectile) event.getEntity();
        if (!(ball.shootingEntity instanceof Player)) { return; }
        Player shooter = (Player) ball.shootingEntity;
        Game game = plugin.inGame.get(shooter);
        if (game == null || !game.pvp() || plugin.kit(shooter) != Kit.SWITCHER) { return; }
        Player hit = null;
        double best = 4;
        for (Player other : game.players.keySet()) {
            if (other == shooter || other.level != ball.level) { continue; }
            double d = other.add(0, 1, 0).distanceSquared(ball);
            if (d < best) { best = d; hit = other; }
        }
        if (hit == null || plugin.kit(hit) == Kit.NEO) { return; }
        Location a = shooter.getLocation(), b = hit.getLocation();
        shooter.teleport(b);
        hit.teleport(a);
    }

    // ---------- protecoes da sala ----------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void command(PlayerCommandPreprocessEvent event) {
        Game game = plugin.inGame.get(event.getPlayer());
        if (game != null && !plugin.commandAllowed(event.getMessage())) {
            event.setCancelled();
            event.getPlayer().sendMessage(GoldHG.PREFIX + "§cComando bloqueado durante o HG. Use /hg sair para sair.");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void drop(PlayerDropItemEvent event) {
        Game game = plugin.inGame.get(event.getPlayer());
        if (game != null && game.waiting()) { event.setCancelled(); }
    }

    // ---------- utilitarios ----------

    private void launch(Player p, double forward, double up) {
        Vector3 dir = p.getDirectionVector();
        p.setMotion(new Vector3(dir.x * forward, up, dir.z * forward));
        fallSafeUntil.put(p, System.currentTimeMillis() + 6000);
    }

    /** Intervalo curto anti-spam (sem mensagem). */
    private boolean ready(Player p, long millis) {
        long now = System.currentTimeMillis();
        Long last = cooldown.get(p);
        if (last != null && now - last < millis) { return false; }
        cooldown.put(p, now);
        return true;
    }

    /** Recarga longa: avisa quanto falta. */
    private boolean cooldownOk(Player p, long millis) {
        long now = System.currentTimeMillis();
        Long last = cooldown.get(p);
        if (last != null && now - last < millis) { p.sendPopup("§cRecarga: " + ((millis - (now - last)) / 1000 + 1) + " s"); return false; }
        cooldown.put(p, now);
        return true;
    }

    private static Block targetBlock(Player p, int max) {
        Vector3 dir = p.getDirectionVector();
        double x = p.x, y = p.y + p.getEyeHeight(), z = p.z;
        for (double d = 0; d < max; d += 0.5) {
            x += dir.x * 0.5; y += dir.y * 0.5; z += dir.z * 0.5;
            if (y < 0 || y > 127) { return null; }
            Block block = p.level.getBlock(new Vector3(Math.floor(x), Math.floor(y), Math.floor(z)));
            if (block.getId() != 0 && block.isSolid()) { return block; }
        }
        return null;
    }

    /** Jogador da mesma partida mais proximo na linha de mira. */
    private Player targetPlayer(Player p, Game game, double max) {
        Vector3 dir = p.getDirectionVector();
        double ex = p.x, ey = p.y + p.getEyeHeight(), ez = p.z;
        Player best = null;
        double bestT = max;
        for (Player other : game.players.keySet()) {
            if (other == p || other.level != p.level) { continue; }
            double vx = other.x - ex, vy = other.y + 1 - ey, vz = other.z - ez;
            double t = vx * dir.x + vy * dir.y + vz * dir.z;
            if (t <= 0 || t > bestT) { continue; }
            double perp = vx * vx + vy * vy + vz * vz - t * t;
            if (perp < 1.5 * 1.5) { best = other; bestT = t; }
        }
        return best;
    }

    private static boolean free(Level level, Vector3 pos) {
        Vector3 feet = new Vector3(Math.floor(pos.x), Math.floor(pos.y), Math.floor(pos.z));
        return feet.y > 0 && feet.y < 127 && !level.getBlock(feet).isSolid() && !level.getBlock(feet.add(0, 1, 0)).isSolid();
    }

    private static void lightning(Level level, double x, double y, double z) {
        CompoundTag nbt = new CompoundTag()
                .putList(new ListTag<DoubleTag>("Pos").add(new DoubleTag("", x)).add(new DoubleTag("", y)).add(new DoubleTag("", z)))
                .putList(new ListTag<DoubleTag>("Motion").add(new DoubleTag("", 0)).add(new DoubleTag("", 0)).add(new DoubleTag("", 0)))
                .putList(new ListTag<FloatTag>("Rotation").add(new FloatTag("", 0)).add(new FloatTag("", 0)));
        new EntityLightning(level.getChunk((int) Math.floor(x) >> 4, (int) Math.floor(z) >> 4), nbt).spawnToAll();
    }

    private static String key(Level level, Vector3 pos) { return level.getFolderName() + ":" + pos.getFloorX() + ":" + pos.getFloorY() + ":" + pos.getFloorZ(); }

    /** Duelo do Gladiator numa caixa de vidro no alto; o vidro some no fim e o vencedor volta. */
    private final class Duel {
        final Player first, second;
        final Location firstFrom, secondFrom;
        final List<Vector3> glass = new ArrayList<>();
        final long started = System.currentTimeMillis();

        Duel(Player first, Player second) {
            this.first = first; this.second = second;
            this.firstFrom = first.getLocation(); this.secondFrom = second.getLocation();
        }

        Player other(Player p) { return p == first ? second : first; }

        void start() {
            Level level = first.level;
            int cx = first.getFloorX(), cz = first.getFloorZ(), y0 = 110;
            for (int dx = -5; dx <= 5; dx++) {
                for (int dz = -5; dz <= 5; dz++) {
                    for (int dy = 0; dy <= 6; dy++) {
                        boolean shell = dy == 0 || dy == 6 || Math.abs(dx) == 5 || Math.abs(dz) == 5;
                        Vector3 pos = new Vector3(cx + dx, y0 + dy, cz + dz);
                        if (shell && level.getBlock(pos).getId() == 0) { level.setBlock(pos, Block.get(Block.GLASS)); glass.add(pos); }
                    }
                }
            }
            duels.put(first, this); duels.put(second, this);
            first.teleport(new Location(cx + 3.5, y0 + 1, cz + 0.5, 90, 0, level));
            second.teleport(new Location(cx - 2.5, y0 + 1, cz + 0.5, 270, 0, level));
            first.sendMessage(GoldHG.PREFIX + "§6Duelo contra " + second.getName() + "! 2 minutos.");
            second.sendMessage(GoldHG.PREFIX + "§6" + first.getName() + " te desafiou para um duelo! 2 minutos.");
        }

        /** Desmonta a arena; o vencedor (ou os dois, sem vencedor) voltam ao ponto de origem. */
        void finish(Player winner) {
            duels.remove(first); duels.remove(second);
            Level level = firstFrom.level;
            for (Vector3 pos : glass) { if (level.getBlock(pos).getId() == Block.GLASS) { level.setBlock(pos, Block.get(Block.AIR)); } }
            for (Player p : new Player[] {first, second}) {
                if ((winner == null || winner == p) && p.isOnline() && plugin.inGame.containsKey(p)) { p.teleport(p == first ? firstFrom : secondFrom); fallSafeUntil.put(p, System.currentTimeMillis() + 3000); }
            }
        }
    }
}
