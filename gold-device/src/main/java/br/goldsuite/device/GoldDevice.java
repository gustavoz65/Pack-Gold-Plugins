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
import cn.nukkit.plugin.PluginBase;
import java.util.*;

/**
 * Plataforma de cada jogador (Windows 10, Android, iOS), modo leve /fps e limite de cliques por segundo (CPS)
 * por plataforma, tudo configuravel no /painel. Nenhuma tarefa repetitiva: so trabalha no login e em cada hit.
 */
public final class GoldDevice extends PluginBase implements Listener, DeviceService {
    static String PREFIX = "§b[§3Aparelho§b]§f ";
    static final String FPS = "golddevice-fps", OVERRIDE = "golddevice-plataforma";

    private CoreService core;
    private final Map<Player, Platform> platforms = new IdentityHashMap<>();
    private final Set<Player> low = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<Player, ClickLimiter> clicks = new IdentityHashMap<>();
    private final Map<Player, Long> alerted = new IdentityHashMap<>();
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
        if (detected == Platform.DESCONHECIDO) {
            try { detected = Platforms.parse(fallback); } catch (IllegalArgumentException bad) { detected = Platform.ANDROID; }
        }
        platforms.put(p, detected);
        String name = key(p);
        // Correcao manual da staff vale mais que a deteccao.
        core.getStore().get(OVERRIDE, name).thenAccept(v -> { if (v.isPresent() && p.isOnline()) { try { platforms.put(p, Platforms.parse(v.get())); } catch (IllegalArgumentException ignored) { } } });
        core.getStore().get(FPS, name).thenAccept(v -> {
            if (!p.isOnline()) { return; }
            if (v.isPresent() && v.get().equals("1")) { low.add(p); }
            else if (!v.isPresent() && suggestLow == 1 && platform(p).mobile()) { p.sendMessage(PREFIX + "Celular travando? Use §e/fps§f para o modo leve (menos particulas e efeitos)."); }
        });
        getLogger().info(p.getName() + " entrou pelo " + Platforms.label(platform(p)) + " (protocolo " + p.getProtocol() + ", dados do cliente: " + p.getLoginData().keySet() + ")");
    }

    @EventHandler(priority = EventPriority.MONITOR) public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        platforms.remove(p); low.remove(p); clicks.remove(p); alerted.remove(p);
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
        sender.sendMessage(PREFIX + target.getName() + ": §e" + Platforms.label(platform(target)) + "§f, limite de §e" + limit(target) + " CPS§f, modo leve " + (lowEffects(target) ? "§aligado" : "§7desligado") + "§f, protocolo " + target.getProtocol());
        return true;
    }
}
