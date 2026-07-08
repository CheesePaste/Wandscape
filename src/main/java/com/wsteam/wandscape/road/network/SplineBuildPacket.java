package com.wsteam.wandscape.road.network;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.wsteam.wandscape.engine.WandscapeEngine;
import com.wsteam.wandscape.shared.log.Log;
import com.wsteam.wandscape.task.engine.pool.TaskRequest;
import com.wsteam.wandscape.task.source.PlayerManualSource;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import static com.wsteam.wandscape.Wandscape.MODID;

public record SplineBuildPacket(String tilesJson) implements CustomPacketPayload {
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

            PlayerManualSource source = WandscapeEngine.getPlayerManualSource();
            if (source == null) {
                Log.warn(TAG, "PlayerManualSource not available — cannot publish spline road task");
                return;
            }

            Map<String, JsonElement> params = new HashMap<>();
            params.put("tiles", tiles);
            params.put("segment_id", new JsonPrimitive(UUID.randomUUID().toString()));
            params.put("edge_id", new JsonPrimitive(UUID.randomUUID().toString()));

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
            Log.info(TAG, "[Spline] Published task #{}: tiles={}", taskId, tiles.size());
        } catch (Exception e) {
            Log.warn(TAG, "[Spline] Failed to publish spline road task: {}", e.getMessage());
        }
    }

    static void write(RegistryFriendlyByteBuf buf, SplineBuildPacket pkt) {
        // String limit allows very large payloads (up to 4MB string)
        buf.writeUtf(pkt.tilesJson(), 4 * 1024 * 1024);
    }

    static SplineBuildPacket read(RegistryFriendlyByteBuf buf) {
        return new SplineBuildPacket(buf.readUtf(4 * 1024 * 1024));
    }
}
