package br.goldsuite.core;

import br.goldsuite.api.MenuService;
import cn.nukkit.Player;
import cn.nukkit.block.Block;
import cn.nukkit.blockentity.BlockEntity;
import cn.nukkit.inventory.ContainerInventory;
import cn.nukkit.inventory.InventoryHolder;
import cn.nukkit.inventory.InventoryType;
import cn.nukkit.item.Item;
import cn.nukkit.math.Vector3;
import cn.nukkit.nbt.NBTIO;
import cn.nukkit.nbt.tag.CompoundTag;
import cn.nukkit.network.protocol.BlockEntityDataPacket;
import cn.nukkit.network.protocol.ContainerSetSlotPacket;
import cn.nukkit.network.protocol.DataPacket;
import cn.nukkit.network.protocol.ProtocolInfo;
import cn.nukkit.network.protocol.UpdateBlockPacket;
import cn.nukkit.plugin.Plugin;
import java.io.IOException;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.BiConsumer;

/** Bau falso enviado so ao jogador; cliques chegam como ContainerSetSlotPacket e sao cancelados antes do core. */
final class MenuEngine implements MenuService {
    private final Plugin plugin;
    private final Map<Player, Menu> open = new IdentityHashMap<>();

    MenuEngine(Plugin plugin) { this.plugin = plugin; }

    @Override public boolean open(Player player, String title, Map<Integer, Item> icons, BiConsumer<Player, Integer> onClick) {
        if (!player.isOnline() || !player.spawned) { return false; }
        close(player);
        int x = player.getFloorX(), z = player.getFloorZ();
        int y = player.getFloorY() - 2 >= 1 ? player.getFloorY() - 2 : player.getFloorY() + 3;
        Menu menu = new Menu(new Holder(x, y, z), title, onClick);
        for (Map.Entry<Integer, Item> icon : icons.entrySet()) {
            if (icon.getKey() >= 0 && icon.getKey() < SIZE) { menu.setItem(icon.getKey(), icon.getValue().clone()); }
        }
        open.put(player, menu);
        // O bau precisa existir no cliente antes da janela abrir.
        UpdateBlockPacket block = new UpdateBlockPacket();
        block.records = new UpdateBlockPacket.Entry[] {new UpdateBlockPacket.Entry(x, z, y, Block.CHEST, 0, UpdateBlockPacket.FLAG_ALL_PRIORITY)};
        BlockEntityDataPacket tile = new BlockEntityDataPacket();
        tile.x = x; tile.y = y; tile.z = z;
        try {
            tile.namedTag = NBTIO.write(new CompoundTag().putString("id", BlockEntity.CHEST).putInt("x", x).putInt("y", y).putInt("z", z).putString("CustomName", title), ByteOrder.LITTLE_ENDIAN);
        } catch (IOException error) { throw new IllegalStateException(error); }
        player.getServer().batchPackets(new Player[] {player}, new DataPacket[] {block, tile}, false);
        plugin.getServer().getScheduler().scheduleDelayedTask(new cn.nukkit.scheduler.PluginTask<Plugin>(plugin) {
            @Override public void onRun(int tick) { if (open.get(player) == menu && player.isOnline()) { player.addWindow(menu); } }
        }, 3);
        return true;
    }

    @Override public void close(Player player) {
        Menu menu = open.remove(player);
        if (menu == null) { return; }
        if (player.getWindowId(menu) >= 0) { player.removeWindow(menu); }
        restore(player, menu);
    }

    @Override public boolean isOpen(Player player) { return open.containsKey(player); }

    /** Retorna true quando o pacote pertence a um menu e foi consumido. */
    boolean intercept(Player player, DataPacket packet) {
        if (packet.pid() != ProtocolInfo.CONTAINER_SET_SLOT_PACKET) { return false; }
        Menu menu = open.get(player);
        if (menu == null) { return false; }
        ContainerSetSlotPacket slot = (ContainerSetSlotPacket) packet;
        int windowId = player.getWindowId(menu);
        // Com o menu aberto, nenhum item pode entrar ou sair do inventario do jogador.
        if (slot.windowid == windowId && slot.slot >= 0 && slot.slot < SIZE && !menu.clicked) {
            menu.clicked = true;
            int index = slot.slot;
            plugin.getServer().getScheduler().scheduleDelayedTask(new cn.nukkit.scheduler.PluginTask<Plugin>(plugin) {
                @Override public void onRun(int tick) {
                    close(player);
                    if (player.isOnline()) { player.getInventory().sendContents(player); menu.onClick.accept(player, index); }
                }
            }, 1);
        }
        menu.sendContents(player);
        player.getInventory().sendContents(player);
        return true;
    }

    void forget(Player player) { open.remove(player); }

    private static void restore(Player player, Menu menu) {
        if (player.isOnline() && player.level != null) { player.level.sendBlocks(new Player[] {player}, new Vector3[] {menu.holder}); }
    }

    private static final class Holder extends Vector3 implements InventoryHolder {
        Holder(int x, int y, int z) { super(x, y, z); }
        @Override public cn.nukkit.inventory.Inventory getInventory() { return null; }
    }

    private final class Menu extends ContainerInventory {
        final Holder holder;
        final BiConsumer<Player, Integer> onClick;
        boolean clicked;
        Menu(Holder holder, String title, BiConsumer<Player, Integer> onClick) {
            super(holder, InventoryType.CHEST, new HashMap<>(), SIZE, title);
            this.holder = holder; this.onClick = onClick;
        }
        /** Fechado pelo cliente (botao X): limpa o estado e devolve o bloco verdadeiro. */
        @Override public void onClose(Player who) {
            super.onClose(who);
            if (open.get(who) == this) { open.remove(who); restore(who, this); }
        }
    }
}
