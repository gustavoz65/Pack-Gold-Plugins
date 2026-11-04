package br.goldsuite.skywars;

import cn.nukkit.Player;
import cn.nukkit.item.Item;
import cn.nukkit.potion.Effect;
import java.util.*;

/** Uma escolha por jogador/categoria. Empates usam o padrao, sem sorteio a cada tick. */
final class MatchOptions {
    static final String[] CATEGORIES = {"baus","hora","clima","vida","modificador"};
    static final String[][] VALUES = {{"basico","normal","insano"},{"dia","noite"},{"limpo","chuva"},{"normal","dupla"},{"nenhum","velocidade","pulo"}};
    final Map<Player, Map<String, String>> votes = new IdentityHashMap<>();
    String winner(String category, String fallback) {
        Map<String, Integer> totals = new TreeMap<>();
        for (Map<String, String> vote : votes.values()) { String choice = vote.get(category); if (choice != null) { totals.merge(choice, 1, Integer::sum); } }
        String result = fallback; int best = 0; boolean tied = false;
        for (Map.Entry<String, Integer> e : totals.entrySet()) {
            if (e.getValue() > best) { best = e.getValue(); result = e.getKey(); tied = false; }
            else if (e.getValue() == best) { tied = true; }
        }
        return tied ? fallback : result;
    }
    void vote(Player p, String category, String value) {
        int index = Arrays.asList(CATEGORIES).indexOf(category);
        if (index < 0 || !Arrays.asList(VALUES[index]).contains(value)) { throw new IllegalArgumentException(M.t("voto_uso")); }
        votes.computeIfAbsent(p, k -> new HashMap<>()).put(category, value);
    }
    static void menu(GoldSkyWars plugin, Player p) {
        GoldSkyWars.Game g = plugin.inGame.get(p);
        if (g == null || !g.waiting()) { throw new IllegalArgumentException(M.t("perfil_antes_partida")); }
        Map<Integer, Item> icons = new HashMap<>(); final List<String[]> actions = new ArrayList<>();
        for (int i = 0; i < CATEGORIES.length; i++) { for (String value : VALUES[i]) {
            icons.put(actions.size(), Progression.icon(339, M.t("voto_item", CATEGORIES[i], value))); actions.add(new String[] {CATEGORIES[i], value});
        } }
        plugin.menus().open(p, M.t("voto_menu"), icons, (who, slot) -> {
            if (slot < 0 || slot >= actions.size() || plugin.inGame.get(who) != g || !g.waiting()) { return; }
            String[] action = actions.get(slot); g.options.vote(who, action[0], action[1]); who.sendMessage(M.t("voto_salvo", action[0], action[1]));
        });
    }
    void apply(GoldSkyWars plugin, GoldSkyWars.Game g) {
        g.lootMode = winner("baus", g.arena().insane ? "insano" : "normal");
        g.level().setTime(winner("hora", "dia").equals("noite") ? 14000 : 1000);
        g.level().setRaining(winner("clima", "limpo").equals("chuva")); g.level().setThundering(false);
        boolean doubled = winner("vida", "normal").equals("dupla");
        String modifier = winner("modificador", "nenhum");
        for (Player p : g.players.keySet()) {
            p.setMaxHealth(doubled ? 40 : 20); p.setHealth(p.getMaxHealth());
            if (!modifier.equals("nenhum")) { p.addEffect(Effect.getEffect(modifier.equals("pulo") ? Effect.JUMP : Effect.SPEED).setDuration((int)plugin.gameSeconds * 20).setAmplifier(0)); }
        }
    }
}

