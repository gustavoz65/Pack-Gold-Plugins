package br.goldsuite.skywars;

import cn.nukkit.Player;
import cn.nukkit.level.Level;
import java.util.*;

/**
 * Fila do lobby (/sw fila solo|dupla, pensada para o NPC): o jogador espera no lobby; com o minimo de jogadores
 * comeca uma contagem e, no fim, o grupo vai junto para um mapa sorteado. Se a fila cai abaixo do minimo, a contagem para.
 * Tocar de novo no NPC (ou /sw fila) sai da fila. Trabalho por segundo: so percorre quem esta na fila.
 */
final class LobbyQueue {
    private final GoldSkyWars plugin;
    /** Tamanho do time (1 solo, 2 dupla) -> jogadores na ordem em que entraram. */
    private final Map<Integer, LinkedHashSet<Player>> queues = new HashMap<>();
    /** Mundo onde cada um entrou na fila: sair do lobby tira da fila. */
    private final Map<Player, Level> where = new IdentityHashMap<>();
    private final Map<Integer, Integer> seconds = new HashMap<>();
    int minimum = 2, countdown = 10;
    private final Random random = new Random();

    LobbyQueue(GoldSkyWars plugin) { this.plugin = plugin; }

    /** Entra na fila ou, se ja estava nela, sai. */
    void toggle(Player p, int teamSize) {
        plugin.checkCanJoin(p);
        LinkedHashSet<Player> q = queues.computeIfAbsent(teamSize, k -> new LinkedHashSet<>());
        if (q.remove(p)) { where.remove(p); p.sendMessage(GoldSkyWars.PREFIX + M.t("fila_saiu")); return; }
        forget(p);
        q.add(p);
        where.put(p, p.level);
        p.sendMessage(GoldSkyWars.PREFIX + M.t("fila_entrou", mode(teamSize), q.size(), minimum));
    }

    void forget(Player p) { for (Set<Player> q : queues.values()) { q.remove(p); } where.remove(p); }

    boolean queued(Player p) { return where.containsKey(p); }

    /** Chamado a cada segundo pelo plugin. */
    void second() {
        if (where.isEmpty()) { return; }
        for (Map.Entry<Integer, LinkedHashSet<Player>> e : queues.entrySet()) {
            int size = e.getKey();
            LinkedHashSet<Player> q = e.getValue();
            q.removeIf(p -> { boolean out = !p.isOnline() || plugin.inGame.containsKey(p) || p.level != where.get(p); if (out) { where.remove(p); } return out; });
            if (q.isEmpty()) { seconds.remove(size); continue; }
            if (q.size() < minimum) {
                seconds.remove(size);
                for (Player p : q) { p.sendTip(M.t("fila_aguardando", mode(size), q.size(), minimum)); }
                continue;
            }
            // Primeiro segundo com o minimo: comeca em "countdown"; depois desce 1 por segundo.
            Integer current = seconds.get(size);
            int left = current == null ? countdown : current - 1;
            seconds.put(size, left);
            if (left > 0) {
                for (Player p : q) { p.sendTip(M.t("fila_contagem", mode(size), left, q.size())); }
                continue;
            }
            seconds.remove(size);
            GoldSkyWars.Game target = pick(size, q.size());
            if (target == null) {
                for (Player p : q) { p.sendMessage(GoldSkyWars.PREFIX + M.t("nenhuma_sala_aberta_agora_tente_em", size == 1 ? M.t("solo") : M.t("de_dupla"))); }
                seconds.put(size, countdown);
                continue;
            }
            for (Player p : new ArrayList<>(q)) {
                if (target.players.size() >= target.capacity()) { break; }
                q.remove(p); where.remove(p);
                try { plugin.join(p, size, target); }
                catch (RuntimeException ex) { p.sendMessage(GoldSkyWars.PREFIX + "§c" + ex.getMessage()); }
            }
        }
    }

    /** Sorteia entre as salas vazias que cabem o grupo; sem sala vazia, a que tem mais vagas. */
    private GoldSkyWars.Game pick(int size, int group) {
        List<GoldSkyWars.Game> empty = new ArrayList<>();
        GoldSkyWars.Game roomiest = null;
        for (GoldSkyWars.Game g : plugin.games.values()) {
            if (!plugin.open(g, size)) { continue; }
            int free = g.capacity() - g.players.size();
            if (g.players.isEmpty() && free >= Math.min(group, g.capacity())) { empty.add(g); }
            if (roomiest == null || free > roomiest.capacity() - roomiest.players.size()) { roomiest = g; }
        }
        return empty.isEmpty() ? roomiest : empty.get(random.nextInt(empty.size()));
    }

    private static String mode(int size) { return size == 1 ? M.t("fila_solo") : M.t("fila_dupla"); }
}
