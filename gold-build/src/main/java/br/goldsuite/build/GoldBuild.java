package br.goldsuite.build;

import br.goldsuite.api.Settings;
import cn.nukkit.Player;
import cn.nukkit.block.Block;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.player.PlayerQuitEvent;
import cn.nukkit.level.Level;
import cn.nukkit.math.Vector3;
import cn.nukkit.plugin.PluginBase;
import cn.nukkit.scheduler.PluginTask;
import cn.nukkit.scheduler.TaskHandler;
import java.util.*;

/**
 * Edicao de mundo (MagicWE e BlockSniper refeitos): set, replace, walls, esfera e undo.
 * Toda operacao roda em fatias de ate 4000 blocos por tick numa fila unica.
 */
public final class GoldBuild extends PluginBase implements Listener {
    static String PREFIX = "§6[§eWE§6]§f ";
    static final int PER_TICK = 4000, MAX_BLOCKS = 500_000, MAX_UNDO = 100_000;
    private final Map<Player, int[][]> selection = new IdentityHashMap<>();
    /** Ultima operacao de cada admin: posicoes e blocos anteriores ({x,y,z,id,meta}). */
    private final Map<String, List<int[]>> undo = new HashMap<>();
    private final Deque<Job> queue = new ArrayDeque<>();
    private TaskHandler task;

    /** Uma operacao: posicoes geradas sob demanda e o bloco novo de cada uma. */
    static final class Job {
        final Level level;
        final Iterator<int[]> positions;
        final BlockChooser chooser;
        final List<int[]> history = new ArrayList<>();
        final CommandSender owner;
        int changed;
        boolean undoable = true;
        Job(Level level, Iterator<int[]> positions, BlockChooser chooser, CommandSender owner) { this.level = level; this.positions = positions; this.chooser = chooser; this.owner = owner; }
    }

    /** Bloco novo para a posicao, ou null para manter. */
    interface BlockChooser { int[] choose(int id, int meta, int x, int y, int z); }

    @Override public void onEnable() {
        Settings.prefix(this, PREFIX, v -> PREFIX = v); getServer().getPluginManager().registerEvents(this, this); }
    @Override public void onDisable() { queue.clear(); selection.clear(); undo.clear(); if (task != null) { task.cancel(); } }

    @EventHandler(priority = EventPriority.MONITOR) public void quit(PlayerQuitEvent e) { selection.remove(e.getPlayer()); }

    static int[] block(String text) {
        String[] parts = text.split(":");
        try {
            int id = Integer.parseInt(parts[0]), meta = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            if (id < 0 || id > 255 || meta < 0 || meta > 15 || parts.length > 2 || (id != 0 && Block.list != null && Block.list[id] == null)) { throw new NumberFormatException(); }
            return new int[] {id, meta};
        } catch (NumberFormatException e) { throw new IllegalArgumentException("Bloco invalido: " + text + " (use id ou id:meta)"); }
    }

    /** Todas as posicoes do cubo, sem criar a lista inteira na memoria. */
    static Iterator<int[]> cuboid(int[] a, int[] b, boolean wallsOnly) {
        int minX = Math.min(a[0], b[0]), maxX = Math.max(a[0], b[0]), minY = Math.max(0, Math.min(a[1], b[1])), maxY = Math.min(127, Math.max(a[1], b[1])), minZ = Math.min(a[2], b[2]), maxZ = Math.max(a[2], b[2]);
        return new Iterator<int[]>() {
            int x = minX, y = minY, z = minZ - 1;
            int[] next = advance();
            int[] advance() {
                while (true) {
                    if (++z > maxZ) { z = minZ; if (++x > maxX) { x = minX; if (++y > maxY) { return null; } } }
                    if (!wallsOnly || x == minX || x == maxX || z == minZ || z == maxZ) { return new int[] {x, y, z}; }
                }
            }
            @Override public boolean hasNext() { return next != null; }
            @Override public int[] next() { int[] r = next; next = advance(); return r; }
        };
    }

    static long volume(int[] a, int[] b) {
        return (long) (Math.abs(a[0] - b[0]) + 1) * (Math.min(127, Math.max(a[1], b[1])) - Math.max(0, Math.min(a[1], b[1])) + 1) * (Math.abs(a[2] - b[2]) + 1);
    }

    static Iterator<int[]> sphere(int cx, int cy, int cz, int r) {
        List<int[]> list = new ArrayList<>();
        for (int x = -r; x <= r; x++) { for (int y = -r; y <= r; y++) { for (int z = -r; z <= r; z++) { if (x * x + y * y + z * z <= r * r && cy + y >= 0 && cy + y <= 127) { list.add(new int[] {cx + x, cy + y, cz + z}); } } } }
        return list.iterator();
    }

    private void enqueue(Job job) {
        queue.add(job);
        if (task == null) { task = getServer().getScheduler().scheduleRepeatingTask(new PluginTask<GoldBuild>(this) { @Override public void onRun(int tick) { work(); } }, 1); }
        job.owner.sendMessage(PREFIX + "Na fila (" + queue.size() + "). Ate " + PER_TICK + " blocos por tick.");
    }

