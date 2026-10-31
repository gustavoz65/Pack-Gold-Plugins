package br.goldsuite.party;

import cn.nukkit.Player;
import cn.nukkit.entity.Entity;
import cn.nukkit.level.Level;
import cn.nukkit.level.Location;
import java.util.*;

/**
 * Uma sala do Party numa vaga propria do mundo: espera, sorteio das provas, cada prova (construir, apresentar, jogar,
 * resultado) e o ranking final. Varias salas rodam ao mesmo tempo, cada uma com as suas arenas.
 */
final class Room {
    enum Phase { ESPERANDO, CONSTRUINDO, APRESENTANDO, JOGANDO, RESULTADO, FINAL }

    /** Pontos por colocacao em cada prova (empatados levam os pontos da melhor posicao do grupo). */
    static final int[] POINTS = {10, 8, 6, 5, 4, 3, 2, 1};
    static final String[] COLORS = {"§c", "§9", "§a", "§e", "§d", "§b", "§6", "§f"};

    final GoldParty plugin;
    final int id, slot;
    final Blocks.Queue queue = new Blocks.Queue();
    final List<Player> players = new ArrayList<>();
    final Set<Player> spectators = new HashSet<>();
    final Map<Player, Integer> total = new HashMap<>(), firsts = new HashMap<>();
    final List<Integer> order = new ArrayList<>();
    final Map<Player, Location> frozen = new HashMap<>();
    private final List<Object[]> delayed = new ArrayList<>();
    Phase phase = Phase.ESPERANDO;
    Mini mini;
    int index = -1, countdown = -1, timer, ticks;
    boolean forced;
    private String pendingBuilt;

    Room(GoldParty plugin, int id, int slot) { this.plugin = plugin; this.id = id; this.slot = slot; }

    Level level() { return plugin.world; }
    int ox() { return slot * 1024; }
    int oz(int miniIndex) { return miniIndex * 128; }
    Location lobby() { return new Location(ox() + 48.5, 101, oz(12) + 48.5, 0, 0, level()); }

    boolean alive(Player p) { return players.contains(p) && !spectators.contains(p); }
    int aliveCount() { int n = 0; for (Player p : players) { if (!spectators.contains(p)) { n++; } } return n; }
    String color(Player p) { int i = players.indexOf(p); return i < 0 ? "§7" : COLORS[i % COLORS.length]; }
    boolean waiting() { return phase == Phase.ESPERANDO; }

    void say(String msg) { for (Player p : players) { p.sendMessage(msg); } }
    void later(int ticksAhead, Runnable r) { delayed.add(new Object[] {ticks + ticksAhead, r}); }

    /** Eliminado: invisivel, voando e sem itens, assistindo de cima. */
    void spectate(Player p, Location where) {
        spectators.add(p);
        p.getInventory().clearAll();
        p.setDataFlag(Entity.DATA_FLAGS, Entity.DATA_FLAG_INVISIBLE, true);
        if (!p.getAllowFlight()) { p.setAllowFlight(true); }
        p.setHealth(p.getMaxHealth());
        p.teleport(where);
    }

    /** Devolve o jogador ao normal para a proxima prova. */
    void revive(Player p) {
        spectators.remove(p);
        p.setDataFlag(Entity.DATA_FLAGS, Entity.DATA_FLAG_INVISIBLE, false);
        if (!p.isCreative()) { p.setAllowFlight(false); }
        p.getInventory().clearAll();
        p.removeAllEffects();
        p.extinguish();
        p.setHealth(p.getMaxHealth());
        p.getFoodData().setLevel(20);
        p.setMotion(new cn.nukkit.math.Vector3(0, 0, 0));
        // Os itens das provas vao nos slots 0-8: a hotbar precisa apontar para eles.
        for (int i = 0; i < 9; i++) { if (p.getInventory().getHotbarSlotIndex(i) < 0) { p.getInventory().setHotbarSlotIndex(i, i); } }
    }

    // ---------- ciclo ----------

