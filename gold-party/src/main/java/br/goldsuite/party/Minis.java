package br.goldsuite.party;

import cn.nukkit.Player;
import cn.nukkit.block.Block;
import cn.nukkit.entity.projectile.EntityArrow;
import cn.nukkit.event.entity.EntityDamageByChildEntityEvent;
import cn.nukkit.event.entity.EntityDamageEvent;
import cn.nukkit.event.player.PlayerInteractEvent;
import cn.nukkit.item.Item;
import cn.nukkit.level.Location;
import cn.nukkit.math.Vector3;
import java.util.*;
import java.util.function.Supplier;

/** As provas do Party. A ordem da lista define a regiao de cada uma na vaga da sala (nao mude a ordem). */
final class Minis {
    private Minis() { }

    static final List<Supplier<Mini>> ALL = Arrays.asList(CorridaMortal::new, PvpMlg::new, Fisherman::new, CorridaGrappler::new, CorridaKangaroo::new,
            ColorFloor::new, TntRun::new, WaterDrop::new, Arqueiro::new, Sumo::new);

    static final int Y = 64;
    static final int QUARTZ = 155, CLAY = 159, WOOL = 35, EMERALD = 133, GOLD = 41, SANDSTONE = 24, BRICKS = 98, WATER = 9;

    // ======================================================================
    // Corridas: chegada, checkpoints e classificacao por tempo, depois por avanco.
    // ======================================================================

    abstract static class Race extends Mini {
        final Map<Player, Integer> finishedAt = new LinkedHashMap<>();
        final Map<Player, Location> checkpoint = new HashMap<>();
        final Map<Player, Double> best = new HashMap<>();

        @Override Kind kind() { return Kind.TEMPO; }
        abstract double progress(Player p);
        abstract boolean atFinish(Player p);
        boolean isCheckpoint(int blockId) { return blockId == EMERALD; }

        @Override void tick() {
            for (Player p : room.players) {
                if (!room.alive(p)) { continue; }
                double pr = progress(p);
                if (pr > best.getOrDefault(p, -1e9)) { best.put(p, pr); }
                if (p.isOnGround() && isCheckpoint(below(p))) { checkpoint.put(p, p.getLocation()); }
                if (atFinish(p)) {
                    finishedAt.put(p, elapsedTicks);
                    int place = finishedAt.size();
                    room.say("§a" + p.getName() + " §fchegou em §e" + place + "o §7(" + String.format(Locale.ROOT, "%.1f", elapsedTicks / 20.0) + " s)");
                    room.spectate(p, spectatorSpot());
                }
            }
        }

        /** Caiu: volta ao ultimo checkpoint e perde o tempo da queda. */
        @Override void fell(Player p) {
            Location c = checkpoint.get(p);
            p.teleport(c != null ? c : spawn(room.players.indexOf(p), room.players.size()));
            p.setMotion(new Vector3(0, 0, 0));
        }

        @Override List<List<Player>> ranking() {
            List<List<Player>> out = new ArrayList<>();
            for (Player p : finishedAt.keySet()) { if (room.players.contains(p)) { out.add(new ArrayList<>(Collections.singletonList(p))); } }
            List<Player> rest = new ArrayList<>();
            for (Player p : room.players) { if (!finishedAt.containsKey(p)) { rest.add(p); } }
            rest.sort((a, b) -> Double.compare(best.getOrDefault(b, -1e9), best.getOrDefault(a, -1e9)));
            for (Player p : rest) { out.add(new ArrayList<>(Collections.singletonList(p))); }
            return out;
        }

        @Override String tip(Player p) {
            return super.tip(p) + " §7| chegaram §a" + finishedAt.size() + "/" + room.players.size();
        }
    }

    /** Percurso de parkour: saltos, vigas e escadas sobre o vazio. */
    static final class CorridaMortal extends Race {
        @Override String id() { return "corrida_mortal"; }
        @Override String name() { return "Corrida Mortal"; }
        @Override String description() { return "Parkour sobre o vazio: chegue primeiro ao bloco de ouro. Caiu, volta ao ultimo bloco de esmeralda."; }
        @Override int seconds() { return 150; }

