package br.goldsuite.build;

import br.goldsuite.api.MenuService;
import br.goldsuite.api.Settings;
import cn.nukkit.Player;
import cn.nukkit.block.Block;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.block.BlockBreakEvent;
import cn.nukkit.event.player.PlayerInteractEvent;
import cn.nukkit.event.player.PlayerItemHeldEvent;
import cn.nukkit.event.player.PlayerQuitEvent;
import cn.nukkit.item.Item;
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
    static String PREFIX = M.t("we");
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
        M.B.attach(this);
        Settings.prefix(this, PREFIX, v -> PREFIX = v); getServer().getPluginManager().registerEvents(this, this); }
    @Override public void onDisable() { queue.clear(); selection.clear(); undo.clear(); if (task != null) { task.cancel(); } }

    @EventHandler(priority = EventPriority.MONITOR) public void quit(PlayerQuitEvent e) { selection.remove(e.getPlayer()); lastMark.remove(e.getPlayer()); lastHeld.remove(e.getPlayer()); }

    // ---------- menu de ferramentas na hotbar (pensado para o celular) ----------

    private final Map<Player, Integer> lastHeld = new IdentityHashMap<>();

    private boolean toolItem(Player p, Item item) {
        return item != null && item.getId() == Item.BLAZE_ROD && item.hasCustomName() && item.getCustomName().equals(M.t("menu_item_nome")) && p.hasPermission("goldbuild.admin");
    }

    /** Selecionar o item do menu na hotbar abre as ferramentas e a selecao volta ao slot anterior. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void held(PlayerItemHeldEvent e) {
        Player p = e.getPlayer();
        if (!toolItem(p, e.getItem())) { lastHeld.put(p, p.getInventory().getHeldItemIndex()); return; }
        Integer before = lastHeld.get(p);
        int slot = p.getInventory().getHeldItemIndex();
        int back = before == null || before == slot ? (slot == 0 ? 1 : 0) : before;
        openTools(p);
        getServer().getScheduler().scheduleDelayedTask(new PluginTask<GoldBuild>(this) { @Override public void onRun(int t) {
            if (!p.isOnline() || !toolItem(p, p.getInventory().getItemInHand())) { return; }
            p.getInventory().setHeldItemIndex(back);
            p.getInventory().sendHeldItem(p);
        } }, 2);
    }

    /** Tocar no ar ou num bloco com o item do menu tambem abre. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void toolTap(PlayerInteractEvent e) {
        if (toolItem(e.getPlayer(), e.getItem()) && (e.getAction() == PlayerInteractEvent.RIGHT_CLICK_AIR || e.getAction() == PlayerInteractEvent.RIGHT_CLICK_BLOCK)) { e.setCancelled(); openTools(e.getPlayer()); }
    }

    private MenuService menus() { return getServer().getServiceManager().load(MenuService.class); }

    private static Item icon(int id, int meta, String name) { Item i = Item.get(id, meta, 1); i.setCustomName(name); return i; }

    void openTools(Player p) {
        MenuService menus = menus();
        if (menus == null) { p.sendMessage(PREFIX + M.t("we_pos1_pos2_set_bloco_walls")); return; }
        int[][] sel = selection.get(p);
        String info = sel == null || sel[0] == null || sel[1] == null ? M.t("menu_sem_selecao") : M.t("menu_selecao", volume(sel[0], sel[1]));
        Map<Integer, Item> icons = new HashMap<>();
        icons.put(4, icon(340, 0, info));
        icons.put(10, icon(1, 0, M.t("menu_preencher")));
        icons.put(11, icon(45, 0, M.t("menu_paredes")));
        icons.put(12, icon(35, 14, M.t("menu_trocar")));
        icons.put(13, icon(341, 0, M.t("menu_esfera")));
        icons.put(14, icon(46, 0, M.t("menu_limpar")));
        icons.put(16, icon(331, 0, M.t("menu_desfazer")));
        icons.put(20, icon(Item.WOODEN_AXE, 0, M.t("menu_varinha")));
        icons.put(21, icon(2, 0, M.t("menu_pos1_aqui")));
        icons.put(23, icon(3, 0, M.t("menu_pos2_aqui")));
        menus.open(p, M.t("menu_titulo"), icons, (who, slot) -> {
            try {
                switch (slot) {
                    case 10: selected(who); pickBlock(who, M.t("menu_escolha_preencher"), false, to -> fill(who, to, false)); break;
                    case 11: selected(who); pickBlock(who, M.t("menu_escolha_paredes"), false, to -> fill(who, to, true)); break;
                    case 12: selected(who); pickBlock(who, M.t("menu_escolha_trocar_de"), true, from -> pickBlock(who, M.t("menu_escolha_trocar_para"), true, to -> replace(who, from, to, true))); break;
                    case 13: pickRadius(who); break;
                    case 14: fill(who, new int[] {0, 0}, false); break;
                    case 16: undo(who); break;
                    case 20: giveTools(who); break;
                    case 21: lastMark.remove(who); mark(who, 0, who.getFloorX(), who.getFloorY() - 1, who.getFloorZ()); break;
                    case 23: lastMark.remove(who); mark(who, 1, who.getFloorX(), who.getFloorY() - 1, who.getFloorZ()); break;
                    default:
                }
            } catch (RuntimeException ex) { who.sendMessage(PREFIX + "\u00a7c" + ex.getMessage()); }
        });
    }

    /** Os blocos que o jogador tem no inventario viram botoes; "ar" aparece quando a operacao aceita. */
    private void pickBlock(Player p, String title, boolean allowAir, java.util.function.Consumer<int[]> then) {
        Map<Integer, Item> icons = new HashMap<>();
        List<int[]> blocks = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Item it : p.getInventory().getContents().values()) {
            if (it == null || it.getId() <= 0 || it.getId() > 255 || blocks.size() >= 26) { continue; }
            int meta = it.getDamage() & 15;
            if (Block.list != null && Block.list[it.getId()] == null) { continue; }
            if (seen.add(it.getId() + ":" + meta)) { blocks.add(new int[] {it.getId(), meta}); icons.put(blocks.size() - 1, icon(it.getId(), meta, "\u00a7f" + it.getName() + " \u00a77(" + it.getId() + (meta != 0 ? ":" + meta : "") + ")")); }
        }
        if (allowAir) { icons.put(26, icon(20, 0, M.t("menu_ar"))); }
        if (blocks.isEmpty() && !allowAir) { p.sendMessage(PREFIX + M.t("menu_sem_blocos")); return; }
        menus().open(p, title, icons, (who, slot) -> {
            int[] chosen = slot == 26 && allowAir ? new int[] {0, 0} : slot < blocks.size() ? blocks.get(slot) : null;
            if (chosen == null) { return; }
            getServer().getScheduler().scheduleDelayedTask(new PluginTask<GoldBuild>(this) { @Override public void onRun(int t) {
                if (!who.isOnline()) { return; }
                try { then.accept(chosen); } catch (RuntimeException ex) { who.sendMessage(PREFIX + "\u00a7c" + ex.getMessage()); }
            } }, 2);
        });
    }

    private void pickRadius(Player p) {
        int[] radii = {2, 3, 5, 8, 12, 16, 20, 25};
        Map<Integer, Item> icons = new HashMap<>();
        for (int i = 0; i < radii.length; i++) { Item ball = icon(341, 0, M.t("menu_raio", radii[i])); ball.setCount(radii[i]); icons.put(9 + i, ball); }
        menus().open(p, M.t("menu_titulo_raio"), icons, (who, slot) -> {
            int i = slot - 9;
            if (i < 0 || i >= radii.length) { return; }
            int r = radii[i];
            getServer().getScheduler().scheduleDelayedTask(new PluginTask<GoldBuild>(this) { @Override public void onRun(int t) {
                if (who.isOnline()) { pickBlock(who, M.t("menu_escolha_esfera", r), false, to -> sphereAt(who, r, to)); }
            } }, 2);
        });
    }

    private void giveTools(Player p) {
        Item axe = Item.get(Item.WOODEN_AXE, 0, 1);
        axe.setCustomName(M.t("varinha_nome"));
        Item menu = Item.get(Item.BLAZE_ROD, 0, 1);
        menu.setCustomName(M.t("menu_item_nome"));
        for (Item it : new Item[] {axe, menu}) { if (!p.getInventory().contains(it)) { for (Item left : p.getInventory().addItem(it)) { p.level.dropItem(p, left); } } }
        p.sendMessage(PREFIX + M.t("varinha_recebida"));
    }

    // ---------- operacoes (usadas pelos comandos e pelo menu) ----------

    private void fill(Player p, int[] to, boolean walls) {
        int[][] sel = selected(p);
        enqueue(new Job(p.level, cuboid(sel[0], sel[1], walls), (id, meta, x, y, z) -> to, p));
    }

    private void replace(Player p, int[] from, int[] to, boolean anyMeta) {
        int[][] sel = selected(p);
        enqueue(new Job(p.level, cuboid(sel[0], sel[1], false), (id, meta, x, y, z) -> id == from[0] && (anyMeta || meta == from[1]) ? to : null, p));
    }

    private void sphereAt(Player p, int radius, int[] to) {
        if (radius < 1 || radius > 30) { throw new IllegalArgumentException(M.t("raio_entre_1_30")); }
        Vector3 target = target(p);
        enqueue(new Job(p.level, sphere(target.getFloorX(), target.getFloorY(), target.getFloorZ(), radius), (id, meta, x, y, z) -> to, p));
    }

    private void undo(Player p) {
        List<int[]> history = undo.remove(p.getName().toLowerCase(Locale.ROOT));
        if (history == null || history.isEmpty()) { throw new IllegalArgumentException(M.t("nada_para_desfazer")); }
        Map<String, int[]> before = new HashMap<>();
        for (int[] h : history) { before.put(h[0] + ":" + h[1] + ":" + h[2], new int[] {h[3], h[4]}); }
        Iterator<int[]> positions = history.stream().map(h -> new int[] {h[0], h[1], h[2]}).iterator();
        enqueue(new Job(p.level, positions, (id, meta, x, y, z) -> before.get(x + ":" + y + ":" + z), p));
    }

    // ---------- varinha (machado de madeira) ----------

    /** Ultimo ponto marcado pela varinha: {indice, x, y, z, tick}, para nao repetir a mensagem do mesmo toque. */
    private final Map<Player, int[]> lastMark = new IdentityHashMap<>();

    private static boolean wand(Player p, Item item) { return item != null && item.getId() == Item.WOODEN_AXE && p.hasPermission("goldbuild.admin"); }

    /** Quebrar (ou segurar no bloco, no celular) com a varinha marca o ponto 1, sem quebrar o bloco. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void wandBreak(BlockBreakEvent e) {
        if (!wand(e.getPlayer(), e.getItem())) { return; }
        e.setCancelled();
        mark(e.getPlayer(), 0, e.getBlock().getFloorX(), e.getBlock().getFloorY(), e.getBlock().getFloorZ());
    }

    /** Tocar no bloco com a varinha marca o ponto 2. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void wandTap(PlayerInteractEvent e) {
        Block b = e.getBlock();
        if (b == null || b.getId() == 0 || !wand(e.getPlayer(), e.getItem())) { return; }
        if (e.getAction() == PlayerInteractEvent.LEFT_CLICK_BLOCK) { e.setCancelled(); mark(e.getPlayer(), 0, b.getFloorX(), b.getFloorY(), b.getFloorZ()); }
        else if (e.getAction() == PlayerInteractEvent.RIGHT_CLICK_BLOCK) { e.setCancelled(); mark(e.getPlayer(), 1, b.getFloorX(), b.getFloorY(), b.getFloorZ()); }
    }

    private void mark(Player p, int index, int x, int y, int z) {
        int tick = getServer().getTick();
        int[] last = lastMark.get(p);
        int[][] sel = selection.computeIfAbsent(p, k -> new int[2][]);
        sel[index] = new int[] {x, y, z};
        if (last != null && last[0] == index && last[1] == x && last[2] == y && last[3] == z && tick - last[4] < 10) { return; }
        lastMark.put(p, new int[] {index, x, y, z, tick});
        p.sendMessage(PREFIX + M.t("ponto", index + 1, x, y, z, (sel[0] != null && sel[1] != null ? M.t("blocos", volume(sel[0], sel[1])) : "")));
    }

    static int[] block(String text) {
        String[] parts = text.split(":");
        try {
            int id = Integer.parseInt(parts[0]), meta = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            if (id < 0 || id > 255 || meta < 0 || meta > 15 || parts.length > 2 || (id != 0 && Block.list != null && Block.list[id] == null)) { throw new NumberFormatException(); }
            return new int[] {id, meta};
        } catch (NumberFormatException e) { throw new IllegalArgumentException(M.t("bloco_invalido_use_id_ou_id", text)); }
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
        job.owner.sendMessage(PREFIX + M.t("na_fila_ate_blocos_por_tick", queue.size(), PER_TICK));
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
                job.owner.sendMessage(PREFIX + M.t("blocos_alterados", job.changed, (job.undoable ? M.t("we_undo_desfaz") : M.t("grande_demais_para_desfazer"))));
            }
        }
        if (queue.isEmpty() && task != null) { task.cancel(); task = null; }
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) { sender.sendMessage(M.t("use_dentro_do_jogo")); return true; }
        Player p = (Player) sender;
        String sub = args.length == 0 ? "ajuda" : args[0].toLowerCase(Locale.ROOT);
        try {
            switch (sub) {
                case "pos1": case "pos2": {
                    lastMark.remove(p);
                    mark(p, sub.equals("pos1") ? 0 : 1, p.getFloorX(), p.getFloorY() - 1, p.getFloorZ());
                    return true;
                }
                case "varinha": case "wand": case "menu": giveTools(p); return true;
                case "set": case "walls": case "paredes": fill(p, block(arg(args, 1)), !sub.equals("set")); return true;
                case "replace": case "trocar": replace(p, block(arg(args, 1)), block(arg(args, 2)), !args[1].contains(":")); return true;
                case "esfera": case "sphere": sphereAt(p, Integer.parseInt(arg(args, 1)), block(arg(args, 2))); return true;
                case "undo": case "desfazer": undo(p); return true;
                default:
                    p.sendMessage(PREFIX + M.t("we_pos1_pos2_set_bloco_walls"));
                    return true;
            }
        } catch (NumberFormatException e) { p.sendMessage(PREFIX + M.t("numero_invalido")); return true; }
        catch (RuntimeException e) { p.sendMessage(PREFIX + "§c" + e.getMessage()); return true; }
    }

    private int[][] selected(Player p) {
        int[][] sel = selection.get(p);
        if (sel == null || sel[0] == null || sel[1] == null) { throw new IllegalArgumentException(M.t("marque_we_pos1_we_pos2_antes")); }
        if (volume(sel[0], sel[1]) > MAX_BLOCKS) { throw new IllegalArgumentException(M.t("selecao_maior_que_blocos", MAX_BLOCKS)); }
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
        throw new IllegalArgumentException(M.t("mire_num_bloco"));
    }

    private static String arg(String[] args, int i) { if (args.length <= i) { throw new IllegalArgumentException(M.t("faltam_argumentos_use_we")); } return args[i]; }
}
