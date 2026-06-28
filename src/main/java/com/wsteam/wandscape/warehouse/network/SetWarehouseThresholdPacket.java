package com.wsteam.wandscape.warehouse.network;

import java.util.Map;
import java.util.UUID;

import com.wsteam.wandscape.building.internal.BuildingSavedData;
import com.wsteam.wandscape.building.internal.BuildingState;
import com.wsteam.wandscape.core.system.WarehouseSource;
import com.wsteam.wandscape.core.types.ResourceId;
import com.wsteam.wandscape.warehouse.ColonyItemBank;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import static com.wsteam.wandscape.Wandscape.MODID;

/**
 * Client→server packet: sets an auto-production threshold for a resource.
 * Server updates both ColonyItemBank (persisted) and WarehouseSource (live).
 * Responds with a refreshed {@link WarehouseThresholdDataPacket}.
 */
public record SetWarehouseThresholdPacket(BlockPos buildingPos, UUID colonyId,
                                           String resourceId, int newValue)
        implements CustomPacketPayload {

    public static final Type<SetWarehouseThresholdPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "set_warehouse_threshold"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SetWarehouseThresholdPacket> STREAM_CODEC =
            StreamCodec.of(SetWarehouseThresholdPacket::write, SetWarehouseThresholdPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handleServer(SetWarehouseThresholdPacket packet,
                                      net.neoforged.neoforge.network.handling.IPayloadContext ctx) {
        if (packet.resourceId.isEmpty()) return;
        if (packet.newValue < 0) return;

        ctx.enqueueWork(() -> {
            var player = (net.minecraft.server.level.ServerPlayer) ctx.player();
            if (player == null) return;

            // Persist threshold in ColonyItemBank
            ColonyItemBank bank = ColonyItemBank.get(player.serverLevel());
            bank.setThreshold(packet.colonyId, packet.resourceId, packet.newValue);

            // Update live WarehouseSource
            WarehouseSource ws = WarehouseSource.getActive();
            if (ws != null) {
                ws.setThreshold(new ResourceId(packet.resourceId), packet.newValue);
            }

            // Send refreshed threshold data back to player
            Map<String, Long> updated = bank.getAllThresholds(packet.colonyId);
            var refresh = WarehouseThresholdDataPacket.from(
                    packet.buildingPos, packet.colonyId, updated);
            PacketDistributor.sendToPlayer(player, refresh);
        });
    }

    // ── StreamCodec helpers ──

    static void write(RegistryFriendlyByteBuf buf, SetWarehouseThresholdPacket pkt) {
        CompoundTag tag = new CompoundTag();
        tag.putLong("pos", pkt.buildingPos.asLong());
        tag.putUUID("colony", pkt.colonyId);
        tag.putString("res", pkt.resourceId);
        tag.putInt("val", pkt.newValue);
        buf.writeNbt(tag);
    }

    static SetWarehouseThresholdPacket read(RegistryFriendlyByteBuf buf) {
        CompoundTag tag = buf.readNbt();
        if (tag == null) {
            return new SetWarehouseThresholdPacket(BlockPos.ZERO, new UUID(0, 0), "", 0);
        }
        return new SetWarehouseThresholdPacket(
                BlockPos.of(tag.getLong("pos")),
                tag.getUUID("colony"),
                tag.getString("res"),
                tag.getInt("val"));
    }
}
