package br.goldsuite.npc;

import cn.nukkit.Player;
import cn.nukkit.Server;
import cn.nukkit.entity.Entity;
import cn.nukkit.entity.data.EntityMetadata;
import cn.nukkit.entity.data.Skin;
import cn.nukkit.item.Item;
import cn.nukkit.level.Level;
import cn.nukkit.network.protocol.AddEntityPacket;
import cn.nukkit.network.protocol.AddPlayerPacket;
import cn.nukkit.network.protocol.DataPacket;
import cn.nukkit.network.protocol.MoveEntityPacket;
import cn.nukkit.network.protocol.MovePlayerPacket;
import cn.nukkit.network.protocol.PlayerListPacket;
import cn.nukkit.network.protocol.RemoveEntityPacket;
import cn.nukkit.network.protocol.SetEntityDataPacket;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * NPC so de pacotes: nao existe como entidade no mundo, nao faz tick e nao e salvo no chunk.
 * Guarda apenas quem o esta vendo e o ultimo angulo enviado a cada um.
 */
final class NpcView {
    /** Distancia de exibicao; menor que o raio minimo de chunks para o cliente nao descartar. */
    static final double VIEW_SQUARED = 40 * 40;
    static final double LOOK_SQUARED = 8 * 8;
    private static final float LOOK_THRESHOLD = 3f;

    final int id;
    final long eid = Entity.entityCount++;
    private final UUID uuid = UUID.randomUUID();
    final NpcState.Npc data;
    private final Skin skin;
    final boolean dynamicName;
    String shownName;
    /** Viewer -> {yaw, pitch} enviados por ultimo. */
    final Map<Player, float[]> viewers = new IdentityHashMap<>();

    NpcView(int id, NpcState.Npc data, Skin skin, String shownName) {
        this.id = id; this.data = data; this.skin = skin;
        this.dynamicName = data.name.indexOf('{') >= 0;
        this.shownName = shownName;
    }

    boolean human() { return data.type.equals("humano"); }
    float eyeHeight() { return human() ? NpcState.HUMAN_EYE : NpcState.TYPES.get(data.type)[1]; }

    boolean canShowTo(Player player) {
        if (!player.spawned || player.level == null || !player.level.getFolderName().equals(data.world)) { return false; }
        double dx = player.x - data.x, dy = player.y - data.y, dz = player.z - data.z;
        if (dx * dx + dy * dy + dz * dz > VIEW_SQUARED) { return false; }
        // So depois que o chunk do NPC chegou ao cliente; antes disso ele descartaria a entidade.
        return Boolean.TRUE.equals(player.usedChunks.get(Level.chunkHash((int) Math.floor(data.x) >> 4, (int) Math.floor(data.z) >> 4)));
    }

    void spawnTo(Player player) {
        viewers.put(player, new float[] {data.yaw, data.pitch});
        Server server = player.getServer();
        if (human()) {
            PlayerListPacket add = new PlayerListPacket();
            add.type = PlayerListPacket.TYPE_ADD;
            add.entries = new PlayerListPacket.Entry[] {new PlayerListPacket.Entry(uuid, eid, "", skin)};
            AddPlayerPacket spawn = new AddPlayerPacket();
            spawn.uuid = uuid; spawn.username = ""; spawn.eid = eid;
            spawn.x = (float) data.x; spawn.y = (float) data.y; spawn.z = (float) data.z;
            spawn.yaw = data.yaw; spawn.pitch = data.pitch;
            spawn.item = Item.get(data.itemId, data.itemMeta, 1);
            spawn.metadata = metadata();
            // Sai da lista de jogadores logo depois: a skin continua, mas o NPC nao aparece no TAB.
            PlayerListPacket remove = new PlayerListPacket();
            remove.type = PlayerListPacket.TYPE_REMOVE;
            remove.entries = new PlayerListPacket.Entry[] {new PlayerListPacket.Entry(uuid)};
            server.batchPackets(new Player[] {player}, new DataPacket[] {add, spawn, remove}, false);
        } else {
            AddEntityPacket spawn = new AddEntityPacket();
            spawn.eid = eid; spawn.type = (int) NpcState.TYPES.get(data.type)[0];
            spawn.x = (float) data.x; spawn.y = (float) data.y; spawn.z = (float) data.z;
            spawn.yaw = data.yaw; spawn.pitch = data.pitch;
            spawn.metadata = metadata();
            player.dataPacket(spawn);
        }
    }

    void despawnFrom(Player player) {
        if (viewers.remove(player) == null) { return; }
        if (!player.isConnected()) { return; }
        RemoveEntityPacket packet = new RemoveEntityPacket();
        packet.eid = eid;
        player.dataPacket(packet);
    }

    void despawnAll() { for (Player player : new ArrayList<>(viewers.keySet())) { despawnFrom(player); } }

    /** Reenvia o nome para todos os viewers, so se mudou. */
    void updateName(String name) {
        if (name.equals(shownName)) { return; }
        shownName = name;
        if (viewers.isEmpty()) { return; }
        SetEntityDataPacket packet = new SetEntityDataPacket();
        packet.eid = eid;
        packet.metadata = new EntityMetadata().putString(Entity.DATA_NAMETAG, name);
        Server.broadcastPacket(viewers.keySet(), packet);
    }

    /** Vira a cabeca para cada viewer proximo; quem se afastou recebe a pose original uma vez. */
    void look() {
        double eyeY = data.y + eyeHeight();
        for (Map.Entry<Player, float[]> entry : viewers.entrySet()) {
            Player player = entry.getKey();
            float[] last = entry.getValue();
            double dx = player.x - data.x, dz = player.z - data.z, dy = player.y + NpcState.HUMAN_EYE - eyeY;
            float yaw, pitch;
            if (dx * dx + dz * dz <= LOOK_SQUARED) {
                yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
            } else { yaw = data.yaw; pitch = data.pitch; }
            if (Math.abs(wrap(yaw - last[0])) < LOOK_THRESHOLD && Math.abs(pitch - last[1]) < LOOK_THRESHOLD) { continue; }
            last[0] = yaw; last[1] = pitch;
            player.dataPacket(move(yaw, pitch));
        }
    }

    private DataPacket move(float yaw, float pitch) {
        double y = data.y + eyeHeight();
        if (human()) {
            MovePlayerPacket packet = new MovePlayerPacket();
            packet.eid = eid; packet.x = (float) data.x; packet.y = (float) y; packet.z = (float) data.z;
            packet.yaw = yaw; packet.headYaw = yaw; packet.pitch = pitch; packet.onGround = true;
            return packet;
        }
        MoveEntityPacket packet = new MoveEntityPacket();
        packet.eid = eid; packet.x = data.x; packet.y = y; packet.z = data.z;
        packet.yaw = yaw; packet.headYaw = yaw; packet.pitch = pitch;
        return packet;
    }

    private EntityMetadata metadata() {
        return new EntityMetadata()
                .putByte(Entity.DATA_FLAGS, 0)
                .putShort(Entity.DATA_AIR, 300)
                .putString(Entity.DATA_NAMETAG, shownName)
                .putBoolean(Entity.DATA_SHOW_NAMETAG, true)
                .putBoolean(Entity.DATA_SILENT, true)
                .putBoolean(Entity.DATA_NO_AI, true)
                .putLong(Entity.DATA_LEAD_HOLDER, -1)
                .putByte(Entity.DATA_LEAD, 0);
    }

    private static float wrap(float angle) { angle %= 360; return angle > 180 ? angle - 360 : angle < -180 ? angle + 360 : angle; }
}
