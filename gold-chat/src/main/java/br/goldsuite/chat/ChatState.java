package br.goldsuite.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** Configuracao do chat; mutes guardam o instante final (0 = permanente). */
public final class ChatState {
    public boolean enabled = true;
    /** Grupo do GoldPerms -> formato. {grupo}, {nome} e {mensagem}; & vira cor. */
    public Map<String, String> formats = defaults();
    public List<String> announcements = new ArrayList<>(java.util.Arrays.asList(
            "&eUse &f/kits &epara ver os kits do HG.", "&eChame amigos! Use &f/tpa &epara ir ate eles.", "&eRegras: sem hack, sem ofensa. Bom jogo!"));
    public int announceSeconds = 180;
    public String join = "&a+ &f{nome}";
    public String quit = "&c- &f{nome}";
    public Map<String, Long> mutes = new TreeMap<>();
    public Set<String> tellOff = new TreeSet<>();

    static Map<String, String> defaults() {
        Map<String, String> f = new TreeMap<>();
        f.put("padrao", "&7{nome}&8: &f{mensagem}");
        f.put("vip", "&6[VIP] &e{nome}&8: &f{mensagem}");
        f.put("admin", "&c[ADMIN] &4{nome}&8: &f{mensagem}");
        return f;
    }

    static final Pattern LINK = Pattern.compile("(?i)\\b[\\w-]{2,}\\s*(\\.|\\(\\.\\)|\\[\\.\\]|ponto)\\s*(com|net|org|br|gg|io|xyz|me|tk|ml)\\b|\\b\\d{1,3}(\\.\\d{1,3}){3}\\b");

    /** Converte o formato do admin no formato do Nukkit; a mensagem do jogador nunca e interpretada. */
    static String nukkitFormat(String format, String group) {
        return color(format.replace("{grupo}", group)).replace("{nome}", "{%0}").replace("{mensagem}", "{%1}");
    }

    static String color(String text) { return text.replace('&', '§'); }

    /** Remove cores digitadas pelo jogador e caracteres de controle. */
    static String clean(String message) {
        return message.replace("§", "").replaceAll("[\\p{Cntrl}]", "").trim();
    }

    /** Mais de 70% de maiusculas em mensagens com 6+ letras vira minuscula. */
    static String calm(String message) {
        int letters = 0, upper = 0;
        for (char c : message.toCharArray()) { if (Character.isLetter(c)) { letters++; if (Character.isUpperCase(c)) { upper++; } } }
        return letters >= 6 && upper * 10 > letters * 7 ? message.toLowerCase(Locale.ROOT) : message;
    }

    public static void validate(ChatState s) {
        if (s == null || s.formats == null || s.announcements == null || s.mutes == null || s.tellOff == null || s.join == null || s.quit == null
                || s.formats.size() > 128 || s.announcements.size() > 50 || s.mutes.size() > 10000 || s.tellOff.size() > 10000
                || s.announceSeconds < 30 || s.announceSeconds > 3600 || !s.formats.containsKey("padrao")) { throw new IllegalArgumentException("Configuracao de chat invalida."); }
        for (String f : s.formats.values()) { if (f == null || f.length() > 120 || !f.contains("{mensagem}")) { throw new IllegalArgumentException("Formato precisa de {mensagem} e ate 120 caracteres."); } }
        for (String a : s.announcements) { if (a == null || a.length() > 200) { throw new IllegalArgumentException("Anuncio invalido."); } }
    }
}
