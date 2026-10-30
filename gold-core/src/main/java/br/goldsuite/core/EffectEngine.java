package br.goldsuite.core;

import br.goldsuite.api.EffectService;
import cn.nukkit.Player;
import cn.nukkit.Server;
import cn.nukkit.level.Level;
import cn.nukkit.level.Position;
import cn.nukkit.level.particle.Particle;
import cn.nukkit.network.protocol.DataPacket;
import cn.nukkit.network.protocol.LevelEventPacket;
import cn.nukkit.plugin.Plugin;
import cn.nukkit.scheduler.PluginTask;
import cn.nukkit.scheduler.TaskHandler;
import java.util.ArrayList;
import java.util.List;

/**
 * Particulas com orcamento fixo. Uma unica tarefa roda apenas enquanto houver efeitos ativos;
 * cada efeito vira um lote comprimido por tick enviado so a quem esta perto.
 */
final class EffectEngine implements EffectService {
    static final int MAX_EFFECTS = 48;
    static final int MAX_TICKS = 200;
    /** Particulas por tick somando todos os efeitos; o excedente espera o proximo tick. */
    static final int TICK_BUDGET = 320;
    static final double VIEW_RADIUS_SQUARED = 24 * 24;
    private static final int STEPS = 32;
    private static final double[] SIN = new double[STEPS], COS = new double[STEPS];
    static {
        for (int i = 0; i < STEPS; i++) { SIN[i] = Math.sin(2 * Math.PI * i / STEPS); COS[i] = Math.cos(2 * Math.PI * i / STEPS); }
    }

    private final Plugin plugin;
    private final List<Active> active = new ArrayList<>();
    private TaskHandler task;
    private int start;

    EffectEngine(Plugin plugin) { this.plugin = plugin; }

    @Override public boolean play(Position center, Shape shape, int ticks) {
        if (center.level == null) { throw new IllegalArgumentException("Posicao sem mundo."); }
        return add(new Active(null, center.level, center.x, center.y, center.z, shape, ticks));
    }

    @Override public boolean follow(Player player, Shape shape, int ticks) {
        if (!player.isOnline()) { return false; }
        return add(new Active(player, player.level, player.x, player.y, player.z, shape, ticks));
    }

    private boolean add(Active effect) {
        if (effect.ticks < 1 || effect.ticks > MAX_TICKS) { throw new IllegalArgumentException("Duracao entre 1 e " + MAX_TICKS + " ticks."); }
        if (active.size() >= MAX_EFFECTS) { return false; }
        active.add(effect);
        if (task == null) { task = plugin.getServer().getScheduler().scheduleRepeatingTask(new PluginTask<Plugin>(plugin) { @Override public void onRun(int tick) { EffectEngine.this.tick(); } }, 1); }
        return true;
    }

    /** Encerra os efeitos que seguem o jogador (saida, morte ou troca de mundo). */
    void stop(Player player) { active.removeIf(effect -> effect.player == player); }

    void close() { active.clear(); if (task != null) { task.cancel(); task = null; } }

    private void tick() {
        Server server = plugin.getServer();
        float tps = server.getTicksPerSecond();
        // Com o servidor sobrecarregado, metade da densidade; abaixo de 12 TPS so o essencial.
        int density = tps >= 18 ? 2 : tps >= 12 ? 1 : 0;
        int budget = TICK_BUDGET;
        int size = active.size();
        for (int n = 0; n < size && budget > 0; n++) {
            Active effect = active.get((start + n) % size);
            if (!effect.update()) { effect.age = effect.ticks; continue; }
            if (effect.age % 5 == 0 || effect.viewers == null) { effect.viewers = viewers(effect); }
            List<DataPacket> packets = new ArrayList<>(16);
            emit(effect, density, packets);
            if (!packets.isEmpty() && effect.viewers.length > 0) {
                budget -= packets.size();
                server.batchPackets(effect.viewers, packets.toArray(new DataPacket[0]), false);
            }
            effect.age++;
        }
        start = size == 0 ? 0 : (start + 1) % size;
        active.removeIf(effect -> effect.age >= effect.ticks);
        if (active.isEmpty() && task != null) { task.cancel(); task = null; }
    }

    private static Player[] viewers(Active effect) {
        List<Player> result = new ArrayList<>();
        for (Player player : effect.level.getPlayers().values()) {
            if (!player.spawned) { continue; }
            double dx = player.x - effect.x, dy = player.y - effect.y, dz = player.z - effect.z;
            if (dx * dx + dy * dy + dz * dz <= VIEW_RADIUS_SQUARED) { result.add(player); }
        }
        return result.toArray(new Player[0]);
    }

