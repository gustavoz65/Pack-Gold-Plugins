package br.goldsuite.core;

import br.goldsuite.api.Destination;
import br.goldsuite.api.EffectService.Shape;
import br.goldsuite.api.TravelService;
import cn.nukkit.Player;
import cn.nukkit.Server;
import cn.nukkit.event.player.PlayerTeleportEvent.TeleportCause;
import cn.nukkit.level.Level;
import cn.nukkit.level.Location;
import cn.nukkit.level.Position;
import cn.nukkit.level.sound.ClickSound;
import cn.nukkit.level.sound.EndermanTeleportSound;
import cn.nukkit.level.sound.ExplodeSound;
import cn.nukkit.level.sound.PopSound;
import cn.nukkit.level.sound.Sound;
import cn.nukkit.plugin.Plugin;
import cn.nukkit.potion.Effect;
import cn.nukkit.scheduler.PluginTask;
import cn.nukkit.scheduler.TaskHandler;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Uma viagem por jogador; a tarefa de 1 tick so existe enquanto ha viagens em andamento. */
final class TravelEngine implements TravelService {
    private final Plugin plugin;
    private final EffectEngine effects;
    private final Map<Player, Trip> trips = new IdentityHashMap<>();
    private TaskHandler task;

    TravelEngine(Plugin plugin, EffectEngine effects) { this.plugin = plugin; this.effects = effects; }

