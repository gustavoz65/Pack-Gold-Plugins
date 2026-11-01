package br.goldsuite.device;

import br.goldsuite.api.*;
import br.goldsuite.api.DeviceService.Platform;
import cn.nukkit.Player;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.entity.EntityDamageByEntityEvent;
import cn.nukkit.event.entity.EntityDamageEvent;
import cn.nukkit.event.player.PlayerJoinEvent;
import cn.nukkit.event.player.PlayerQuitEvent;
import cn.nukkit.event.server.DataPacketReceiveEvent;
import cn.nukkit.entity.Entity;
import cn.nukkit.network.protocol.InteractPacket;
import cn.nukkit.network.protocol.UseItemPacket;
import cn.nukkit.plugin.PluginBase;
import java.util.*;

/**
 * Plataforma de cada jogador (Windows 10, Android, iOS), modo leve /fps e limite de cliques por segundo (CPS)
 * por plataforma, tudo configuravel no /painel. Nenhuma tarefa repetitiva: so trabalha no login e em cada hit.
 */
public final class GoldDevice extends PluginBase implements Listener, DeviceService {
    static String PREFIX = "§b[§3Aparelho§b]§f ";
    static final String FPS = "golddevice-fps", OVERRIDE = "golddevice-plataforma", LEARNED = "golddevice-aprendido";

    private CoreService core;
    private final Map<Player, Platform> platforms = new IdentityHashMap<>();
    private final Set<Player> low = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<Player, ClickLimiter> clicks = new IdentityHashMap<>();
    private final Map<Player, Long> alerted = new IdentityHashMap<>();
    /** Mira de quem ainda nao teve a plataforma confirmada (sem DeviceOS e sem correcao da staff). */
    private final Map<Player, AimProfile> aim = new IdentityHashMap<>();
    private long cpsMobile = 12, cpsWindows = 16, cpsOther = 12, suggestLow = 1;
    private String fallback = "android";

    @Override public void onEnable() {
        Settings.prefix(this, PREFIX, v -> PREFIX = v);
        Settings.number(this, "cps.celular", 12, "Cliques por segundo maximos no celular (Android/iOS)", v -> cpsMobile = Math.max(1, v));
        Settings.number(this, "cps.windows", 16, "Cliques por segundo maximos no Windows 10", v -> cpsWindows = Math.max(1, v));
        Settings.number(this, "cps.outros", 12, "Cliques por segundo maximos em plataforma desconhecida", v -> cpsOther = Math.max(1, v));
        Settings.number(this, "fps.sugerir_celular", 1, "1 = sugere /fps ao entrar pelo celular, 0 = nao", v -> suggestLow = v);
        Settings.text(this, "plataforma.padrao", "android", "Plataforma assumida quando o cliente nao informa (0.15 quase nunca informa): windows ou android", v -> fallback = v);
        core = getServer().getServiceManager().load(CoreService.class);
        if (core == null) { throw new IllegalStateException("GoldCore precisa estar ativo antes do GoldDevice."); }
        getServer().getServiceManager().register(DeviceService.class, this, this);
        getServer().getPluginManager().registerEvents(this, this);
        for (Player p : getServer().getOnlinePlayers().values()) { join(p); }
    }

    @Override public void onDisable() { platforms.clear(); low.clear(); clicks.clear(); alerted.clear(); }

    static String key(Player p) { return p.getName().toLowerCase(Locale.ROOT); }

    @Override public Platform platform(Player p) { return platforms.getOrDefault(p, Platform.DESCONHECIDO); }
    @Override public boolean lowEffects(Player p) { return low.contains(p); }

    @EventHandler(priority = EventPriority.LOWEST) public void onJoin(PlayerJoinEvent e) { join(e.getPlayer()); }

