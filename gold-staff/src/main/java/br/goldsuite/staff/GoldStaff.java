package br.goldsuite.staff;

import br.goldsuite.api.*;
import cn.nukkit.Player;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.entity.Entity;
import cn.nukkit.entity.item.EntityItem;
import cn.nukkit.entity.item.EntityXPOrb;
import cn.nukkit.entity.projectile.EntityArrow;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.player.PlayerCommandPreprocessEvent;
import cn.nukkit.item.Item;
import cn.nukkit.level.Level;
import cn.nukkit.plugin.PluginBase;
import cn.nukkit.scheduler.PluginTask;
import java.util.*;

/** Report, ajuda, giveall, bloqueio de comandos, restart, clearlag e comandos agendados. */
public final class GoldStaff extends PluginBase implements Listener {
    static final String PREFIX = "§4[§cStaff§4]§f ";
    private PersistentState<StaffState> data;
    private int minute, clearlagIn, restartIn = -1;
    private final Map<Player, Long> lastReport = new IdentityHashMap<>();

    @Override public void onEnable() {
        CoreService core = getServer().getServiceManager().load(CoreService.class);
        if (core == null) { throw new IllegalStateException("GoldCore precisa estar ativo antes do GoldStaff."); }
        data = new PersistentState<>(core.getStore(), "goldstaff", StaffState.class, StaffState::new, StaffState::validate);
        getServer().getPluginManager().registerEvents(this, this);
        data.ready().thenRun(() -> { clearlagIn = data.read(s -> s.clearlagMinutes) * 60; int h = data.read(s -> s.restartHours); restartIn = h == 0 ? -1 : h * 3600; });
        getServer().getScheduler().scheduleRepeatingTask(new PluginTask<GoldStaff>(this) { @Override public void onRun(int tick) { second(); } }, 20);
    }

    @Override public void onDisable() { lastReport.clear(); if (data != null) { data.close(); } }

    /** Contadores em segundos; nenhum trabalho pesado fora dos instantes marcados. */
    private void second() {
        if (!data.isLoaded()) { return; }
        int every = data.read(s -> s.clearlagMinutes);
        if (every > 0) {
            clearlagIn--;
            if (clearlagIn == 30 || clearlagIn == 10) { getServer().broadcastMessage(PREFIX + "§eItens no chao serao removidos em " + clearlagIn + " s."); }
            if (clearlagIn <= 0) { int removed = clearlag(); clearlagIn = every * 60; getServer().broadcastMessage(PREFIX + "§a" + removed + " entidades removidas."); }
        }
        if (restartIn > 0) {
            restartIn--;
            if (restartIn == 600 || restartIn == 300 || restartIn == 60 || restartIn == 30 || (restartIn <= 10 && restartIn > 0)) {
                getServer().broadcastMessage(PREFIX + "§cO servidor reinicia em " + (restartIn >= 60 ? restartIn / 60 + " min" : restartIn + " s") + ".");
            }
            if (restartIn == 0) { getServer().broadcastMessage(PREFIX + "§cReiniciando..."); markRestart(); getServer().shutdown(); }
        }
        if (++minute % 60 == 0) {
            int now = minute / 60;
            for (Map.Entry<String, Integer> e : data.read(s -> new ArrayList<>(s.scheduled.entrySet()))) {
                if (now % e.getValue() == 0) { getServer().dispatchCommand(getServer().getConsoleSender(), e.getKey()); }
            }
        }
    }

    /** O iniciador do Nukkit Gold reabre o servidor quando encontra este arquivo; um stop normal nao o cria. */
    private void markRestart() {
        try { java.nio.file.Files.write(java.nio.file.Paths.get(getServer().getDataPath(), "reiniciar.servidor"), new byte[0]); }
        catch (java.io.IOException e) { getLogger().warning("Nao foi possivel marcar o restart: " + e.getMessage()); }
    }

