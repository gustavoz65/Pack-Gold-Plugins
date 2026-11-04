package br.goldsuite.skywars;

import cn.nukkit.Player;
import cn.nukkit.block.Block;
import cn.nukkit.event.*;
import cn.nukkit.event.player.*;
import cn.nukkit.item.Item;
import cn.nukkit.level.Level;
import cn.nukkit.math.Vector3;
import cn.nukkit.scheduler.TaskHandler;
import java.util.*;

/** Ferramenta administrativa: selecao por clique e geracao apenas no editor. */
final class OreEditor implements Listener {
    private final GoldSkyWars plugin;
    private final Map<Player, Selection> selections = new IdentityHashMap<>();
    /** Um toque gera interacao + inicio de quebra no 0.15: ignora o segundo evento em 400 ms. */
    private final Map<Player, Long> lastClick = new IdentityHashMap<>();
    private Job job;
    private TaskHandler task;
    private static final class Selection {
        String world;
        final List<IslandScan> islands = new ArrayList<>();
        Selection(String world) { this.world = world; }
    }
    private static final class Job {
        Player player; GoldSkyWars.Game game; IslandScan scan; Level level;
        boolean generating; List<IslandScan> scans = new ArrayList<>(); int index;
        List<long[]> changes = new ArrayList<>();
    }
    OreEditor(GoldSkyWars plugin) { this.plugin = plugin; }
    void tool(Player p) {
        GoldSkyWars.Game g = editable(p);
        Selection s = selections.get(p);
        if (s == null || !s.world.equals(p.level.getFolderName())) { selections.put(p, new Selection(p.level.getFolderName())); }
        Item stick = Item.get(280, 0, 1);
        stick.setNamedTag(new cn.nukkit.nbt.tag.CompoundTag().putString("GoldOreTool", "1"));
        stick.setCustomName(M.t("minerios_ferramenta"));
        for (Item it : p.getInventory().getContents().values()) { if (isTool(it)) { p.sendMessage(M.t("minerios_instrucoes")); return; } }
        if (!p.getInventory().canAddItem(stick)) { throw new IllegalArgumentException(M.t("minerios_inventario_cheio")); }
        p.getInventory().addItem(stick); p.sendMessage(M.t("minerios_instrucoes"));
    }
    private boolean isTool(Item i) { return i != null && i.getId() == 280 && i.hasCompoundTag() && "1".equals(i.getNamedTag().getString("GoldOreTool")); }
    /** Sem ignoreCancelled: protecao de mundo nao pode travar a ferramenta do admin (o evento e cancelado aqui de qualquer jeito). */
    @EventHandler(priority = EventPriority.LOW)
    public void click(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        if (!isTool(e.getItem()) || !p.hasPermission("goldskywars.admin")) { return; }
        e.setCancelled();
        long now = System.currentTimeMillis();
        Long last = lastClick.put(p, now);
        if (last != null && now - last < 400) { return; }
        try {
            GoldSkyWars.Game g = editable(p);
            if (job != null) { throw new IllegalArgumentException(M.t("minerios_ocupado")); }
            Block block = e.getBlock();
            if (block == null || block.getId() == 0) { return; }
            Selection selection = selections.computeIfAbsent(p, k -> new Selection(g.arena().world));
            if (!selection.world.equals(g.arena().world)) { selection = new Selection(g.arena().world); selections.put(p, selection); }
            if (selection.islands.size() >= 24) { throw new IllegalArgumentException(M.t("minerios_limite")); }
            job = new Job(); job.player = p; job.game = g; job.level = p.level;
            job.scan = new IslandScan(block.getFloorX(), block.getFloorY(), block.getFloorZ());
            g.editing = true; start();
        } catch (RuntimeException error) { p.sendMessage(error.getMessage()); }
    }
    void clear(Player p) {
        if (job != null && job.player == p) { throw new IllegalArgumentException(M.t("minerios_ocupado")); }
        selections.remove(p); p.sendMessage(M.t("minerios_limpos"));
    }
    void generate(Player p) {
        GoldSkyWars.Game g = editable(p);
        if (job != null) { throw new IllegalArgumentException(M.t("minerios_ocupado")); }
        Selection s = selections.get(p);
        if (s == null || !s.world.equals(g.arena().world) || s.islands.isEmpty()) { throw new IllegalArgumentException(M.t("minerios_selecione")); }
        job = new Job(); job.player = p; job.game = g; job.level = p.level; job.generating = true;
        for (IslandScan old : s.islands) { job.scans.add(new IslandScan(old.sx, old.sy, old.sz)); }
        job.scan = job.scans.get(0); g.editing = true; start();
    }
    private GoldSkyWars.Game editable(Player p) {
        if (!p.hasPermission("goldskywars.admin")) { throw new IllegalArgumentException(M.t("minerios_sem_permissao")); }
        GoldSkyWars.Game g = plugin.gameAt(p.level);
        if (g == null || !g.waiting() || !g.players.isEmpty() || g.editing) { throw new IllegalArgumentException(M.t("minerios_editor_vazio")); }
        return g;
    }
    private void start() {
        task = plugin.getServer().getScheduler().scheduleRepeatingTask(new cn.nukkit.scheduler.PluginTask<GoldSkyWars>(plugin) {
            @Override public void onRun(int tick) { work(); }
        }, 1);
    }
    private void work() {
        Job j = job;
        if (j == null) { return; }
        try {
            if (!j.player.isOnline() || j.player.level != j.level) { throw new IllegalArgumentException(M.t("minerios_cancelado")); }
            j.scan.step((x,y,z) -> {
                if (!j.level.isChunkLoaded(x >> 4, z >> 4)) { throw new IllegalArgumentException(M.t("minerios_chunk")); }
                return j.level.getBlockIdAt(x, y, z);
            }, 1200);
            if (!j.scan.done) { return; }
            if (!j.generating) {
                Selection s = selections.get(j.player);
                for (IslandScan island : s.islands) { if (island.overlaps(j.scan)) { throw new IllegalArgumentException(M.t("minerios_repetida")); } }
                s.islands.add(j.scan);
                j.player.sendMessage(M.t("minerios_selecionada", s.islands.size(), j.scan.solid));
                stop(); return;
            }
            int[] ids = {56,15,16}, amounts = {4,5,6};
            int needed = 0; for (int i = 0; i < 3; i++) { needed += Math.max(0, amounts[i] - j.scan.ores[i]); }
            if (j.scan.candidates.size() < needed) { throw new IllegalArgumentException(M.t("minerios_sem_espaco")); }
            Collections.shuffle(j.scan.candidates);
            int at = 0;
            for (int type = 0; type < 3; type++) { for (int n = j.scan.ores[type]; n < amounts[type]; n++) { j.changes.add(new long[] {j.scan.candidates.get(at++), ids[type]}); } }
            if (++j.index < j.scans.size()) { j.scan = j.scans.get(j.index); return; }
            // Plano completo antes de tocar no mapa. No maximo 24 * 15 = 360 blocos.
            Set<Long> unique = new HashSet<>();
            for (long[] change : j.changes) {
                long pos = change[0];
                int id = j.level.getBlockIdAt(IslandScan.x(pos), IslandScan.y(pos), IslandScan.z(pos));
                if (!unique.add(pos) || (id != 1 && id != 3)) { throw new IllegalArgumentException(M.t("minerios_mapa_alterado")); }
            }
            for (long[] change : j.changes) {
                long pos = change[0]; j.level.setBlock(new Vector3(IslandScan.x(pos), IslandScan.y(pos), IslandScan.z(pos)), Block.get((int)change[1]), true, false);
            }
            j.level.save(true);
            j.player.sendMessage(M.t("minerios_gerados", j.scans.size(), j.changes.size()));
            stop();
            // Faz a copia padrao pelo mesmo fluxo seguro do editor.
            plugin.edit(j.player, j.game, "salvar", new String[] {"salvar"});
        } catch (RuntimeException error) { j.player.sendMessage(error.getMessage()); stop(); }
    }
    private void stop() {
        if (job != null) { job.game.editing = false; }
        job = null;
        if (task != null) { task.cancel(); task = null; }
    }
    /** No criativo o graveto quebraria o bloco da ilha na hora. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void breakWithTool(cn.nukkit.event.block.BlockBreakEvent e) { if (isTool(e.getItem()) && e.getPlayer().hasPermission("goldskywars.admin")) { e.setCancelled(); } }
    @EventHandler public void quit(PlayerQuitEvent e) { lastClick.remove(e.getPlayer()); selections.remove(e.getPlayer()); if (job != null && job.player == e.getPlayer()) { stop(); } }
    @EventHandler public void world(cn.nukkit.event.entity.EntityLevelChangeEvent e) { if (e.getEntity() instanceof Player) { selections.remove((Player)e.getEntity()); } }
    void close() { stop(); selections.clear(); lastClick.clear(); }
}