        @Override void buildStatic(Blocks.Queue q) {
            fill(q, 40, Y - 1, 1, 56, Y - 1, 8, QUARTZ, 0);
            Random r = new Random(77);
            int x = 48, y = Y - 1, z = 9, sinceCheckpoint = 0, color = 0;
            while (z < 84) {
                int type = r.nextInt(10);
                if (sinceCheckpoint >= 18) {
                    z += 2; fill(q, x - 1, y, z, x + 1, y, z + 2, EMERALD, 0); z += 3; sinceCheckpoint = 0; continue;
                }
                int startZ = z;
                if (type < 5) {
                    int dy = r.nextInt(3) - 1, gap = dy > 0 ? 1 + r.nextInt(2) : 1 + r.nextInt(3), len = 2 + r.nextInt(3), w = r.nextInt(3);
                    y = Math.max(Y - 4, Math.min(Y + 5, y + dy)); x = Math.max(42, Math.min(54, x + r.nextInt(5) - 2)); z += gap;
                    fill(q, x - w / 2, y, z, x + (w + 1) / 2, y, z + len - 1, CLAY, (color++ % 14) + 1); z += len;
                } else if (type < 8) {
                    z += 1; int len = 6 + r.nextInt(4);
                    fill(q, x, y, z, x, y, z + len - 1, QUARTZ, 0); z += len;
                } else {
                    for (int i = 0; i < 3 && y < Y + 5; i++) { z += 2; y++; q.add(ox + x, y, oz + z, CLAY, 4); }
                    z += 1;
                }
                sinceCheckpoint += z - startZ;
            }
            fill(q, 42, y, 86, 54, y, 93, GOLD, 0);
        }

        @Override Location spawn(int i, int n) { return line(i, 48.5, Y, 4.5, 0); }
        @Override double progress(Player p) { return p.z - oz; }
        @Override boolean atFinish(Player p) { return p.z - oz >= 85 && below(p) == GOLD; }
        @Override Location spectatorSpot() { return at(48.5, Y + 12, 89.5, 180); }
    }

    /** Subida vertical num poco de vidro, passando pelos buracos das tampas com o Grappler. */
    static final class CorridaGrappler extends Race {
        static final int TOP = 116;
        private final Map<Player, Long> cooldown = new HashMap<>();
        @Override String id() { return "corrida_grappler"; }
        @Override String name() { return "Corrida de Grappler"; }
        @Override String description() { return "Suba o poco ate o topo! Mire num bloco acima e toque com a vara (Grappler) para ser puxado. Passe pelos buracos das tampas."; }
        @Override int seconds() { return 150; }
        @Override int voidY() { return 0; }

        @Override void buildStatic(Blocks.Queue q) {
            fill(q, 41, Y - 1, 41, 49, Y - 1, 49, QUARTZ, 0);
            walls(q, 40, 40, 50, 50, Y, TOP + 4);
            int[][] holes = {{41, 41}, {47, 47}, {41, 47}};
            for (int k = 0; k < 3; k++) {
                int ly = Y + 14 + k * 14;
                for (int x = 41; x <= 49; x++) {
                    for (int z = 41; z <= 49; z++) {
                        boolean hole = x >= holes[k][0] && x <= holes[k][0] + 2 && z >= holes[k][1] && z <= holes[k][1] + 2;
                        if (!hole) { q.add(ox + x, ly, oz + z, CLAY, 3 + k * 4); }
                    }
                }
            }
            fill(q, 38, TOP, 38, 52, TOP, 39, GOLD, 0);
            fill(q, 38, TOP, 51, 52, TOP, 52, GOLD, 0);
        }

        @Override Location spawn(int i, int n) { return circle(i, n, 45.5, Y, 45.5, 2.5); }
        @Override void start() { for (Player p : room.players) { p.getInventory().setItem(0, named(346, 0, 1, "§bGrappler")); p.getInventory().setHeldItemIndex(0); p.getInventory().sendContents(p); } }
        @Override double progress(Player p) { return p.y; }
        @Override boolean atFinish(Player p) { return p.y >= TOP - 1; }
        @Override Location spectatorSpot() { return at(45.5, TOP + 3, 38.5, 0); }

