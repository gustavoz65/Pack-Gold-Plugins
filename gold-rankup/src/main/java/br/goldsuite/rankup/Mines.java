package br.goldsuite.rankup;

import br.goldsuite.rankup.RankState.Mine;
import br.goldsuite.rankup.RankState.Part;
import cn.nukkit.Player;
import cn.nukkit.block.Block;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.block.BlockBreakEvent;
import cn.nukkit.event.block.BlockPlaceEvent;
import cn.nukkit.event.player.PlayerBucketEmptyEvent;
import cn.nukkit.item.Item;
import cn.nukkit.item.enchantment.Enchantment;
import cn.nukkit.level.Level;
import cn.nukkit.level.Location;
import cn.nukkit.math.Vector3;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Minas que se regeneram. Trabalho por tick limitado (blocos_por_tick, dividido entre todas as minas), so em chunks
 * carregados; o contador de blocos minerados e o relogio ficam so em memoria (uma mina recem-ligada regenera cheia).
 */
final class Mines implements Listener {
    private final GoldRankup plugin;
    int perTick = 2000;

    /** Estado de cada mina em memoria. */
    static final class Runtime {
        int mined;
        long nextAt, scheduledAt;
        boolean resetting, filled;
    }
    final Map<String, Runtime> runtime = new HashMap<>();

    /** Uma caixa a preencher, aos poucos. */
    private static final class Job {
        final Level level; final int x1, y1, z1, x2, y2, z2; final List<Part> parts; final int total; final boolean loadChunks; final Runnable done;
        int x, y, z;
        Job(Level level, int x1, int y1, int z1, int x2, int y2, int z2, List<Part> parts, boolean loadChunks, Runnable done) {
            this.level = level; this.x1 = x1; this.y1 = y1; this.z1 = z1; this.x2 = x2; this.y2 = y2; this.z2 = z2; this.parts = parts; this.loadChunks = loadChunks; this.done = done;
            int t = 0; for (Part p : parts) { t += p.weight; } this.total = t;
            x = x1; y = y1; z = z1;
        }
    }
    private final ArrayDeque<Job> jobs = new ArrayDeque<>();

    Mines(GoldRankup plugin) { this.plugin = plugin; }

    Runtime rt(String id) { return runtime.computeIfAbsent(id, k -> new Runtime()); }

    // ---------- trabalho por tick ----------

    /** Chamado a cada tick: repoe ate perTick blocos no total. */
    void tick() {
        int budget = perTick;
        while (budget > 0 && !jobs.isEmpty()) {
            Job j = jobs.peek();
            if (j.level == null || plugin.getServer().getLevelByName(j.level.getFolderName()) == null) { jobs.poll(); continue; }
            ThreadLocalRandom rnd = ThreadLocalRandom.current();
            while (budget > 0 && j.x <= j.x2) {
                int cx = j.x >> 4, cz = j.z >> 4;
                if (!j.level.isChunkLoaded(cx, cz)) {
                    if (!j.loadChunks) { budget = 0; break; }
                    j.level.loadChunk(cx, cz, true);
                }
                Part part = pick(j.parts, j.total, rnd);
                j.level.setBlock(new Vector3(j.x, j.y, j.z), Block.get(part.id, part.meta), true, false);
                budget--;
                if (++j.y > j.y2) { j.y = j.y1; if (++j.z > j.z2) { j.z = j.z1; j.x++; } }
            }
            if (j.x > j.x2) { jobs.poll(); if (j.done != null) { j.done.run(); } }
        }
    }

    private static Part pick(List<Part> parts, int total, ThreadLocalRandom rnd) {
        if (parts.size() == 1) { return parts.get(0); }
        int r = rnd.nextInt(total);
        for (Part p : parts) { r -= p.weight; if (r < 0) { return p; } }
        return parts.get(parts.size() - 1);
    }

    boolean busy() { return !jobs.isEmpty(); }

    // ---------- relogio (a cada segundo) ----------

    void second() {
        if (!plugin.data.isLoaded()) { return; }
        long now = System.currentTimeMillis();
        Map<String, Mine> mines = plugin.data.read(s -> new LinkedHashMap<>(s.mines));
        for (Map.Entry<String, Mine> e : mines.entrySet()) {
            Mine m = e.getValue();
            if (!m.placed()) { continue; }
            Level level = plugin.getServer().getLevelByName(m.world);
            if (level == null) { continue; }
            Runtime rt = rt(e.getKey());
            if (rt.resetting) { continue; }
            if (!rt.filled) { start(e.getKey(), m, level, false); continue; }
            int pct = (int) (rt.mined * 100L / Math.max(1, m.volume()));
            if (rt.scheduledAt == 0) {
                if (pct >= m.percent) { rt.scheduledAt = now + 10_000; warn(m, level); }
                else if (now >= rt.nextAt - 10_000) { rt.scheduledAt = Math.max(rt.nextAt, now); warn(m, level); }
            }
            if (rt.scheduledAt > 0 && now >= rt.scheduledAt) { start(e.getKey(), m, level, true); }
        }
    }

