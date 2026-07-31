package com.wsteam.wandscape.shared.network;

import java.util.ArrayList;
import java.util.List;

import com.wsteam.wandscape.building.data.BuildingConfig;
import com.wsteam.wandscape.building.internal.BuildingConfigLoader;
import com.wsteam.wandscape.projection.BuildingRotation;
import com.wsteam.wandscape.shared.data.BuildingData;
import com.wsteam.wandscape.shared.log.Log;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import static com.wsteam.wandscape.Wandscape.MODID;

/**
 * Server→Client: Syncs all building positions and pre-rotated boundary boxes
 * for interaction area overlay when the Wandscape panel is opened.
 *
 * <p>Boundary is rotated on the server so clients never need to apply
 * rotation themselves — all boundary fields in {@link BuildingEntry}
 * are already in world-space offset coordinates ready for direct use.
 */
public record BuildingAreaSyncPacket(List<BuildingEntry> buildings) implements CustomPacketPayload {

    private static final String TAG = "BuildingAreaSync";

    public static final Type<BuildingAreaSyncPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "building_area_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BuildingAreaSyncPacket> STREAM_CODEC =
            StreamCodec.of(BuildingAreaSyncPacket::write, BuildingAreaSyncPacket::read);

    /** Client-side cache, updated each time the panel opens. */
    private static volatile List<BuildingEntry> cached = List.of();

    public static List<BuildingEntry> getCached() {
        return cached;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    // ── Client handler ──

    public static void handleClient(BuildingAreaSyncPacket packet) {
        cached = packet.buildings;
        com.wsteam.wandscape.shared.ui.panel.WandscapePanelState.evaluateGuidance();
        Log.info(TAG, "[Area] Cached {} building areas", packet.buildings.size());
    }

    // ── Factory: server-side creation with pre-rotated boundary ──

    /**
     * Create a BuildingEntry from a BuildingData, pre-rotating the boundary
     * so clients never need to apply rotation themselves.
     */
    public static BuildingEntry fromBuildingData(BuildingData building) {
        BlockPos anchor = building.getPosition();
        String typeId = building.getBuildingTypeId();
        int rotationSteps = building.getRotationSteps();

        BuildingConfig config = BuildingConfigLoader.getInstance().get(typeId);
        BuildingConfig.BoundaryBox raw = config != null ? config.boundary() : null;
        if (raw == null) {
            return new BuildingEntry(anchor, typeId, rotationSteps,
                    false, 0, 0, 0, 0, 0, 0);
        }
        BuildingConfig.BoundaryBox boundary = rotationSteps != 0
                ? BuildingRotation.rotateBoundary(raw, rotationSteps) : raw;
        return new BuildingEntry(anchor, typeId, rotationSteps,
                true,
                boundary.min().x(), boundary.min().y(), boundary.min().z(),
                boundary.max().x(), boundary.max().y(), boundary.max().z());
    }

    // ── StreamCodec ──

    static void write(RegistryFriendlyByteBuf buf, BuildingAreaSyncPacket pkt) {
        buf.writeVarInt(pkt.buildings.size());
        for (BuildingEntry entry : pkt.buildings) {
            buf.writeBlockPos(entry.anchor());
            buf.writeUtf(entry.buildingTypeId());
            buf.writeByte(entry.rotationSteps());
            buf.writeBoolean(entry.hasBoundary());
            if (entry.hasBoundary()) {
                buf.writeVarInt(entry.bMinX());
                buf.writeVarInt(entry.bMinY());
                buf.writeVarInt(entry.bMinZ());
                buf.writeVarInt(entry.bMaxX());
                buf.writeVarInt(entry.bMaxY());
                buf.writeVarInt(entry.bMaxZ());
            }
        }
    }

    static BuildingAreaSyncPacket read(RegistryFriendlyByteBuf buf) {
        int count = buf.readVarInt();
        List<BuildingEntry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            BlockPos anchor = buf.readBlockPos();
            String typeId = buf.readUtf();
            int rotationSteps = buf.readByte();
            boolean hasBoundary = buf.readBoolean();
            int bMinX = 0, bMinY = 0, bMinZ = 0, bMaxX = 0, bMaxY = 0, bMaxZ = 0;
            if (hasBoundary) {
                bMinX = buf.readVarInt();
                bMinY = buf.readVarInt();
                bMinZ = buf.readVarInt();
                bMaxX = buf.readVarInt();
                bMaxY = buf.readVarInt();
                bMaxZ = buf.readVarInt();
            }
            entries.add(new BuildingEntry(anchor, typeId, rotationSteps,
                    hasBoundary, bMinX, bMinY, bMinZ, bMaxX, bMaxY, bMaxZ));
        }
        return new BuildingAreaSyncPacket(entries);
    }

    // ── Data ──

    public record BuildingEntry(BlockPos anchor, String buildingTypeId, int rotationSteps,
                                boolean hasBoundary,
                                int bMinX, int bMinY, int bMinZ,
                                int bMaxX, int bMaxY, int bMaxZ) {}
}