        @Override void interact(Player p, PlayerInteractEvent e) {
            if (!holding(p, 346)) { return; }
            e.setCancelled();
            long now = System.currentTimeMillis();
            if (now - cooldown.getOrDefault(p, 0L) < 500) { return; }
            Block target = targetBlock(p, 30);
            if (target == null) { p.sendPopup("§7Mire num bloco"); return; }
            cooldown.put(p, now);
            Vector3 pull = new Vector3(target.x + 0.5 - p.x, target.y + 1 - p.y, target.z + 0.5 - p.z);
            double len = Math.max(0.01, pull.length());
            p.setMotion(new Vector3(pull.x / len * 1.6, Math.min(1.6, pull.y / len * 1.6 + 0.45), pull.z / len * 1.6));
        }
    }

    /** Ilhas separadas por buracos e muros: so passa quem domina o impulso do Kangaroo. */
    static final class CorridaKangaroo extends Race {
        private final Map<Player, Boolean> airUsed = new HashMap<>();
        private final Map<Player, Long> cooldown = new HashMap<>();
        @Override String id() { return "corrida_kangaroo"; }
        @Override String name() { return "Corrida de Kangaroo"; }
        @Override String description() { return "Toque com o item Kangaroo para dar um impulso (no chao e mais um no ar). Atravesse ilhas e muros ate o ouro."; }
        @Override int seconds() { return 150; }
        @Override boolean isCheckpoint(int id) { return id == SANDSTONE; }

        @Override void buildStatic(Blocks.Queue q) {
            fill(q, 42, Y - 1, 1, 54, Y - 1, 8, SANDSTONE, 0);
            Random r = new Random(91);
            int z = 8, y = Y - 1, x = 48, n = 0;
            while (z < 78) {
                int gap = 6 + r.nextInt(4), size = 2 + r.nextInt(2);
                y = Math.max(Y - 3, Math.min(Y + 6, y + r.nextInt(5) - 1)); x = Math.max(42, Math.min(54, x + r.nextInt(7) - 3)); z += gap;
                fill(q, x - size, y, z, x + size, y, z + size * 2, SANDSTONE, 0);
                if (n++ % 3 == 2) { fill(q, x - size, y + 1, z + size, x + size, y + 3, z + size, BRICKS, 0); }
                z += size * 2;
            }
            fill(q, 41, y, 84, 55, y, 92, GOLD, 0);
        }

        @Override Location spawn(int i, int n) { return line(i, 48.5, Y, 4.5, 0); }
        @Override void start() { for (Player p : room.players) { p.getInventory().setItem(0, named(332, 0, 1, "§aKangaroo")); p.getInventory().setHeldItemIndex(0); p.getInventory().sendContents(p); } }
        @Override double progress(Player p) { return p.z - oz; }
        @Override boolean atFinish(Player p) { return p.z - oz >= 83 && below(p) == GOLD; }
        @Override Location spectatorSpot() { return at(48.5, Y + 14, 88.5, 180); }

        @Override void tick() {
            super.tick();
            for (Player p : room.players) { if (p.isOnGround()) { airUsed.remove(p); } }
        }

        @Override void interact(Player p, PlayerInteractEvent e) {
            if (!holding(p, 332)) { return; }
            e.setCancelled();
            long now = System.currentTimeMillis();
            if (now - cooldown.getOrDefault(p, 0L) < 300) { return; }
            Vector3 dir = p.getDirectionVector();
            if (p.isOnGround()) { p.setMotion(new Vector3(dir.x * 1.2, 0.95, dir.z * 1.2)); }
            else if (airUsed.put(p, true) == null) { p.setMotion(new Vector3(dir.x * 0.9, 0.7, dir.z * 0.9)); }
            else { return; }
            cooldown.put(p, now);
        }
    }

    // ======================================================================
    // Sobrevivencia
    // ======================================================================

    /** Chao de cores: fica so a cor sorteada, o resto some. O tempo para achar a cor diminui a cada rodada. */
    static final class ColorFloor extends Mini {
        static final int[] COLORS = {14, 11, 5, 4, 1, 10};
        static final String[] NAMES = {"§cVERMELHO", "§9AZUL", "§aVERDE", "§eAMARELO", "§6LARANJA", "§5ROXO"};
        private final Random r = new Random();
        private final int[][] pattern = new int[8][8];
        private int round, color, wait = 40;
        private boolean showing;

        @Override String id() { return "color_floor"; }
        @Override String name() { return "Color Floor"; }
        @Override String description() { return "Uma cor e sorteada (aparece na hotbar). Corra para um bloco dessa cor antes do tempo acabar: o resto do chao some!"; }
        @Override Kind kind() { return Kind.SOBREVIVENCIA; }
        @Override int seconds() { return 240; }
        @Override int voidY() { return Y - 8; }

