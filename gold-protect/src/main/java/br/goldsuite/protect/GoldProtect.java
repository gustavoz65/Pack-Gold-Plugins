package br.goldsuite.protect;

import br.goldsuite.api.*;
import cn.nukkit.Player;
import cn.nukkit.block.Block;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.block.BlockBreakEvent;
import cn.nukkit.event.block.BlockPlaceEvent;
import cn.nukkit.event.entity.EntityDamageByEntityEvent;
import cn.nukkit.event.entity.EntityDamageEvent;
import cn.nukkit.event.player.PlayerInteractEvent;
import cn.nukkit.event.player.PlayerQuitEvent;
import cn.nukkit.level.Level;
import cn.nukkit.plugin.PluginBase;
import cn.nukkit.potion.Effect;
import cn.nukkit.scheduler.PluginTask;
import java.util.*;

/** Areas protegidas, protecao por mundo, efeitos por area, anti-void e alerta de x-ray. */
public final class GoldProtect extends PluginBase implements Listener {
    static String PREFIX = "§2[§aProtecao§2]§f ";
    private static final Set<Integer> INTERACTIVE = new HashSet<>(Arrays.asList(54, 61, 62, 64, 71, 96, 107, 117, 130, 145, 146, 183, 184, 185, 186, 187, 193, 194, 195, 196, 197, 69, 77, 143, 25, 116, 58, 138, 154));
    private static final Set<Integer> RARE_ORES = new HashSet<>(Arrays.asList(56, 129, 14));

    private PersistentState<ProtectState> data;
    /** Indice mundo:chunkX:chunkZ -> areas que tocam o chunk. Refeito so quando as areas mudam. */
    private Map<String, List<Map.Entry<String, ProtectState.Area>>> index = new HashMap<>();
    private final Map<Player, int[][]> selection = new IdentityHashMap<>();
    private final Map<Player, String> insideArea = new IdentityHashMap<>();
    /** {pedras, minerios raros, ultimo alerta em segundos} na janela atual. */
    private final Map<Player, long[]> mining = new IdentityHashMap<>();

    @Override public void onEnable() {
        Settings.prefix(this, PREFIX, v -> PREFIX = v);
        CoreService core = getServer().getServiceManager().load(CoreService.class);
        if (core == null) { throw new IllegalStateException("GoldCore precisa estar ativo antes do GoldProtect."); }
        data = new PersistentState<>(core.getStore(), "goldprotect", ProtectState.class, ProtectState::new, ProtectState::validate);
        data.ready().whenComplete((v, e) -> {
            if (e != null) { getLogger().error("Falha ao carregar protecoes.", e); getServer().getPluginManager().disablePlugin(this); return; }
            reindex();
        });
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getScheduler().scheduleRepeatingTask(new PluginTask<GoldProtect>(this) { @Override public void onRun(int tick) { areaTick(); } }, 40);
        getServer().getScheduler().scheduleRepeatingTask(new PluginTask<GoldProtect>(this) { @Override public void onRun(int tick) { mining.clear(); } }, 20 * 60 * 10);
    }

    @Override public void onDisable() { selection.clear(); insideArea.clear(); mining.clear(); index.clear(); if (data != null) { data.close(); } }

    private void reindex() {
        Map<String, List<Map.Entry<String, ProtectState.Area>>> next = new HashMap<>();
        data.read(s -> {
            for (Map.Entry<String, ProtectState.Area> e : s.areas.entrySet()) {
                ProtectState.Area a = e.getValue();
                for (int cx = a.minX >> 4; cx <= a.maxX >> 4; cx++) {
                    for (int cz = a.minZ >> 4; cz <= a.maxZ >> 4; cz++) { next.computeIfAbsent(a.world + ":" + cx + ":" + cz, k -> new ArrayList<>(2)).add(e); }
                }
            }
            return null;
        });
        index = next;
    }

    /** Area mais especifica (menor volume) que contem o ponto, ou null. */
    private Map.Entry<String, ProtectState.Area> areaAt(Level level, int x, int y, int z) {
        List<Map.Entry<String, ProtectState.Area>> list = index.get(level.getFolderName() + ":" + (x >> 4) + ":" + (z >> 4));
        if (list == null) { return null; }
        Map.Entry<String, ProtectState.Area> best = null;
        for (Map.Entry<String, ProtectState.Area> e : list) {
            if (e.getValue().contains(level.getFolderName(), x, y, z) && (best == null || e.getValue().volume() < best.getValue().volume())) { best = e; }
        }
        return best;
    }