    @Override public CompletableFuture<Boolean> travel(Player player, Destination destination, Transition transition) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        try {
            if (!player.isOnline()) { throw new IllegalStateException("Jogador offline."); }
            if (trips.containsKey(player)) { throw new IllegalStateException("Aguarde a viagem atual terminar."); }
            // Valida antes da animacao para nao mostrar efeitos de uma viagem impossivel.
            resolve(destination);
        } catch (RuntimeException error) { result.completeExceptionally(error); return result; }
        Trip trip = new Trip(player, destination, transition, result);
        trips.put(player, trip);
        if (!step(trip)) { trips.remove(player); }
        else if (task == null) { task = plugin.getServer().getScheduler().scheduleRepeatingTask(new PluginTask<Plugin>(plugin) { @Override public void onRun(int tick) { TravelEngine.this.tick(); } }, 1); }
        return result;
    }

    @Override public boolean isTraveling(Player player) { return trips.containsKey(player); }

    @Override public void cancel(Player player) {
        Trip trip = trips.remove(player);
        if (trip != null) { trip.finish(false); }
    }

    void close() {
        for (Trip trip : new ArrayList<>(trips.values())) { trip.finish(false); }
        trips.clear();
        if (task != null) { task.cancel(); task = null; }
    }

    private void tick() {
        for (Trip trip : new ArrayList<>(trips.values())) {
            if (!step(trip)) { trips.remove(trip.player); }
        }
        if (trips.isEmpty() && task != null) { task.cancel(); task = null; }
    }

    /** Avanca um tick; false quando a viagem terminou (com sucesso, cancelada ou com erro). */
    private boolean step(Trip t) {
        Player p = t.player;
        if (!p.isOnline() || !p.isAlive() || (!t.arrived && p.level != t.originLevel)) { t.finish(false); return false; }
        int age = t.age++;
        try {
            switch (t.transition) {
                case INSTANTANEA:
                    return arrive(t, new PopSound(p));
                case PORTAL:
                    if (age == 0) { p.addEffect(Effect.getEffect(Effect.NAUSEA).setDuration(60).setVisible(false)); effects.follow(p, Shape.PORTAL, 30); sound(p, new EndermanTeleportSound(p)); }
                    if (age < 30) { return true; }
                    p.removeEffect(Effect.NAUSEA);
                    if (!arrive(t, null)) { return false; }
                    effects.play(p, Shape.PORTAL, 10); sound(p, new EndermanTeleportSound(p));
                    return false;
                case ESPIRAL:
                    if (age == 0) { effects.follow(p, Shape.ESPIRAL, 30); }
                    if (age < 30) { return true; }
                    if (!arrive(t, new PopSound(p))) { return false; }
                    effects.play(p, Shape.ANEL, 20);
                    return false;
                case ESCURECER:
                    if (age == 0) { p.addEffect(Effect.getEffect(Effect.BLINDNESS).setDuration(40).setVisible(false)); }
                    if (age == 10 && !teleport(t)) { return false; }
                    if (age < 20) { return true; }
                    p.removeEffect(Effect.BLINDNESS);
                    t.finish(true);
                    return false;
                case CONTAGEM:
                    if (p.distanceSquared(t.origin) > 1.0) { p.sendTip("Teleporte cancelado: voce se moveu."); t.finish(false); return false; }
                    if (age % 20 == 0 && age < 60) { p.sendTip("Teleportando em " + (3 - age / 20) + "..."); sound(p, new ClickSound(p)); }
                    if (age < 60) { return true; }
                    return arrive(t, new EndermanTeleportSound(p));
                case EXPLOSAO:
                    if (age == 0) { effects.play(p, Shape.EXPLOSAO, 12); sound(p, new ExplodeSound(p)); }
                    if (age < 2) { return true; }
                    return arrive(t, new PopSound(p));
                default:
                    t.finish(false);
                    return false;
            }
        } catch (RuntimeException error) {
            t.future.completeExceptionally(error);
            t.done = true;
            return false;
        }
    }

    /** Teleporta e conclui; o som e tocado no destino. Sempre retorna false (fim da viagem). */
    private boolean arrive(Trip t, Sound sound) {
        if (!teleport(t)) { return false; }
        if (sound != null) { sound.setComponents(t.player.x, t.player.y, t.player.z); sound(t.player, sound); }
        t.finish(true);
        return false;
    }

    private boolean teleport(Trip t) {
        if (t.arrived) { return true; }
        if (!t.player.teleport(resolve(t.destination), TeleportCause.PLUGIN)) { t.finish(false); return false; }
        t.arrived = true;
        return true;
    }

    private Location resolve(Destination destination) {
        if (destination.type == Destination.Type.SERVER) {
            throw new IllegalStateException("Transferencia entre servidores exige o proxy da GoldSuite; a 0.15.10 nao tem TransferPacket.");
        }
        Server server = plugin.getServer();
        Level level = server.getLevelByName(destination.target);
        if (level == null && server.isLevelGenerated(destination.target)) {
            server.loadLevel(destination.target);
            level = server.getLevelByName(destination.target);
        }
        if (level == null) { throw new IllegalArgumentException("Mundo inexistente: " + destination.target); }
        if (destination.type == Destination.Type.WORLD) {
            Position spawn = level.getSafeSpawn();
            return new Location(spawn.x, spawn.y, spawn.z, 0, 0, level);
        }
        return new Location(destination.x, destination.y, destination.z, destination.yaw, destination.pitch, level);
    }

    /** Som so para quem esta perto (o mesmo raio das particulas). */
    private static void sound(Player around, Sound sound) {
        java.util.List<Player> near = new ArrayList<>();
        for (Player other : around.level.getPlayers().values()) { if (other.distanceSquared(around) <= EffectEngine.VIEW_RADIUS_SQUARED) { near.add(other); } }
        around.level.addSound(sound, near);
    }

    private final class Trip {
        final Player player;
        final Destination destination;
        final Transition transition;
        final CompletableFuture<Boolean> future;
        final Level originLevel;
        final Position origin;
        int age;
        boolean arrived, done;
        Trip(Player player, Destination destination, Transition transition, CompletableFuture<Boolean> future) {
            this.player = player; this.destination = destination; this.transition = transition; this.future = future;
            this.originLevel = player.level; this.origin = player.getPosition();
        }
        void finish(boolean success) {
            if (done) { return; }
            done = true;
            effects.stop(player);
            if (!success) { player.removeEffect(Effect.NAUSEA); player.removeEffect(Effect.BLINDNESS); }
            future.complete(success);
        }
    }
}