        @Override void buildStatic(Blocks.Queue q) { walls(q, 35, 35, 60, 60, Y, Y + 3); }
        @Override void prepare(Blocks.Queue q) { shuffle(); paint(q); }
        @Override Location spawn(int i, int n) { return circle(i, n, 48, Y + 1, 48, 5); }

        private void shuffle() { for (int[] row : pattern) { for (int j = 0; j < 8; j++) { row[j] = r.nextInt(COLORS.length); } } }

        private void paint(Blocks.Queue q) {
            for (int px = 0; px < 8; px++) { for (int pz = 0; pz < 8; pz++) { fill(q, 36 + px * 3, Y, 36 + pz * 3, 38 + px * 3, Y, 38 + pz * 3, WOOL, COLORS[pattern[px][pz]]); } }
        }

        @Override void tick() {
            if (--wait > 0) {
                if (showing && wait % 10 == 0) { for (Player p : room.players) { if (room.alive(p)) { p.sendPopup("§l" + NAMES[color] + " §r§f" + String.format(Locale.ROOT, "%.1f", wait / 20.0) + " s"); } } }
                return;
            }
            Blocks.Queue q = room.queue;
            if (!showing) {
                // Nova rodada: chao novo (menos na primeira), sorteia uma cor que exista no chao.
                if (round > 0) { shuffle(); paint(q); }
                round++;
                color = pattern[r.nextInt(8)][r.nextInt(8)];
                Item wool = named(WOOL, COLORS[color], 1, "§l" + NAMES[color]);
                for (Player p : room.players) { if (room.alive(p)) { for (int s = 0; s < 9; s++) { p.getInventory().setItem(s, wool.clone()); } p.getInventory().sendContents(p); } }
                room.say("§fRodada §e" + round + "§f: §l" + NAMES[color]);
                wait = Math.max(32, 100 - round * 7);
                showing = true;
            } else {
                // Tempo esgotado: so a cor certa fica.
                for (int px = 0; px < 8; px++) { for (int pz = 0; pz < 8; pz++) { if (pattern[px][pz] != color) { fill(q, 36 + px * 3, Y, 36 + pz * 3, 38 + px * 3, Y, 38 + pz * 3, 0, 0); } } }
                wait = 50;
                showing = false;
            }
        }
    }

    /** O chao some por onde voce passa; tres camadas e o vazio embaixo. */
    static final class TntRun extends Mini {
        static final int[] LAYERS = {Y + 16, Y + 8, Y};
        /** Bloco empacotado -> tick em que some. */
        private final Map<Long, Integer> pending = new HashMap<>();
        private final ArrayDeque<long[]> due = new ArrayDeque<>();

        @Override String id() { return "tnt_run"; }
        @Override String name() { return "TNT Run"; }
        @Override String description() { return "Cada bloco que voce pisa some logo depois. Nao pare de correr e economize o chao: quem cair no vazio sai."; }
        @Override Kind kind() { return Kind.SOBREVIVENCIA; }
        @Override int seconds() { return 240; }
        @Override int voidY() { return Y - 6; }

        @Override void buildStatic(Blocks.Queue q) { walls(q, 36, 36, 59, 59, Y, LAYERS[0] + 3); }
        @Override void prepare(Blocks.Queue q) { int[] c = {14, 4, 5}; for (int i = 0; i < 3; i++) { fill(q, 37, LAYERS[i], 37, 58, LAYERS[i], 58, WOOL, c[i]); } }
        @Override Location spawn(int i, int n) { return circle(i, n, 48, LAYERS[0] + 1, 48, 6); }

        @Override void tick() {
            if (elapsedTicks > 40) {
                for (Player p : room.players) {
                    if (!room.alive(p)) { continue; }
                    int y = (int) Math.floor(p.y - 0.1);
                    if (y != LAYERS[0] && y != LAYERS[1] && y != LAYERS[2]) { continue; }
                    mark(p.x - 0.3, y, p.z - 0.3); mark(p.x + 0.3, y, p.z - 0.3); mark(p.x - 0.3, y, p.z + 0.3); mark(p.x + 0.3, y, p.z + 0.3);
                }
            }
            while (!due.isEmpty() && due.peekFirst()[0] <= elapsedTicks) {
                long[] d = due.pollFirst();
                pending.remove(d[1]);
                room.queue.add((int) d[2], (int) d[3], (int) d[4], 0, 0);
            }
        }