    /** true quando a acao e negada para o jogador neste ponto. */
    private boolean denied(Player p, String flag, Level level, int x, int y, int z) {
        if (!data.isLoaded() || p.hasPermission("goldprotect.admin")) { return false; }
        Map.Entry<String, ProtectState.Area> e = areaAt(level, x, y, z);
        if (e != null) {
            ProtectState.Area a = e.getValue();
            String name = p.getName().toLowerCase(Locale.ROOT);
            if (name.equals(a.owner) || a.members.contains(name)) { return false; }
            return a.deny.contains(flag);
        }
        Set<String> world = data.read(s -> s.worlds.get(level.getFolderName()));
        return world != null && world.contains(flag);
    }

    // ---------- eventos ----------

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void breakBlock(BlockBreakEvent event) {
        Block b = event.getBlock();
        Player p = event.getPlayer();
        if (denied(p, "construir", b.level, b.getFloorX(), b.getFloorY(), b.getFloorZ())) { event.setCancelled(); p.sendPopup("§cArea protegida"); return; }
        watchMining(p, b.getId());
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void place(BlockPlaceEvent event) {
        Block b = event.getBlock();
        if (denied(event.getPlayer(), "construir", b.level, b.getFloorX(), b.getFloorY(), b.getFloorZ())) { event.setCancelled(); event.getPlayer().sendPopup("§cArea protegida"); }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void interact(PlayerInteractEvent event) {
        Block b = event.getBlock();
        if (b == null || b.level == null || event.getAction() == PlayerInteractEvent.LEFT_CLICK_AIR || event.getAction() == PlayerInteractEvent.RIGHT_CLICK_AIR) { return; }
        Player p = event.getPlayer();
        boolean usesBlock = INTERACTIVE.contains(b.getId());
        // Colocar baldes, isqueiro e afins num bloco tambem e construir.
        int item = p.getInventory().getItemInHand().getId();
        boolean builds = item == 259 || item == 325 || item == 351 && p.getInventory().getItemInHand().getDamage() == 15;
        if ((usesBlock && denied(p, "interagir", b.level, b.getFloorX(), b.getFloorY(), b.getFloorZ())) || (builds && denied(p, "construir", b.level, b.getFloorX(), b.getFloorY(), b.getFloorZ()))) {
            event.setCancelled();
            p.sendPopup("§cArea protegida");
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void damage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player) || !data.isLoaded()) { return; }
        Player victim = (Player) event.getEntity();
        if (event.getCause() == EntityDamageEvent.CAUSE_VOID && data.read(s -> s.antiVoid.contains(victim.level.getFolderName()))) {
            event.setCancelled();
            victim.teleport(victim.level.getSafeSpawn());
            victim.sendPopup("§eVoce foi salvo do void");
            return;
        }
        int x = victim.getFloorX(), y = victim.getFloorY(), z = victim.getFloorZ();
        if (event instanceof EntityDamageByEntityEvent && ((EntityDamageByEntityEvent) event).getDamager() instanceof Player) {
            Player attacker = (Player) ((EntityDamageByEntityEvent) event).getDamager();
            if (pvpBlocked(victim.level, x, y, z) || pvpBlocked(attacker.level, attacker.getFloorX(), attacker.getFloorY(), attacker.getFloorZ())) { event.setCancelled(); attacker.sendPopup("§cPvP desativado aqui"); return; }
        }
        Map.Entry<String, ProtectState.Area> e = areaAt(victim.level, x, y, z);
        if (e != null && e.getValue().deny.contains("dano")) { event.setCancelled(); }
    }

    /** PvP vale para todos, inclusive donos e admins: e regra do lugar, nao de quem constroi. */
    private boolean pvpBlocked(Level level, int x, int y, int z) {
        Map.Entry<String, ProtectState.Area> e = areaAt(level, x, y, z);
        if (e != null) { return e.getValue().deny.contains("pvp"); }
        Set<String> world = data.read(s -> s.worlds.get(level.getFolderName()));
        return world != null && world.contains("pvp");
    }

    @EventHandler(priority = EventPriority.MONITOR) public void quit(PlayerQuitEvent event) { selection.remove(event.getPlayer()); insideArea.remove(event.getPlayer()); mining.remove(event.getPlayer()); }

    /** A cada 2 s: mensagem de entrada e efeitos de area, so para quem esta numa area. */
    private void areaTick() {
        if (!data.isLoaded() || index.isEmpty()) { return; }
        for (Player p : getServer().getOnlinePlayers().values()) {
            if (!p.spawned) { continue; }
            Map.Entry<String, ProtectState.Area> e = areaAt(p.level, p.getFloorX(), p.getFloorY(), p.getFloorZ());
            String now = e == null ? null : e.getKey(), before = insideArea.get(p);
            if (!Objects.equals(now, before)) {
                if (now == null) { insideArea.remove(p); } else { insideArea.put(p, now); if (!e.getValue().greeting.isEmpty()) { p.sendTip(e.getValue().greeting.replace('&', '§')); } }
            }
            if (e != null && e.getValue().effect.length == 2) {
                try { p.addEffect(Effect.getEffect(e.getValue().effect[0]).setAmplifier(e.getValue().effect[1]).setDuration(60).setVisible(false)); }
                catch (RuntimeException unknown) { /* id sem efeito nesta versao: ignora */ }
            }
        }
    }

    /** Muito minerio raro para pouca pedra numa janela de 10 min: avisa a staff (uma vez por janela). */
    private void watchMining(Player p, int id) {
        if (id != 1 && !RARE_ORES.contains(id)) { return; }
        long[] m = mining.computeIfAbsent(p, k -> new long[3]);
        if (id == 1) { m[0]++; return; }
        m[1]++;
        if (m[2] == 0 && m[1] >= 8 && m[1] * 100 > Math.max(1, m[0]) * 8) {
            m[2] = 1;
            String alert = PREFIX + "§cX-ray suspeito: §f" + p.getName() + " §7(" + m[1] + " minerios raros para " + m[0] + " pedras em 10 min)";
            getLogger().warning(alert);
            for (Player staff : getServer().getOnlinePlayers().values()) { if (staff.hasPermission("goldprotect.admin")) { staff.sendMessage(alert); } }
        }
    }

    // ---------- comandos ----------

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        try {
            if (command.getName().equals("antivoid")) {
                String world = Destination.world(args.length > 0 ? args[0] : (sender instanceof Player ? ((Player) sender).level.getFolderName() : "")).target;
                boolean[] on = new boolean[1];
                change(sender, s -> { on[0] = s.antiVoid.add(world); if (!on[0]) { s.antiVoid.remove(world); } }, () -> PREFIX + "Anti-void " + (on[0] ? "ligado" : "desligado") + " em " + world + ".");
                return true;
            }
            if (command.getName().equals("mundoprotecao")) {
                if (args.length < 3) { return false; }
                String world = Destination.world(args[0]).target, flag = ProtectState.flag(args[1]);
                boolean deny = args[2].equalsIgnoreCase("off") || args[2].equalsIgnoreCase("nao");
                change(sender, s -> { Set<String> f = s.worlds.computeIfAbsent(world, k -> new TreeSet<>()); if (deny) { f.add(flag); } else { f.remove(flag); } if (f.isEmpty()) { s.worlds.remove(world); } },
                        () -> PREFIX + flag + (deny ? " bloqueado" : " liberado") + " em " + world + ".");
                return true;
            }
            return area(sender, args);
        } catch (RuntimeException e) { sender.sendMessage(PREFIX + "§c" + root(e)); return true; }
    }

