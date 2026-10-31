package br.goldsuite.party;

import cn.nukkit.Player;
import cn.nukkit.block.Block;
import cn.nukkit.event.entity.EntityDamageEvent;
import cn.nukkit.event.player.PlayerInteractEvent;
import cn.nukkit.item.Item;
import cn.nukkit.level.Location;
import cn.nukkit.math.Vector3;
import java.util.*;

/**
 * Um minigame do Party. Cada rodada cria uma instancia nova (o estado da prova fica nos campos dela).
 * A arena fica na regiao (ox..ox+95, oz..oz+95) da vaga da sala; y sempre abaixo de 128 (mundo McRegion).
 */
abstract class Mini {
    /** Como a prova decide a classificacao. */
    enum Kind { TEMPO, SOBREVIVENCIA, PONTOS }

    Room room;
    int ox, oz;
    /** Ordem de eliminacao (o primeiro eliminado fica no fim do ranking). */
    final List<Player> eliminated = new ArrayList<>();
    final Map<Player, Integer> score = new HashMap<>();
    final Map<Player, Player> lastHit = new HashMap<>();
    final Map<Player, Long> lastHitAt = new HashMap<>();
    int elapsedTicks;

    abstract String id();
    abstract String name();
    abstract String description();
    abstract Kind kind();
    abstract int seconds();
    /** Muda quando a geometria fixa muda (refaz a arena nas vagas ja construidas). */
    int version() { return 1; }

    /** Geometria fixa, construida uma vez por vaga. */
    void buildStatic(Blocks.Queue q) { }
    /** Geometria que se refaz a cada rodada (chao de cores, camadas do TNT Run...). */
    void prepare(Blocks.Queue q) { }
    abstract Location spawn(int index, int total);
    /** Itens e estado iniciais (os jogadores ja estao no lugar). */
    void start() { }
    void tick() { }
    /** Limpa o que os jogadores mudaram na arena (fim da prova). */
    void cleanup(Blocks.Queue q) { }
    void second() { }
    /** y abaixo do qual o jogador caiu no vazio. */
    int voidY() { return 50; }
    boolean pvp() { return false; }
    /** PvP so empurra, sem tirar vida. */
    boolean knockbackOnly() { return false; }
    boolean fallDamage() { return false; }
    /** Pode colocar blocos (MLG). */
    boolean building() { return false; }
    void interact(Player p, PlayerInteractEvent e) { }
    /** Dano de jogador em jogador ja aceito; devolve true se a prova tratou (ex.: flecha mata na hora). */
    boolean hit(Player victim, Player attacker, EntityDamageEvent e) { return false; }

    /** Caiu no vazio: padrao elimina (credita quem bateu por ultimo). */
    void fell(Player p) { lethal(p, recentAttacker(p)); }
    /** Morreria: padrao elimina. As provas com renascimento sobrescrevem. */
    void lethal(Player victim, Player killer) {
        if (killer != null && killer != victim && room.alive(killer)) { score.merge(killer, 1, Integer::sum); room.say(room.color(victim) + victim.getName() + " §7foi eliminado por " + room.color(killer) + killer.getName()); }
        else { room.say(room.color(victim) + victim.getName() + " §7foi eliminado."); }
        eliminate(victim);
    }

    void eliminate(Player p) {
        if (!room.alive(p)) { return; }
        eliminated.add(p);
        room.spectate(p, spectatorSpot());
    }

    Location spectatorSpot() { return new Location(ox + 48.5, 100, oz + 48.5, 0, 60, room.level()); }

    Player recentAttacker(Player p) {
        Long at = lastHitAt.get(p);
        return at != null && System.currentTimeMillis() - at < 10_000 ? lastHit.get(p) : null;
    }

    /** Fim antes do tempo. */
    boolean done() {
        if (kind() == Kind.PONTOS) { return false; }
        int alive = room.aliveCount();
        return kind() == Kind.SOBREVIVENCIA ? alive <= (room.players.size() > 1 ? 1 : 0) : alive == 0;
    }

