package br.goldsuite.cosmetics;

import cn.nukkit.Player;
import cn.nukkit.Server;
import cn.nukkit.entity.Entity;
import cn.nukkit.entity.data.EntityMetadata;
import cn.nukkit.item.Item;
import cn.nukkit.network.protocol.*;
import java.util.*;

/**
 * Modelo 3D que so existe em pacotes: blocos flutuantes (entidade de bloco caindo, id 66) e itens soltos.
 * O servidor nao cria entidade, nao calcula fisica nem colisao. Cada modelo vai so para quem esta a ate
 * {@link #RANGE} blocos e so e reenviado quando o dono anda ou gira (mais um reforco a cada meio segundo parado).
 * Usado na Nuvem Voadora e nos pingentes-chaveiro do ranked.
 */
final class PacketModel {
    static final double RANGE = 32;
    private static final int FALLING_BLOCK = 66, DATA_BLOCK_INFO = 20, DATA_NO_AI = 15, WOOL = 35, YELLOW = 4, ORANGE = 1;

    /** Parte do modelo no espaco do jogador: x = direita, y = altura a partir dos pes, z = frente. */
    static final class Part {
        final double right, up, forward; final boolean item; final int id, meta;
        Part(double right, double up, double forward, boolean item, int id, int meta) { this.right = right; this.up = up; this.forward = forward; this.item = item; this.id = id; this.meta = meta; }
    }

    private static Part block(double r, double u, double f, int meta) { return new Part(r, u, f, false, WOOL, meta); }

    /** Nuvem do estilo "Kinto'un": corpo 3x3 amarelo, bordas laranja, fofuras e a cauda curvada para tras. */
    static PacketModel cloud(Player owner) {
        double y = -0.8;
        return new PacketModel(owner, Arrays.asList(
                block(-1, y, -1, YELLOW), block(0, y, -1, YELLOW), block(1, y, -1, YELLOW),
                block(-1, y, 0, YELLOW), block(0, y, 0, YELLOW), block(1, y, 0, YELLOW),
                block(-1, y, 1, YELLOW), block(0, y, 1, YELLOW), block(1, y, 1, YELLOW),
                block(-2, y - 0.2, 0, ORANGE), block(2, y - 0.2, 0, ORANGE), block(0, y - 0.2, 2, ORANGE),
                block(-1, y + 0.45, 1, YELLOW), block(1, y + 0.45, -1, YELLOW),
                block(0, y - 0.1, -2, ORANGE), block(1, y + 0.2, -3, ORANGE), block(1.6, y + 0.8, -3.6, ORANGE)));
    }

    /** Chaveiro: o item do pingente flutuando e girando ao lado do ombro direito. */
    static PacketModel keychain(Player owner, int id, int meta) { return new PacketModel(owner, Collections.singletonList(new Part(0.7, 1.05, -0.25, true, id, meta))); }

    private final Player owner;
    private final List<Part> parts;
    private final long[] ids;
    private final Set<Player> viewers = Collections.newSetFromMap(new IdentityHashMap<>());
    private double lx = Double.NaN, ly, lz, lyaw;
    private int idle;

    private PacketModel(Player owner, List<Part> parts) {
        this.owner = owner; this.parts = parts; this.ids = new long[parts.size()];
        for (int i = 0; i < ids.length; i++) { ids[i] = Entity.entityCount++; }
    }

    /** Posicao de cada parte girada pelo yaw do dono (frente = (-sin, cos), direita = (-cos, -sin)). */
    private double[][] positions() {
        double yaw = Math.toRadians(owner.yaw), sin = Math.sin(yaw), cos = Math.cos(yaw);
        double[][] out = new double[parts.size()][3];
        for (int i = 0; i < out.length; i++) {
            Part p = parts.get(i);
            out[i][0] = owner.x - sin * p.forward - cos * p.right;
            out[i][1] = owner.y + p.up;
            out[i][2] = owner.z + cos * p.forward - sin * p.right;
        }
        return out;
    }

