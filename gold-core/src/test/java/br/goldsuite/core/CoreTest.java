package br.goldsuite.core;

import cn.nukkit.item.Item;
import cn.nukkit.nbt.tag.CompoundTag;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;

public final class CoreTest {
    public static void main(String[] args) throws Exception {
        if (args.length == 1) {
            Path persisted = java.nio.file.Paths.get(args[0]);
            if (!Files.isRegularFile(persisted)) { throw new AssertionError("Banco do teste integrado ausente."); }
            SqliteBackend reopened = new SqliteBackend(persisted);
            reopened.initialize();
            if (!reopened.get("integracao", "desligamento").orElse("").equals("gravado")) {
                throw new AssertionError("Escrita pendente nao sobreviveu ao desligamento.");
            }
            System.out.println("GoldCore: escrita pendente conferida no banco apos encerrar o servidor.");
            return;
        }
        Path directory = Files.createTempDirectory("goldsuite-teste-");
        try {
            Path file = directory.resolve("dados.db");
            SqliteBackend backend = new SqliteBackend(file);
            backend.initialize();
            String key = "jogador'; DROP TABLE gold_records;--";
            backend.put("teste", key, "Proteção e ação");
            SqliteBackend reopened = new SqliteBackend(file);
            reopened.initialize();
            if (!reopened.get("teste", key).orElse("").equals("Proteção e ação")) {
                throw new AssertionError("Persistencia ou prepared statement falhou.");
            }
            reopened.delete("teste", key);
            if (reopened.get("teste", key).isPresent()) { throw new AssertionError("Registro removido ainda existe."); }
            byte[] secret = new byte[32];
            new SecureRandom().nextBytes(secret);
            HmacItemSigner signer = new HmacItemSigner(secret);
            Item original = new Item(264, 0, 1, "Diamante de teste");
            original.setNamedTag(new CompoundTag().putString("nome", "Chave legítima").putInt("valor", 7));
            Item signed = signer.sign(original, "goldcrates:chave");
            if (!signer.verify(signed, "goldcrates:chave") || signer.verify(signed, "goldkits:item")) {
                throw new AssertionError("Assinatura ou isolamento por finalidade falhou.");
            }
            Item pilha = signed.clone();
            pilha.setCount(64);
            if (!signer.verify(pilha, "goldcrates:chave")) { throw new AssertionError("Item assinado deixou de valer ao empilhar."); }
            Item variante = signed.clone();
            variante.setDamage(1);
            if (signer.verify(variante, "goldcrates:chave")) { throw new AssertionError("Variante adulterada foi aceita."); }
            cn.nukkit.block.Block.init();
            Item.init();
            Item espada = signer.sign(Item.get(Item.DIAMOND_SWORD), "goldcrates:espada");
            espada.setDamage(120);
            if (!signer.verify(espada, "goldcrates:espada")) { throw new AssertionError("Espada assinada deixou de valer ao desgastar."); }
            Item forged = signed.clone();
            CompoundTag tag = (CompoundTag) forged.getNamedTag().copy();
            tag.putInt("valor", 999);
            forged.setNamedTag(tag);
            if (signer.verify(forged, "goldcrates:chave")) { throw new AssertionError("NBT adulterado foi aceito."); }
            if (new HmacItemSigner(new byte[32]).verify(signed, "goldcrates:chave")) {
                throw new AssertionError("Chave de outro servidor foi aceita.");
            }
            // Painel: validacao por tipo, cores da paleta e webhook so do Discord.
            SettingsEngine.Def cor = new SettingsEngine.Def(SettingsEngine.SERVER, "cor.primaria", br.goldsuite.api.SettingsService.Kind.TEXTO, "&6", "");
            SettingsEngine.Def num = new SettingsEngine.Def("GoldPvP", "limite", br.goldsuite.api.SettingsService.Kind.NUMERO, "3", "");
            SettingsEngine.Def txt = new SettingsEngine.Def("GoldPvP", "msg", br.goldsuite.api.SettingsService.Kind.TEXTO, "oi", "");
            if (!SettingsEngine.check(cor, "&6&l").equals("&6&l") || !SettingsEngine.check(num, " 42 ").equals("42") || !SettingsEngine.check(txt, "&aOla {primaria}x").equals("&aOla {primaria}x")) { throw new AssertionError("Valores validos recusados."); }
            for (Object[] bad : new Object[][] {{cor, "vermelho"}, {cor, "&z"}, {num, "-1"}, {num, "abc"}, {txt, ""}, {txt, new String(new char[201]).replace('\0', 'a')}}) {
                try { SettingsEngine.check((SettingsEngine.Def) bad[0], (String) bad[1]); throw new AssertionError("Valor invalido aceito: " + bad[1]); } catch (IllegalArgumentException expected) { }
            }
            if (!DiscordWebhook.valid("") || !DiscordWebhook.valid("https://discord.com/api/webhooks/123456789012345678/abcdefghijklmnopqrstuvwxyzABCD_-12")
                    || DiscordWebhook.valid("http://discord.com/api/webhooks/1/x") || DiscordWebhook.valid("https://evil.com/api/webhooks/123456789012345678/abcdefghijklmnopqrstuvwxyz")
                    || DiscordWebhook.valid("https://discord.com.evil.com/api/webhooks/123456789012345678/abcdefghijklmnopqrstuvwxyz")) { throw new AssertionError("Webhook mal validado."); }
            if (!DiscordWebhook.json("a\"b\\c\nd").equals("\"a\\\"b\\\\c\\nd\"")) { throw new AssertionError("JSON do Discord mal escapado."); }
            if (!SettingsEngine.strip("\u00a76ola\u00a7r!").equals("ola!")) { throw new AssertionError("Cores nao removidas."); }
            System.out.println("GoldCore: SQLite persistente, entrada SQL, UTF-8, HMAC, painel e webhook verificados.");
        } finally {
            Path root = directory.toRealPath();
            if (!root.getParent().equals(java.nio.file.Paths.get(System.getProperty("java.io.tmpdir")).toRealPath())) {
                throw new IllegalStateException("Pasta temporaria de teste fora do local esperado.");
            }
            try (java.util.stream.Stream<Path> paths = Files.walk(directory)) {
                for (Path path : (Iterable<Path>) paths.sorted(java.util.Comparator.reverseOrder())::iterator) { Files.delete(path); }
            }
        }
    }
}
