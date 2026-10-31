package br.goldsuite.comandos;

import java.util.*;

/** Zonas (grupos de mundos) com regras de comando, boas-vindas e itens da hotbar, mais a protecao do OP por IP. */
public final class CmdState {
    public static final int MAX_ZONES = 64, MAX_COMMANDS = 200, MAX_VISITS = 50_000;

    public Map<String, Zone> zones = new TreeMap<>();
    /** Comandos que funcionam em qualquer zona (login, suporte, spawn...). */
    public Set<String> always = new TreeSet<>(Arrays.asList("login", "logar", "registrar", "register", "2fa", "suporte", "ticket", "atendimento", "finalizar", "avaliar",
            "ajuda", "help", "spawn", "lobby", "tell", "msg", "w", "r", "responder", "equipe", "staff", "cargos", "report", "denunciar"));
    /** Nick (minusculo) -> IPs de onde ele pode dar OP pelo jogo e manter o proprio OP. */
    public Map<String, Set<String>> opIps = new TreeMap<>();
    /** Com a lista preenchida: OP que entra de outro IP perde o OP ate voltar de um IP autorizado. */
    public boolean opRequireIp = true;
    /** OPs suspensos por entrar de IP desconhecido (devolvido quando voltam do IP certo). */
    public Set<String> suspended = new TreeSet<>();
    /** Jogador -> zonas que ja visitou (boas-vindas so na primeira vez). */
    public Map<String, Set<String>> visited = new HashMap<>();

    public static final class Zone {
        public Set<String> worlds = new TreeSet<>();
        /** true: so os comandos da lista funcionam; false: os da lista ficam bloqueados. */
        public boolean whitelist;
        public Set<String> commands = new TreeSet<>();
        public String welcome = "";
        public boolean welcomeAlways;
        /** Linhas extras no topo do /help desta zona. */
        public List<String> help = new ArrayList<>();
        public Map<Integer, HubItem> items = new TreeMap<>();
    }

    public static final class HubItem {
        public int id, meta;
        public String name = "", command = "";
        public HubItem() { }
        public HubItem(int id, int meta, String name, String command) { this.id = id; this.meta = meta; this.name = name; this.command = command; }
    }

    public static String zone(String name) {
        String z = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (!z.matches("[a-z0-9_-]{1,24}")) { throw new IllegalArgumentException("Nome de zona: ate 24 letras minusculas, numeros, _ ou -."); }
        return z;
    }

    public static String command(String text) {
        String c = text == null ? "" : text.toLowerCase(Locale.ROOT);
        if (c.startsWith("/")) { c = c.substring(1); }
        if (c.contains(":")) { c = c.substring(c.indexOf(':') + 1); }
        if (!c.matches("[a-z0-9_?-]{1,32}")) { throw new IllegalArgumentException("Comando invalido: " + text); }
        return c;
    }

    public static String ip(String text) {
        String v = text == null ? "" : text.trim();
        if (!v.matches("\\d{1,3}(\\.\\d{1,3}){3}") && !v.matches("[0-9a-fA-F:]{2,39}")) { throw new IllegalArgumentException("IP invalido."); }
        return v.toLowerCase(Locale.ROOT);
    }

    /** Zona de cada mundo (indice montado a cada mudanca, consulta O(1) por comando). */
    public Map<String, String> worldIndex() {
        Map<String, String> out = new HashMap<>();
        for (Map.Entry<String, Zone> e : zones.entrySet()) { for (String w : e.getValue().worlds) { out.put(w.toLowerCase(Locale.ROOT), e.getKey()); } }
        return out;
    }

    /** O comando (nome real e o que foi digitado) pode ser usado nesta zona? */
    public boolean allowed(Zone zone, String typed, String real) {
        if (zone == null || always.contains(typed) || always.contains(real)) { return true; }
        boolean listed = zone.commands.contains(typed) || zone.commands.contains(real);
        return zone.whitelist == listed;
    }

    public boolean opAllowed(String player, String address) {
        Set<String> ips = opIps.get(player.toLowerCase(Locale.ROOT));
        return ips != null && address != null && ips.contains(address.toLowerCase(Locale.ROOT));
    }

    /** Marca a visita; devolve true se foi a primeira. */
    public boolean visit(String player, String zone) {
        if (visited.size() >= MAX_VISITS && !visited.containsKey(player)) { visited.clear(); }
        return visited.computeIfAbsent(player, k -> new TreeSet<>()).add(zone);
    }

    public static void validate(CmdState s) {
        if (s == null || s.zones == null || s.always == null || s.opIps == null || s.suspended == null || s.visited == null || s.zones.size() > MAX_ZONES
                || s.always.size() > MAX_COMMANDS || s.opIps.size() > 100 || s.visited.size() > MAX_VISITS) { throw new IllegalArgumentException("Configuracao de comandos invalida."); }
        Set<String> seenWorlds = new HashSet<>();
        for (Map.Entry<String, Zone> e : s.zones.entrySet()) {
            zone(e.getKey());
            Zone z = e.getValue();
            if (z == null || z.worlds == null || z.commands == null || z.welcome == null || z.help == null || z.items == null || z.commands.size() > MAX_COMMANDS
                    || z.worlds.size() > 100 || z.welcome.length() > 200 || z.help.size() > 15 || z.items.size() > 9) { throw new IllegalArgumentException("Zona invalida: " + e.getKey()); }
            for (String w : z.worlds) { if (w == null || w.length() > 64 || !seenWorlds.add(w.toLowerCase(Locale.ROOT))) { throw new IllegalArgumentException("Mundo em duas zonas ou invalido: " + w); } }
            for (String c : z.commands) { command(c); }
            for (String h : z.help) { if (h == null || h.length() > 150) { throw new IllegalArgumentException("Linha de ajuda invalida."); } }
            for (Map.Entry<Integer, HubItem> i : z.items.entrySet()) {
                HubItem it = i.getValue();
                if (i.getKey() == null || i.getKey() < 0 || i.getKey() > 8 || it == null || it.id <= 0 || it.id > 511 || it.meta < 0 || it.meta > 32767 || it.name == null || it.name.isEmpty()
                        || it.name.length() > 40 || it.command == null || it.command.isEmpty() || it.command.length() > 100) { throw new IllegalArgumentException("Item de hotbar invalido."); }
            }
        }
        for (String c : s.always) { command(c); }
        for (Map.Entry<String, Set<String>> e : s.opIps.entrySet()) {
            if (e.getValue() == null || e.getValue().isEmpty() || e.getValue().size() > 10) { throw new IllegalArgumentException("Lista de IPs do OP invalida."); }
            for (String ip : e.getValue()) { ip(ip); }
        }
    }
}
