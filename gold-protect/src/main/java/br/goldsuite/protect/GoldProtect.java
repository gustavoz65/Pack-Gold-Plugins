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
import cn.nukkit.event.player.PlayerFoodLevelChangeEvent;
import cn.nukkit.event.player.PlayerInteractEvent;
import cn.nukkit.event.player.PlayerInvalidMoveEvent;
import cn.nukkit.event.player.PlayerQuitEvent;
import cn.nukkit.level.Level;
import cn.nukkit.plugin.PluginBase;
import cn.nukkit.potion.Effect;
import cn.nukkit.scheduler.PluginTask;
import java.util.*;

/** Areas protegidas, protecao por mundo, efeitos por area, anti-void e alerta de x-ray. */
public final class GoldProtect extends PluginBase implements Listener {
    static String PREFIX = M.t("protecao");
    private static final Set<Integer> INTERACTIVE = new HashSet<>(Arrays.asList(54, 61, 62, 64, 71, 96, 107, 117, 130, 145, 146, 183, 184, 185, 186, 187, 193, 194, 195, 196, 197, 69, 77, 143, 25, 116, 58, 138, 154));
    private static final Set<Integer> RARE_ORES = new HashSet<>(Arrays.asList(56, 129, 14));

    private PersistentState<ProtectState> data;
    /** Indice mundo:chunkX:chunkZ -> areas que tocam o chunk. Refeito so quando as areas mudam. */
    private Map<String, List<Map.Entry<String, ProtectState.Area>>> index = new HashMap<>();
    private final Map<Player, int[][]> selection = new IdentityHashMap<>();
    private final Map<Player, String> insideArea = new IdentityHashMap<>();
    /** {pedras, minerios raros, ultimo alerta em segundos} na janela atual. */
    private final Map<Player, long[]> mining = new IdentityHashMap<>();

    private boolean noMinecart = true, noBoat = true, noFrameRiding = true, warnStaff;

