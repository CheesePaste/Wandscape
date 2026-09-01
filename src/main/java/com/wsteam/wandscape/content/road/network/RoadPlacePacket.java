package com.wsteam.wandscape.content.road.network;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.wsteam.wandscape.content.road.core.*;
import com.wsteam.wandscape.engine.WandscapeEngine;
import com.wsteam.wandscape.engine.service.SoundService;
import com.wsteam.wandscape.engine.sound.WandscapeSounds;
import com.wsteam.wandscape.content.road.core.RoadNetwork;
import com.wsteam.wandscape.content.road.data.RoadPreset;
import com.wsteam.wandscape.content.road.engine.RoadPlaceAttribution;
import com.wsteam.wandscape.content.road.engine.RoadSavedData;
import com.wsteam.wandscape.shared.log.Log;
import com.wsteam.wandscape.shared.network.ScreenFeedbackPacket;
import com.wsteam.wandscape.shared.ui.I18n;
import com.wsteam.wandscape.content.task.engine.pool.TaskRequest;
import com.wsteam.wandscape.content.task.source.PlayerManualSource;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static com.wsteam.wandscape.Wandscape.MODID;

/**
 * Client→Server: Player submits a road placement task.
 *
 * <p>Server handler:
 * <ol>
 *   <li>Finds the preset by ID</li>
 *   <li>Computes the rectangle between start and end (min → max on XZ plane)</li>
 *   <li>Places tiles at the start point's height (Y) using the preset's {@link RoadPreset#pickBlock}</li>
 *   <li>Pushes a {@link TaskRequest} with blueprint {@code road:build_segment}</li>
 * </ol>
 */
public record RoadPlacePacket(String presetId, BlockPos startPos, BlockPos endPos) implements CustomPacketPayload {

    private static final String TAG = "RoadPlacePacket";

    public static final Type<RoadPlacePacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "road_place"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RoadPlacePacket> STREAM_CODEC =
            StreamCodec.of(RoadPlacePacket::write, RoadPlacePacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    // ── Server handler ──

    public static void handleServer(RoadPlacePacket packet, ServerPlayer player) {
        if (player == null || player.level() == null) return;
        Level level = player.level();

        // 1. Find preset
        RoadPreset preset = findPreset(packet.presetId());
        if (preset == null) {
            Log.warn(TAG, "Unknown road preset: {} from player {}", packet.presetId(), player.getName().getString());
            return;
        }

        BlockPos start = packet.startPos();
        BlockPos end = packet.endPos();
        if (start == null || end == null) return;
        // 2. Compute rectangle bounds between start and end
        int minX = Math.min(start.getX(), end.getX());
        int maxX = Math.max(start.getX(), end.getX());
        int minZ = Math.min(start.getZ(), end.getZ());
        int maxZ = Math.max(start.getZ(), end.getZ());

        // 3. Build tiles: every block within the rectangle at start point's height Y
        //    Cap at 10 000 tiles to prevent accidental server lag
        JsonArray tiles = new JsonArray();
        int area = (maxX - minX + 1) * (maxZ - minZ + 1);
        if (area > 10_000) {
            Log.warn(TAG, "Rect {}×{} = {} tiles exceeds max (10000), rejecting",
                    maxX - minX + 1, maxZ - minZ + 1, area);
            return;
        }

        java.util.Map<String, Integer> materials = new java.util.LinkedHashMap<>();
        var elementApi = com.wsteam.wandscape.shared.registry.WandscapeApis.getElementApi();
        int targetY = start.getY();

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                JsonObject tile = new JsonObject();
                JsonArray posArr = new JsonArray();
                posArr.add(x);
                posArr.add(targetY);
                posArr.add(z);
                tile.add("pos", posArr);
                String blockId = preset.pickBlock(x, z);
                tile.addProperty("block", blockId);
                tiles.add(tile);
                
                if (blockId != null && !"minecraft:air".equals(blockId)) {
                    String pureId = blockId.replaceAll("\\[.*?\\]", "").trim();
                    if (elementApi.hasElementMapping(pureId)) {
                        materials.merge(blockId, 1, Integer::sum);
                    }
                }
            }
        }

