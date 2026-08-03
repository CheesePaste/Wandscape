package com.wsteam.wandscape.road.network;

import java.util.HashMap;
import java.util.Map;

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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import static com.wsteam.wandscape.Wandscape.MODID;

/**
 * Client→Server: Player submits a pure 3D box fill task.
 *
 * <p>The player right-clicked one corner block and left-clicked the opposite
 * corner. The server fills the entire cube (all X/Y/Z positions between the
 * two corners) with the selected preset's block, then pushes a
 * {@link TaskRequest} with blueprint {@code terrain:fill_box}.
 */
public record FillBoxPacket(String presetId, BlockPos startPos, BlockPos endPos) implements CustomPacketPayload {

    private static final String TAG = "FillBoxPacket";

    public static final Type<FillBoxPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "fill_box"));

    public static final StreamCodec<RegistryFriendlyByteBuf, FillBoxPacket> STREAM_CODEC =
            StreamCodec.of(FillBoxPacket::write, FillBoxPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    // ── Server handler ──

    public static void handleServer(FillBoxPacket packet, ServerPlayer player) {
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

        // 2. Compute cube bounds, clamped to the world build range
        int minX = Math.min(start.getX(), end.getX());
        int maxX = Math.max(start.getX(), end.getX());
        int minY = Math.max(Math.min(start.getY(), end.getY()), level.getMinBuildHeight());
        int maxY = Math.min(Math.max(start.getY(), end.getY()), level.getMaxBuildHeight() - 1);
        int minZ = Math.min(start.getZ(), end.getZ());
        int maxZ = Math.max(start.getZ(), end.getZ());

        // 3. Cap at 10 000 blocks to prevent accidental server lag
        int volume = (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        if (volume > 10_000) {
            Log.warn(TAG, "Box {}×{}×{} = {} blocks exceeds max (10000), rejecting",
                    maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1, volume);
            return;
        }

        // 4. Build tiles: every position in the cube gets the preset's block (skip bedrock)
        JsonArray tiles = new JsonArray();
        java.util.Map<String, Integer> materials = new java.util.LinkedHashMap<>();
        var elementApi = com.wsteam.wandscape.shared.registry.WandscapeApis.getElementApi();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = minY; y <= maxY; y++) {
                    cursor.set(x, y, z);
                    if (level.getBlockState(cursor).is(Blocks.BEDROCK)) continue;

                    JsonObject tile = new JsonObject();
                    JsonArray posArr = new JsonArray();
                    posArr.add(x);
                    posArr.add(y);
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
        }

        if (tiles.isEmpty()) return;

        // 5. Push task via PlayerManualSource
        PlayerManualSource source = WandscapeEngine.getPlayerManualSource();
        if (source == null) {
            Log.warn(TAG, "PlayerManualSource not available — cannot publish fill task");
            return;
        }

        Map<String, JsonElement> params = new HashMap<>();
        params.put("tiles", tiles);

        JsonArray list = new JsonArray();
        JsonObject counts = new JsonObject();
        for (var entry : materials.entrySet()) {
            list.add(new JsonPrimitive(entry.getKey()));
            counts.addProperty(entry.getKey(), String.valueOf(entry.getValue()));
        }
        params.put("material_list", list);
        params.put("material_counts", counts);

        try {
            long taskId = source.publish(new TaskRequest("terrain:fill_box", params, 10));
            SoundService.playAt(player.serverLevel(), player.getX(), player.getY(), player.getZ(),
                    WandscapeSounds.TASK_PUBLISH, SoundSource.PLAYERS, 0.4f, 1.0f);
            Log.info(TAG, "[Fill] Published task #{}: preset={} box=({},{},{})→({},{},{}) tiles={}",
                    taskId, packet.presetId(), minX, minY, minZ, maxX, maxY, maxZ, tiles.size());
        } catch (Exception e) {
            Log.warn(TAG, "[Fill] Failed to publish fill task: {}", e.getMessage());
        }
    }

    // ── Helper ──

    private static RoadPreset findPreset(String id) {
        for (RoadPreset p : RoadPreset.DEFAULT_PRESETS) {
            if (p.id().equals(id)) return p;
        }
        return null;
    }

    // ── StreamCodec ──

    static void write(RegistryFriendlyByteBuf buf, FillBoxPacket pkt) {
        buf.writeUtf(pkt.presetId());
        buf.writeBlockPos(pkt.startPos());
        buf.writeBlockPos(pkt.endPos());
    }

    static FillBoxPacket read(RegistryFriendlyByteBuf buf) {
        String presetId = buf.readUtf();
        BlockPos startPos = buf.readBlockPos();
        BlockPos endPos = buf.readBlockPos();
        return new FillBoxPacket(presetId, startPos, endPos);
    }
}