    /** Anti-dupe (como o TintaDupe): subir em veiculo chega como toque na entidade; barrado antes do core montar. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void vehicle(cn.nukkit.event.server.DataPacketReceiveEvent e) {
        if (!(e.getPacket() instanceof cn.nukkit.network.protocol.InteractPacket) || (!noMinecart && !noBoat)) { return; }
        cn.nukkit.network.protocol.InteractPacket pk = (cn.nukkit.network.protocol.InteractPacket) e.getPacket();
        if (pk.action != cn.nukkit.network.protocol.InteractPacket.ACTION_RIGHT_CLICK) { return; }
        Player p = e.getPlayer();
        if (p.level == null || p.hasPermission("goldprotect.admin")) { return; }
        cn.nukkit.entity.Entity t = p.level.getEntity(pk.target);
        boolean cart = t instanceof cn.nukkit.entity.item.EntityMinecartEmpty || t instanceof cn.nukkit.entity.item.EntityMinecartChest || t instanceof cn.nukkit.entity.item.EntityMinecartTNT;
        boolean boat = t instanceof cn.nukkit.entity.item.EntityBoat;
        if ((cart && noMinecart) || (boat && noBoat)) {
            e.setCancelled();
            p.sendMessage(PREFIX + M.t("este_veiculo_esta_proibido_para_evitar"));
            if (warnStaff) { Settings.alert("Anti-dupe", M.t("tentou_subir_num", p.getName(), (cart ? M.t("carrinho") : M.t("barco")))); }
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void frameInVehicle(PlayerInteractEvent e) {
        Block b = e.getBlock();
        if (!noFrameRiding || b == null || b.getId() != 199 || e.getPlayer().riding == null || e.getPlayer().hasPermission("goldprotect.admin")) { return; }
        e.setCancelled();
        e.getPlayer().sendMessage(PREFIX + M.t("voce_nao_pode_usar_moldura_dentro"));
        if (warnStaff) { Settings.alert("Anti-dupe", M.t("tentou_usar_moldura_dentro_de_um", e.getPlayer().getName())); }
    }

    @Override public void onEnable() {
        M.B.attach(this);
        Settings.prefix(this, PREFIX, v -> PREFIX = v);
        CoreService core = getServer().getServiceManager().load(CoreService.class);
        if (core == null) { throw new IllegalStateException(M.t("goldcore_precisa_estar_ativo_antes_do")); }
        data = new PersistentState<>(core.getStore(), "goldprotect", ProtectState.class, ProtectState::new, ProtectState::validate);
        Settings.number(this, "antidupe.carrinho", 1, "1 = ninguem sobe em carrinho (dupe classico)", v -> noMinecart = v == 1);
        Settings.number(this, "antidupe.barco", 1, "1 = ninguem sobe em barco", v -> noBoat = v == 1);
        Settings.number(this, "antidupe.moldura_no_veiculo", 1, "1 = moldura nao pode ser usada de dentro de um veiculo", v -> noFrameRiding = v == 1);
        Settings.number(this, "antidupe.avisar_staff", 0, "1 = avisa a staff quando alguem tenta", v -> warnStaff = v == 1);
        data.ready().whenComplete((v, e) -> {
            if (e != null) { getLogger().error(M.t("falha_ao_carregar_protecoes"), e); getServer().getPluginManager().disablePlugin(this); return; }
            reindex();
        });
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getScheduler().scheduleRepeatingTask(new PluginTask<GoldProtect>(this) { @Override public void onRun(int tick) { areaTick(); feed(); } }, 40);
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
        if (denied(p, "construir", b.level, b.getFloorX(), b.getFloorY(), b.getFloorZ())) { event.setCancelled(); p.sendPopup(M.t("area_protegida")); return; }
        watchMining(p, b.getId());
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void place(BlockPlaceEvent event) {
        Block b = event.getBlock();
        if (denied(event.getPlayer(), "construir", b.level, b.getFloorX(), b.getFloorY(), b.getFloorZ())) { event.setCancelled(); event.getPlayer().sendPopup(M.t("area_protegida")); }
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
            p.sendPopup(M.t("area_protegida"));
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void damage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player) || !data.isLoaded()) { return; }
        Player victim = (Player) event.getEntity();
        if (event.getCause() == EntityDamageEvent.CAUSE_VOID && data.read(s -> s.antiVoid.contains(victim.level.getFolderName()))) {
            event.setCancelled();
            victim.teleport(victim.level.getSafeSpawn());
            victim.sendPopup(M.t("voce_foi_salvo_do_void"));
            return;
        }
        int x = victim.getFloorX(), y = victim.getFloorY(), z = victim.getFloorZ();
        if (event instanceof EntityDamageByEntityEvent && ((EntityDamageByEntityEvent) event).getDamager() instanceof Player) {
            Player attacker = (Player) ((EntityDamageByEntityEvent) event).getDamager();
            if (pvpBlocked(victim.level, x, y, z) || pvpBlocked(attacker.level, attacker.getFloorX(), attacker.getFloorY(), attacker.getFloorZ())) { event.setCancelled(); attacker.sendPopup(M.t("pvp_desativado_aqui")); return; }
        }
        Map.Entry<String, ProtectState.Area> e = areaAt(victim.level, x, y, z);
        // A area manda dentro dela; fora, vale o "dano off" do mundo (queda, fogo, afogamento...).
        if (e != null ? e.getValue().deny.contains("dano") : worldDenies(victim.level, "dano")) { event.setCancelled(); }
    }

    private boolean worldDenies(Level level, String flag) {
        if (!data.isLoaded() || level == null) { return false; }
        Set<String> world = data.read(s -> s.worlds.get(level.getFolderName()));
        return world != null && world.contains(flag);
    }

    /** "fome off" no mundo: a fome nao cai (lobbies). Comer continua subindo. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void hunger(PlayerFoodLevelChangeEvent event) {
        Player p = event.getPlayer();
        if (event.getFoodLevel() < p.getFoodData().getLevel() && worldDenies(p.level, "fome")) { event.setCancelled(); }
    }

    /**
     * "movimento off" no mundo: o Nukkit nao puxa o jogador de volta quando acha que ele andou demais num tick. Na beira dos
     * blocos e com o lag do celular isso dispara a toda hora ("moveu erradamente"). So para lobbies: nas arenas a checagem fica.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void invalidMove(PlayerInvalidMoveEvent event) { if (worldDenies(event.getPlayer().level, "movimento")) { event.setCancelled(); } }

    /** Quem chega com fome num mundo "fome off" fica com a barra cheia (a cada 2 s, so le o conjunto do mundo). */
    private void feed() {
        if (!data.isLoaded()) { return; }
        for (Player p : getServer().getOnlinePlayers().values()) {
            if (p.spawned && p.getFoodData().getLevel() < 20 && worldDenies(p.level, "fome")) { p.getFoodData().setLevel(20); }
        }
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
            String alert = PREFIX + M.t("ray_suspeito_minerios_raros_para_pedras", p.getName(), m[1], m[0]);
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
                p.sendMessage(PREFIX + M.t("ponto_marcado_em", sub.charAt(3), p.getFloorX(), p.getFloorY(), p.getFloorZ()));
                return true;
            }
            case "criar": {
                if (p == null || args.length < 2) { sender.sendMessage(PREFIX + M.t("uso_area_criar_nome_depois_de")); return true; }
                int[][] sel = selection.get(p);
                if (sel == null || sel[0] == null || sel[1] == null) { throw new IllegalArgumentException(M.t("marque_area_pos1_area_pos2_antes")); }
                String name = ProtectState.name(args[1]);
                ProtectState.Area a = ProtectState.area(p.level.getFolderName(), sel[0][0], sel[0][1], sel[0][2], sel[1][0], sel[1][1], sel[1][2]);
                a.owner = p.getName().toLowerCase(Locale.ROOT);
                change(sender, s -> { if (s.areas.size() >= ProtectState.MAX_AREAS) { throw new IllegalArgumentException(M.t("limite_de_areas")); } if (s.areas.putIfAbsent(name, a) != null) { throw new IllegalArgumentException(M.t("ja_existe_uma_area_com_esse")); } },
                        () -> PREFIX + M.t("area_criada_blocos_bloqueado_para_visitantes", name, a.volume()));
                return true;
            }
            case "flag": {
                if (args.length < 4) { sender.sendMessage(PREFIX + M.t("uso_area_flag_area_construir_pvp")); return true; }
                String name = ProtectState.name(args[1]), flag = ProtectState.flag(args[2]);
                boolean deny = args[3].equalsIgnoreCase("off") || args[3].equalsIgnoreCase("nao");
                change(sender, s -> { ProtectState.Area a = require(s, name); if (deny) { a.deny.add(flag); } else { a.deny.remove(flag); } }, () -> PREFIX + flag + (deny ? " bloqueado" : " liberado") + " em " + name + ".");
                return true;
            }
            case "membro": {
                if (args.length < 4) { sender.sendMessage(PREFIX + M.t("uso_area_membro_area_adicionar_remover")); return true; }
                String name = ProtectState.name(args[1]), member = args[3].toLowerCase(Locale.ROOT);
                if (!member.matches("[a-z0-9_]{3,16}")) { throw new IllegalArgumentException(M.t("jogador_invalido")); }
                boolean add = args[2].equalsIgnoreCase("adicionar");
                change(sender, s -> { ProtectState.Area a = require(s, name); if (add) { if (a.members.size() >= 64) { throw new IllegalArgumentException(M.t("limite_de_membros")); } a.members.add(member); } else { a.members.remove(member); } },
                        () -> PREFIX + member + (add ? " adicionado a " : M.t("removido_de")) + name + ".");
                return true;
            }
            case "efeito": {
                if (args.length < 3) { sender.sendMessage(PREFIX + M.t("uso_area_efeito_area_id_1")); return true; }
                String name = ProtectState.name(args[1]);
                int[] effect = args[2].equalsIgnoreCase("nenhum") ? new int[0] : new int[] {Integer.parseInt(args[2]), args.length > 3 ? Integer.parseInt(args[3]) : 0};
                change(sender, s -> require(s, name).effect = effect, () -> PREFIX + M.t("efeito_de_atualizado", name));
                return true;
            }
            case "mensagem": {
                if (args.length < 2) { return false; }
                String name = ProtectState.name(args[1]), text = args.length > 2 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : "";
                if (text.length() > 100) { throw new IllegalArgumentException(M.t("mensagem_ate_100_caracteres")); }
                change(sender, s -> require(s, name).greeting = text, () -> PREFIX + M.t("mensagem_de_entrada_atualizada"));
                return true;
            }
            case "remover": {
                if (args.length < 2) { return false; }
                String name = ProtectState.name(args[1]);
                change(sender, s -> { if (s.areas.remove(name) == null) { throw new IllegalArgumentException(M.t("area_inexistente")); } }, () -> PREFIX + M.t("area_removida"));
                return true;
            }
            case "info": {
                if (p == null) { return true; }
                Map.Entry<String, ProtectState.Area> e = areaAt(p.level, p.getFloorX(), p.getFloorY(), p.getFloorZ());
                p.sendMessage(PREFIX + (e == null ? M.t("nenhuma_area_aqui") : M.t("dono_bloqueado_membros", e.getKey(), e.getValue().owner, e.getValue().deny, e.getValue().members)));
                return true;
            }
            case "lista": sender.sendMessage(PREFIX + data.read(s -> s.areas.isEmpty() ? M.t("nenhuma_area") : String.join(", ", s.areas.keySet()))); return true;
            default:
                sender.sendMessage(PREFIX + M.t("area_pos1_pos2_criar_nome_flag"));
                sender.sendMessage(PREFIX + M.t("mundoprotecao_mundo_flag_on_off_antivoid"));
                return true;
        }
    }

    private static ProtectState.Area require(ProtectState s, String name) {
        ProtectState.Area a = s.areas.get(name);
        if (a == null) { throw new IllegalArgumentException(M.t("area_inexistente")); }
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