        private void mark(double fx, int y, double fz) {
            int x = (int) Math.floor(fx), z = (int) Math.floor(fz);
            if (x < ox + 37 || x > ox + 58 || z < oz + 37 || z > oz + 58) { return; }
            long key = ((long) x << 36) ^ ((long) y << 28) ^ (z & 0xFFFFFFFL);
            if (pending.containsKey(key) || room.level().getBlockIdAt(x, y, z) != WOOL) { return; }
            pending.put(key, elapsedTicks + 8);
            due.addLast(new long[] {elapsedTicks + 8, key, x, y, z});
        }
    }

    /** Ilhas no vazio com graveto de repulsao, espada, balde de agua (some em 3 s) e la para pontes. */
    static final class PvpMlg extends Mini {
        private final List<int[]> placed = new ArrayList<>();
        @Override String id() { return "pvp_mlg"; }
        @Override String name() { return "PvP MLG"; }
        @Override String description() { return "Derrube os outros das ilhas! Graveto de repulsao, espada, la e um balde de agua para salvar a queda (a agua some em 3 s)."; }
        @Override Kind kind() { return Kind.SOBREVIVENCIA; }
        @Override int seconds() { return 150; }
        @Override int voidY() { return Y - 20; }
        @Override boolean pvp() { return true; }
        @Override boolean fallDamage() { return true; }
        @Override boolean building() { return true; }

        @Override void buildStatic(Blocks.Queue q) {
            fill(q, 44, Y + 6, 44, 52, Y + 6, 52, QUARTZ, 0);
            int[][] off = {{-15, 0}, {15, 0}, {0, -15}, {0, 15}};
            for (int[] o : off) { fill(q, 46 + o[0], Y + 4, 46 + o[1], 50 + o[0], Y + 4, 50 + o[1], CLAY, 9); }
        }

        @Override Location spawn(int i, int n) {
            int[][] off = {{-15, 0}, {15, 0}, {0, -15}, {0, 15}};
            int[] o = off[i % 4];
            return at(48.5 + o[0] + (i / 4), Y + 5, 48.5 + o[1], Math.toDegrees(Math.atan2(o[0], -o[1])));
        }

        @Override void start() {
            for (Player p : room.players) {
                List<Item> kit = Items.parse("280:0:1/12:2", "268", "325:8:1", "35:0:32");
                for (int s = 0; s < kit.size(); s++) { p.getInventory().setItem(s, kit.get(s)); }
                p.getInventory().sendContents(p);
            }
        }

        void placed(int x, int y, int z) { placed.add(new int[] {x, y, z}); }

        /** Agua do balde: posta na hora e tirada em 3 s. */
        void water(int x, int y, int z) {
            room.level().setBlock(new Vector3(x, y, z), Block.get(WATER, 0), true, false);
            placed.add(new int[] {x, y, z});
            room.later(60, () -> { if (room.level().getBlockIdAt(x, y, z) == WATER) { room.queue.add(x, y, z, 0, 0); } });
        }

        @Override void cleanup(Blocks.Queue q) { for (int[] b : placed) { q.add(b[0], b[1], b[2], 0, 0); } placed.clear(); }
    }

    /** Plataforma redonda: puxe os outros com a vara (Fisherman) e jogue-os no vazio. */
    static final class Fisherman extends Mini {
        private final Map<Player, Long> cooldown = new HashMap<>();
        @Override String id() { return "fisherman"; }
        @Override String name() { return "Fisherman"; }
        @Override String description() { return "Mire num adversario e toque com a vara para puxa-lo ate voce. Tire todos da plataforma; o ultimo em pe vence."; }
        @Override Kind kind() { return Kind.SOBREVIVENCIA; }
        @Override int seconds() { return 120; }
        @Override int voidY() { return Y - 10; }
        @Override boolean pvp() { return true; }
        @Override boolean knockbackOnly() { return true; }

        @Override void buildStatic(Blocks.Queue q) {
            for (int x = -9; x <= 9; x++) { for (int z = -9; z <= 9; z++) { if (x * x + z * z <= 81) { q.add(ox + 48 + x, Y + 4, oz + 48 + z, (x + z & 1) == 0 ? QUARTZ : CLAY, 0); } } }
        }