    /** A cada 2 ticks: acerta quem ve e move o modelo se o dono se mexeu. */
    void update(Server server) {
        Set<Player> now = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Player p : owner.level.getPlayers().values()) { if (p.spawned && p.distanceSquared(owner) <= RANGE * RANGE) { now.add(p); } }
        for (Iterator<Player> it = viewers.iterator(); it.hasNext(); ) {
            Player p = it.next();
            if (!now.contains(p)) { if (p.isOnline()) { despawn(server, p); } it.remove(); }
        }
        double[][] pos = positions();
        List<Player> fresh = new ArrayList<>();
        for (Player p : now) { if (viewers.add(p)) { fresh.add(p); } }
        if (!fresh.isEmpty()) { server.batchPackets(fresh.toArray(new Player[0]), spawnPackets(pos), false); }
        boolean moved = Double.isNaN(lx) || Math.abs(owner.x - lx) + Math.abs(owner.y - ly) + Math.abs(owner.z - lz) > 0.02 || Math.abs(owner.yaw - lyaw) > 2;
        // Parado, reforca a posicao a cada meio segundo (o cliente pode puxar o bloco ou item para baixo).
        if (!moved && ++idle < 5) { return; }
        idle = 0;
        lx = owner.x; ly = owner.y; lz = owner.z; lyaw = owner.yaw;
        if (viewers.isEmpty()) { return; }
        DataPacket[] moves = new DataPacket[ids.length];
        for (int i = 0; i < ids.length; i++) {
            MoveEntityPacket m = new MoveEntityPacket();
            m.eid = ids[i]; m.x = pos[i][0]; m.y = pos[i][1]; m.z = pos[i][2];
            moves[i] = m;
        }
        server.batchPackets(viewers.toArray(new Player[0]), moves, false);
    }

    private DataPacket[] spawnPackets(double[][] pos) {
        DataPacket[] packets = new DataPacket[ids.length];
        for (int i = 0; i < ids.length; i++) {
            Part part = parts.get(i);
            if (part.item) {
                AddItemEntityPacket add = new AddItemEntityPacket();
                add.eid = ids[i]; add.item = glint(Item.get(part.id, part.meta, 1));
                add.x = (float) pos[i][0]; add.y = (float) pos[i][1]; add.z = (float) pos[i][2];
                packets[i] = add;
                continue;
            }
            AddEntityPacket add = new AddEntityPacket();
            add.eid = ids[i]; add.type = FALLING_BLOCK;
            add.x = (float) pos[i][0]; add.y = (float) pos[i][1]; add.z = (float) pos[i][2];
            add.metadata = new EntityMetadata().putLong(Entity.DATA_FLAGS, 0).putInt(DATA_BLOCK_INFO, part.id | part.meta << 8).putByte(DATA_NO_AI, 1);
            packets[i] = add;
        }
        return packets;
    }

    /** Brilho de encantado: o item do chaveiro se destaca de qualquer item comum. */
    static Item glint(Item item) {
        try { item.addEnchantment(cn.nukkit.item.enchantment.Enchantment.getEnchantment(cn.nukkit.item.enchantment.Enchantment.ID_DURABILITY)); } catch (RuntimeException ignored) { }
        return item;
    }

    private void despawn(Server server, Player p) {
        DataPacket[] packets = new DataPacket[ids.length];
        for (int i = 0; i < ids.length; i++) { RemoveEntityPacket r = new RemoveEntityPacket(); r.eid = ids[i]; packets[i] = r; }
        server.batchPackets(new Player[] {p}, packets, false);
    }

    /** Some para todos (tirou o cosmetico, saiu, trocou de mundo, TPS baixo). */
    void remove(Server server) {
        for (Player p : viewers) { if (p.isOnline()) { despawn(server, p); } }
        viewers.clear();
        lx = Double.NaN;
    }

    void forget(Player p) { viewers.remove(p); }
}