    private void warn(Mine m, Level level) {
        for (Player p : level.getPlayers().values()) { if (near(m, p)) { p.sendMessage(GoldRankup.PREFIX + M.t("mina_regenera_em_10s", m.name)); } }
    }

    private static boolean near(Mine m, Player p) {
        return p.x >= m.x1 - 12 && p.x <= m.x2 + 13 && p.z >= m.z1 - 12 && p.z <= m.z2 + 13 && p.y >= m.y1 - 4 && p.y <= m.y2 + 20;
    }

    /** Comeca a regenerar: so com todos os chunks da mina carregados; quem esta dentro vai para a borda. */
    boolean start(String id, Mine m, Level level, boolean announce) {
        for (int cx = m.x1 >> 4; cx <= m.x2 >> 4; cx++) { for (int cz = m.z1 >> 4; cz <= m.z2 >> 4; cz++) { if (!level.isChunkLoaded(cx, cz)) { return false; } } }
        Runtime rt = rt(id);
        rt.resetting = true;
        for (Player p : level.getPlayers().values()) {
            if (p.x >= m.x1 - 1 && p.x <= m.x2 + 2 && p.z >= m.z1 - 1 && p.z <= m.z2 + 2 && p.y >= m.y1 - 2 && p.y <= m.y2 + 3) {
                p.teleport(new Location(m.tx, m.ty, m.tz, m.tyaw, 0, level));
                p.sendMessage(GoldRankup.PREFIX + M.t("voce_foi_para_o_topo"));
            }
        }
        jobs.add(new Job(level, m.x1, m.y1, m.z1, m.x2, m.y2, m.z2, new ArrayList<>(m.blocks), false, () -> {
            rt.resetting = false; rt.filled = true; rt.mined = 0; rt.scheduledAt = 0;
            rt.nextAt = System.currentTimeMillis() + m.minutes * 60_000L;
            if (announce) { for (Player p : level.getPlayers().values()) { if (near(m, p)) { p.sendMessage(GoldRankup.PREFIX + M.t("mina_regenerada", m.name)); } } }
        }));
        return true;
    }

    /** Admin: regenerar agora (pelo comando), mesmo antes do tempo. */
    boolean resetNow(String id) {
        Mine m = plugin.data.read(s -> s.mines.get(id));
        if (m == null || !m.placed()) { return false; }
        Level level = plugin.getServer().getLevelByName(m.world);
        if (level == null) { plugin.getServer().loadLevel(m.world); level = plugin.getServer().getLevelByName(m.world); }
        if (level == null || rt(id).resetting) { return false; }
        for (int cx = m.x1 >> 4; cx <= m.x2 >> 4; cx++) { for (int cz = m.z1 >> 4; cz <= m.z2 >> 4; cz++) { level.loadChunk(cx, cz, true); } }
        return start(id, m, level, true);
    }

    // ---------- protecao e minerio ----------

    Map.Entry<String, Mine> at(Level level, int x, int y, int z) {
        String w = level.getFolderName();
        return plugin.data.read(s -> { for (Map.Entry<String, Mine> e : s.mines.entrySet()) { if (e.getValue().contains(w, x, y, z)) { return new AbstractMap.SimpleEntry<>(e.getKey(), e.getValue()); } } return null; });
    }

