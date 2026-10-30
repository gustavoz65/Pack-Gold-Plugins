package br.goldsuite.chat;

import com.google.gson.Gson;

public final class ChatTest {
    public static void main(String[] args) {
        ChatState s = new ChatState();
        ChatState.validate(s);
        ChatState back = new Gson().fromJson(new Gson().toJson(s), ChatState.class);
        ChatState.validate(back);
        check(ChatState.nukkitFormat("&6[{grupo}] {nome}: {mensagem}", "vip").equals("§6[vip] {%0}: {%1}"), "Formato errado.");
        check(ChatState.clean("§cOi\u0000 ").equals("cOi"), "Cores ou controle nao removidos.");
        check(ChatState.calm("OLA PESSOAL").equals("ola pessoal") && ChatState.calm("Oi BR").equals("Oi BR"), "Caps incorreto.");
        for (String link : new String[] {"entra em meuserver.com", "meuserver . net", "joga 192.168.0.1", "site ponto com", "meuhg(.)gg"}) { check(ChatState.LINK.matcher(link).find(), "Link passou: " + link); }
        for (String ok : new String[] {"bom dia", "kkk 3.5 coracoes", "vou na casa"}) { check(!ChatState.LINK.matcher(ok).find(), "Falso positivo: " + ok); }
        back.formats.put("vip", "&6{nome}");
        rejects(() -> ChatState.validate(back));
        System.out.println("GoldChat: formato, limpeza, caps, links e validacao aprovados.");
    }
    private static void check(boolean c, String m) { if (!c) { throw new AssertionError(m); } }
    private static void rejects(Runnable a) { try { a.run(); } catch (IllegalArgumentException e) { return; } throw new AssertionError("Entrada invalida aceita."); }
}
