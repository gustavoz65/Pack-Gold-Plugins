package br.goldsuite.core;

import br.goldsuite.api.ItemSigner;
import cn.nukkit.item.Item;
import cn.nukkit.nbt.tag.*;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Assina identidade e NBT canonico; o nome visual nao concede privilegios.
 *
 * <p>Quantidade e desgaste ficam fora da assinatura: dois itens assinados iguais
 * precisam empilhar, uma pilha precisa poder ser dividida, e uma espada especial
 * continua valida depois de usada. Clonar itens e problema do inventario (as
 * transacoes do core), nao da assinatura.</p>
 */
final class HmacItemSigner implements ItemSigner {
    private static final String SIGNATURE = "GoldSuiteSignature";
    private static final String PURPOSE = "GoldSuitePurpose";
    private final byte[] key;

    HmacItemSigner(byte[] key) {
        if (key == null || key.length < 32) { throw new IllegalArgumentException("A chave precisa ter pelo menos 32 bytes."); }
        this.key = key.clone();
    }

    @Override
    public Item sign(Item original, String purpose) {
        validatePurpose(purpose);
        Item item = original.clone();
        CompoundTag tag = item.hasCompoundTag() ? (CompoundTag) item.getNamedTag().copy() : new CompoundTag();
        tag.remove(SIGNATURE);
        tag.putString(PURPOSE, purpose);
        item.setNamedTag(tag);
        tag.putString(SIGNATURE, Base64.getEncoder().encodeToString(calculate(item, tag)));
        item.setNamedTag(tag);
        return item;
    }

    @Override
    public boolean verify(Item item, String purpose) {
        try {
            validatePurpose(purpose);
            if (item == null || !item.hasCompoundTag()) { return false; }
            CompoundTag tag = (CompoundTag) item.getNamedTag().copy();
            if (!purpose.equals(tag.getString(PURPOSE))) { return false; }
            String signature = tag.getString(SIGNATURE);
            if (signature.length() != 44) { return false; }
            byte[] supplied = Base64.getDecoder().decode(signature);
            tag.remove(SIGNATURE);
            return MessageDigest.isEqual(supplied, calculate(item, tag));
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    private static void validatePurpose(String purpose) {
        if (purpose == null || !purpose.matches("[a-z0-9:_-]{1,80}")) { throw new IllegalArgumentException("Finalidade de assinatura invalida."); }
    }

    private byte[] calculate(Item item, CompoundTag tag) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(item.getId());
            // Em itens com durabilidade o meta e desgaste; nos demais e a variante (ex.: cor da la).
            out.writeInt(item.getMaxDurability() > 0 ? -1 : item.getDamage());
            writeTag(out, tag, 0);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(this.key, "HmacSHA256"));
            return mac.doFinal(bytes.toByteArray());
        } catch (IOException | GeneralSecurityException error) {
            throw new IllegalStateException("Nao foi possivel assinar o item.", error);
        }
    }

    private static void text(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static void writeTag(DataOutputStream out, Tag tag, int depth) throws IOException {
        if (depth > 32) { throw new IllegalArgumentException("NBT profundo demais para assinatura."); }
        out.writeByte(tag.getId());
        text(out, tag.getName());
        switch (tag.getId()) {
            case Tag.TAG_End: return;
            case Tag.TAG_Byte: out.writeByte(((ByteTag) tag).data); return;
            case Tag.TAG_Short: out.writeShort(((ShortTag) tag).data); return;
            case Tag.TAG_Int: out.writeInt(((IntTag) tag).data); return;
            case Tag.TAG_Long: out.writeLong(((LongTag) tag).data); return;
            case Tag.TAG_Float: out.writeFloat(((FloatTag) tag).data); return;
            case Tag.TAG_Double: out.writeDouble(((DoubleTag) tag).data); return;
            case Tag.TAG_String: text(out, ((StringTag) tag).data); return;
            case Tag.TAG_Byte_Array:
                byte[] bytes = ((ByteArrayTag) tag).data;
                out.writeInt(bytes.length); out.write(bytes); return;
            case Tag.TAG_Int_Array:
                int[] numbers = ((IntArrayTag) tag).data;
                out.writeInt(numbers.length);
                for (int number : numbers) { out.writeInt(number); }
                return;
            case Tag.TAG_List:
                ListTag<?> list = (ListTag<?>) tag;
                out.writeInt(list.size());
                for (Tag child : list.getAll()) { writeTag(out, child, depth + 1); }
                return;
            case Tag.TAG_Compound:
                List<Tag> children = new ArrayList<>(((CompoundTag) tag).getAllTags());
                children.sort(Comparator.comparing(Tag::getName));
                out.writeInt(children.size());
                for (Tag child : children) { writeTag(out, child, depth + 1); }
                return;
            default: throw new IllegalArgumentException("Tipo de NBT nao reconhecido.");
        }
    }
}
