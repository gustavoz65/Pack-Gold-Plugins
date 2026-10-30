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
    public Map<String, Boolean> permissions(String name, long now) { return resolve(group(name, now), new HashSet<>()); }
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
            if (group == null || group.parent == null || group.permissions == null || group.permissions.size() > 512) { throw new IllegalArgumentException("Grupo invalido."); }
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
