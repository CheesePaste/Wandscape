package com.wsteam.wandscape.road.network;

import com.google.gson.*;
import com.wsteam.wandscape.engine.WandscapeEngine;
import com.wsteam.wandscape.engine.service.SoundService;
import com.wsteam.wandscape.engine.sound.WandscapeSounds;
import com.wsteam.wandscape.road.core.PathPoint;
import com.wsteam.wandscape.road.core.RoadEdge;
import com.wsteam.wandscape.road.core.RoadNetwork;
import com.wsteam.wandscape.road.core.RoadNode;
import com.wsteam.wandscape.road.engine.RoadSavedData;
import com.wsteam.wandscape.shared.log.Log;
import com.wsteam.wandscape.shared.network.ScreenFeedbackPacket;
import com.wsteam.wandscape.shared.ui.I18n;
import com.wsteam.wandscape.content.task.engine.pool.TaskRequest;
import com.wsteam.wandscape.content.task.source.PlayerManualSource;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;

import java.util.*;

import static com.wsteam.wandscape.Wandscape.MODID;

public record SplineBuildPacket(String tilesJson, String splineJson) implements CustomPacketPayload {
    private static final String TAG = "SplineBuildPacket";

    public static final Type<SplineBuildPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "spline_build"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SplineBuildPacket> STREAM_CODEC =
            StreamCodec.of(SplineBuildPacket::write, SplineBuildPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handleServer(SplineBuildPacket packet, ServerPlayer player) {
        if (player == null || player.level() == null) return;
        
        try {
            JsonElement parsed = JsonParser.parseString(packet.tilesJson());
            if (!parsed.isJsonArray()) return;
            JsonArray tiles = parsed.getAsJsonArray();
            if (tiles.isEmpty()) return;

            // Disabled blocks must not be placed as free material — refuse before any
            // RoadEdge/network side effects below.
            var elementApi = com.wsteam.wandscape.shared.registry.WandscapeApis.getElementApi();
            for (JsonElement tileEl : tiles) {
                JsonObject tileObj = tileEl.getAsJsonObject();
                if (tileObj.has("block")) {
                    String pureId = tileObj.get("block").getAsString().replaceAll("\\[.*?\\]", "").trim();
                    if (elementApi.isDisabled(pureId)) {
                        Log.warn(TAG, "[Spline] Refuse — block {} disabled by element mapping", pureId);
                        ScreenFeedbackPacket.send(player, I18n.name("message.wandscape.spline.disabled_block",
                                "§cSpline road contains a disabled block: %s", pureId), true);
                        return;
                    }
                }
            }

            JsonElement splineParsed = JsonParser.parseString(packet.splineJson());
            com.wsteam.wandscape.road.core.SplineModel model = new com.wsteam.wandscape.road.core.SplineModel();
            
            if (splineParsed.isJsonArray()) {
                JsonArray splineArr = splineParsed.getAsJsonArray();
                for (JsonElement e : splineArr) {
                    JsonObject obj = e.getAsJsonObject();
                    JsonArray aArr = obj.getAsJsonArray("a");
                    JsonArray pArr = obj.getAsJsonArray("p");
                    JsonArray nArr = obj.getAsJsonArray("n");
                    boolean locked = obj.has("l") && obj.get("l").getAsBoolean();
                    
                    com.wsteam.wandscape.road.core.SplineVec3 a = new com.wsteam.wandscape.road.core.SplineVec3(aArr.get(0).getAsDouble(), aArr.get(1).getAsDouble(), aArr.get(2).getAsDouble());
                    com.wsteam.wandscape.road.core.SplineVec3 p = new com.wsteam.wandscape.road.core.SplineVec3(pArr.get(0).getAsDouble(), pArr.get(1).getAsDouble(), pArr.get(2).getAsDouble());
                    com.wsteam.wandscape.road.core.SplineVec3 n = new com.wsteam.wandscape.road.core.SplineVec3(nArr.get(0).getAsDouble(), nArr.get(1).getAsDouble(), nArr.get(2).getAsDouble());
                    
                    model.getPoints().add(new com.wsteam.wandscape.road.core.SplinePoint(a, p, n, locked));
                }
            }

            if (model.getPoints().isEmpty()) {
                Log.warn(TAG, "[Spline] Spline data is empty, cannot create RoadEdge");
                return;
            }

            PlayerManualSource source = WandscapeEngine.getPlayerManualSource();
            if (source == null) {
                Log.warn(TAG, "PlayerManualSource not available — cannot publish spline road task");
                return;
            }

            // --- Construct RoadEdge and insert into RoadNetwork ---
            RoadSavedData savedData = RoadSavedData.getOrCreate(player.serverLevel());
            RoadNetwork network = savedData.getNetwork();

            com.wsteam.wandscape.road.core.SplineVec3 sPos = model.evaluate(0.0).position();
            PathPoint startPt = new PathPoint((int) Math.floor(sPos.x()), (int) Math.floor(sPos.y()), (int) Math.floor(sPos.z()));
            com.wsteam.wandscape.road.core.SplineVec3 ePos = model.evaluate(model.getSegmentsCount()).position();
            PathPoint endPt = new PathPoint((int) Math.floor(ePos.x()), (int) Math.floor(ePos.y()), (int) Math.floor(ePos.z()));

            UUID fromNodeId = null;
            UUID toNodeId = null;

            // Attempt to snap to existing node or walkable path point near endpoints
            RoadNode startNode = network.findNearestNode(startPt.xz());
            if (startNode != null && startNode.xz().manhattanTo(startPt.xz()) <= 3) {
                fromNodeId = startNode.nodeId();
            } else {
                fromNodeId = UUID.randomUUID();
                network.addNode(new RoadNode(fromNodeId, new com.wsteam.wandscape.core.types.GridPos(startPt.x(), startPt.y(), startPt.z()), RoadNode.NodeType.ORPHAN));
            }

            RoadNode endNode = network.findNearestNode(endPt.xz());
            if (endNode != null && endNode.xz().manhattanTo(endPt.xz()) <= 3) {
                toNodeId = endNode.nodeId();
            } else {
                toNodeId = UUID.randomUUID();
                network.addNode(new RoadNode(toNodeId, new com.wsteam.wandscape.core.types.GridPos(endPt.x(), endPt.y(), endPt.z()), RoadNode.NodeType.ORPHAN));
            }

            UUID edgeId = UUID.randomUUID();
            RoadEdge edge = new RoadEdge(edgeId, fromNodeId, toNodeId, "dirt", model);
            edge.setStatus(RoadEdge.EdgeStatus.BUILDING);
            
            // Register placed blocks footprint
            List<PathPoint> placedList = new ArrayList<>();
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
            // --------------------------------------------------------

            Map<String, JsonElement> params = new HashMap<>();
            params.put("tiles", tiles);
            params.put("segment_id", new JsonPrimitive(edgeId.toString()));
            params.put("edge_id", new JsonPrimitive(edgeId.toString()));

            java.util.Map<String, Integer> materials = new java.util.LinkedHashMap<>();
            
            for (JsonElement tileEl : tiles) {
                JsonObject tileObj = tileEl.getAsJsonObject();
                if (tileObj.has("block")) {
                    String blockId = tileObj.get("block").getAsString();
                    if (!"minecraft:air".equals(blockId)) {
                        String pureId = blockId.replaceAll("\\[.*?\\]", "").trim();
                        if (elementApi.hasElementMapping(pureId)) {
                            materials.merge(blockId, 1, Integer::sum);
                        }
                    }
                }
            }
            JsonArray list = new JsonArray();
            JsonObject counts = new JsonObject();
            for (var entry : materials.entrySet()) {
                list.add(new JsonPrimitive(entry.getKey()));
                counts.addProperty(entry.getKey(), String.valueOf(entry.getValue()));
            }
            params.put("material_list", list);
            params.put("material_counts", counts);

            long taskId = source.publish(new TaskRequest("road:build_segment", params, 10,
                    com.wsteam.wandscape.shared.registry.WandscapeApis.colonyAt(player.blockPosition())));
            // Capture demand + live task id on the edge so withdraw can cancel & refund.
            edge.setMaterialCounts(materials);
            edge.addSegmentTaskId(taskId);
            SoundService.playAt(player.serverLevel(), player.getX(), player.getY(), player.getZ(),
                    WandscapeSounds.TASK_PUBLISH, SoundSource.PLAYERS, 0.4f, 1.0f);
            edge.incrementPendingSegments(1);
            savedData.setDirty();

            Log.info(TAG, "[Spline] Published task #{} for RoadEdge {}: tiles={}, spline nodes={}", taskId, edgeId, tiles.size(), model.getPoints().size());
        } catch (Exception e) {
            Log.warn(TAG, "[Spline] Failed to publish spline road task: {}", e.getMessage());
            e.printStackTrace();
        }
    }

    static void write(RegistryFriendlyByteBuf buf, SplineBuildPacket pkt) {
        buf.writeUtf(pkt.tilesJson(), 4 * 1024 * 1024);
        buf.writeUtf(pkt.splineJson(), 1024 * 1024);
    }

    static SplineBuildPacket read(RegistryFriendlyByteBuf buf) {
        return new SplineBuildPacket(buf.readUtf(4 * 1024 * 1024), buf.readUtf(1024 * 1024));
    }
}
