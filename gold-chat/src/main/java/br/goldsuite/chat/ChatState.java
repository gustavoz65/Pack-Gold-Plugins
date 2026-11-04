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
    /** Grupo do GoldPerms -> formato. {grupo}, {tag}, {nome} e {mensagem}; & vira cor. Cargo sem formato aqui usa a tag dele. */
    public Map<String, String> formats = defaults();
    public List<String> announcements = new ArrayList<>(java.util.Arrays.asList(
            M.t("euse_kits_epara_ver_os_kits"), M.t("echame_amigos_use_tpa_epara_ir"), M.t("eregras_sem_hack_sem_ofensa_bom")));
    public int announceSeconds = 180;
    public String join = "&a+ &f{nome}";
    public String quit = "&c- &f{nome}";
    public Map<String, Long> mutes = new TreeMap<>();
    public Set<String> tellOff = new TreeSet<>();
    /** Filtro de palavroes: termos acrescentados pela staff e termos da lista padrao que foram liberados. */
    public Set<String> extraWords = new TreeSet<>();
    public Set<String> allowedWords = new TreeSet<>();

    static Map<String, String> defaults() {
        Map<String, String> f = new TreeMap<>();
        f.put("padrao", "{mctop}{tag} &7{nome}&8: &f{mensagem}");
        f.put("vip", "{mctop}{tag} &e{nome}&8: &f{mensagem}");
        return f;
    }

    static final Pattern LINK = Pattern.compile("(?i)\\b[\\w-]{2,}\\s*(\\.|\\(\\.\\)|\\[\\.\\]|ponto)\\s*(com|net|org|br|gg|io|xyz|me|tk|ml)\\b|\\b\\d{1,3}(\\.\\d{1,3}){3}\\b");

    /** Converte o formato do admin no formato do Nukkit; a mensagem do jogador nunca e interpretada. */
    static String nukkitFormat(String format, String group, String tag) {
        String safeTag = tag.replaceAll("[{}%]", "");
        // Sem tag, "{tag} " some inteiro (nao sobra espaco antes do nome).
        String f = safeTag.isEmpty() ? format.replace("{tag} ", "") : format;
        return color(f.replace("{grupo}", group).replace("{tag}", safeTag)).replace("{nome}", "{%0}").replace("{mensagem}", "{%1}");
    }

    /** Formato de quem tem tag de cargo e nenhum formato proprio. */
    static final String TAGGED = "{mctop}{tag} &f{nome}&8: &f{mensagem}";

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
        // Configuracoes antigas nao tinham o filtro: comecam com a lista padrao.
        if (s != null && s.extraWords == null) { s.extraWords = new TreeSet<>(); }
        if (s != null && s.allowedWords == null) { s.allowedWords = new TreeSet<>(); }
        if (s != null && (s.extraWords.size() > 500 || s.allowedWords.size() > 500)) { throw new IllegalArgumentException(M.t("filtro_lista_grande")); }
        if (s == null || s.formats == null || s.announcements == null || s.mutes == null || s.tellOff == null || s.join == null || s.quit == null
                || s.formats.size() > 128 || s.announcements.size() > 50 || s.mutes.size() > 10000 || s.tellOff.size() > 10000
                || s.announceSeconds < 30 || s.announceSeconds > 3600 || !s.formats.containsKey("padrao")) { throw new IllegalArgumentException(M.t("configuracao_de_chat_invalida")); }
        for (String f : s.formats.values()) { if (f == null || f.length() > 120 || !f.contains("{mensagem}")) { throw new IllegalArgumentException(M.t("formato_precisa_de_mensagem_ate_120")); } }
        for (String a : s.announcements) { if (a == null || a.length() > 200) { throw new IllegalArgumentException(M.t("anuncio_invalido")); } }
    }
}
