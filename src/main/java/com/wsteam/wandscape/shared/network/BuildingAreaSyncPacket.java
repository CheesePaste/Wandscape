package com.wsteam.wandscape.shared.network;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.wsteam.wandscape.building.data.BuildingConfig;
import com.wsteam.wandscape.building.internal.BuildingConfigLoader;
import com.wsteam.wandscape.projection.BuildingRotation;
import com.wsteam.wandscape.shared.api.BuildingApi;
import com.wsteam.wandscape.shared.api.ColonyApi;
import com.wsteam.wandscape.shared.data.BuildingData;
import com.wsteam.wandscape.shared.log.Log;
import com.wsteam.wandscape.shared.registry.WandscapeApis;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;

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

    /**
     * Find which building (if any) contains the given block position, using the
     * cached building-area data. Returns a stable per-building UUID derived from
     * type + anchor, or {@code null} if the block is not inside any building.
     */
    @Nullable
    public static UUID findBuildingIdAt(BlockPos pos) {
        for (BuildingEntry entry : cached) {
            if (!entry.hasBoundary()) continue;

            BlockPos anchor = entry.anchor();
            int x = pos.getX(), y = pos.getY(), z = pos.getZ();
            int ax = anchor.getX(), ay = anchor.getY(), az = anchor.getZ();

            if (x >= ax + entry.bMinX() && x <= ax + entry.bMaxX()
                    && y >= ay + entry.bMinY() && y <= ay + entry.bMaxY()
                    && z >= az + entry.bMinZ() && z <= az + entry.bMaxZ()) {
                return UUID.nameUUIDFromBytes((
                        entry.buildingTypeId() + "@" + anchor).getBytes());
            }
        }
        return null;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    // ── Client handler ──

    public static void handleClient(BuildingAreaSyncPacket packet) {
        cached = packet.buildings;
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
        boolean completed = building.hasEverCompleted();

        BuildingConfig config = BuildingConfigLoader.getInstance().get(typeId);
        String category = config != null ? config.category() : "";
        BuildingConfig.BoundaryBox raw = config != null ? config.boundary() : null;
        if (raw == null) {
            return new BuildingEntry(anchor, typeId, category, rotationSteps, completed,
                    false, 0, 0, 0, 0, 0, 0);
        }
        BuildingConfig.BoundaryBox boundary = rotationSteps != 0
                ? BuildingRotation.rotateBoundary(raw, rotationSteps) : raw;
        return new BuildingEntry(anchor, typeId, category, rotationSteps, completed,
                true,
                boundary.min().x(), boundary.min().y(), boundary.min().z(),
                boundary.max().x(), boundary.max().y(), boundary.max().z());
    }

    /**
     * Build and send the building-area sync packet to a player (colony-scoped).
     * Mirrors the panel-open sync so callers can refresh the client cache after
     * any building lifecycle change (e.g. a new placement).
     */
    public static void sendToPlayer(ServerPlayer player) {
        sendToPlayer(player, null);
    }

    /**
     * Send the building-area sync, resolving the colony from the player's
     * position, or from {@code colonyHint} when the player stands outside any
     * colony range (e.g. right after creating a colony at a distant anchor).
     */
    public static void sendToPlayer(ServerPlayer player, @Nullable BlockPos colonyHint) {
        ColonyApi colonyApi = WandscapeApis.getColonyApiSilently();
        if (colonyApi == null) return;
        UUID colonyId = colonyApi.getColonyId(player.blockPosition());
        if (colonyId == null && colonyHint != null) {
            colonyId = colonyApi.getColonyId(colonyHint);
        }
        if (colonyId == null) return;
        BuildingApi buildingApi = WandscapeApis.getBuildingApi();
        if (buildingApi == null) return;

        List<BuildingEntry> entries = buildingApi.getColonyBuildings(colonyId).stream()
                .map(BuildingAreaSyncPacket::fromBuildingData)
                .toList();
        PacketDistributor.sendToPlayer(player, new BuildingAreaSyncPacket(entries));
        Log.info(TAG, "[Area] Sent {} building areas to {}", entries.size(),
                player.getGameProfile().getName());
    }

    /**
     * Re-send the area sync to all players after a building integrity
     * transition (completed / broken / repaired), so client caches refresh —
     * e.g. a just-completed building's construction ghost footprint clears.
     * Players outside any colony fall back to the given anchor's colony.
     */
    public static void broadcastToColony(MinecraftServer server, BlockPos anchor) {
        if (server == null) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            sendToPlayer(player, anchor);
        }
    }

    // ── StreamCodec ──

    static void write(RegistryFriendlyByteBuf buf, BuildingAreaSyncPacket pkt) {
        buf.writeVarInt(pkt.buildings.size());
        for (BuildingEntry entry : pkt.buildings) {
            buf.writeBlockPos(entry.anchor());
            buf.writeUtf(entry.buildingTypeId());
            buf.writeUtf(entry.category());
            buf.writeByte(entry.rotationSteps());
            buf.writeBoolean(entry.completed());
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
            String category = buf.readUtf();
            int rotationSteps = buf.readByte();
            boolean completed = buf.readBoolean();
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
            entries.add(new BuildingEntry(anchor, typeId, category, rotationSteps, completed,
                    hasBoundary, bMinX, bMinY, bMinZ, bMaxX, bMaxY, bMaxZ));
        }
        return new BuildingAreaSyncPacket(entries);
    }

    // ── Data ──

    public record BuildingEntry(BlockPos anchor, String buildingTypeId, String category, int rotationSteps,
                                boolean completed,
                                boolean hasBoundary,
                                int bMinX, int bMinY, int bMinZ,
                                int bMaxX, int bMaxY, int bMaxZ) {}
}
