package br.goldsuite.tags;

public final class TagsTest {
    public static void main(String[] args) {
        TagData d = new TagData();
        d.selected = "campeao";
        d.owned.put("campeao", 0L);
        d.owned.put("verao", 1000L);
        TagData back = TagData.decode(d.encode());
        check(back.selected.equals("campeao") && back.owned.size() == 2 && back.owned.get("verao") == 1000L, "Ida e volta perdeu dados.");
        check(back.owns("campeao", 5000) && back.owns("verao", 999) && !back.owns("verao", 1000), "Prazo das tags errado.");
        back.prune(2000);
        check(!back.owned.containsKey("verao") && back.owned.containsKey("campeao"), "Tag vencida nao foi descartada.");
        check(TagData.decode("").selected.isEmpty() && TagData.decode(null).owned.isEmpty(), "Jogador novo deve comecar na automatica.");
        check(TagData.decode("vip|x=abc,=5,y=7").owned.size() == 1, "Linha corrompida deve ser ignorada sem erro.");
        check(TagData.plain("&6[VIP]").equals("VIP") && TagData.plain("§7[Jogador]").equals("Jogador") && TagData.plain("&d«Campeao»").equals("Campeao"), "Nome curto da tag errado.");
        check(TagData.id(" Campeao ").equals("campeao"), "Id deve ir para minusculas.");
        try { TagData.id("com espaco"); throw new AssertionError("Id com espaco aceito."); } catch (IllegalArgumentException expected) { }
        System.out.println("GoldTags: tags guardadas, prazos, linhas corrompidas e nomes curtos aprovados.");
    }
    private static void check(boolean c, String m) { if (!c) { throw new AssertionError(m); } }
}
