package br.goldsuite.chat;

import com.google.gson.Gson;

public final class ChatTest {
    public static void main(String[] args) {
        ChatState s = new ChatState();
        ChatState.validate(s);
        ChatState back = new Gson().fromJson(new Gson().toJson(s), ChatState.class);
        ChatState.validate(back);
        check(ChatState.nukkitFormat("&6[{grupo}] {nome}: {mensagem}", "vip", "").equals("§6[vip] {%0}: {%1}"), "Formato errado.");
        check(ChatState.nukkitFormat(ChatState.TAGGED, "dono", "§4[DONO]{%1}").equals("{mctop}§4[DONO]1 §f{%0}§8: §f{%1}"), "Tag do cargo errada ou injetou o formato.");
        check(ChatState.clean("§cOi\u0000 ").equals("cOi"), "Cores ou controle nao removidos.");
        check(ChatState.calm("OLA PESSOAL").equals("ola pessoal") && ChatState.calm("Oi BR").equals("Oi BR"), "Caps incorreto.");
        for (String link : new String[] {"entra em meuserver.com", "meuserver . net", "joga 192.168.0.1", "site ponto com", "meuhg(.)gg"}) { check(ChatState.LINK.matcher(link).find(), "Link passou: " + link); }
        for (String ok : new String[] {"bom dia", "kkk 3.5 coracoes", "vou na casa"}) { check(!ChatState.LINK.matcher(ok).find(), "Falso positivo: " + ok); }
        back.formats.put("vip", "&6{nome}");
        rejects(() -> ChatState.validate(back));
        WordFilter f = new WordFilter(WordFilter.DEFAULTS);
        check(f.filter("seu fdp", '*').equals("seu ***"), "fdp passou.");
        check(f.filter("PORRAAAA mano", '*').equals("******** mano"), "Letra repetida e maiuscula passaram.");
        check(f.filter("p0rr4", '*').equals("*****"), "Numero no lugar de letra passou.");
        check(f.filter("vai f d p", '*').equals("vai * * *"), "Letras separadas passaram.");
        check(f.filter("f.d.p!", '*').equals("*.*.*!"), "Letras com ponto passaram.");
        check(f.filter("pau no cu", '*').equals("*** ** **"), "Frase passou.");
        check(f.filter("paunocu", '*').equals("*******"), "Frase colada passou.");
        check(f.filter("seu caralhudo", '*').equals("seu *********"), "Prefixo passou.");
        check(f.filter("mcc vtnc krl", '*').equals("*** **** ***"), "Abreviacoes passaram.");
        check(f.filter("vc e um desgraçado", '*').equals("vc e um **********"), "Acento passou.");
        check(f.filter("meu computador tem cuidado com a disputa", '*').equals("meu computador tem cuidado com a disputa"), "Palavra normal foi censurada.");
        check(f.filter("pau de madeira no rio", '*').equals("pau de madeira no rio"), "Pau sozinho nao e palavrao.");
        check(new WordFilter(java.util.Arrays.asList("feio")).filter("que feio", '#').equals("que ####"), "Lista propria e caractere proprio falharam.");
        System.out.println("GoldChat: formato, limpeza, caps, links, validacao e filtro de palavroes aprovados.");
    }
    private static void check(boolean c, String m) { if (!c) { throw new AssertionError(m); } }
    private static void rejects(Runnable a) { try { a.run(); } catch (IllegalArgumentException e) { return; } throw new AssertionError("Entrada invalida aceita."); }
}