        if (tiles.isEmpty()) return;

        // Disabled blocks must not be placed as free material — refuse without publishing.
        for (JsonElement tileEl : tiles) {
            JsonObject tileObj = tileEl.getAsJsonObject();
            if (tileObj.has("block")) {
                String pureId = tileObj.get("block").getAsString().replaceAll("\\[.*?\\]", "").trim();
                if (elementApi.isDisabled(pureId)) {
                    Log.warn(TAG, "[Road] Refuse — block {} disabled by element mapping", pureId);
                    ScreenFeedbackPacket.send(player, I18n.name("message.wandscape.road.disabled_block",
                            "§cRoad contains a disabled block: %s", pureId), true);
                    return;
                }
            }
        }

        // 4. Construct RoadEdge and insert into RoadNetwork
        RoadSavedData savedData = RoadSavedData.getOrCreate(player.serverLevel());
        RoadNetwork network = savedData.getNetwork();

        int startY = targetY;
        int endY = targetY;
        SplineModel model = new SplineModel();
        SplineVec3 pA = new SplineVec3(start.getX() + 0.5, startY + 0.5, start.getZ() + 0.5);
        SplineVec3 pB = new SplineVec3(end.getX() + 0.5, endY + 0.5, end.getZ() + 0.5);

        double distXZ = Math.sqrt(Math.pow(end.getX() - start.getX(), 2) + Math.pow(end.getZ() - start.getZ(), 2));
        if (distXZ > 16) {
            int intermediateSamples = Math.min(6, (int) (distXZ / 12));
            model.getPoints().add(new SplinePoint(pA, pA, pA, true));
            for (int i = 1; i <= intermediateSamples; i++) {
                double factor = (double) i / (intermediateSamples + 1);
                int ix = (int) Math.round(start.getX() + (end.getX() - start.getX()) * factor);
                int iz = (int) Math.round(start.getZ() + (end.getZ() - start.getZ()) * factor);
                int iy = targetY;
                SplineVec3 pMid = new SplineVec3(ix + 0.5, iy + 0.5, iz + 0.5);
                model.getPoints().add(new SplinePoint(pMid, pMid, pMid, true));
            }
            model.getPoints().add(new SplinePoint(pB, pB, pB, true));
        } else {
            model.getPoints().add(new SplinePoint(pA, pA, pA, true));
            model.getPoints().add(new SplinePoint(pB, pB, pB, true));
        }

        PathPoint startPt = new PathPoint(start.getX(), startY, start.getZ());
        PathPoint endPt = new PathPoint(end.getX(), endY, end.getZ());

        UUID fromNodeId;
        UUID toNodeId;
        var startNode = network.findNearestNode(startPt.xz());
        if (startNode != null && startNode.xz().manhattanTo(startPt.xz()) <= 3) {
            fromNodeId = startNode.nodeId();
        } else {
            fromNodeId = UUID.randomUUID();
            network.addNode(new RoadNode(fromNodeId, new com.wsteam.wandscape.core.types.GridPos(startPt.x(), startPt.y(), startPt.z()), RoadNode.NodeType.ORPHAN));
        }

        var endNode = network.findNearestNode(endPt.xz());
        if (endNode != null && endNode.xz().manhattanTo(endPt.xz()) <= 3) {
            toNodeId = endNode.nodeId();
        } else {
            toNodeId = UUID.randomUUID();
            network.addNode(new RoadNode(toNodeId, new com.wsteam.wandscape.core.types.GridPos(endPt.x(), endPt.y(), endPt.z()), RoadNode.NodeType.ORPHAN));
        }