    void tick() {
        ticks++;
        if (!delayed.isEmpty()) {
            Iterator<Object[]> it = delayed.iterator();
            List<Runnable> run = new ArrayList<>();
            while (it.hasNext()) { Object[] d = it.next(); if ((int) d[0] <= ticks) { run.add((Runnable) d[1]); it.remove(); } }
            for (Runnable r : run) { r.run(); }
        }
        // Fora da prova ninguem cai para sempre: volta para cima.
        if (phase != Phase.JOGANDO && phase != Phase.APRESENTANDO && ticks % 10 == 0) {
            for (Player p : players) { if (p.y < 40) { p.teleport(mini != null && phase != Phase.FINAL ? mini.spectatorSpot() : lobby()); } }
        }
        switch (phase) {
            case ESPERANDO: if (ticks % 20 == 0) { waitingSecond(); } return;
            case CONSTRUINDO:
                if (queue.empty()) {
                    if (pendingBuilt != null) { plugin.markBuilt(pendingBuilt); pendingBuilt = null; }
                    present();
                }
                return;
            case APRESENTANDO:
                for (Map.Entry<Player, Location> e : frozen.entrySet()) {
                    Player p = e.getKey(); Location l = e.getValue();
                    if ((p.x - l.x) * (p.x - l.x) + (p.z - l.z) * (p.z - l.z) > 0.6 || p.y < l.y - 1.5) { p.teleport(l); }
                }
                if (--timer <= 0) { frozen.clear(); mini.start(); phase = Phase.JOGANDO; for (Player p : players) { p.sendPopup("§a§lVAI!"); } }
                else if (timer % 20 == 0 && timer <= 60) { for (Player p : players) { p.sendPopup("§e" + timer / 20); } }
                return;
            case JOGANDO: playing(); return;
            case RESULTADO: if (--timer <= 0) { next(); } return;
            default: if (--timer <= 0) { plugin.close(this); }
        }
    }

    private void waitingSecond() {
        int min = forced ? 1 : plugin.minPlayers;
        if (players.size() < min) {
            if (countdown >= 0) { say("§cContagem cancelada: faltam jogadores."); }
            countdown = -1;
            for (Player p : players) { p.sendTip("§eParty §7| aguardando jogadores §f" + players.size() + "/" + plugin.maxPlayers + " §7(minimo " + plugin.minPlayers + ")"); }
            return;
        }
        if (countdown < 0) { countdown = forced ? 5 : plugin.countdown; say("§aPartida comecando em " + countdown + " s!"); }
        if (players.size() >= plugin.maxPlayers && countdown > 10) { countdown = 10; }
        for (Player p : players) { p.sendTip("§eParty §7| comeca em §f" + countdown + " s §7| jogadores §f" + players.size() + "/" + plugin.maxPlayers); }
        if (countdown <= 5 && countdown > 0) { say("§e" + countdown + "..."); }
        if (--countdown < 0) { begin(); }
    }

    /** Sorteia as provas e comeca. */
    private void begin() {
        List<Integer> pool = new ArrayList<>();
        for (int i = 0; i < Minis.ALL.size(); i++) { if (!plugin.disabled.contains(Minis.ALL.get(i).get().id())) { pool.add(i); } }
        Collections.shuffle(pool);
        order.clear();
        order.addAll(pool.subList(0, Math.min(plugin.perMatch, pool.size())));
        for (Player p : players) { total.put(p, 0); }
        StringBuilder names = new StringBuilder();
        for (int i : order) { names.append(names.length() == 0 ? "" : "§7, §f").append(Minis.ALL.get(i).get().name()); }
        say("§6§l===== PARTY =====");
        say("§fProvas: §f" + names);
        plugin.opened(this);
        next();
    }

    private void next() {
        if (mini != null) { mini.cleanup(queue); mini = null; }
        if (players.size() < 2 && !forced || ++index >= order.size()) { finish(); return; }
        int k = order.get(index);
        mini = Minis.ALL.get(k).get();
        mini.room = this; mini.ox = ox(); mini.oz = oz(k);
        String key = slot + ":" + mini.id() + ":" + mini.version();
        if (!plugin.isBuilt(key)) { mini.buildStatic(queue); pendingBuilt = key; }
        mini.prepare(queue);
        // Todos voltam ao jogo e esperam em cima da arena enquanto ela fica pronta.
        for (Player p : players) { revive(p); spectators.add(p); p.setDataFlag(Entity.DATA_FLAGS, Entity.DATA_FLAG_INVISIBLE, true); if (!p.getAllowFlight()) { p.setAllowFlight(true); } p.teleport(mini.spectatorSpot()); }
        phase = Phase.CONSTRUINDO;
    }

