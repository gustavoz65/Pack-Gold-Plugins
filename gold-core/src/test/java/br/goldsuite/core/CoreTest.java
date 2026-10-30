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
            System.out.println("GoldCore: SQLite persistente, entrada SQL, UTF-8, HMAC e adulteracoes verificados.");
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