        @Override Location spawn(int i, int n) { return circle(i, n, 48.5, Y + 5, 48.5, 6); }
        @Override void start() { for (Player p : room.players) { p.getInventory().setItem(0, named(346, 0, 1, "§eFisherman")); p.getInventory().setHeldItemIndex(0); p.getInventory().sendContents(p); } }

        @Override void interact(Player p, PlayerInteractEvent e) {
            if (!holding(p, 346)) { return; }
            e.setCancelled();
            long now = System.currentTimeMillis();
            if (now - cooldown.getOrDefault(p, 0L) < 1200) { return; }
            Player t = targetPlayer(p, 20);
            if (t == null) { p.sendPopup("§7Mire num adversario"); return; }
            cooldown.put(p, now);
            Vector3 d = new Vector3(p.x - t.x, 0, p.z - t.z);
            double len = Math.max(0.01, d.length());
            t.setMotion(new Vector3(d.x / len * 1.15, 0.5, d.z / len * 1.15));
            lastHit.put(t, p); lastHitAt.put(t, now);
            t.sendPopup("§cFisgado por " + p.getName() + "!");
        }
    }

    /** Sumo: plataforma pequena, so empurrao. */
    static final class Sumo extends Mini {
        @Override String id() { return "sumo"; }
        @Override String name() { return "Sumo"; }
        @Override String description() { return "Plataforma pequena e um graveto de repulsao: empurre todos para fora. Sem dano, so empurrao."; }
        @Override Kind kind() { return Kind.SOBREVIVENCIA; }
        @Override int seconds() { return 90; }
        @Override int voidY() { return Y - 8; }
        @Override boolean pvp() { return true; }
        @Override boolean knockbackOnly() { return true; }

        @Override void buildStatic(Blocks.Queue q) {
            for (int x = -5; x <= 5; x++) { for (int z = -5; z <= 5; z++) { if (x * x + z * z <= 25) { q.add(ox + 48 + x, Y + 4, oz + 48 + z, x * x + z * z <= 4 ? 42 : CLAY, x * x + z * z <= 4 ? 0 : 14); } } }
        }

        @Override Location spawn(int i, int n) { return circle(i, n, 48.5, Y + 5, 48.5, 3.5); }
        @Override void start() { for (Player p : room.players) { p.getInventory().setItem(0, Items.parse("280:0:1/12:1")); p.getInventory().sendContents(p); } }
    }

    // ======================================================================
    // Pontos
    // ======================================================================

    /** Pule do alto e caia na agua; a piscina diminui a cada rodada e ganha obstaculos. */
    static final class WaterDrop extends Mini {
        static final int TOP = 118, ROUNDS = 3, JUMP_TICKS = 300;
        private final Random r = new Random();
        private final Set<Player> doneRound = new HashSet<>();
        private final Map<Player, Integer> totalTicks = new HashMap<>();
        private final List<int[]> obstacles = new ArrayList<>();
        private int round, roundTicks, pause = 0, poolX, poolZ, poolSize;
        private boolean finished;

        @Override String id() { return "water_drop"; }
        @Override String name() { return "Water Drop"; }
        @Override String description() { return "Pule do alto e caia dentro da agua! Controle a queda no ar. 3 rodadas, a piscina fica menor e aparecem obstaculos."; }
        @Override Kind kind() { return Kind.PONTOS; }
        @Override int seconds() { return 120; }
        @Override int voidY() { return 0; }

        @Override void buildStatic(Blocks.Queue q) {
            walls(q, 37, 37, 59, 59, Y, TOP);
            for (int x = 37; x <= 59; x++) { for (int z = 37; z <= 59; z++) { if (x < 39 || x > 57 || z < 39 || z > 57) { q.add(ox + x, TOP + 1, oz + z, QUARTZ, 0); } } }
        }

        @Override void prepare(Blocks.Queue q) { newRound(q); }
        @Override Location spawn(int i, int n) { return rim(i, n); }

        private Location rim(int i, int n) {
            double a = Math.PI * 2 * i / Math.max(1, n);
            double x = 48.5 + Math.cos(a) * 10, z = 48.5 + Math.sin(a) * 10;
            x = Math.max(37.5, Math.min(59.5, x)); z = Math.max(37.5, Math.min(59.5, z));
            if (x > 38.5 && x < 58.5 && z > 38.5 && z < 58.5) { if (Math.abs(x - 48.5) > Math.abs(z - 48.5)) { x = x < 48.5 ? 37.5 : 59.5; } else { z = z < 48.5 ? 37.5 : 59.5; } }
            return at(x, TOP + 2, z, Math.toDegrees(Math.atan2(-(48.5 - x), 48.5 - z)));
        }

