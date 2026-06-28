package com.wsteam.wandscape.warehouse.network;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import static com.wsteam.wandscape.Wandscape.MODID;

/**
 * Server→client packet carrying warehouse auto-production thresholds for GUI display.
 * Sent alongside {@link WarehouseDataPacket} when the player opens the warehouse screen.
 */
public record WarehouseThresholdDataPacket(BlockPos buildingPos, UUID colonyId,
                                            ListTag thresholds) implements CustomPacketPayload {

    public static final Type<WarehouseThresholdDataPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "warehouse_threshold_data"));

    public static final StreamCodec<RegistryFriendlyByteBuf, WarehouseThresholdDataPacket> STREAM_CODEC =
            StreamCodec.of(WarehouseThresholdDataPacket::write, WarehouseThresholdDataPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Build packet from position, colony, and threshold snapshot. */
    public static WarehouseThresholdDataPacket from(BlockPos buildingPos, UUID colonyId,
                                                     Map<String, Long> thresholdMap) {
        ListTag list = new ListTag();
        for (var entry : thresholdMap.entrySet()) {
            CompoundTag tag = new CompoundTag();
            tag.putString("res", entry.getKey());
            tag.putLong("val", entry.getValue());
            list.add(tag);
        }
        return new WarehouseThresholdDataPacket(buildingPos, colonyId, list);
    }

    /** Decode threshold map for client rendering. */
    public Map<String, Long> thresholdMap() {
        Map<String, Long> result = new LinkedHashMap<>();
        for (int i = 0; i < thresholds.size(); i++) {
            CompoundTag tag = thresholds.getCompound(i);
            String res = tag.getString("res");
            long val = tag.getLong("val");
            if (!res.isEmpty() && val > 0) {
                result.put(res, val);
            }
        }
        return result;
    }

    private static Consumer<WarehouseThresholdDataPacket> clientHandler;

    public static void setClientHandler(Consumer<WarehouseThresholdDataPacket> handler) {
        clientHandler = handler;
    }

    /** Handle on client: dispatched via injected Consumer from WandscapeClient. */
    public static void handleClient(WarehouseThresholdDataPacket packet) {
        if (clientHandler != null) {
            clientHandler.accept(packet);
        }
    }

    // ── StreamCodec helpers ──

    static void write(RegistryFriendlyByteBuf buf, WarehouseThresholdDataPacket pkt) {
        CompoundTag wrapper = new CompoundTag();
        wrapper.putLong("pos", pkt.buildingPos.asLong());
        wrapper.putUUID("colony", pkt.colonyId);
        wrapper.put("thresh", pkt.thresholds);
        buf.writeNbt(wrapper);
    }

    static WarehouseThresholdDataPacket read(RegistryFriendlyByteBuf buf) {
        CompoundTag wrapper = buf.readNbt();
        if (wrapper == null) {
            return new WarehouseThresholdDataPacket(BlockPos.ZERO, new UUID(0, 0), new ListTag());
        }
        BlockPos buildingPos = BlockPos.of(wrapper.getLong("pos"));
        UUID colonyId = wrapper.contains("colony") ? wrapper.getUUID("colony") : new UUID(0, 0);
        ListTag thresholds = wrapper.getList("thresh", Tag.TAG_COMPOUND);
        return new WarehouseThresholdDataPacket(buildingPos, colonyId, thresholds);
    }
}
