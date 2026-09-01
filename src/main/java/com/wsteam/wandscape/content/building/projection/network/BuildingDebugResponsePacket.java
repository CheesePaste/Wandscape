package com.wsteam.wandscape.content.building.projection.network;

import com.wsteam.wandscape.content.building.data.WorkItem;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.UUID;

import static com.wsteam.wandscape.Wandscape.MODID;

/**
 * Server→Client: debug data snapshot for a building.
 */
public record BuildingDebugResponsePacket(
        UUID buildingId,
        String buildingTypeId,
        String displayName,
        String category,
        UUID colonyId,
        BlockPos anchor,
        boolean intact,
        boolean needsRepair,
        boolean underConstruction,
        boolean constructionStarted,
        boolean demolishing,
        int comfort,
        int magic,
        int wonder,
        List<WorkItem> queue,
        UUID currentTaskId
) implements CustomPacketPayload {

    private static final String TAG = "BuildingDebugResponsePacket";

    public static final Type<BuildingDebugResponsePacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "building_debug_response"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BuildingDebugResponsePacket> STREAM_CODEC =
            StreamCodec.of(BuildingDebugResponsePacket::write, BuildingDebugResponsePacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static java.util.function.Consumer<BuildingDebugResponsePacket> clientHandler = packet -> {};
    public static void setClientHandler(java.util.function.Consumer<BuildingDebugResponsePacket> handler) { clientHandler = handler; }

    public static void handleClient(BuildingDebugResponsePacket packet) {
        clientHandler.accept(packet);
    }

    // ── StreamCodec ──

    static void write(RegistryFriendlyByteBuf buf, BuildingDebugResponsePacket pkt) {
        buf.writeUUID(pkt.buildingId());
        buf.writeUtf(pkt.buildingTypeId(), 256);
        buf.writeUtf(pkt.displayName(), 256);
        buf.writeUtf(pkt.category(), 256);
        if (pkt.colonyId() != null) {
            buf.writeBoolean(true);
            buf.writeUUID(pkt.colonyId());
        } else {
            buf.writeBoolean(false);
        }
        buf.writeBlockPos(pkt.anchor());
        buf.writeBoolean(pkt.intact());
        buf.writeBoolean(pkt.needsRepair());
        buf.writeBoolean(pkt.underConstruction());
        buf.writeBoolean(pkt.constructionStarted());
        buf.writeBoolean(pkt.demolishing());
        buf.writeInt(pkt.comfort());
        buf.writeInt(pkt.magic());
        buf.writeInt(pkt.wonder());

        // Queue — params are not sent (client ignores them anyway)
        int qSize = pkt.queue() != null ? pkt.queue().size() : 0;
        buf.writeInt(qSize);
        for (WorkItem item : pkt.queue()) {
            buf.writeUtf(item.blueprintId(), 256);
            buf.writeInt(item.priority());
            buf.writeUtf("", 0);
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
        String buildingTypeId = buf.readUtf(256);
        String displayName = buf.readUtf(256);
        String category = buf.readUtf(256);
        UUID colonyId = buf.readBoolean() ? buf.readUUID() : null;
        BlockPos anchor = buf.readBlockPos();
        boolean intact = buf.readBoolean();
        boolean needsRepair = buf.readBoolean();
        boolean underConstruction = buf.readBoolean();
        boolean constructionStarted = buf.readBoolean();
        boolean demolishing = buf.readBoolean();
        int comfort = buf.readInt();
        int magic = buf.readInt();
        int wonder = buf.readInt();

        int qSize = buf.readInt();
        List<WorkItem> queue = new java.util.ArrayList<>(qSize);
        for (int i = 0; i < qSize; i++) {
            String bpId = buf.readUtf(256);
            int prio = buf.readInt();
            buf.readUtf(0);
            queue.add(new WorkItem(bpId, java.util.Collections.emptyMap(), prio));
        }

        UUID currentTaskId = buf.readBoolean() ? buf.readUUID() : null;

        return new BuildingDebugResponsePacket(
                buildingId, buildingTypeId, displayName, category,
                colonyId, anchor, intact, needsRepair,
                underConstruction, constructionStarted, demolishing,
                comfort, magic, wonder, queue, currentTaskId
        );
    }
}