        private void newRound(Blocks.Queue q) {
            for (int[] o : obstacles) { q.add(o[0], o[1], o[2], 0, 0); }
            obstacles.clear();
            fill(q, 38, Y - 1, 38, 58, Y - 1, 58, BRICKS, 0);
            poolSize = round == 0 ? 5 : round == 1 ? 3 : 2;
            poolX = 40 + r.nextInt(17 - poolSize); poolZ = 40 + r.nextInt(17 - poolSize);
            fill(q, poolX, Y - 1, poolZ, poolX + poolSize - 1, Y - 1, poolZ + poolSize - 1, WATER, 0);
            if (round > 0) {
                for (int i = 0; i < 4 + round * 4; i++) {
                    int x = 40 + r.nextInt(17), z = 40 + r.nextInt(17), y = Y + 12 + r.nextInt(TOP - Y - 24);
                    if (x >= poolX - 1 && x <= poolX + poolSize && z >= poolZ - 1 && z <= poolZ + poolSize) { continue; }
                    for (int dx = 0; dx < 2; dx++) { for (int dz = 0; dz < 2; dz++) { q.add(ox + x + dx, y, oz + z + dz, CLAY, 15); obstacles.add(new int[] {ox + x + dx, y, oz + z + dz}); } }
                }
            }
        }

        @Override void start() { room.say("§fRodada §e1/" + ROUNDS + "§f: pule!"); }

        @Override void tick() {
            if (finished) { return; }
            if (pause > 0) {
                if (--pause == 0) {
                    int i = 0;
                    for (Player p : room.players) { if (room.alive(p)) { p.teleport(rim(i++, room.players.size())); } }
                    room.say("§fRodada §e" + (round + 1) + "/" + ROUNDS + "§f: pule!");
                }
                return;
            }
            roundTicks++;
            for (Player p : room.players) {
                if (!room.alive(p) || doneRound.contains(p)) { continue; }
                int fx = p.getFloorX(), fz = p.getFloorZ();
                int id = room.level().getBlockIdAt(fx, (int) Math.floor(p.y), fz), under = room.level().getBlockIdAt(fx, (int) Math.floor(p.y) - 1, fz);
                if (id == 8 || id == WATER || (p.y < Y + 1 && (under == 8 || under == WATER))) {
                    score.merge(p, 1, Integer::sum); totalTicks.merge(p, roundTicks, Integer::sum); doneRound.add(p);
                    room.say("§a" + p.getName() + " §facertou a agua!"); p.teleport(rim(room.players.indexOf(p), room.players.size()));
                } else if (p.isOnGround() && p.y < TOP - 2) {
                    doneRound.add(p); p.sendPopup("§cErrou a agua!"); p.teleport(rim(room.players.indexOf(p), room.players.size()));
                }
            }
            if (roundTicks >= JUMP_TICKS || doneRound.size() >= room.aliveCount()) {
                for (Player p : room.players) { if (room.alive(p) && !doneRound.contains(p)) { p.sendPopup("§cTempo esgotado!"); p.teleport(rim(room.players.indexOf(p), room.players.size())); } }
                doneRound.clear(); roundTicks = 0;
                if (++round >= ROUNDS) { finished = true; return; }
                newRound(room.queue);
                pause = 50;
            }
        }

        @Override boolean done() { return finished; }

        @Override List<List<Player>> ranking() {
            List<Player> list = new ArrayList<>(room.players);
            list.sort((a, b) -> score.getOrDefault(a, 0).equals(score.getOrDefault(b, 0)) ? Integer.compare(totalTicks.getOrDefault(a, 1 << 20), totalTicks.getOrDefault(b, 1 << 20)) : Integer.compare(score.getOrDefault(b, 0), score.getOrDefault(a, 0)));
            List<List<Player>> out = new ArrayList<>();
            for (Player p : list) { out.add(new ArrayList<>(Collections.singletonList(p))); }
            return out;
        }

