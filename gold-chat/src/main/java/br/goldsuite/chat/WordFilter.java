package br.goldsuite.chat;

import java.text.Normalizer;
import java.util.*;

/**
 * Filtro de palavroes: troca a palavra inteira por asteriscos do mesmo tamanho. Pega acento, letra repetida ("porraaa"),
 * numero no lugar de letra ("p0rr4"), letras separadas ("f d p", "f.d.p"), frases ("pau no cu") e frase colada
 * ("paunocu"). So compara palavras inteiras, entao "computador" e "cuidado" passam normalmente.
 * Termos: palavra exata ("fdp"), prefixo com * ("caralh*") ou frase com espacos ("vai tomar no cu").
 */
final class WordFilter {
    /** Lista padrao (pt-BR, abreviacoes e o basico em ingles). Editavel no jogo com /chat palavra. */
    static final List<String> DEFAULTS = Collections.unmodifiableList(Arrays.asList(
            // abreviacoes
            "fdp", "fdps", "pqp", "vsf", "vsfd", "vsfdr", "tnc", "vtnc", "tmnc", "vtmnc", "krl", "krlh", "crl", "crlh", "pnc", "mcc", "bct", "ppk", "sfd",
            // palavroes
            "cu", "cus", "cuzao", "cuzona", "cuzinho", "porra", "porras", "porrinha", "merda", "merdas", "merdinha", "merdao", "bosta", "bostas", "bostinha", "bostao",
            "puta", "putas", "puto", "putos", "putaria", "putinha", "putinho", "putona", "putao", "putamerda", "putaquepariu",
            "buceta", "bucetas", "bucetinha", "xoxota", "xota", "piroca", "pirocao", "pica", "caralh*", "cacete",
            "foda", "fodas", "fodase", "foder", "fodeu", "fodido", "fodida", "fodidos", "fuder", "fudeu", "fudido", "fudida", "fudidos",
            // ofensas
            "viado", "viada", "viados", "viadinho", "viadao", "bicha", "bichas", "bichinha", "traveco", "sapatao",
            "corno", "corna", "cornos", "cornao", "cornudo", "vadia", "vadias", "piranha", "piranhas", "prostitut*", "rapariga",
            "otario", "otaria", "otarios", "otarias", "babaca", "babacas", "imbecil", "imbecis", "idiota", "idiotas", "retardad*", "mongol", "mongoloide", "mongoloides",
            "escrot*", "vagabund*", "desgracad*", "arrombad*", "lazarento", "lazarenta", "cretino", "cretina", "verme",
            // frases
            "pau no cu", "pau no seu cu", "tomar no cu", "vai tomar no cu", "vai tomar no seu cu", "vai se foder", "vai se fuder", "vai te foder",
            "filho da puta", "filha da puta", "filho de uma puta", "puta que pariu", "puta merda", "vai a merda", "vai pra merda", "mae com cancer", "sua mae e puta",
            // ingles
            "fuck*", "motherfuck*", "shit", "bullshit", "bitch", "bitches", "asshole", "stfu", "wtf", "nigg*", "cunt", "dick", "pussy"));

    private final Set<String> exact = new HashSet<>();
    private final List<String> prefixes = new ArrayList<>();
    private final List<String[]> phrases = new ArrayList<>();

    WordFilter(Collection<String> terms) {
        for (String t : terms) {
            String term = t == null ? "" : t.trim().toLowerCase(Locale.ROOT);
            if (term.isEmpty()) { continue; }
            if (term.indexOf(' ') >= 0) {
                List<String> words = new ArrayList<>();
                for (String w : term.split("\\s+")) { String n = normalize(w); if (!n.isEmpty()) { words.add(n); } }
                if (words.size() > 1) { phrases.add(words.toArray(new String[0])); exact.add(String.join("", words)); }
                else if (words.size() == 1) { exact.add(words.get(0)); }
            } else if (term.endsWith("*")) {
                String n = normalize(term.substring(0, term.length() - 1));
                if (n.length() >= 3) { prefixes.add(n); }
            } else {
                String n = normalize(term);
                if (!n.isEmpty()) { exact.add(n); }
            }
        }
    }

    /** Minusculas, sem acento, numeros e simbolos viram letras e letras repetidas viram uma so ("p0rr44" -> "pora"). */
    static String normalize(String word) {
        String s = Normalizer.normalize(word.toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
        StringBuilder out = new StringBuilder(s.length());
        char last = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '0': c = 'o'; break;
                case '1': case '!': c = 'i'; break;
                case '3': c = 'e'; break;
                case '4': case '@': c = 'a'; break;
                case '5': case '$': c = 's'; break;
                case '7': c = 't'; break;
                case '8': c = 'b'; break;
                default:
            }
            if (c < 'a' || c > 'z') { continue; }
            if (c == last) { continue; }
            out.append(c);
            last = c;
        }
        return out.toString();
    }

    private boolean bad(String normalized) {
        if (normalized.isEmpty()) { return false; }
        if (exact.contains(normalized)) { return true; }
        for (String p : prefixes) { if (normalized.startsWith(p)) { return true; } }
        return false;
    }

    private static boolean wordChar(char c) { return Character.isLetterOrDigit(c) || c == '@' || c == '$'; }

    /** Devolve a mensagem com as palavras proibidas trocadas por {@code mask} (uma por letra). */
    String filter(String message, char mask) {
        if (message == null || message.isEmpty()) { return message; }
        // Palavras da mensagem: inicio, fim e forma normalizada.
        List<int[]> spans = new ArrayList<>();
        List<String> norm = new ArrayList<>();
        int i = 0, n = message.length();
        while (i < n) {
            if (!wordChar(message.charAt(i))) { i++; continue; }
            int start = i;
            while (i < n && wordChar(message.charAt(i))) { i++; }
            spans.add(new int[] {start, i});
            norm.add(normalize(message.substring(start, i)));
        }
        if (spans.isEmpty()) { return message; }
        boolean[] hide = new boolean[spans.size()];
        for (int k = 0; k < spans.size(); k++) { if (bad(norm.get(k))) { hide[k] = true; } }
        // Letras soltas em sequencia ("f d p", "f.d.p"): junta e confere.
        int k = 0;
        while (k < spans.size()) {
            int j = k;
            StringBuilder joined = new StringBuilder();
            while (j < spans.size() && spans.get(j)[1] - spans.get(j)[0] == 1 && (j == k || spans.get(j)[0] - spans.get(j - 1)[1] <= 2)) { joined.append(norm.get(j)); j++; }
            if (j - k >= 2 && bad(normalize(joined.toString()))) { for (int x = k; x < j; x++) { hide[x] = true; } }
            k = Math.max(j, k + 1);
        }
        // Frases ("pau no cu"): todas as palavras da frase somem.
        for (String[] phrase : phrases) {
            for (int s = 0; s + phrase.length <= spans.size(); s++) {
                boolean match = true;
                for (int w = 0; w < phrase.length && match; w++) { match = norm.get(s + w).equals(phrase[w]); }
                if (match) { for (int w = 0; w < phrase.length; w++) { hide[s + w] = true; } }
            }
        }
        StringBuilder out = new StringBuilder(message);
        boolean any = false;
        for (int x = 0; x < spans.size(); x++) {
            if (!hide[x]) { continue; }
            any = true;
            for (int c = spans.get(x)[0]; c < spans.get(x)[1]; c++) { out.setCharAt(c, mask); }
        }
        return any ? out.toString() : message;
    }
}
