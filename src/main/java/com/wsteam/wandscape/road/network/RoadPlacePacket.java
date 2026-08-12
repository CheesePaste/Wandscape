package com.wsteam.wandscape.road.network;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.wsteam.wandscape.road.data.RoadPreset;
import com.wsteam.wandscape.engine.WandscapeEngine;
import com.wsteam.wandscape.engine.service.SoundService;
import com.wsteam.wandscape.engine.sound.WandscapeSounds;
import com.wsteam.wandscape.task.source.PlayerManualSource;
import com.wsteam.wandscape.task.engine.pool.TaskRequest;
import com.wsteam.wandscape.shared.log.Log;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;

import static com.wsteam.wandscape.Wandscape.MODID;

/**
 * Client→Server: Player submits a road placement task.
 *
 * <p>Server handler:
 * <ol>
 *   <li>Finds the preset by ID</li>
 *   <li>Computes the rectangle between start and end (min → max on XZ plane)</li>
 *   <li>For each position in the rectangle, gets the surface Y via {@link Heightmap.Types#WORLD_SURFACE}</li>
 *   <li>Builds a tiles array with block chosen by the preset's {@link RoadPreset#pickBlock}</li>
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

        // 3. Build tiles: every surface block within the rectangle
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

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1;

                JsonObject tile = new JsonObject();
                JsonArray posArr = new JsonArray();
                posArr.add(x);
                posArr.add(surfaceY);
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

        // 5. Push task via PlayerManualSource
        PlayerManualSource source = WandscapeEngine.getPlayerManualSource();
        if (source == null) {
            Log.warn(TAG, "PlayerManualSource not available — cannot publish road task");
            return;
        }

        Map<String, JsonElement> params = new HashMap<>();
        params.put("tiles", tiles);
        params.put("segment_id", new JsonPrimitive(UUID.randomUUID().toString()));
        params.put("edge_id", new JsonPrimitive(UUID.randomUUID().toString()));
        
        JsonArray list = new JsonArray();
        JsonObject counts = new JsonObject();
        for (var entry : materials.entrySet()) {
            list.add(new JsonPrimitive(entry.getKey()));
            counts.addProperty(entry.getKey(), String.valueOf(entry.getValue()));
        }
        params.put("material_list", list);
        params.put("material_counts", counts);

        try {
            long taskId = source.publish(new TaskRequest("road:build_segment", params, 10));
            SoundService.playAt(player.serverLevel(), player.getX(), player.getY(), player.getZ(),
                    WandscapeSounds.TASK_PUBLISH, SoundSource.PLAYERS, 0.4f, 1.0f);
            Log.info(TAG, "[Road] Published task #{}: preset={} from={} to={} tiles={}",
                    taskId, packet.presetId(), start.toShortString(), end.toShortString(), tiles.size());

            // Manual road placement counts toward onboarding step 6.
            var colonyApi = com.wsteam.wandscape.shared.registry.WandscapeApis.getColonyApiSilently();
            UUID colonyId = colonyApi != null ? colonyApi.getColonyId(player.blockPosition()) : null;
            if (colonyId != null) {
                var bank = com.wsteam.wandscape.warehouse.ColonyItemBank.get(player.serverLevel());
                if (bank != null) bank.recordPlayerRoadPlace(colonyId);
                var guideApi = com.wsteam.wandscape.shared.registry.WandscapeApis.getGuideProgressApiSilently();
                if (guideApi != null) guideApi.sendToPlayer(player, colonyId);
            }
        } catch (Exception e) {
            Log.warn(TAG, "[Road] Failed to publish road task: {}", e.getMessage());
        }
    }

    // ── Helper ──

    private static RoadPreset findPreset(String id) {
        return com.wsteam.wandscape.road.data.RoadPresetLoader.getInstance().get(id);
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
