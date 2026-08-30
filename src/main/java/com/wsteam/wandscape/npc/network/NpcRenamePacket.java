package com.wsteam.wandscape.npc.network;

import com.wsteam.wandscape.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.shared.log.Log;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import static com.wsteam.wandscape.Wandscape.MODID;
/**
 * Client→server packet: rename an NPC from the info screen's editable name field.
 * Player edits the top-left name box → each accepted edit sends this packet;
 * server trims / length-limits and sets the custom name (auto-save, no button).
 * The head nametag updates via the entity's custom-name sync.
 */
public record NpcRenamePacket(int entityId, String name) implements CustomPacketPayload {

    public static final int MAX_NAME_LENGTH = 30;

    public static final Type<NpcRenamePacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "npc_rename"));

    public static final StreamCodec<RegistryFriendlyByteBuf, NpcRenamePacket> STREAM_CODEC =
            StreamCodec.of(NpcRenamePacket::write, NpcRenamePacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    static void write(RegistryFriendlyByteBuf buf, NpcRenamePacket pkt) {
        buf.writeInt(pkt.entityId);
        buf.writeUtf(pkt.name != null ? pkt.name : "");
    }

    static NpcRenamePacket read(RegistryFriendlyByteBuf buf) {
        return new NpcRenamePacket(buf.readInt(), buf.readUtf());
    }

    private static final String TAG = "NpcRenamePacket";

    public static void handleServer(NpcRenamePacket packet, ServerPlayer player) {
        if (player == null || player.isRemoved()) return;

        var level = player.serverLevel();
        var entity = level.getEntity(packet.entityId());
        if (!(entity instanceof WandscapeNpc npc)) {
            Log.warn(TAG, "Rename target entity {} is not a WandscapeNpc", packet.entityId());
            return;
        }

        String name = packet.name() == null ? "" : packet.name().trim();
        if (name.isEmpty()) return;
        if (name.length() > MAX_NAME_LENGTH) {
            name = name.substring(0, MAX_NAME_LENGTH);
        }
        npc.setCustomName(Component.literal(name));
        npc.setCustomNameVisible(true);
        Log.info(TAG, "NPC {} renamed to {}", npc.getUUID().toString().substring(0, 8), name);
    }
}
