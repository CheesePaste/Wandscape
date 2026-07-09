package com.wsteam.wandscape.road.network;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.List;
import java.util.ArrayList;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.wsteam.wandscape.engine.WandscapeEngine;
import com.wsteam.wandscape.shared.log.Log;
import com.wsteam.wandscape.task.engine.pool.TaskRequest;
import com.wsteam.wandscape.task.source.PlayerManualSource;
import com.wsteam.wandscape.road.core.PathPoint;
import com.wsteam.wandscape.road.core.RoadEdge;
import com.wsteam.wandscape.road.core.RoadNode;
import com.wsteam.wandscape.road.core.RoadNetwork;
import com.wsteam.wandscape.road.core.XZPoint;
import com.wsteam.wandscape.road.engine.RoadSavedData;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import static com.wsteam.wandscape.Wandscape.MODID;

public record SplineBuildPacket(String tilesJson, String centerlineJson) implements CustomPacketPayload {
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

            JsonElement centerParsed = JsonParser.parseString(packet.centerlineJson());
            List<PathPoint> path = new ArrayList<>();
            if (centerParsed.isJsonArray()) {
                JsonArray centerArr = centerParsed.getAsJsonArray();
                for (JsonElement e : centerArr) {
                    JsonArray pt = e.getAsJsonArray();
                    path.add(new PathPoint(pt.get(0).getAsInt(), pt.get(1).getAsInt(), pt.get(2).getAsInt()));
                }
            }

            if (path.isEmpty()) {
                Log.warn(TAG, "[Spline] Centerline is empty, cannot create RoadEdge");
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

            PathPoint startPt = path.get(0);
            PathPoint endPt = path.get(path.size() - 1);

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
            RoadEdge edge = new RoadEdge(edgeId, fromNodeId, toNodeId, "dirt", path);
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
            var elementApi = com.wsteam.wandscape.shared.registry.WandscapeApis.getElementApi();
            
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

            long taskId = source.publish(new TaskRequest("road:build_segment", params, 10));
            edge.incrementPendingSegments(1);
            savedData.setDirty();

            Log.info(TAG, "[Spline] Published task #{} for RoadEdge {}: tiles={}, path={}", taskId, edgeId, tiles.size(), path.size());
        } catch (Exception e) {
            Log.warn(TAG, "[Spline] Failed to publish spline road task: {}", e.getMessage());
            e.printStackTrace();
        }
    }

    static void write(RegistryFriendlyByteBuf buf, SplineBuildPacket pkt) {
        // String limit allows very large payloads (up to 4MB string)
        buf.writeUtf(pkt.tilesJson(), 4 * 1024 * 1024);
        buf.writeUtf(pkt.centerlineJson(), 1024 * 1024);
    }

    static SplineBuildPacket read(RegistryFriendlyByteBuf buf) {
        return new SplineBuildPacket(buf.readUtf(4 * 1024 * 1024), buf.readUtf(1024 * 1024));
    }
}
