package br.goldsuite.hud;

import br.goldsuite.api.*;
import cn.nukkit.Player;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.player.PlayerQuitEvent;
import cn.nukkit.item.Item;
import cn.nukkit.plugin.PluginBase;
import cn.nukkit.scheduler.PluginTask;
import java.math.BigDecimal;
import java.util.*;

/** Barra acima da hotbar: coordenadas, dinheiro, grupo e armadura (superBAR, Cordenadas, Status Armor, itemID). */
public final class GoldHud extends PluginBase implements Listener {
    static final String PREFIX = "§9[§bHUD§9]§f ";
    /** Durabilidade maxima por id de armadura da 0.15 (couro, malha, ferro, diamante, ouro). */
    private static final Map<Integer, Integer> ARMOR = new HashMap<>();
    static {
        int[][] t = {{298,56},{299,81},{300,76},{301,66},{302,166},{303,241},{304,226},{305,196},{306,166},{307,241},{308,226},{309,196},{310,364},{311,529},{312,496},{313,430},{314,78},{315,113},{316,106},{317,92}};
        for (int[] a : t) { ARMOR.put(a[0], a[1]); }
    }
    private PersistentState<HudState> data;

    public static final class HudState {
        public Set<String> off = new TreeSet<>();
        /** Mundos sem HUD (ex.: arenas de HG, que usam a propria barra). */
        public Set<String> hiddenWorlds = new TreeSet<>(Collections.singleton("HG"));
        static void validate(HudState s) {
            if (s == null || s.off == null || s.hiddenWorlds == null || s.off.size() > 100000 || s.hiddenWorlds.size() > 64) { throw new IllegalArgumentException("HUD invalido."); }
        }
    }

    @Override public void onEnable() {
        CoreService core = getServer().getServiceManager().load(CoreService.class);
        if (core == null) { throw new IllegalStateException("GoldCore precisa estar ativo antes do GoldHud."); }
        data = new PersistentState<>(core.getStore(), "goldhud", HudState.class, HudState::new, HudState::validate);
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getScheduler().scheduleRepeatingTask(new PluginTask<GoldHud>(this) { @Override public void onRun(int tick) { tick(); } }, 20);
    }

    @Override public void onDisable() { if (data != null) { data.close(); } }

    /** Um popup por jogador por segundo; servicos e listas consultados uma vez por ciclo. */
    private void tick() {
        if (!data.isLoaded() || getServer().getOnlinePlayers().isEmpty()) { return; }
        EconomyService economy = getServer().getServiceManager().load(EconomyService.class);
        PermissionService perms = getServer().getServiceManager().load(PermissionService.class);
        AuthService auth = getServer().getServiceManager().load(AuthService.class);
        Set<String> off = data.read(s -> s.off), hidden = data.read(s -> s.hiddenWorlds);
        for (Player p : getServer().getOnlinePlayers().values()) {
            if (!p.spawned || hidden.contains(p.level.getFolderName()) || off.contains(p.getName().toLowerCase(Locale.ROOT))) { continue; }
            if (auth != null && !auth.isAuthenticated(p)) { continue; }
            p.sendPopup(line(p, economy, perms));
        }
    }

    static String direction(double yaw) {
        String[] dirs = {"S", "SO", "O", "NO", "N", "NE", "L", "SE"};
        return dirs[(int) Math.floorMod(Math.round(((yaw % 360) + 360) % 360 / 45.0), 8)];
    }

    private static String line(Player p, EconomyService economy, PermissionService perms) {
        StringBuilder b = new StringBuilder();
        b.append("§e").append(p.getFloorX()).append(' ').append(p.getFloorY()).append(' ').append(p.getFloorZ()).append(" §7").append(direction(p.yaw));
        if (economy != null) { try { b.append(" §8| §a$").append(BigDecimal.valueOf(economy.balance(p.getName(), EconomyService.Currency.MONEY), 2).toPlainString()); } catch (RuntimeException ignored) { } }
        if (perms != null) { try { String g = perms.getGroup(p.getName()); if (!g.equals("padrao")) { b.append(" §8| §6").append(g.toUpperCase(Locale.ROOT)); } } catch (RuntimeException ignored) { } }
        int armor = armorPercent(p.getInventory().getArmorContents());
        if (armor >= 0) { b.append(" §8| ").append(armor <= 20 ? "§c" : "§b").append("Armadura ").append(armor).append('%'); }
        return b.toString();
    }

    /** Media da durabilidade restante das pecas vestidas, ou -1 sem armadura. */
    static int armorPercent(Item[] armor) {
        int total = 0, pieces = 0;
        for (Item piece : armor) {
            Integer max = ARMOR.get(piece.getId());
            if (max == null) { continue; }
            total += Math.max(0, max - piece.getDamage()) * 100 / max;
            pieces++;
        }
        return pieces == 0 ? -1 : total / pieces;
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equals("id")) {
            if (!(sender instanceof Player)) { return true; }
            Item hand = ((Player) sender).getInventory().getItemInHand();
            sender.sendMessage(PREFIX + hand.getName() + " §7= §e" + hand.getId() + (hand.getDamage() != 0 ? ":" + hand.getDamage() : ""));
            return true;
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("mundo") && sender.hasPermission("goldhud.admin")) {
            String world = Destination.world(args[1]).target;
            boolean[] hide = new boolean[1];
            data.change(s -> { hide[0] = s.hiddenWorlds.add(world); if (!hide[0]) { s.hiddenWorlds.remove(world); } }).thenRun(() -> sender.sendMessage(PREFIX + "HUD " + (hide[0] ? "oculto" : "visivel") + " em " + world + "."));
            return true;
        }
        String name = sender.getName().toLowerCase(Locale.ROOT);
        boolean[] nowOff = new boolean[1];
        data.change(s -> { nowOff[0] = s.off.add(name); if (!nowOff[0]) { s.off.remove(name); } }).thenRun(() -> sender.sendMessage(PREFIX + "HUD " + (nowOff[0] ? "desligado." : "ligado.")));
        return true;
    }

}
