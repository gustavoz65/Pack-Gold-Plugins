package br.goldsuite.factions;

import cn.nukkit.Player;
import cn.nukkit.Server;
import cn.nukkit.block.Block;
import cn.nukkit.level.Level;
import cn.nukkit.level.particle.FloatingTextParticle;
import cn.nukkit.math.Vector3;
import java.util.*;

/**
 * Mini mapa construido no mundo: um bloco de la por chunk, uma cor por faccao e o nome flutuando
 * sobre o territorio. So os blocos que mudaram sao regravados; os nomes sao reenviados a cada atualizacao.
 */
final class MiniMap {
    /** Cores de la (meta) para as faccoes, na ordem; e o codigo de cor do chat equivalente. */
    static final int[] WOOL = {14, 11, 5, 4, 1, 10, 3, 6, 13, 9, 2, 12, 15, 0};
    static final char[] CHAT = {'c', '9', 'a', 'e', '6', '5', 'b', 'd', '2', '3', 'd', '6', '8', 'f'};
    static final int FREE = 7, GOLD_BLOCK = 41;

    private final Server server;
    private final Map<String, int[]> painted = new HashMap<>();
    private final Map<String, FloatingTextParticle> labels = new HashMap<>();
    private String paintedWorld;

    MiniMap(Server server) { this.server = server; }

    /** Indice estavel de cor: posicao da faccao na ordem alfabetica. */
    static int colorIndex(FactionState s, String faction) {
        int i = 0;
        for (String f : s.factions.keySet()) { if (f.equals(faction)) { return i % WOOL.length; } i++; }
        return 0;
    }

    void render(FactionState s) {
        FactionState.MiniMap map = s.map;
        if (map == null) { clearLabels(null); return; }
        Level level = server.getLevelByName(map.world);
        if (level == null) { return; }
        if (!map.world.equals(paintedWorld)) { painted.clear(); clearLabels(level); paintedWorld = map.world; }
        Map<String, double[]> centroid = new HashMap<>();
        int r = map.radius;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                String owner = s.claims.get(map.source + ":" + (map.sourceCx + dx) + ":" + (map.sourceCz + dz));
                int id, meta;
                if (owner != null) {
                    id = Block.WOOL; meta = WOOL[colorIndex(s, owner)];
                    double[] c = centroid.computeIfAbsent(owner, k -> new double[3]);
                    c[0] += dx; c[1] += dz; c[2]++;
                } else if (dx == 0 && dz == 0) { id = GOLD_BLOCK; meta = 0; }
                else { id = Block.WOOL; meta = FREE; }
                String key = dx + ":" + dz;
                int[] before = painted.get(key);
                if (before != null && before[0] == id && before[1] == meta) { continue; }
                Vector3 pos = new Vector3(map.x + dx, map.y, map.z + dz);
                level.setBlock(pos, Block.get(id, meta), true, true);
                painted.put(key, new int[] {id, meta});
            }
        }
        Player[] viewers = level.getPlayers().values().toArray(new Player[0]);
        for (Map.Entry<String, double[]> e : centroid.entrySet()) {
            String faction = e.getKey();
            double[] c = e.getValue();
            Vector3 at = new Vector3(map.x + c[0] / c[2] + 0.5, map.y + 2.2, map.z + c[1] / c[2] + 0.5);
            FloatingTextParticle label = labels.computeIfAbsent(faction, k -> new FloatingTextParticle(at, ""));
            label.setComponents(at.x, at.y, at.z);
            label.setInvisible(false);
            label.setTitle("§l§" + CHAT[colorIndex(s, faction)] + "[" + s.factions.get(faction).tag + "] " + faction);
            label.setText("§7poder §f" + s.power(faction) + " §8| §7" + s.claimsOf(faction) + " terras");
            if (viewers.length > 0) { level.addParticle(label, viewers); }
        }
        for (Iterator<Map.Entry<String, FloatingTextParticle>> it = labels.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, FloatingTextParticle> e = it.next();
            if (centroid.containsKey(e.getKey())) { continue; }
            e.getValue().setInvisible(true);
            if (viewers.length > 0) { level.addParticle(e.getValue(), viewers); }
            it.remove();
        }
    }

    /** Quem chega ao mundo do mapa recebe os nomes na hora. */
    void showTo(Player p) {
        if (paintedWorld == null || p.level == null || !paintedWorld.equals(p.level.getFolderName())) { return; }
        for (FloatingTextParticle label : labels.values()) { p.level.addParticle(label, p); }
    }

    private void clearLabels(Level level) {
        if (level != null) {
            Player[] viewers = level.getPlayers().values().toArray(new Player[0]);
            for (FloatingTextParticle label : labels.values()) { label.setInvisible(true); if (viewers.length > 0) { level.addParticle(label, viewers); } }
        }
        labels.clear();
    }

    /** Mapa em texto (11 x 11 chunks) em volta do jogador, para o chat. */
    static List<String> ascii(FactionState s, String world, int pcx, int pcz, String mine) {
        List<String> lines = new ArrayList<>();
        Map<String, Character> legend = new LinkedHashMap<>();
        for (int dz = -5; dz <= 5; dz++) {
            StringBuilder row = new StringBuilder();
            for (int dx = -5; dx <= 5; dx++) {
                if (dx == 0 && dz == 0) { row.append("§e+"); continue; }
                String owner = s.claims.get(world + ":" + (pcx + dx) + ":" + (pcz + dz));
                if (owner == null) { row.append("§8-"); continue; }
                char color = owner.equals(mine) ? 'a' : CHAT[colorIndex(s, owner)];
                legend.putIfAbsent(owner, color);
                row.append('§').append(color).append('#');
            }
            lines.add(row.toString());
        }
        StringBuilder l = new StringBuilder("§e+ §7voce  §8- §7livre");
        for (Map.Entry<String, Character> e : legend.entrySet()) { l.append("  §").append(e.getValue()).append("# §7").append(s.factions.get(e.getKey()).tag); }
        lines.add(l.toString());
        return lines;
    }
}
