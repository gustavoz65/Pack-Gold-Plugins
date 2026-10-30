package br.goldsuite.api;

import cn.nukkit.item.Item;

public interface ItemSigner {
    Item sign(Item item, String purpose);
    boolean verify(Item item, String purpose);
}
