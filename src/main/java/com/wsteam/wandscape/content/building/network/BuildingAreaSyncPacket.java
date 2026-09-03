package com.wsteam.wandscape.content.building.network;
import com.wsteam.wandscape.content.task.component.Position;
import com.wsteam.wandscape.content.task.ecs.World;

import com.wsteam.wandscape.content.building.data.BuildingConfig;
import com.wsteam.wandscape.content.building.internal.BuildingConfigLoader;
import com.wsteam.wandscape.content.building.internal.BuildingVoxels;
import com.wsteam.wandscape.content.building.projection.BuildingRotation;
import com.wsteam.wandscape.api.BuildingApi;
import com.wsteam.wandscape.api.ColonyApi;
import com.wsteam.wandscape.content.building.data.BuildingData;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.api.WandscapeApis;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

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

    /** Cached world-space occupancy of cached buildings, keyed "typeId|anchor|rotation". */
    private static final Map<String, BuildingVoxels.Occupancy> entryOccupancies = new HashMap<>();

    public static List<BuildingEntry> getCached() {
        return cached;
    }

    /** Clear the client cache (e.g. when the client leaves a world/save). */
    public static void clear() {
        cached = List.of();
        entryOccupancies.clear();
    }

    /**
     * Whether placing the building {@code config} at {@code anchor} with
     * {@code rotationSteps} (90° CCW) would put a pattern voxel on top of an
     * existing building's pattern voxel. Two-phase: broad-phase boundary-AABB
     * overlap first, then a precise rotated-pattern compare — sharing the exact
     * {@link BuildingVoxels} rule the server {@code register} gate uses: bounding
     * boxes may overlap freely, a world voxel may belong to at most one building.
     * Advisory preview; the server is authoritative.
     */
    public static boolean voxelConflicts(@Nullable BuildingConfig config, BlockPos anchor, int rotationSteps) {
        if (config == null || config.pattern() == null || config.pattern().isEmpty()) return false;
        BuildingVoxels.Occupancy mine = BuildingVoxels.compute(config, anchor, rotationSteps);
        if (mine.isEmpty()) return false;

        for (BuildingEntry entry : cached) {
            if (!entry.hasBoundary()) continue;
            // Broad phase: existing building's boundary AABB (already rotated world-space).
            if (!extentHitsBoundary(mine.extent(), entry)) continue;
            // Narrow phase: precise voxel compare against the existing building's pattern.
            if (BuildingVoxels.overlaps(mine, entryOccupancy(entry))) {
                return true;
            }
        }
        return false;
    }

    /** Whether a voxel-extent AABB touches a cached entry's boundary box. */
    private static boolean extentHitsBoundary(@Nullable net.minecraft.world.level.levelgen.structure.BoundingBox ext,
                                              BuildingEntry entry) {
        if (ext == null) return false;
        BlockPos a = entry.anchor();
        return ext.maxX() >= a.getX() + entry.bMinX() && ext.minX() <= a.getX() + entry.bMaxX()
                && ext.maxY() >= a.getY() + entry.bMinY() && ext.minY() <= a.getY() + entry.bMaxY()
                && ext.maxZ() >= a.getZ() + entry.bMinZ() && ext.minZ() <= a.getZ() + entry.bMaxZ();
    }

    /** Lazy, cached world-space occupancy of a cached building entry (rotation applied). */
    private static BuildingVoxels.Occupancy entryOccupancy(BuildingEntry entry) {
        String key = entry.buildingTypeId() + '|' + entry.anchor() + '|' + (entry.rotationSteps() & 3);
        BuildingVoxels.Occupancy occ = entryOccupancies.get(key);
        if (occ != null) return occ;
        BuildingConfig cfg = BuildingConfigLoader.getInstance().get(entry.buildingTypeId());
        occ = (cfg == null || cfg.pattern() == null || cfg.pattern().isEmpty())
                ? BuildingVoxels.computeFromOffsets(List.of(), entry.anchor())
                : BuildingVoxels.compute(cfg, entry.anchor(), entry.rotationSteps());
        entryOccupancies.put(key, occ);
        return occ;
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
                return buildingId(entry);
            }
        }
        return null;
    }

    /**
     * Stable per-building UUID derived from type + anchor. Mirrors the derivation
     * used by {@link #findBuildingIdAt}; only meaningful client-side (the server
     * resolves the real UUID by position).
     */
    public static UUID buildingId(BuildingEntry entry) {
        return UUID.nameUUIDFromBytes((entry.buildingTypeId() + "@" + entry.anchor()).getBytes());
    }

    /** World-space AABB for a building entry, or {@code null} when it has no boundary. */
    @Nullable
    public static AABB worldBox(BuildingEntry entry) {
        if (!entry.hasBoundary()) return null;
        BlockPos a = entry.anchor();
        return new AABB(
                a.getX() + entry.bMinX(), a.getY() + entry.bMinY(), a.getZ() + entry.bMinZ(),
                a.getX() + entry.bMaxX() + 1, a.getY() + entry.bMaxY() + 1, a.getZ() + entry.bMaxZ() + 1);
    }

    /** A block position guaranteed to lie inside the entry's boundary box (its center). */
    public static BlockPos interiorBlockPos(BuildingEntry entry) {
        BlockPos a = entry.anchor();
        if (!entry.hasBoundary()) return a;
        return new BlockPos(
                a.getX() + (entry.bMinX() + entry.bMaxX()) / 2,
                a.getY() + (entry.bMinY() + entry.bMaxY()) / 2,
                a.getZ() + (entry.bMinZ() + entry.bMaxZ()) / 2);
    }

    /**
     * 判定盒外扩量（格）。俯瞰相机会从上方 45° 斜射，低矮建筑的射线会“擦着”近底角
     * 入框，入口距离和地形命中几乎相等——外扩一格让入口明确早于地形，俯视也能整片选中。
     */
    private static final double RAYCAST_INFLATE = 1.0;

    /**
     * Raycast against the bounding boxes of all registered-but-not-yet-completed
     * buildings in the cached area data — the construction ghosts. When the
     * crosshair ray passes through a ghost area (or the eye is inside it), the
     * building counts as targeted, so an empty construction site with no placed
     * blocks can still be selected.
     *
     * @return the nearest intersecting unbuilt building, or {@code null}
     */
    @Nullable
    public static BuildingBoxHit raycastUnbuilt(Vec3 origin, Vec3 end) {
        // Prefer boxes whose surface the ray actually crosses; only when no box is
        // crossed (but the eye stands inside one) fall back to the contained box —
        // otherwise a player inside one site but looking at a neighbour's ghost
        // would wrongly target the site underfoot instead of the one being aimed at.
        BuildingBoxHit best = null;
        BuildingBoxHit contained = null;
        double bestDist = Double.MAX_VALUE;
        for (BuildingEntry entry : cached) {
            if (entry.completed()) continue;
            AABB real = worldBox(entry);
            if (real == null) continue;
            AABB box = real.inflate(RAYCAST_INFLATE);

            Optional<Vec3> hit = box.clip(origin, end);
            if (hit.isPresent()) {
                double dist = hit.get().distanceToSqr(origin);
                if (dist < bestDist) {
                    bestDist = dist;
                    best = new BuildingBoxHit(buildingId(entry), interiorBlockPos(entry), dist);
                }
            } else if (contained == null && box.contains(origin)) {
                // Eye inside the ghost box (standing inside the empty site) — treat as hit.
                contained = new BuildingBoxHit(buildingId(entry), interiorBlockPos(entry), 0);
            }
        }
        return best != null ? best : contained;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    // ── Client handler ──

    public static void handleClient(BuildingAreaSyncPacket packet) {
        cached = packet.buildings;
        // Entries changed — drop rebuilt occupancy caches so voxel-conflict previews
        // never compare against stale building shapes.
        entryOccupancies.clear();
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
        sendToPlayer(player, (BlockPos) null);
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
        sendToPlayer(player, colonyId);
    }

    /**
     * Send the building-area sync for a specific colony. When no colony can be
     * resolved (fresh world, player has no colony yet), an empty packet is sent so
     * the client cache is flushed and never carries stale entries from a previous
     * world/save — the overlap check on the client would otherwise report phantom
     * overlaps against the previous save's buildings.
     */
    public static void sendToPlayer(ServerPlayer player, @Nullable UUID colonyId) {
        if (colonyId == null) {
            PacketDistributor.sendToPlayer(player, new BuildingAreaSyncPacket(List.of()));
            return;
        }
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

    /** Result of {@link #raycastUnbuilt}: an unbuilt building targeted by a crosshair ray. */
    public record BuildingBoxHit(UUID buildingId, BlockPos pos, double distSq) {}
}
