package com.wsteam.wandscape.shared.network.tasks;

import com.wsteam.wandscape.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.shared.log.Log;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import static com.wsteam.wandscape.Wandscape.MODID;

/**
 * Client→Server: Toggles Mage mode (Follow player, Peace mode, Rest) from the Mage Roster view.
 */
public record MageModeActionPacket(
        int entityId,
        String mode, // "TOGGLE_FOLLOW", "TOGGLE_PEACE", "TOGGLE_REST"
        boolean enabled
) implements CustomPacketPayload {

    private static final String TAG = "MageModeAction";

    public static final String MODE_FOLLOW = "TOGGLE_FOLLOW";
    public static final String MODE_PEACE = "TOGGLE_PEACE";
    public static final String MODE_REST = "TOGGLE_REST";

    public static final Type<MageModeActionPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "mage_mode_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MageModeActionPacket> STREAM_CODEC =
            StreamCodec.of(MageModeActionPacket::write, MageModeActionPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    static void write(RegistryFriendlyByteBuf buf, MageModeActionPacket pkt) {
        buf.writeVarInt(pkt.entityId);
        buf.writeUtf(pkt.mode != null ? pkt.mode : "");
        buf.writeBoolean(pkt.enabled);
    }

    static MageModeActionPacket read(RegistryFriendlyByteBuf buf) {
        return new MageModeActionPacket(
                buf.readVarInt(),
                buf.readUtf(),
                buf.readBoolean()
        );
    }

    public static void handleServer(MageModeActionPacket packet, ServerPlayer player) {
        if (player == null || player.isRemoved()) return;

        var level = player.serverLevel();
        var entity = level.getEntity(packet.entityId);
        if (!(entity instanceof WandscapeNpc npc)) {
            Log.warn(TAG, "Target entity {} is not WandscapeNpc", packet.entityId);
            return;
        }

        switch (packet.mode) {
            case MODE_FOLLOW -> {
                if (packet.enabled) {
                    npc.setFollowMode(true);
                    npc.setFollowerUuid(player.getUUID());
                } else {
                    npc.setFollowMode(false);
                    npc.setFollowerUuid(null);
                }
            }
            case MODE_PEACE -> {
                npc.setPeaceMode(packet.enabled);
                if (packet.enabled) {
                    npc.clearHatedAttacker();
                }
            }
            default -> Log.warn(TAG, "Unknown mage mode: {}", packet.mode);
        }

        TaskPanelSyncTracker.markDirty();
    }
}
