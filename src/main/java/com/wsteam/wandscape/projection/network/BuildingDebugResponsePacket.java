package com.wsteam.wandscape.projection.network;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.wsteam.wandscape.shared.data.WorkItem;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import static com.wsteam.wandscape.Wandscape.MODID;

/**
 * Server→Client: debug data snapshot for a building.
 */
public record BuildingDebugResponsePacket(
        UUID buildingId,
        String buildingTypeId,
        String category,
        UUID colonyId,
        BlockPos anchor,
        boolean intact,
        boolean shutdown,
        int comfort,
        int magic,
        int wonder,
        int queueCapacity,
        List<WorkItem> queue,
        UUID currentTaskId
) implements CustomPacketPayload {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final Type<BuildingDebugResponsePacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "building_debug_response"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BuildingDebugResponsePacket> STREAM_CODEC =
            StreamCodec.of(BuildingDebugResponsePacket::write, BuildingDebugResponsePacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    // ── Client handler ──

    public static void handleClient(BuildingDebugResponsePacket packet) {
        LOGGER.info("[Debug] Received debug response for '{}' at {}",
                packet.buildingTypeId(), packet.anchor());
        net.minecraft.client.Minecraft.getInstance().execute(() -> {
            var screen = new com.wsteam.wandscape.projection.client.BuildingDebugScreen(packet);
            net.minecraft.client.Minecraft.getInstance().setScreen(screen);
        });
    }

    // ── StreamCodec ──

    static void write(RegistryFriendlyByteBuf buf, BuildingDebugResponsePacket pkt) {
        buf.writeUUID(pkt.buildingId());
        buf.writeResourceLocation(ResourceLocation.parse(pkt.buildingTypeId()));
        buf.writeUtf(pkt.category(), 256);
        if (pkt.colonyId() != null) {
            buf.writeBoolean(true);
            buf.writeUUID(pkt.colonyId());
        } else {
            buf.writeBoolean(false);
        }
        buf.writeBlockPos(pkt.anchor());
        buf.writeBoolean(pkt.intact());
        buf.writeBoolean(pkt.shutdown());
        buf.writeInt(pkt.comfort());
        buf.writeInt(pkt.magic());
        buf.writeInt(pkt.wonder());
        buf.writeInt(pkt.queueCapacity());

        // Queue
        int qSize = pkt.queue() != null ? pkt.queue().size() : 0;
        buf.writeInt(qSize);
        for (WorkItem item : pkt.queue()) {
            buf.writeUtf(item.blueprintId(), 256);
            buf.writeInt(item.priority());
            buf.writeUtf(item.params() != null ? item.params().toString() : "", 2048);
        }

        if (pkt.currentTaskId() != null) {
            buf.writeBoolean(true);
            buf.writeUUID(pkt.currentTaskId());
        } else {
            buf.writeBoolean(false);
        }
    }

    static BuildingDebugResponsePacket read(RegistryFriendlyByteBuf buf) {
        UUID buildingId = buf.readUUID();
        String typeId = buf.readResourceLocation().toString();
        String category = buf.readUtf(256);
        UUID colonyId = buf.readBoolean() ? buf.readUUID() : null;
        BlockPos anchor = buf.readBlockPos();
        boolean intact = buf.readBoolean();
        boolean shutdown = buf.readBoolean();
        int comfort = buf.readInt();
        int magic = buf.readInt();
        int wonder = buf.readInt();
        int queueCap = buf.readInt();

        int qSize = buf.readInt();
        List<WorkItem> queue = new java.util.ArrayList<>(qSize);
        for (int i = 0; i < qSize; i++) {
            String bpId = buf.readUtf(256);
            int priority = buf.readInt();
            String paramsJson = buf.readUtf(2048);
            queue.add(new WorkItem(bpId, Map.of(), priority));
        }

        UUID currentTaskId = buf.readBoolean() ? buf.readUUID() : null;

        return new BuildingDebugResponsePacket(
                buildingId, typeId, category, colonyId, anchor,
                intact, shutdown, comfort, magic, wonder, queueCap,
                queue, currentTaskId);
    }
}