    private void present() {
        int i = 0;
        for (Player p : players) {
            revive(p);
            Location l = mini.spawn(i++, players.size());
            p.teleport(l);
            frozen.put(p, l);
        }
        say("§6§l===== " + mini.name().toUpperCase(Locale.ROOT) + " §r§7(" + (index + 1) + "/" + order.size() + ")");
        say("§f" + mini.description());
        say("§7" + (mini.kind() == Mini.Kind.TEMPO ? "Vale a ordem de chegada." : mini.kind() == Mini.Kind.SOBREVIVENCIA ? "Vale quem sobreviver mais." : "Vale quem fizer mais pontos."));
        timer = 100;
        phase = Phase.APRESENTANDO;
    }

    private void playing() {
        if (players.size() < 2 && !forced) { results(); return; }
        Mini m = mini;
        m.elapsedTicks++;
        m.tick();
        if (phase != Phase.JOGANDO || mini != m) { return; }
        int voidY = m.voidY();
        for (int i = 0; i < players.size(); i++) {
            Player p = players.get(i);
            if (!spectators.contains(p) && p.y < voidY) { m.fell(p); }
        }
        if (m.elapsedTicks % 20 == 0) {
            m.second();
            for (Player p : players) { p.sendTip(m.tip(p)); }
        }
        if (m.elapsedTicks >= m.seconds() * 20 || m.done()) { results(); }
    }

    private void results() {
        List<List<Player>> ranking = mini.ranking();
        say("§6--- Resultado: " + mini.name() + " ---");
        int pos = 0;
        for (List<Player> group : ranking) {
            int pts = pos < POINTS.length ? POINTS[pos] : 0;
            StringBuilder names = new StringBuilder();
            for (Player p : group) {
                if (!players.contains(p)) { continue; }
                total.merge(p, pts, Integer::sum);
                if (pos == 0) { firsts.merge(p, 1, Integer::sum); }
                names.append(names.length() == 0 ? "" : ", ").append(color(p)).append(p.getName());
            }
            if (names.length() > 0) { say("§e" + (pos + 1) + "o §f" + names + " §7+" + pts); }
            pos += group.size();
        }
        say("§7Placar: " + standings(3));
        timer = 100;
        phase = Phase.RESULTADO;
    }

    /** Ranking geral: pontos, desempate por primeiros lugares. */
    List<Player> sorted() {
        List<Player> list = new ArrayList<>(players);
        list.sort((a, b) -> !total.getOrDefault(a, 0).equals(total.getOrDefault(b, 0)) ? Integer.compare(total.getOrDefault(b, 0), total.getOrDefault(a, 0))
                : Integer.compare(firsts.getOrDefault(b, 0), firsts.getOrDefault(a, 0)));
        return list;
    }

    String standings(int n) {
        StringBuilder s = new StringBuilder();
        List<Player> list = sorted();
        for (int i = 0; i < list.size() && i < n; i++) { s.append(i == 0 ? "" : "§7, ").append(color(list.get(i))).append(list.get(i).getName()).append(" §f").append(total.getOrDefault(list.get(i), 0)); }
        return s.toString();
    }

    private void finish() {
        if (mini != null) { mini.cleanup(queue); mini = null; }
        phase = Phase.FINAL;
        timer = 120;
        List<Player> list = sorted();
        say("§6§l===== RANKING FINAL =====");
        for (int i = 0; i < list.size(); i++) {
            Player p = list.get(i);
            revive(p);
            p.teleport(lobby());
            say((i == 0 ? "§6§l1o " : i == 1 ? "§f§l2o " : i == 2 ? "§c§l3o " : "§7" + (i + 1) + "o ") + "§r" + color(p) + p.getName() + " §7- §f" + total.getOrDefault(p, 0) + " pontos");
        }
        plugin.finished(this, list);
    }
}