    /** Gera as particulas do tick atual; density 0 = minimo, 2 = completo. */
    static void emit(Active e, int density, List<DataPacket> out) {
        int age = e.age, step = age % STEPS;
        double progress = (double) age / e.ticks;
        switch (e.shape) {
            case ANEL: {
                if (age % 2 != 0) { return; }
                double y = e.y + 0.1 + progress * 2.0;
                int points = density == 2 ? 12 : 6;
                for (int i = 0; i < points; i++) { int a = (i * STEPS / points + step) % STEPS; out.add(particle(Particle.TYPE_VILLAGER_HAPPY, e.x + COS[a] * 0.9, y, e.z + SIN[a] * 0.9)); }
                return;
            }
            case ESPIRAL: {
                double y = e.y + 0.1 + (age % 30) / 30.0 * 2.2;
                int strands = density == 0 ? 1 : 2;
                for (int s = 0; s < strands; s++) {
                    int a = (step * 2 + s * STEPS / 2) % STEPS;
                    out.add(particle(s == 0 ? Particle.TYPE_FLAME : Particle.TYPE_ENCHANTMENT_TABLE, e.x + COS[a] * 0.8, y, e.z + SIN[a] * 0.8));
                    if (density == 2) { int b = (a + 1) % STEPS; out.add(particle(Particle.TYPE_ENCHANTMENT_TABLE, e.x + COS[b] * 0.8, y + 0.07, e.z + SIN[b] * 0.8)); }
                }
                return;
            }
            case EXPLOSAO: {
                if (age == 0) {
                    out.add(particle(Particle.TYPE_HUGE_EXPLODE, e.x, e.y + 1, e.z));
                    int points = density == 0 ? 8 : 16;
                    for (int i = 0; i < points; i++) { int a = i * STEPS / points; out.add(particle(Particle.TYPE_FLAME, e.x + COS[a] * 1.2, e.y + 0.5, e.z + SIN[a] * 1.2)); }
                } else if (age % 3 == 0 && density > 0) {
                    double r = 1.2 + age * 0.15;
                    for (int i = 0; i < 8; i++) { int a = (i * 4 + age) % STEPS; out.add(particle(Particle.TYPE_SMOKE, e.x + COS[a] * r, e.y + 0.3, e.z + SIN[a] * r)); }
                }
                return;
            }
            case CELEBRACAO: {
                if (age % 2 != 0) { return; }
                double y = e.y + 0.2 + progress * 2.4;
                int points = density == 2 ? 8 : 4;
                for (int i = 0; i < points; i++) { int a = (i * STEPS / points + step * 2) % STEPS; out.add(particle(Particle.TYPE_VILLAGER_HAPPY, e.x + COS[a] * 0.7, y, e.z + SIN[a] * 0.7)); }
                if (age % 6 == 0) { out.add(particle(Particle.TYPE_HEART, e.x, e.y + 2.3, e.z)); }
                return;
            }
            case PORTAL: {
                int points = density == 2 ? 10 : density == 1 ? 5 : 3;
                for (int i = 0; i < points; i++) {
                    int a = (i * STEPS / points + step) % STEPS;
                    out.add(particle(Particle.TYPE_PORTAL, e.x + COS[a] * 1.1, e.y + (i % 4) * 0.5, e.z + SIN[a] * 1.1));
                }
                return;
            }
            default:
        }
    }

    static LevelEventPacket particle(int type, double x, double y, double z) {
        LevelEventPacket packet = new LevelEventPacket();
        packet.evid = LevelEventPacket.EVENT_ADD_PARTICLE_MASK | type;
        packet.x = (float) x; packet.y = (float) y; packet.z = (float) z;
        return packet;
    }

    static final class Active {
        final Player player;
        final Level level;
        final Shape shape;
        final int ticks;
        double x, y, z;
        int age;
        Player[] viewers;
        Active(Player player, Level level, double x, double y, double z, Shape shape, int ticks) {
            this.player = player; this.level = level; this.x = x; this.y = y; this.z = z; this.shape = shape; this.ticks = ticks;
        }
        /** Atualiza a posicao de quem segue um jogador; false quando ele saiu ou mudou de mundo. */
        boolean update() {
            if (player == null) { return true; }
            if (!player.isOnline() || player.level != level) { return false; }
            x = player.x; y = player.y; z = player.z;
            return true;
        }
    }
}