        UUID edgeId = UUID.randomUUID();
        RoadEdge edge = new RoadEdge(edgeId, fromNodeId, toNodeId, packet.presetId(), model);
        edge.setStatus(RoadEdge.EdgeStatus.BUILDING);
        edge.setWidth(Math.max(1, Math.min(maxX - minX + 1, maxZ - minZ + 1)));

        java.util.List<PathPoint> placedList = new java.util.ArrayList<>();
        for (JsonElement tileEl : tiles) {
            JsonObject tileObj = tileEl.getAsJsonObject();
            if (tileObj.has("pos")) {
                JsonArray posArr = tileObj.getAsJsonArray("pos");
                placedList.add(new PathPoint(posArr.get(0).getAsInt(), posArr.get(1).getAsInt(), posArr.get(2).getAsInt()));
            }
        }
        edge.addPlacedBlocks(placedList);

        network.addEdge(edge);
        savedData.setDirty();

        // 5. Push task via PlayerManualSource
        PlayerManualSource source = WandscapeEngine.getPlayerManualSource();
        if (source == null) {
            Log.warn(TAG, "PlayerManualSource not available — cannot publish road task");
            return;
        }

        String segmentId = edgeId.toString();
        Map<String, JsonElement> params = new HashMap<>();
        params.put("tiles", tiles);
        params.put("segment_id", new JsonPrimitive(segmentId));
        params.put("edge_id", new JsonPrimitive(edgeId.toString()));
        
        JsonArray list = new JsonArray();
        JsonObject counts = new JsonObject();
        for (var entry : materials.entrySet()) {
            list.add(new JsonPrimitive(entry.getKey()));
            counts.addProperty(entry.getKey(), String.valueOf(entry.getValue()));
        }
        params.put("material_list", list);
        params.put("material_counts", counts);

        try {
            long taskId = source.publish(new TaskRequest("road:build_segment", params, 10,
                    com.wsteam.wandscape.shared.registry.WandscapeApis.colonyAt(player.blockPosition())));
            // Capture demand + live task id on the edge so withdraw can cancel & refund.
            edge.setMaterialCounts(materials);
            edge.addSegmentTaskId(taskId);
            SoundService.playAt(player.serverLevel(), player.getX(), player.getY(), player.getZ(),
                    WandscapeSounds.TASK_PUBLISH, SoundSource.PLAYERS, 0.4f, 1.0f);
            Log.info(TAG, "[Road] Published task #{}: preset={} from={} to={} tiles={}",
                    taskId, packet.presetId(), start.toShortString(), end.toShortString(), tiles.size());

            // Sync the new under-construction road edge to all clients.
            com.wsteam.wandscape.shared.network.RoadAreaSyncPacket.broadcastToServer(player.serverLevel().getServer());

            // Manual road placement counts toward onboarding step 6 — but only once
            // the road is actually built, so register a pending attribution that
            // RoadSegmentListener consumes on road_segment_complete.
            var colonyApi = com.wsteam.wandscape.shared.registry.WandscapeApis.getColonyApiSilently();
            UUID colonyId = colonyApi != null ? colonyApi.getColonyId(player.blockPosition()) : null;
            if (colonyId != null) {
                RoadPlaceAttribution.register(segmentId, player.getUUID(), colonyId);
            }
        } catch (Exception e) {
            Log.warn(TAG, "[Road] Failed to publish road task: {}", e.getMessage());
        }
    }

    // ── Helper ──

    private static RoadPreset findPreset(String id) {
        return RoadPreset.parseOrGet(id);
    }

    // ── StreamCodec ──

    static void write(RegistryFriendlyByteBuf buf, RoadPlacePacket pkt) {
        buf.writeUtf(pkt.presetId());
        buf.writeBlockPos(pkt.startPos());
        buf.writeBlockPos(pkt.endPos());
    }

    static RoadPlacePacket read(RegistryFriendlyByteBuf buf) {
        String presetId = buf.readUtf();
        BlockPos startPos = buf.readBlockPos();
        BlockPos endPos = buf.readBlockPos();
        return new RoadPlacePacket(presetId, startPos, endPos);
    }
}