    private boolean area(CommandSender sender, String[] args) {
        String sub = args.length == 0 ? "ajuda" : args[0].toLowerCase(Locale.ROOT);
        Player p = sender instanceof Player ? (Player) sender : null;
        switch (sub) {
            case "pos1": case "pos2": {
                if (p == null) { return true; }
                int[][] sel = selection.computeIfAbsent(p, k -> new int[2][]);
                sel[sub.equals("pos1") ? 0 : 1] = new int[] {p.getFloorX(), p.getFloorY(), p.getFloorZ()};
                p.sendMessage(PREFIX + "Ponto " + sub.charAt(3) + " marcado em " + p.getFloorX() + " " + p.getFloorY() + " " + p.getFloorZ() + ".");
                return true;
            }
            case "criar": {
                if (p == null || args.length < 2) { sender.sendMessage(PREFIX + "Uso: /area criar <nome> (depois de /area pos1 e pos2)"); return true; }
                int[][] sel = selection.get(p);
                if (sel == null || sel[0] == null || sel[1] == null) { throw new IllegalArgumentException("Marque /area pos1 e /area pos2 antes."); }
                String name = ProtectState.name(args[1]);
                ProtectState.Area a = ProtectState.area(p.level.getFolderName(), sel[0][0], sel[0][1], sel[0][2], sel[1][0], sel[1][1], sel[1][2]);
                a.owner = p.getName().toLowerCase(Locale.ROOT);
                change(sender, s -> { if (s.areas.size() >= ProtectState.MAX_AREAS) { throw new IllegalArgumentException("Limite de areas."); } if (s.areas.putIfAbsent(name, a) != null) { throw new IllegalArgumentException("Ja existe uma area com esse nome."); } },
                        () -> PREFIX + "Area " + name + " criada (" + a.volume() + " blocos). Bloqueado para visitantes: construir e interagir.");
                return true;
            }
            case "flag": {
                if (args.length < 4) { sender.sendMessage(PREFIX + "Uso: /area flag <area> <construir|pvp|interagir|dano> <on|off>  (off = bloqueado)"); return true; }
                String name = ProtectState.name(args[1]), flag = ProtectState.flag(args[2]);
                boolean deny = args[3].equalsIgnoreCase("off") || args[3].equalsIgnoreCase("nao");
                change(sender, s -> { ProtectState.Area a = require(s, name); if (deny) { a.deny.add(flag); } else { a.deny.remove(flag); } }, () -> PREFIX + flag + (deny ? " bloqueado" : " liberado") + " em " + name + ".");
                return true;
            }
            case "membro": {
                if (args.length < 4) { sender.sendMessage(PREFIX + "Uso: /area membro <area> <adicionar|remover> <jogador>"); return true; }
                String name = ProtectState.name(args[1]), member = args[3].toLowerCase(Locale.ROOT);
                if (!member.matches("[a-z0-9_]{3,16}")) { throw new IllegalArgumentException("Jogador invalido."); }
                boolean add = args[2].equalsIgnoreCase("adicionar");
                change(sender, s -> { ProtectState.Area a = require(s, name); if (add) { if (a.members.size() >= 64) { throw new IllegalArgumentException("Limite de membros."); } a.members.add(member); } else { a.members.remove(member); } },
                        () -> PREFIX + member + (add ? " adicionado a " : " removido de ") + name + ".");
                return true;
            }
            case "efeito": {
                if (args.length < 3) { sender.sendMessage(PREFIX + "Uso: /area efeito <area> <id 1-23|nenhum> [nivel 0-4]"); return true; }
                String name = ProtectState.name(args[1]);
                int[] effect = args[2].equalsIgnoreCase("nenhum") ? new int[0] : new int[] {Integer.parseInt(args[2]), args.length > 3 ? Integer.parseInt(args[3]) : 0};
                change(sender, s -> require(s, name).effect = effect, () -> PREFIX + "Efeito de " + name + " atualizado.");
                return true;
            }
            case "mensagem": {
                if (args.length < 2) { return false; }
                String name = ProtectState.name(args[1]), text = args.length > 2 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : "";
                if (text.length() > 100) { throw new IllegalArgumentException("Mensagem ate 100 caracteres."); }
                change(sender, s -> require(s, name).greeting = text, () -> PREFIX + "Mensagem de entrada atualizada.");
                return true;
            }
            case "remover": {
                if (args.length < 2) { return false; }
                String name = ProtectState.name(args[1]);
                change(sender, s -> { if (s.areas.remove(name) == null) { throw new IllegalArgumentException("Area inexistente."); } }, () -> PREFIX + "Area removida.");
                return true;
            }
            case "info": {
                if (p == null) { return true; }
                Map.Entry<String, ProtectState.Area> e = areaAt(p.level, p.getFloorX(), p.getFloorY(), p.getFloorZ());
                p.sendMessage(PREFIX + (e == null ? "Nenhuma area aqui." : e.getKey() + " | dono: " + e.getValue().owner + " | bloqueado: " + e.getValue().deny + " | membros: " + e.getValue().members));
                return true;
            }
            case "lista": sender.sendMessage(PREFIX + data.read(s -> s.areas.isEmpty() ? "Nenhuma area." : String.join(", ", s.areas.keySet()))); return true;
            default:
                sender.sendMessage(PREFIX + "/area pos1, pos2, criar <nome>, flag <area> <flag> <on|off>, membro <area> adicionar|remover <jogador>, efeito <area> <id> [nivel], mensagem <area> <texto>, info, lista, remover <area>");
                sender.sendMessage(PREFIX + "/mundoprotecao <mundo> <flag> <on|off>, /antivoid [mundo]");
                return true;
        }
    }

    private static ProtectState.Area require(ProtectState s, String name) {
        ProtectState.Area a = s.areas.get(name);
        if (a == null) { throw new IllegalArgumentException("Area inexistente."); }
        return a;
    }

    private void change(CommandSender sender, java.util.function.Consumer<ProtectState> mutation, java.util.function.Supplier<String> message) {
        data.change(mutation).whenComplete((v, e) -> {
            if (!isEnabled()) { return; }
            if (e != null) { sender.sendMessage(PREFIX + "§c" + root(e)); return; }
            reindex();
            sender.sendMessage(message.get());
        });
    }

    static String root(Throwable e) { while (e.getCause() != null) { e = e.getCause(); } return e.getMessage(); }
}
