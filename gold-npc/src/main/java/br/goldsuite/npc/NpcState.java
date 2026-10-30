package br.goldsuite.npc;

import br.goldsuite.api.Destination;
import br.goldsuite.api.TravelService.Transition;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Documento persistido: so dados, sem referencias a jogadores ou entidades. */
public final class NpcState {
    public static final int MAX_NPCS = 256;
    public static final int MAX_NAME = 256;
    public static final int MAX_COMMAND = 200;
    public int nextId = 1;
    public Map<Integer, Npc> npcs = new TreeMap<>();

    public static final class Npc {
        /** "humano" ou um tipo de criatura de {@link #TYPES}. */
        public String type = "humano";
        public String world;
        public double x, y, z;
        public float yaw, pitch;
        /** Linhas separadas por \n; aceita {online}, {max} e {mundo:nome}. */
        public String name = "";
        /** SHA-256 da skin salva em separado (so para humano). */
        public String skin = "";
        public int itemId, itemMeta;
        public boolean lookAt = true;
        public Destination destination;
        public String transition = "PORTAL";
        /** Comando executado como o jogador; {jogador} vira o nome de quem clicou. */
        public String command = "";
        /** "quadro:posicao" (ex.: "ranked:1"): nome e skin seguem o jogador naquela posicao do ranking. */
        public String ranking = "";

        public Transition transition() { return Transition.valueOf(transition); }
    }

    /** Tipos de criatura com id de rede da 0.15.10 e altura dos olhos (para olhar o jogador). */
    public static final Map<String, float[]> TYPES;
    static {
        Map<String, float[]> t = new HashMap<>();
        t.put("galinha", new float[] {10, 0.5f}); t.put("vaca", new float[] {11, 1.2f}); t.put("porco", new float[] {12, 0.7f});
        t.put("ovelha", new float[] {13, 1.1f}); t.put("lobo", new float[] {14, 0.7f}); t.put("villager", new float[] {15, 1.62f});
        t.put("golem", new float[] {20, 2.3f}); t.put("bonecodeneve", new float[] {21, 1.7f}); t.put("zumbi", new float[] {32, 1.62f});
        t.put("creeper", new float[] {33, 1.45f}); t.put("esqueleto", new float[] {34, 1.62f}); t.put("aranha", new float[] {35, 0.65f});
        t.put("porcozumbi", new float[] {36, 1.62f}); t.put("enderman", new float[] {38, 2.55f}); t.put("blaze", new float[] {43, 1.5f});
        t.put("zumbivillager", new float[] {44, 1.62f}); t.put("bruxa", new float[] {45, 1.62f});
        TYPES = Collections.unmodifiableMap(t);
    }
    public static final float HUMAN_EYE = 1.62f;

    public static String type(String text) {
        String type = text.toLowerCase(Locale.ROOT);
        if (!type.equals("humano") && !TYPES.containsKey(type)) { throw new IllegalArgumentException("Tipo invalido. Use humano ou: " + new TreeMap<>(TYPES).keySet()); }
        return type;
    }
    /** "|" vira quebra de linha; codigos de cor com & viram secao. */
    public static String name(String text) {
        String name = text.replace("|", "\n").replace('&', '§');
        if (name.length() > MAX_NAME || name.chars().filter(c -> c == '\n').count() > 4) { throw new IllegalArgumentException("Nome: ate " + MAX_NAME + " caracteres e 5 linhas."); }
        return name;
    }
    public static String command(String text) {
        String command = text.startsWith("/") ? text.substring(1) : text;
        if (command.length() > MAX_COMMAND || command.indexOf('\n') >= 0 || command.indexOf('\r') >= 0) { throw new IllegalArgumentException("Comando invalido ou longo demais."); }
        return command;
    }

    public Npc require(int id) {
        Npc npc = npcs.get(id);
        if (npc == null) { throw new IllegalArgumentException("NPC inexistente: " + id); }
        return npc;
    }

    public static void validate(NpcState state) {
        if (state == null || state.npcs == null || state.npcs.size() > MAX_NPCS || state.nextId < 1) { throw new IllegalArgumentException("Dados de NPC invalidos ou excessivos."); }
        for (Map.Entry<Integer, Npc> entry : state.npcs.entrySet()) {
            Npc npc = entry.getValue();
            if (entry.getKey() == null || entry.getKey() < 1 || entry.getKey() >= state.nextId || npc == null) { throw new IllegalArgumentException("NPC invalido."); }
            type(npc.type);
            Destination.position(npc.world, npc.x, npc.y, npc.z, npc.yaw, npc.pitch);
            if (npc.name == null || npc.name.length() > MAX_NAME || npc.skin == null || !npc.skin.matches("|[0-9a-f]{64}") || npc.command == null || npc.command.length() > MAX_COMMAND) { throw new IllegalArgumentException("NPC " + entry.getKey() + " invalido."); }
            if (npc.ranking == null || !npc.ranking.matches("|[a-z0-9_-]{1,24}:([1-9]|10)")) { throw new IllegalArgumentException("Ranking do NPC invalido."); }
            if (npc.itemId < 0 || npc.itemId > 511 || npc.itemMeta < 0 || npc.itemMeta > 32767) { throw new IllegalArgumentException("Item do NPC invalido."); }
            Transition.valueOf(npc.transition);
            if (npc.destination != null) { npc.destination.validate(); }
        }
    }
}
