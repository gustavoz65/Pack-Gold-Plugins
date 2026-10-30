package br.goldsuite.cosmetics;

import com.google.gson.Gson;
import java.util.HashSet;
import java.util.Set;

public final class CosmeticsTest {
    public static void main(String[] args) {
        Set<String> names = new HashSet<>();
        for (Catalog.Cosmetic c : Catalog.ALL.values()) {
            check(c.id.matches("[a-z0-9_]+") && c.id.startsWith(c.type == Catalog.Type.COR ? "cor_" : c.type.name().toLowerCase()), "Id fora do padrao: " + c.id);
            check(c.forSale() != (c.source != null), "Cosmetico precisa ser vendido ou ter origem: " + c.id);
            check(names.add(c.type + c.name), "Nome repetido: " + c.name);
            check(Catalog.of(c.type).size() <= 18, "Categoria nao cabe no menu.");
        }
        // Recompensas que o GoldPvP entrega precisam existir.
        for (String id : new String[] {"pingente_ferro", "pingente_imortal", "pingente_divino", "trilha_divina", "cor_divina", "abate_imortal", "pingente_vitorias_100"}) { check(Catalog.ALL.containsKey(id), "Falta " + id); }
        Catalog.Cosmetic cloud = Catalog.ALL.get("montaria_nuvem");
        check(cloud != null && cloud.cash && cloud.forSale() && cloud.type == Catalog.Type.MONTARIA && Catalog.price(cloud, 500).equals("500 cash"), "Nuvem deve ser vendida em cash.");
        check(Catalog.ALL.get("pingente_divino").icon == 396 && Catalog.ALL.get("pingente_platina").icon == 351 && Catalog.ALL.get("pingente_platina").meta == 4, "Icones dos pingentes errados.");
        check(Catalog.price(150_000).equals("$1500.00") && Catalog.price(5).equals("$0.05"), "Preco errado.");

        Wardrobe w = new Wardrobe();
        Catalog.Cosmetic heart = Catalog.ALL.get("pingente_coracao"), crown = Catalog.ALL.get("pingente_coroa");
        rejects(() -> w.toggle(heart));
        w.owned.add(heart.id); w.owned.add(crown.id);
        check(w.toggle(heart) && w.equipped(Catalog.Type.PINGENTE).equals(heart.id), "Equipar falhou.");
        check(w.toggle(crown) && w.equipped(Catalog.Type.PINGENTE).equals(crown.id), "Trocar de pingente falhou.");
        check(!w.toggle(crown) && w.equipped(Catalog.Type.PINGENTE) == null, "Desequipar falhou.");
        w.toggle(heart);
        Wardrobe back = new Gson().fromJson(new Gson().toJson(w), Wardrobe.class).clean();
        check(back.owned.size() == 2 && heart.id.equals(back.equipped(Catalog.Type.PINGENTE)), "Serializacao perdeu dados.");
        Wardrobe bad = new Gson().fromJson("{\"owned\":[\"nao_existe\",\"cor_azul\"],\"equipped\":{\"COR\":\"cor_rosa\",\"PINGENTE\":\"cor_azul\"}}", Wardrobe.class).clean();
        check(bad.owned.size() == 1 && bad.equipped.isEmpty(), "Limpeza de dados adulterados falhou.");
        check(new Gson().fromJson("{}", Wardrobe.class).clean().owned.isEmpty(), "Guarda-roupa vazio falhou.");
        System.out.println("GoldCosmetics: catalogo, equipar, serializacao e dados adulterados aprovados.");
    }
    private static void check(boolean c, String m) { if (!c) { throw new AssertionError(m); } }
    private static void rejects(Runnable r) { try { r.run(); } catch (IllegalArgumentException e) { return; } throw new AssertionError("Aceitou entrada invalida."); }
}
