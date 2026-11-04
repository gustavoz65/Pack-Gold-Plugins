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
    static String PREFIX = M.t("staff");
    private PersistentState<StaffState> data;
    private int minute, clearlagIn, restartIn = -1;
    private final Map<Player, Long> lastReport = new IdentityHashMap<>();
    private Tickets tickets;
    private Punishments punishments;
    private Welcome welcome;
    /** Comandos de punicao: o alvo nao pode ter cargo igual ou acima de quem pune. */
    private static final Set<String> PUNISH = new HashSet<>(Arrays.asList("kick", "ban", "ban-ip", "banip", "mute", "tempban", "tempmute", "banir", "expulsar", "silenciar", "jail", "prender"));

    @Override public void onEnable() {
        M.B.attach(this);
        Settings.prefix(this, PREFIX, v -> PREFIX = v);
        CoreService core = getServer().getServiceManager().load(CoreService.class);
        if (core == null) { throw new IllegalStateException(M.t("goldcore_precisa_estar_ativo_antes_do")); }
        data = new PersistentState<>(core.getStore(), "goldstaff", StaffState.class, StaffState::new, StaffState::validate);
        getServer().getPluginManager().registerEvents(this, this);
        tickets = new Tickets(this, core);
        punishments = new Punishments(this, core);
        welcome = new Welcome(this);
        getServer().getPluginManager().registerEvents(welcome, this);
        getServer().getPluginManager().registerEvents(punishments, this);
        getServer().getPluginManager().registerEvents(tickets, this);
        data.ready().thenRun(() -> { clearlagIn = data.read(s -> s.clearlagMinutes) * 60; int h = data.read(s -> s.restartHours); restartIn = h == 0 ? -1 : h * 3600; });
        getServer().getScheduler().scheduleRepeatingTask(new PluginTask<GoldStaff>(this) { @Override public void onRun(int tick) { second(); } }, 20);
    }

    @Override public void onDisable() { lastReport.clear(); if (welcome != null) { welcome.close(); } if (punishments != null) { punishments.close(); } if (tickets != null) { tickets.close(); } if (data != null) { data.close(); } }

    /** Linhas do /ajuda (a IA do suporte usa para responder duvidas). */
    List<String> helpLines() { return data != null && data.isLoaded() ? data.read(s -> new ArrayList<>(s.help)) : Collections.emptyList(); }

    /** Contadores em segundos; nenhum trabalho pesado fora dos instantes marcados. */
    private void second() {
        tickets.second();
        if (!data.isLoaded()) { return; }
        int every = data.read(s -> s.clearlagMinutes);
        if (every > 0) {
            clearlagIn--;
            if (clearlagIn == 30 || clearlagIn == 10) { getServer().broadcastMessage(PREFIX + M.t("itens_no_chao_serao_removidos_em", clearlagIn)); }
            if (clearlagIn <= 0) { int removed = clearlag(); clearlagIn = every * 60; getServer().broadcastMessage(PREFIX + M.t("entidades_removidas", removed)); }
        }
        if (restartIn > 0) {
            restartIn--;
            if (restartIn == 600 || restartIn == 300 || restartIn == 60 || restartIn == 30 || (restartIn <= 10 && restartIn > 0)) {
                getServer().broadcastMessage(PREFIX + M.t("servidor_reinicia_em", (restartIn >= 60 ? M.t("min", restartIn / 60) : restartIn + " s")));
            }
            if (restartIn == 0) { getServer().broadcastMessage(PREFIX + M.t("reiniciando")); markRestart(); getServer().shutdown(); }
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
        String message = event.getMessage();
        if (!data.isLoaded() || !message.startsWith("/")) { return; }
        String[] parts = message.substring(1).trim().split("\\s+");
        String name = parts[0].toLowerCase(Locale.ROOT);
        // "plugin:comando" tambem e bloqueado pelo nome curto.
        String shortName = name.contains(":") ? name.substring(name.indexOf(':') + 1) : name;
        if (PUNISH.contains(shortName) && parts.length > 1 && !outranks(p, parts[1])) {
            event.setCancelled();
            p.sendMessage(PREFIX + M.t("voce_nao_pode_punir_alguem_do"));
            return;
        }
        if (p.hasPermission("goldstaff.admin") || p.hasPermission("goldstaff.cmd." + shortName)) { return; }
        if (data.read(s -> s.blocked.contains(name) || s.blocked.contains(shortName))) { event.setCancelled(); p.sendMessage(PREFIX + M.t("comando_indisponivel")); }
    }

    /** OP manda em todos; fora isso, so pune quem tem cargo (peso) menor. Jogador sem cargo pode ser punido por qualquer staff. */
    boolean outranks(Player p, String target) {
        if (p.isOp() || target.equalsIgnoreCase(p.getName())) { return true; }
        if (getServer().isOp(target)) { return false; }
        PermissionService perms = getServer().getServiceManager().load(PermissionService.class);
        if (perms == null) { return true; }
        try {
            int theirs = perms.getWeight(target);
            return theirs == 0 || theirs < perms.getWeight(p.getName());
        } catch (RuntimeException e) { return false; }
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        switch (command.getName()) {
            case "welcomebook": return welcome.command(sender, args);
            case "advertir": case "advertencias": case "banir": case "tempban": case "desbanir": return punishments.command(sender, command.getName(), args);
            case "suporte": case "atender": case "finalizar": case "avaliar": case "tickets": return tickets.onCommand(sender, command.getName(), args);
            default: break;
        }
        try {
            switch (command.getName()) {
                case "ajuda": for (String line : data.read(s -> s.help)) { sender.sendMessage(line.replace('&', '§')); } return true;
                case "report": report(sender, args); return true;
                case "reports": reports(sender, args); return true;
                case "giveall": {
                    if (args.length < 1) { return false; }
                    String[] id = args[0].split(":");
                    Item item = Item.get(Integer.parseInt(id[0]), id.length > 1 ? Integer.parseInt(id[1]) : 0, args.length > 1 ? Math.max(1, Math.min(64, Integer.parseInt(args[1]))) : 1);
                    if (item.getId() == 0) { throw new IllegalArgumentException(M.t("item_invalido")); }
                    int given = 0;
                    for (Player p : getServer().getOnlinePlayers().values()) { if (p.spawned) { p.getInventory().addItem(item.clone()); given++; } }
                    getServer().broadcastMessage(PREFIX + M.t("deu_para_todos", sender.getName(), item.getCount(), item.getName()));
                    sender.sendMessage(PREFIX + M.t("entregue_para_jogador_es", given));
                    return true;
                }
                case "bloquearcmd": {
                    if (args.length < 1) { sender.sendMessage(PREFIX + M.t("bloqueados", data.<Object>read(s -> String.join(", ", s.blocked)))); return true; }
                    String c = StaffState.command(args[0]);
                    boolean[] added = new boolean[1];
                    change(sender, s -> { added[0] = s.blocked.add(c); if (!added[0]) { s.blocked.remove(c); } }, () -> "/" + c + (added[0] ? " bloqueado." : " liberado."));
                    return true;
                }
                case "clearlag": sender.sendMessage(PREFIX + M.t("entidades_removidas_2", clearlag())); return true;
                case "agendar": {
                    if (sender instanceof Player && !sender.isOp()) { sender.sendMessage(M.t("somente_console_op")); return true; }
                    if (args.length < 2) { sender.sendMessage(PREFIX + M.t("uso_agendar_minutos_remover_comando_atuais", data.<Object>read(s -> s.scheduled.toString()))); return true; }
                    String cmd = String.join(" ", Arrays.copyOfRange(args, 1, args.length)).replaceFirst("^/", "");
                    if (args[0].equalsIgnoreCase("remover")) { change(sender, s -> { if (s.scheduled.remove(cmd) == null) { throw new IllegalArgumentException(M.t("agendamento_inexistente")); } }, () -> M.t("agendamento_removido")); return true; }
                    int minutes = Integer.parseInt(args[0]);
                    change(sender, s -> s.scheduled.put(cmd, minutes), () -> "\"/" + cmd + "\" a cada " + minutes + " min.");
                    return true;
                }
                case "restart": {
                    if (args.length > 0 && args[0].equalsIgnoreCase("auto")) {
                        int hours = args.length > 1 ? Integer.parseInt(args[1]) : 0;
                        change(sender, s -> s.restartHours = hours, () -> { restartIn = hours == 0 ? -1 : hours * 3600; return hours == 0 ? M.t("restart_automatico_desligado") : M.t("restart_cada", hours); });
                        return true;
                    }
                    int seconds = args.length > 0 ? Math.max(5, Integer.parseInt(args[0])) : 60;
                    restartIn = seconds;
                    getServer().broadcastMessage(PREFIX + M.t("servidor_reinicia_em_2", seconds));
                    return true;
                }
                default: return false;
            }
        } catch (NumberFormatException e) { sender.sendMessage(PREFIX + M.t("numero_invalido")); return true; }
        catch (RuntimeException e) { sender.sendMessage(PREFIX + "§c" + root(e)); return true; }
    }

    private void report(CommandSender sender, String[] args) {
        if (!(sender instanceof Player) || args.length < 2) { sender.sendMessage(PREFIX + M.t("uso_report_jogador_motivo")); return; }
        Player p = (Player) sender;
        Long last = lastReport.get(p);
        if (last != null && System.currentTimeMillis() - last < 60_000) { throw new IllegalArgumentException(M.t("espere_1_minuto_entre_denuncias")); }
        String target = args[0].toLowerCase(Locale.ROOT);
        if (!target.matches("[a-z0-9_]{3,16}")) { throw new IllegalArgumentException(M.t("jogador_invalido")); }
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
                if (staff.hasPermission("goldstaff.reports")) { staff.sendMessage(PREFIX + M.t("report_por", id[0], target, p.getName(), finalReason)); }
            }
            return M.t("denuncia_enviada_para_staff_obrigado", id[0]);
        });
    }

    private void reports(CommandSender sender, String[] args) {
        if (args.length >= 2 && args[0].equalsIgnoreCase("fechar")) {
            int id = Integer.parseInt(args[1]);
            change(sender, s -> { if (s.reports.remove(id) == null) { throw new IllegalArgumentException(M.t("report_inexistente")); } }, () -> "Report #" + id + " fechado.");
            return;
        }
        List<String> lines = data.read(s -> {
            List<String> out = new ArrayList<>();
            for (Map.Entry<Integer, StaffState.Report> e : s.reports.entrySet()) { out.add("§e#" + e.getKey() + " §f" + e.getValue().target + " §7(" + e.getValue().reporter + "): " + e.getValue().reason); }
            return out;
        });
        if (lines.isEmpty()) { sender.sendMessage(PREFIX + M.t("nenhuma_denuncia_aberta")); return; }
        for (String line : lines.subList(Math.max(0, lines.size() - 15), lines.size())) { sender.sendMessage(line); }
        sender.sendMessage(PREFIX + M.t("feche_com_reports_fechar_id"));
    }

    private void change(CommandSender sender, java.util.function.Consumer<StaffState> mutation, java.util.function.Supplier<String> message) {
        data.change(mutation).whenComplete((v, e) -> { if (isEnabled()) { sender.sendMessage(PREFIX + (e != null ? "§c" + root(e) : message.get())); } });
    }

    static String root(Throwable e) { while (e.getCause() != null) { e = e.getCause(); } return e.getMessage(); }
}