    boolean minesWorld(Level level) {
        String w = level.getFolderName();
        return plugin.data.isLoaded() && plugin.data.read(s -> { for (Mine m : s.mines.values()) { if (m.world.equals(w)) { return true; } } return false; });
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void breakBlock(BlockBreakEvent event) {
        Player p = event.getPlayer();
        Block b = event.getBlock();
        if (b.level == null || !minesWorld(b.level) || p.hasPermission("goldrankup.admin")) {
            if (b.level != null && minesWorld(b.level)) { count(b); }
            return;
        }
        Map.Entry<String, Mine> e = at(b.level, b.getFloorX(), b.getFloorY(), b.getFloorZ());
        if (e == null) { event.setCancelled(); p.sendPopup(M.t("so_dentro_das_minas")); return; }
        Runtime rt = rt(e.getKey());
        if (rt.resetting) { event.setCancelled(); p.sendPopup(M.t("mina_regenerando")); return; }
        if (!plugin.reached(p, e.getValue().rank)) { event.setCancelled(); p.sendPopup(M.t("mina_precisa_do_rank", plugin.rankTag(e.getValue().rank))); return; }
        rt.mined++;
        fortune(event);
    }

    private void count(Block b) {
        Map.Entry<String, Mine> e = at(b.level, b.getFloorX(), b.getFloorY(), b.getFloorZ());
        if (e != null) { rt(e.getKey()).mined++; }
    }

    /** Fortuna: minerios que caem como item (carvao, lapis, redstone, diamante, esmeralda) rendem mais. */
    static void fortune(BlockBreakEvent event) {
        Item tool = event.getItem();
        if (tool == null || !tool.hasEnchantments()) { return; }
        Enchantment f = tool.getEnchantment(Enchantment.ID_FORTUNE_DIGGING);
        int id = event.getBlock().getId();
        if (f == null || f.getLevel() <= 0 || !(id == 16 || id == 21 || id == 73 || id == 74 || id == 56 || id == 129 || id == 153)) { return; }
        int extra = Math.max(0, ThreadLocalRandom.current().nextInt(f.getLevel() + 2) - 1);
        if (extra == 0) { return; }
        Item[] drops = event.getDrops();
        for (Item d : drops) { d.setCount(d.getCount() * (extra + 1)); }
        event.setDrops(drops);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void place(BlockPlaceEvent event) {
        if (minesWorld(event.getBlock().level) && !event.getPlayer().hasPermission("goldrankup.admin")) { event.setCancelled(); event.getPlayer().sendPopup(M.t("nao_pode_colocar_nas_minas")); }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void bucket(PlayerBucketEmptyEvent event) {
        if (minesWorld(event.getPlayer().level) && !event.getPlayer().hasPermission("goldrankup.admin")) { event.setCancelled(); }
    }

    // ---------- mundo das minas ----------

    /**
    * Monta o mundo das minas: patio no spawn, avenida para leste e as minas dos dois lados (15x15, 20 de fundo),
    * com parede de tijolo de pedra, fundo de bedrock e borda no bloco da mina. Ja grava caixa, borda e chegada.
    */
    List<String> layout(String world, Level level, List<String> ids) {
        List<String> report = new ArrayList<>();
        Part brick = new Part(98, 0, 1), air = new Part(0, 0, 1), bedrock = new Part(7, 0, 1), glow = new Part(89, 0, 1);
        fixed(level, -12, 64, -12, 12, 64, 12, brick);
        fixed(level, -12, 63, -12, 12, 63, 12, brick);
        for (int[] c : new int[][] {{-12, -12}, {12, -12}, {-12, 12}, {12, 12}}) { fixed(level, c[0], 65, c[1], c[0], 66, c[1], glow); }
        int count = ids.size();
        int cols = (count + 1) / 2;
        int lastX = 27 + (cols - 1) * 28 + 9;
        fixed(level, 13, 64, -2, lastX, 64, 2, brick);
        fixed(level, 13, 63, -2, lastX, 63, 2, brick);
        for (int i = 0; i < count; i++) {
            String id = ids.get(i);
            int side = i % 2 == 0 ? -1 : 1, cx = 27 + (i / 2) * 28, cz = side * 13;
            Mine m = plugin.data.read(s -> s.mines.get(id));
            Part rim = new Part(m == null ? 98 : Math.min(255, m.iconId), m == null ? 0 : m.iconMeta, 1);
            fixed(level, cx - 8, 43, cz - 8, cx + 8, 64, cz + 8, brick);
            fixed(level, cx - 8, 64, cz - 8, cx + 8, 64, cz + 8, rim);
            fixed(level, cx - 7, 44, cz - 7, cx + 7, 64, cz + 7, air);
            fixed(level, cx - 7, 43, cz - 7, cx + 7, 43, cz + 7, bedrock);
            fixed(level, cx - 1, 64, side < 0 ? -4 : 3, cx + 1, 64, side < 0 ? -3 : 4, brick);
            int rimZ = side < 0 ? cz + 8 : cz - 8;
            fixed(level, cx - 8, 65, rimZ, cx - 8, 66, rimZ, glow);
            fixed(level, cx + 8, 65, rimZ, cx + 8, 66, rimZ, glow);
            final int fcx = cx, fcz = cz, frim = rimZ, fside = side;
            plugin.data.change(s -> {
                Mine x = s.mines.get(id);
                if (x == null) { return; }
                x.world = world; x.x1 = fcx - 7; x.x2 = fcx + 7; x.z1 = fcz - 7; x.z2 = fcz + 7; x.y1 = 44; x.y2 = 63;
                x.tx = fcx + 0.5; x.ty = 65; x.tz = frim + 0.5; x.tyaw = fside < 0 ? 180 : 0;
            });
            runtime.remove(id);
            report.add(id + " " + (cx - 7) + "," + (cz - 7) + " .. " + (cx + 7) + "," + (cz + 7));
        }
        level.setSpawnLocation(new Vector3(0.5, 65, 0.5));
        return report;
    }

    private void fixed(Level level, int x1, int y1, int z1, int x2, int y2, int z2, Part block) {
        jobs.add(new Job(level, x1, y1, z1, x2, y2, z2, Collections.singletonList(block), true, null));
    }
}
