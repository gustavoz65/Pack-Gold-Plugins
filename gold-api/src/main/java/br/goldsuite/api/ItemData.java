package br.goldsuite.api;

import cn.nukkit.item.Item;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** Item serializavel com Gson: id, meta, quantidade e NBT (encantamentos, nome) em base64. */
public final class ItemData {
    /** NBT acima disso e recusado: evita itens gigantes usados para travar o servidor. */
    public static final int MAX_NBT = 4096;
    public int id, meta, count;
    public String nbt = "";

    public static ItemData of(Item item) {
        ItemData d = new ItemData();
        d.id = item.getId(); d.meta = item.getDamage(); d.count = item.getCount();
        if (item.hasCompoundTag()) { d.nbt = Base64.getEncoder().encodeToString(item.getCompoundTag()); }
        d.validate();
        return d;
    }

    public Item toItem() {
        Item item = Item.get(id, meta, count);
        if (!nbt.isEmpty()) { item.setCompoundTag(Base64.getDecoder().decode(nbt)); }
        return item;
    }

    public void validate() {
        if (id < 1 || id > 511 || meta < 0 || meta > 32767 || count < 1 || count > 64 || nbt == null || nbt.length() > MAX_NBT * 4 / 3 + 4) { throw new IllegalArgumentException("Item invalido."); }
        if (!nbt.isEmpty()) { Base64.getDecoder().decode(nbt); }
    }

    /** Conteudo de um inventario sem os espacos vazios. */
    public static Map<Integer, ItemData> snapshot(Map<Integer, Item> contents, int size) {
        Map<Integer, ItemData> out = new LinkedHashMap<>();
        for (Map.Entry<Integer, Item> e : contents.entrySet()) {
            if (e.getKey() >= 0 && e.getKey() < size && e.getValue() != null && e.getValue().getId() != 0 && e.getValue().getCount() > 0) { out.put(e.getKey(), of(e.getValue())); }
        }
        return out;
    }
}
