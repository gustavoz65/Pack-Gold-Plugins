package br.goldsuite.perms;

import java.util.*;
import cn.nukkit.utils.PlayerName;

/** Modelo puro: heranca explicita, negacoes e expiracao verificadas a cada consulta. */
public final class PermissionState {
    public Map<String, Group> groups = new TreeMap<>();
    public Map<String, Member> members = new TreeMap<>();
    public static final class Group {
        public String parent = "";
        public Map<String, Boolean> permissions = new TreeMap<>();
        /** Posicao na hierarquia: quem tem peso maior manda em quem tem peso menor. */
        public int weight;
        /** Tag mostrada no chat (aceita &). */
        public String tag = "";
        /** Cargo da equipe: recebe tickets e aparece em /equipe. */
        public boolean staff;
        public String description = "";
    }
    public static final class Member {
        public String group;
        public long expiresAt;
        public Member(String group, long expiresAt) { this.group = group; this.expiresAt = expiresAt; }
    }
    public PermissionState() { groups.put("padrao", new Group()); }
    public static String player(String name) {
        if (!PlayerName.isValid(name)) { throw new IllegalArgumentException("Nome de jogador invalido."); }
        return name.toLowerCase(Locale.ROOT);
    }
    public static String groupName(String name) {
        if (name == null || !name.matches("[a-z0-9_-]{1,24}")) { throw new IllegalArgumentException("Grupo: use ate 24 letras minusculas, numeros, _ ou -."); }
        return name;
    }
    public String group(String name, long now) {
        Member member = members.get(player(name));
        return member == null || (member.expiresAt != 0 && member.expiresAt <= now) ? "padrao" : member.group;
    }
    public long expiry(String name, long now) {
        Member member = members.get(player(name));
        return member == null || (member.expiresAt != 0 && member.expiresAt <= now) ? 0 : member.expiresAt;
    }
    public Map<String, Boolean> permissions(String name, long now) { return resolve(group(name, now), new HashSet<>()); }
    public Group info(String name, long now) { return groups.get(group(name, now)); }
    /** Permissoes de um cargo com a heranca resolvida. */
    public Map<String, Boolean> groupPermissions(String group) { return resolve(group, new HashSet<>()); }

    /** Texto seguro para a tag: sem chaves nem %, que o formato do chat usa. */
    public static String tag(String text) {
        String t = text == null ? "" : text.replaceAll("[{}%\\p{Cntrl}]", "").trim();
        if (t.length() > 32) { throw new IllegalArgumentException("Tag de ate 32 caracteres."); }
        return t;
    }

    /** Cargos da equipe criados na primeira vez (so os que faltam; nada que o dono ja configurou e trocado). */
    public boolean preset() {
        boolean changed = false;
        String[][] ranks = {
                {"ajudante", "padrao", "20", "&e[AJUDANTE]", "Atende tickets e silencia no chat"},
                {"moderador", "ajudante", "40", "&2[MOD]", "Reports, kick e links no chat"},
                {"admin", "moderador", "60", "&c[ADMIN]", "Ban, teleporte, modo de jogo e ferramentas da staff"},
                {"gerente", "admin", "80", "&5[GERENTE]", "Metricas da equipe, cargos abaixo dele e alertas"},
                {"dono", "gerente", "100", "&4[DONO]", "Tudo, inclusive painel e grupos"}};
        String[][] perms = {
                {"goldstaff.suporte", "goldchat.staff"},
                {"goldstaff.reports", "goldchat.links", "nukkit.command.kick", "nukkit.command.teleport"},
                {"goldstaff.admin", "goldchat.cores", "nukkit.command.ban.player", "nukkit.command.ban.ip", "nukkit.command.ban.list", "nukkit.command.unban.player",
                        "nukkit.command.unban.ip", "nukkit.command.gamemode", "goldessentials.fly", "goldessentials.instantaneo", "goldvault.admin", "goldmarket.admin"},
                {"goldstaff.metricas", "goldperms.cargo", "goldcore.alertas", "goldessentials.admin", "goldkits.admin", "goldvip.admin"},
                {"goldperms.admin", "goldcore.painel", "goldkeys.admin", "goldeconomy.admin", "goldcosmetics.admin", "goldcrates.admin"}};
        for (int i = 0; i < ranks.length; i++) {
            String[] r = ranks[i];
            Group g = groups.get(r[0]);
            if (g == null) {
                g = new Group();
                g.parent = r[1].isEmpty() || !groups.containsKey(r[1]) ? "" : r[1];
                for (String node : perms[i]) { g.permissions.put(node, true); }
                groups.put(r[0], g);
                changed = true;
            }
            if (g.weight == 0 && g.tag.isEmpty()) { g.weight = Integer.parseInt(r[2]); g.tag = r[3]; g.staff = true; g.description = r[4]; changed = true; }
        }
        Group vip = groups.get("vip");
        if (vip != null && vip.weight == 0 && vip.tag.isEmpty()) { vip.weight = 10; vip.tag = "&6[VIP]"; vip.description = "Vantagens de VIP"; changed = true; }
        return changed;
    }
    /** Vincula ou, para padrao, remove o vinculo; nao guarda entradas redundantes. */
    public void assign(String name, String group, long expiresAt) {
        String key = player(name), target = groupName(group);
        if (target.equals("padrao")) { members.remove(key); } else { members.put(key, new Member(target, expiresAt)); }
    }
    /** Remove vinculos expirados para que nao ocupem o limite nem bloqueiem a remocao de grupos. */
    public void prune(long now) { members.values().removeIf(m -> m.expiresAt != 0 && m.expiresAt <= now); }
    private Map<String, Boolean> resolve(String name, Set<String> visited) {
        if (!visited.add(name)) { throw new IllegalArgumentException("Heranca circular de grupos."); }
        Group group = groups.get(name);
        if (group == null) { throw new IllegalArgumentException("Grupo inexistente: " + name); }
        Map<String, Boolean> result = group.parent.isEmpty() ? new TreeMap<>() : resolve(group.parent, visited);
        result.putAll(group.permissions);
        return result;
    }
    public static void validate(PermissionState state) {
        if (state == null || state.groups == null || state.members == null || !state.groups.containsKey("padrao") || state.groups.size() > 128 || state.members.size() > 10000) {
            throw new IllegalArgumentException("Dados de permissoes invalidos ou excessivos.");
        }
        for (Map.Entry<String, Group> entry : state.groups.entrySet()) {
            groupName(entry.getKey());
            Group group = entry.getValue();
            if (group == null || group.parent == null || group.permissions == null || group.permissions.size() > 512 || group.weight < 0 || group.weight > 1000
                    || group.tag == null || !tag(group.tag).equals(group.tag) || group.description == null || group.description.length() > 100) { throw new IllegalArgumentException("Grupo invalido."); }
            for (Map.Entry<String, Boolean> node : group.permissions.entrySet()) {
                if (!node.getKey().matches("[a-z0-9_.-]{1,128}") || node.getValue() == null) { throw new IllegalArgumentException("No de permissao invalido. Use nomes exatos."); }
            }
            state.resolve(entry.getKey(), new HashSet<>());
        }
        for (Map.Entry<String, Member> entry : state.members.entrySet()) {
            if (!player(entry.getKey()).equals(entry.getKey()) || entry.getValue() == null || entry.getValue().expiresAt < 0 || !state.groups.containsKey(entry.getValue().group)) {
                throw new IllegalArgumentException("Vinculo de jogador invalido.");
            }
        }
    }
}