    private void join(Player p) {
        Platform detected = Platforms.detect(p.getLoginData());
        String name = key(p);
        if (detected == Platform.DESCONHECIDO) {
            // O 0.15 nao diz o sistema: comeca pelo padrao (ou pelo que a mira mostrou na ultima vez) e confirma pelos cliques.
            try { detected = Platforms.parse(fallback); } catch (IllegalArgumentException bad) { detected = Platform.ANDROID; }
            aim.put(p, new AimProfile());
            core.getStore().get(LEARNED, name).thenAccept(v -> { if (v.isPresent() && p.isOnline() && aim.containsKey(p)) { try { platforms.put(p, Platforms.parse(v.get())); } catch (IllegalArgumentException ignored) { } } });
        }
        platforms.put(p, detected);
        // Correcao manual da staff vale mais que a deteccao.
        core.getStore().get(OVERRIDE, name).thenAccept(v -> { if (v.isPresent() && p.isOnline()) { try { platforms.put(p, Platforms.parse(v.get())); aim.remove(p); } catch (IllegalArgumentException ignored) { } } });
        core.getStore().get(FPS, name).thenAccept(v -> {
            if (!p.isOnline()) { return; }
            if (v.isPresent() && v.get().equals("1")) { low.add(p); }
            else if (!v.isPresent() && suggestLow == 1 && platform(p).mobile()) { p.sendMessage(PREFIX + "Celular travando? Use §e/fps§f para o modo leve (menos particulas e efeitos)."); }
        });
        getLogger().info(p.getName() + " entrou pelo " + Platforms.label(platform(p)) + " (protocolo " + p.getProtocol() + ", " + Platforms.evidence(p.getLoginData()) + (aim.containsKey(p) ? "; confirmando pela mira" : "") + ")");
    }