    /** Grupos do melhor para o pior (empatados no mesmo grupo). */
    List<List<Player>> ranking() {
        List<List<Player>> out = new ArrayList<>();
        if (kind() == Kind.PONTOS) {
            TreeMap<Integer, List<Player>> by = new TreeMap<>(Comparator.reverseOrder());
            for (Player p : room.players) { by.computeIfAbsent(score.getOrDefault(p, 0), k -> new ArrayList<>()).add(p); }
            out.addAll(by.values());
            return out;
        }
        // Sobrevivencia: quem sobrou empata em 1o (desempate por abates), depois a ordem inversa de eliminacao.
        List<Player> alive = new ArrayList<>();
        for (Player p : room.players) { if (!eliminated.contains(p)) { alive.add(p); } }
        TreeMap<Integer, List<Player>> byKills = new TreeMap<>(Comparator.reverseOrder());
        for (Player p : alive) { byKills.computeIfAbsent(score.getOrDefault(p, 0), k -> new ArrayList<>()).add(p); }
        out.addAll(byKills.values());
        for (int i = eliminated.size() - 1; i >= 0; i--) { if (room.players.contains(eliminated.get(i))) { out.add(new ArrayList<>(Collections.singletonList(eliminated.get(i)))); } }
        return out;
    }

    /** Texto da barra de dica a cada segundo. */
    String tip(Player p) {
        int left = Math.max(0, seconds() - elapsedTicks / 20);
        return "§e" + name() + " §7| §f" + left / 60 + ":" + String.format("%02d", left % 60) + (kind() == Kind.SOBREVIVENCIA ? " §7| vivos §a" + room.aliveCount() : kind() == Kind.PONTOS ? " §7| pontos §a" + score.getOrDefault(p, 0) : "");
    }

    // ---------- ajudantes de geometria e itens ----------

    void fill(Blocks.Queue q, int x1, int y1, int z1, int x2, int y2, int z2, int id, int meta) {
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
            for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
                for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) { q.add(ox + x, y, oz + z, id, meta); }
            }
        }
    }

    /** Paredes de vidro em volta de um retangulo (so as bordas). */
    void walls(Blocks.Queue q, int x1, int z1, int x2, int z2, int y1, int y2) {
        for (int y = y1; y <= y2; y++) {
            for (int x = x1; x <= x2; x++) { q.add(ox + x, y, oz + z1, Block.GLASS, 0); q.add(ox + x, y, oz + z2, Block.GLASS, 0); }
            for (int z = z1; z <= z2; z++) { q.add(ox + x1, y, oz + z, Block.GLASS, 0); q.add(ox + x2, y, oz + z, Block.GLASS, 0); }
        }
    }

    Location at(double x, double y, double z, double yaw) { return new Location(ox + x, y, oz + z, yaw, 0, room.level()); }

    /** Espalha os jogadores numa fila no eixo x a partir do centro. */
    Location line(int index, double cx, double y, double z, double yaw) { return at(cx + ((index % 8) - 3.5) * 1.5, y, z, yaw); }

    /** Espalha os jogadores num circulo. */
    Location circle(int index, int total, double cx, double y, double cz, double r) {
        double a = Math.PI * 2 * index / Math.max(1, total);
        double x = cx + Math.cos(a) * r, z = cz + Math.sin(a) * r;
        return at(x, y, z, Math.toDegrees(Math.atan2(-(cx - x), cz - z)));
    }

    static Item named(int id, int meta, int count, String name) { Item i = Item.get(id, meta, count); i.setCustomName(name); return i; }

    static boolean holding(Player p, int id) { return p.getInventory().getItemInHand().getId() == id; }

    /** Bloco mais proximo na mira (passo de meio bloco). */
    static Block targetBlock(Player p, int max) {
        Vector3 dir = p.getDirectionVector();
        double x = p.x, y = p.y + p.getEyeHeight(), z = p.z;
        Vector3 v = new Vector3();
        for (double d = 0; d < max; d += 0.5) {
            x += dir.x * 0.5; y += dir.y * 0.5; z += dir.z * 0.5;
            if (y < 0 || y > 127) { return null; }
            v.x = Math.floor(x); v.y = Math.floor(y); v.z = Math.floor(z);
            Block b = p.level.getBlock(v);
            if (b.getId() != 0 && b.isSolid()) { return b; }
        }
        return null;
    }

    /** Adversario vivo mais proximo na linha de mira. */
    Player targetPlayer(Player p, double max) {
        Vector3 dir = p.getDirectionVector();
        double ex = p.x, ey = p.y + p.getEyeHeight(), ez = p.z, best = max;
        Player out = null;
        for (Player o : room.players) {
            if (o == p || !room.alive(o)) { continue; }
            double vx = o.x - ex, vy = o.y + 1 - ey, vz = o.z - ez;
            double t = vx * dir.x + vy * dir.y + vz * dir.z;
            if (t <= 0 || t > best) { continue; }
            if (vx * vx + vy * vy + vz * vz - t * t < 2.25) { out = o; best = t; }
        }
        return out;
    }

    /** Bloco embaixo dos pes. */
    static int below(Player p) { return p.level.getBlockIdAt(p.getFloorX(), (int) Math.floor(p.y - 0.2), p.getFloorZ()); }
}