    /** Uma fatia por tick; o TPS nao cai mesmo com edicoes grandes. */
    private void work() {
        int budget = PER_TICK;
        while (budget > 0 && !queue.isEmpty()) {
            Job job = queue.peek();
            Vector3 pos = new Vector3();
            while (budget > 0 && job.positions.hasNext()) {
                int[] p = job.positions.next();
                budget--;
                pos.setComponents(p[0], p[1], p[2]);
                Block current = job.level.getBlock(pos);
                int[] next = job.chooser.choose(current.getId(), current.getDamage(), p[0], p[1], p[2]);
                if (next == null || (next[0] == current.getId() && next[1] == current.getDamage())) { continue; }
                // Acima do limite o historico e descartado: memoria nao cresce sem controle.
                if (job.undoable) { if (job.history.size() < MAX_UNDO) { job.history.add(new int[] {p[0], p[1], p[2], current.getId(), current.getDamage()}); } else { job.undoable = false; job.history.clear(); } }
                job.level.setBlock(new Vector3(p[0], p[1], p[2]), Block.get(next[0], next[1]), true, false);
                job.changed++;
            }
            if (!job.positions.hasNext()) {
                queue.poll();
                if (job.undoable) { undo.put(job.owner.getName().toLowerCase(Locale.ROOT), job.history); } else { undo.remove(job.owner.getName().toLowerCase(Locale.ROOT)); }
                job.owner.sendMessage(PREFIX + job.changed + " blocos alterados." + (job.undoable ? " /we undo desfaz." : " Grande demais para desfazer."));
            }
        }
        if (queue.isEmpty() && task != null) { task.cancel(); task = null; }
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) { sender.sendMessage("Use dentro do jogo."); return true; }
        Player p = (Player) sender;
        String sub = args.length == 0 ? "ajuda" : args[0].toLowerCase(Locale.ROOT);
        try {
            switch (sub) {
                case "pos1": case "pos2": {
                    int[][] sel = selection.computeIfAbsent(p, k -> new int[2][]);
                    sel[sub.equals("pos1") ? 0 : 1] = new int[] {p.getFloorX(), p.getFloorY(), p.getFloorZ()};
                    p.sendMessage(PREFIX + "Ponto " + sub.charAt(3) + ": " + p.getFloorX() + " " + p.getFloorY() + " " + p.getFloorZ() + (sel[0] != null && sel[1] != null ? " (" + volume(sel[0], sel[1]) + " blocos)" : ""));
                    return true;
                }
                case "set": case "walls": case "paredes": {
                    int[][] sel = selected(p);
                    int[] to = block(arg(args, 1));
                    enqueue(new Job(p.level, cuboid(sel[0], sel[1], !sub.equals("set")), (id, meta, x, y, z) -> to, p));
                    return true;
                }
                case "replace": case "trocar": {
                    int[][] sel = selected(p);
                    int[] from = block(arg(args, 1)), to = block(arg(args, 2));
                    boolean anyMeta = !args[1].contains(":");
                    enqueue(new Job(p.level, cuboid(sel[0], sel[1], false), (id, meta, x, y, z) -> id == from[0] && (anyMeta || meta == from[1]) ? to : null, p));
                    return true;
                }
                case "esfera": case "sphere": {
                    int radius = Integer.parseInt(arg(args, 1));
                    if (radius < 1 || radius > 30) { throw new IllegalArgumentException("Raio entre 1 e 30."); }
                    int[] to = block(arg(args, 2));
                    Vector3 target = target(p);
                    enqueue(new Job(p.level, sphere(target.getFloorX(), target.getFloorY(), target.getFloorZ(), radius), (id, meta, x, y, z) -> to, p));
                    return true;
                }
                case "undo": case "desfazer": {
                    List<int[]> history = undo.remove(p.getName().toLowerCase(Locale.ROOT));
                    if (history == null || history.isEmpty()) { throw new IllegalArgumentException("Nada para desfazer."); }
                    Map<String, int[]> before = new HashMap<>();
                    for (int[] h : history) { before.put(h[0] + ":" + h[1] + ":" + h[2], new int[] {h[3], h[4]}); }
                    Iterator<int[]> positions = history.stream().map(h -> new int[] {h[0], h[1], h[2]}).iterator();
                    enqueue(new Job(p.level, positions, (id, meta, x, y, z) -> before.get(x + ":" + y + ":" + z), p));
                    return true;
                }
                default:
                    p.sendMessage(PREFIX + "/we pos1, pos2, set <bloco>, walls <bloco>, replace <de> <para>, esfera <raio> <bloco> (onde voce mira), undo");
                    return true;
            }
        } catch (NumberFormatException e) { p.sendMessage(PREFIX + "§cNumero invalido."); return true; }
        catch (RuntimeException e) { p.sendMessage(PREFIX + "§c" + e.getMessage()); return true; }
    }

    private int[][] selected(Player p) {
        int[][] sel = selection.get(p);
        if (sel == null || sel[0] == null || sel[1] == null) { throw new IllegalArgumentException("Marque /we pos1 e /we pos2 antes."); }
        if (volume(sel[0], sel[1]) > MAX_BLOCKS) { throw new IllegalArgumentException("Selecao maior que " + MAX_BLOCKS + " blocos."); }
        return sel;
    }

    /** Bloco que o jogador mira (ate 100 blocos), para o pincel. */
    private static Vector3 target(Player p) {
        Vector3 dir = p.getDirectionVector();
        double x = p.x, y = p.y + p.getEyeHeight(), z = p.z;
        for (int i = 0; i < 200; i++) {
            x += dir.x * 0.5; y += dir.y * 0.5; z += dir.z * 0.5;
            if (y < 0 || y > 127) { break; }
            Vector3 pos = new Vector3(Math.floor(x), Math.floor(y), Math.floor(z));
            if (p.level.getBlock(pos).getId() != 0) { return pos; }
        }
        throw new IllegalArgumentException("Mire num bloco.");
    }

    private static String arg(String[] args, int i) { if (args.length <= i) { throw new IllegalArgumentException("Faltam argumentos. Use /we."); } return args[i]; }
}