    @EventHandler(priority = EventPriority.MONITOR) public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        platforms.remove(p); low.remove(p); clicks.remove(p); alerted.remove(p); aim.remove(p);
    }

    /**
     * Cada clique de quem esta sem plataforma confirmada vira uma amostra: o ponto clicado (ou a direcao do toque no ar,
     * ou o alvo atacado) esta na linha do olhar? So le os pacotes; nada e cancelado.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void packet(DataPacketReceiveEvent e) {
        if (aim.isEmpty()) { return; }
        Player p = e.getPlayer();
        AimProfile profile = aim.get(p);
        if (profile == null || !p.spawned) { return; }
        double[] look = AimProfile.look(p.yaw, p.pitch);
        double ex = p.x, ey = p.y + p.getEyeHeight(), ez = p.z;
        if (e.getPacket() instanceof UseItemPacket) {
            UseItemPacket u = (UseItemPacket) e.getPacket();
            if (u.face == 0xff) {
                profile.add(AimProfile.angle(u.x / 32768.0, u.y / 32768.0, u.z / 32768.0, look[0], look[1], look[2]) <= AimProfile.ALIGNED_DEG);
            } else if (u.face >= 0 && u.face <= 5 && u.fx >= 0 && u.fx <= 1 && u.fy >= 0 && u.fy <= 1 && u.fz >= 0 && u.fz <= 1) {
                double dx = u.x + u.fx - ex, dy = u.y + u.fy - ey, dz = u.z + u.fz - ez;
                if (dx * dx + dy * dy + dz * dz < 1.0) { return; }
                profile.add(AimProfile.angle(dx, dy, dz, look[0], look[1], look[2]) <= AimProfile.ALIGNED_DEG);
            } else { return; }
        } else if (e.getPacket() instanceof InteractPacket && ((InteractPacket) e.getPacket()).action == InteractPacket.ACTION_LEFT_CLICK) {
            Entity t = p.level.getEntity(((InteractPacket) e.getPacket()).target);
            if (t == null || t.boundingBox == null) { return; }
            double g = 0.15;
            profile.add(AimProfile.rayHitsBox(ex, ey, ez, look[0], look[1], look[2], t.boundingBox.minX - g, t.boundingBox.minY - g, t.boundingBox.minZ - g, t.boundingBox.maxX + g, t.boundingBox.maxY + g, t.boundingBox.maxZ + g));
        } else { return; }
        AimProfile.Verdict v = profile.verdict();
        if (v == AimProfile.Verdict.INCERTO) { return; }
        Platform learned = v == AimProfile.Verdict.PC ? Platform.WINDOWS10 : platform(p).mobile() ? platform(p) : Platform.ANDROID;
        aim.remove(p);
        Platform before = platform(p);
        platforms.put(p, learned);
        core.getStore().put(LEARNED, key(p), learned == Platform.WINDOWS10 ? "windows" : learned == Platform.IOS ? "ios" : "android");
        getLogger().info(p.getName() + " confirmado pela mira: " + Platforms.label(learned) + " (" + profile.aligned() + " de " + profile.count() + " cliques no centro da tela)" + (before != learned ? ", antes " + Platforms.label(before) : ""));
    }

    long limit(Player p) {
        Platform pl = platform(p);
        return pl == Platform.WINDOWS10 ? cpsWindows : pl.mobile() ? cpsMobile : cpsOther;
    }

    /** Antes de qualquer outro plugin: hit acima do limite de CPS da plataforma e ignorado. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void hit(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player) || e.getCause() != EntityDamageEvent.CAUSE_ENTITY_ATTACK) { return; }
        Player p = (Player) e.getDamager();
        long now = System.currentTimeMillis();
        ClickLimiter limiter = clicks.computeIfAbsent(p, k -> new ClickLimiter());
        if (limiter.allow(now, (int) Math.min(64, limit(p)))) { return; }
        e.setCancelled();
        // Muitos hits barrados seguidos: provavel autoclick; avisa a staff (e o Discord) no maximo 1x por minuto.
        if (limiter.blocked(now) >= 15 && now - alerted.getOrDefault(p, 0L) > 60_000) {
            alerted.put(p, now);
            Settings.alert("Anti-autoclick", p.getName() + " passou de " + limit(p) + " CPS (" + Platforms.label(platform(p)) + ") varias vezes");
        }
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equals("fps")) {
            if (!(sender instanceof Player)) { return true; }
            Player p = (Player) sender;
            boolean on = low.add(p);
            if (!on) { low.remove(p); }
            core.getStore().put(FPS, key(p), on ? "1" : "0");
            p.sendMessage(PREFIX + (on ? "§aModo leve ligado: §fsem particulas decorativas, trilhas, modelos 3D e animacoes. §7(/fps para desligar)" : "Modo leve desligado: efeitos completos."));
            return true;
        }
        // /plataforma [jogador] | definir <jogador> <windows|android|ios>
        if (args.length >= 3 && args[0].equalsIgnoreCase("definir")) {
            if (!sender.hasPermission("golddevice.admin")) { sender.sendMessage(PREFIX + "§cSem permissao."); return true; }
            Platform pl;
            try { pl = Platforms.parse(args[2]); } catch (IllegalArgumentException e) { sender.sendMessage(PREFIX + "§c" + e.getMessage()); return true; }
            String name = args[1].toLowerCase(Locale.ROOT);
            core.getStore().put(OVERRIDE, name, args[2].toLowerCase(Locale.ROOT)).whenComplete((v, e) -> {
                Player target = getServer().getPlayerExact(name);
                if (target != null) { platforms.put(target, pl); }
                sender.sendMessage(PREFIX + (e != null ? "§cNao foi possivel gravar." : name + " agora conta como " + Platforms.label(pl) + "."));
            });
            return true;
        }
        Player target = args.length > 0 ? getServer().getPlayerExact(args[0]) : sender instanceof Player ? (Player) sender : null;
        if (target == null) { sender.sendMessage(PREFIX + "Jogador offline."); return true; }
        sender.sendMessage(PREFIX + target.getName() + ": §e" + Platforms.label(platform(target)) + "§f, limite de §e" + limit(target) + " CPS§f, modo leve " + (lowEffects(target) ? "§aligado" : "§7desligado") + "§f, protocolo " + target.getProtocol() + (aim.containsKey(target) ? "§7 (confirmando pela mira: " + aim.get(target).aligned() + "/" + aim.get(target).count() + " cliques no centro)" : ""));
        return true;
    }
}