        @Override String tip(Player p) { return "§e" + name() + " §7| rodada §f" + Math.min(ROUNDS, round + 1) + "/" + ROUNDS + " §7| acertos §a" + score.getOrDefault(p, 0); }
    }

    /** Deathmatch de arco: flecha mata na hora, renasce em 2 s, 2 minutos e vence quem matar mais. */
    static final class Arqueiro extends Mini {
        private final Map<Player, Integer> deaths = new HashMap<>();
        private final Map<Player, Integer> safeUntil = new HashMap<>();
        private final Random r = new Random();
        @Override String id() { return "arqueiro"; }
        @Override String name() { return "Arqueiro"; }
        @Override String description() { return "Deathmatch: arco com 2 flechas e espada de madeira, sem armadura. Flecha mata na hora e cada abate da mais uma flecha. 2 minutos, vence quem matar mais."; }
        @Override Kind kind() { return Kind.PONTOS; }
        @Override int seconds() { return 120; }
        @Override int voidY() { return 0; }
        @Override boolean pvp() { return true; }

        @Override void buildStatic(Blocks.Queue q) {
            fill(q, 33, Y - 1, 33, 63, Y - 1, 63, BRICKS, 0);
            walls(q, 32, 32, 64, 64, Y, Y + 5);
            Random g = new Random(5);
            for (int i = 0; i < 14; i++) {
                int x = 35 + g.nextInt(26), z = 35 + g.nextInt(26), h = 2 + g.nextInt(2);
                boolean wall = g.nextBoolean();
                fill(q, x, Y, z, wall ? x + 3 : x + 1, Y + h - 1, wall ? z : z + 1, CLAY, 7 + g.nextInt(2));
            }
        }

        @Override Location spawn(int i, int n) { return circle(i, n, 48.5, Y, 48.5, 12); }

        private void kit(Player p, int arrows) {
            p.getInventory().clearAll();
            p.getInventory().setItem(0, Item.get(261, 0, 1));
            p.getInventory().setItem(1, Item.get(268, 0, 1));
            p.getInventory().setItem(8, Item.get(262, 0, arrows));
            p.getInventory().setHeldItemIndex(0);
            p.getInventory().sendContents(p);
        }

        @Override void start() { for (Player p : room.players) { kit(p, 2); } }

        @Override boolean hit(Player victim, Player attacker, EntityDamageEvent e) {
            if (safeUntil.getOrDefault(victim, 0) > elapsedTicks) { e.setCancelled(); return true; }
            if (e instanceof EntityDamageByChildEntityEvent && ((EntityDamageByChildEntityEvent) e).getChild() instanceof EntityArrow) {
                e.setCancelled();
                lethal(victim, attacker);
                return true;
            }
            return false;
        }

        @Override void lethal(Player victim, Player killer) {
            if (killer != null && killer != victim) {
                score.merge(killer, 1, Integer::sum);
                killer.getInventory().addItem(Item.get(262, 0, 1));
                killer.sendPopup("§a+1 abate §7(" + score.get(killer) + ")");
                room.say(room.color(killer) + killer.getName() + " §7matou " + room.color(victim) + victim.getName());
            }
            deaths.merge(victim, 1, Integer::sum);
            victim.setHealth(victim.getMaxHealth());
            victim.teleport(spawn(r.nextInt(8), 8));
            safeUntil.put(victim, elapsedTicks + 40);
            kit(victim, 2);
        }

        @Override void fell(Player p) { lethal(p, recentAttacker(p)); }

        @Override List<List<Player>> ranking() {
            List<Player> list = new ArrayList<>(room.players);
            list.sort((a, b) -> score.getOrDefault(a, 0).equals(score.getOrDefault(b, 0)) ? Integer.compare(deaths.getOrDefault(a, 0), deaths.getOrDefault(b, 0)) : Integer.compare(score.getOrDefault(b, 0), score.getOrDefault(a, 0)));
            List<List<Player>> out = new ArrayList<>();
            for (int i = 0; i < list.size(); i++) {
                Player p = list.get(i);
                if (i > 0 && score.getOrDefault(p, 0).equals(score.getOrDefault(list.get(i - 1), 0)) && deaths.getOrDefault(p, 0).equals(deaths.getOrDefault(list.get(i - 1), 0))) { out.get(out.size() - 1).add(p); }
                else { out.add(new ArrayList<>(Collections.singletonList(p))); }
            }
            return out;
        }
    }
}