    /** Remove itens no chao, orbes de XP e flechas cravadas; nunca mobs, NPCs ou jogadores. */
    private int clearlag() {
        int removed = 0;
        for (Level level : getServer().getLevels().values()) {
            for (Entity entity : level.getEntities()) {
                if (entity instanceof EntityItem || entity instanceof EntityXPOrb || (entity instanceof EntityArrow && entity.onGround)) { entity.close(); removed++; }
            }
        }
        return removed;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void command(PlayerCommandPreprocessEvent event) {
        Player p = event.getPlayer();
        if (!data.isLoaded() || p.hasPermission("goldstaff.admin")) { return; }
        String message = event.getMessage();
        if (!message.startsWith("/")) { return; }
        int space = message.indexOf(' ');
        String name = (space < 0 ? message.substring(1) : message.substring(1, space)).toLowerCase(Locale.ROOT);
        // "plugin:comando" tambem e bloqueado pelo nome curto.
        String shortName = name.contains(":") ? name.substring(name.indexOf(':') + 1) : name;
        if (data.read(s -> s.blocked.contains(name) || s.blocked.contains(shortName))) { event.setCancelled(); p.sendMessage(PREFIX + "§cComando indisponivel."); }
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        try {
            switch (command.getName()) {
                case "ajuda": for (String line : data.read(s -> s.help)) { sender.sendMessage(line.replace('&', '§')); } return true;
                case "report": report(sender, args); return true;
                case "reports": reports(sender, args); return true;
                case "giveall": {
                    if (args.length < 1) { return false; }
                    String[] id = args[0].split(":");
                    Item item = Item.get(Integer.parseInt(id[0]), id.length > 1 ? Integer.parseInt(id[1]) : 0, args.length > 1 ? Math.max(1, Math.min(64, Integer.parseInt(args[1]))) : 1);
                    if (item.getId() == 0) { throw new IllegalArgumentException("Item invalido."); }
                    int given = 0;
                    for (Player p : getServer().getOnlinePlayers().values()) { if (p.spawned) { p.getInventory().addItem(item.clone()); given++; } }
                    getServer().broadcastMessage(PREFIX + "§a" + sender.getName() + " deu " + item.getCount() + "x " + item.getName() + " para todos!");
                    sender.sendMessage(PREFIX + "Entregue para " + given + " jogador(es).");
                    return true;
                }
                case "bloquearcmd": {
                    if (args.length < 1) { sender.sendMessage(PREFIX + "Bloqueados: " + data.read(s -> String.join(", ", s.blocked))); return true; }
                    String c = StaffState.command(args[0]);
                    boolean[] added = new boolean[1];
                    change(sender, s -> { added[0] = s.blocked.add(c); if (!added[0]) { s.blocked.remove(c); } }, () -> "/" + c + (added[0] ? " bloqueado." : " liberado."));
                    return true;
                }
                case "clearlag": sender.sendMessage(PREFIX + clearlag() + " entidades removidas."); return true;
                case "agendar": {
                    if (args.length < 2) { sender.sendMessage(PREFIX + "Uso: /agendar <minutos|remover> <comando>. Atuais: " + data.read(s -> s.scheduled.toString())); return true; }
                    String cmd = String.join(" ", Arrays.copyOfRange(args, 1, args.length)).replaceFirst("^/", "");
                    if (args[0].equalsIgnoreCase("remover")) { change(sender, s -> { if (s.scheduled.remove(cmd) == null) { throw new IllegalArgumentException("Agendamento inexistente."); } }, () -> "Agendamento removido."); return true; }
                    int minutes = Integer.parseInt(args[0]);
                    change(sender, s -> s.scheduled.put(cmd, minutes), () -> "\"/" + cmd + "\" a cada " + minutes + " min.");
                    return true;
                }
                case "restart": {
                    if (args.length > 0 && args[0].equalsIgnoreCase("auto")) {
                        int hours = args.length > 1 ? Integer.parseInt(args[1]) : 0;
                        change(sender, s -> s.restartHours = hours, () -> { restartIn = hours == 0 ? -1 : hours * 3600; return hours == 0 ? "Restart automatico desligado." : "Restart a cada " + hours + " h."; });
                        return true;
                    }
                    int seconds = args.length > 0 ? Math.max(5, Integer.parseInt(args[0])) : 60;
                    restartIn = seconds;
                    getServer().broadcastMessage(PREFIX + "§cO servidor reinicia em " + seconds + " s.");
                    return true;
                }
                default: return false;
            }
        } catch (NumberFormatException e) { sender.sendMessage(PREFIX + "§cNumero invalido."); return true; }
        catch (RuntimeException e) { sender.sendMessage(PREFIX + "§c" + root(e)); return true; }
    }

    private void report(CommandSender sender, String[] args) {
        if (!(sender instanceof Player) || args.length < 2) { sender.sendMessage(PREFIX + "Uso: /report <jogador> <motivo>"); return; }
        Player p = (Player) sender;
        Long last = lastReport.get(p);
        if (last != null && System.currentTimeMillis() - last < 60_000) { throw new IllegalArgumentException("Espere 1 minuto entre denuncias."); }
        String target = args[0].toLowerCase(Locale.ROOT);
        if (!target.matches("[a-z0-9_]{3,16}")) { throw new IllegalArgumentException("Jogador invalido."); }
        String reason = String.join(" ", Arrays.copyOfRange(args, 1, args.length)).replace("§", "");
        if (reason.length() > 150) { reason = reason.substring(0, 150); }
        StaffState.Report report = new StaffState.Report(p.getName().toLowerCase(Locale.ROOT), target, reason);
        int[] id = new int[1];
        String finalReason = reason;
        lastReport.put(p, System.currentTimeMillis());
        change(sender, s -> {
            // Os mais antigos saem quando a fila enche.
            while (s.reports.size() >= StaffState.MAX_REPORTS) { s.reports.remove(((TreeMap<Integer, StaffState.Report>) s.reports).firstKey()); }
            id[0] = s.nextReport++;
            s.reports.put(id[0], report);
        }, () -> {
            for (Player staff : getServer().getOnlinePlayers().values()) {
                if (staff.hasPermission("goldstaff.admin")) { staff.sendMessage(PREFIX + "§eReport #" + id[0] + ": §f" + target + " §7por " + p.getName() + ": " + finalReason); }
            }
            return "Denuncia #" + id[0] + " enviada para a staff. Obrigado!";
        });
    }

    private void reports(CommandSender sender, String[] args) {
        if (args.length >= 2 && args[0].equalsIgnoreCase("fechar")) {
            int id = Integer.parseInt(args[1]);
            change(sender, s -> { if (s.reports.remove(id) == null) { throw new IllegalArgumentException("Report inexistente."); } }, () -> "Report #" + id + " fechado.");
            return;
        }
        List<String> lines = data.read(s -> {
            List<String> out = new ArrayList<>();
            for (Map.Entry<Integer, StaffState.Report> e : s.reports.entrySet()) { out.add("§e#" + e.getKey() + " §f" + e.getValue().target + " §7(" + e.getValue().reporter + "): " + e.getValue().reason); }
            return out;
        });
        if (lines.isEmpty()) { sender.sendMessage(PREFIX + "Nenhuma denuncia aberta."); return; }
        for (String line : lines.subList(Math.max(0, lines.size() - 15), lines.size())) { sender.sendMessage(line); }
        sender.sendMessage(PREFIX + "Feche com /reports fechar <id>.");
    }

    private void change(CommandSender sender, java.util.function.Consumer<StaffState> mutation, java.util.function.Supplier<String> message) {
        data.change(mutation).whenComplete((v, e) -> { if (isEnabled()) { sender.sendMessage(PREFIX + (e != null ? "§c" + root(e) : message.get())); } });
    }

    static String root(Throwable e) { while (e.getCause() != null) { e = e.getCause(); } return e.getMessage(); }
}
