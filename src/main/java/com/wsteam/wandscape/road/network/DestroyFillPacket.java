package com.wsteam.wandscape.road.network;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.wsteam.wandscape.engine.WandscapeEngine;
import com.wsteam.wandscape.engine.service.SoundService;
import com.wsteam.wandscape.engine.sound.WandscapeSounds;
import com.wsteam.wandscape.shared.log.Log;
import com.wsteam.wandscape.task.engine.pool.TaskRequest;
import com.wsteam.wandscape.task.source.PlayerManualSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.HashMap;
import java.util.Map;

import static com.wsteam.wandscape.Wandscape.MODID;

/**
 * Client→Server: Player submits a terrain destroy/fill task.
 *
 * <p>The player right-clicked a block to set the reference height + reference block,
 * then left-clicked to define the rectangle. The server:
 * <ol>
 *   <li>Determines reference height ({@link Heightmap.Types#MOTION_BLOCKING} at refPos) and reference block</li>
 *   <li>For each position in the rectangle, compares surface height to reference</li>
 *   <li>Higher → adds break tiles (remove blocks down to ref height)</li>
 *   <li>Lower → adds fill tiles (place ref block up to ref height)</li>
 *   <li>Pushes a {@link TaskRequest} with blueprint {@code terrain:flatten}</li>
 * </ol>
 */
public record DestroyFillPacket(BlockPos refPos, BlockPos endPos, boolean fillDepressions) implements CustomPacketPayload {

    private static final String TAG = "DestroyFillPacket";

    public static final Type<DestroyFillPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "destroy_fill"));

    public static final StreamCodec<RegistryFriendlyByteBuf, DestroyFillPacket> STREAM_CODEC =
            StreamCodec.of(DestroyFillPacket::write, DestroyFillPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    // ── Server handler ──

    public static void handleServer(DestroyFillPacket packet, ServerPlayer player) {
        if (player == null || player.level() == null) return;
        Level level = player.level();

        BlockPos ref = packet.refPos();
        BlockPos end = packet.endPos();
        if (ref == null || end == null) return;
        boolean fillDepressions = packet.fillDepressions();

        // 1. Determine reference Y and reference block from the ref position (strictly adhere to baseline plane)
        int refY = ref.getY();
        BlockState refState = level.getBlockState(ref);
        String refBlockId = BuiltInRegistries.BLOCK.getKey(refState.getBlock()).toString();
        // Fallback: If ref block is dirt_path or air/unobtainable, check one block below or use natural dirt
        if ("minecraft:dirt_path".equals(refBlockId) || "minecraft:air".equals(refBlockId)) {
            BlockState belowState = level.getBlockState(ref.below());
            String belowId = BuiltInRegistries.BLOCK.getKey(belowState.getBlock()).toString();
            if (!belowState.isAir() && !"minecraft:dirt_path".equals(belowId)) {
                refBlockId = belowId;
            } else {
                refBlockId = "minecraft:dirt";
            }
        }

        // 2. Compute rectangle bounds
        int minX = Math.min(ref.getX(), end.getX());
        int maxX = Math.max(ref.getX(), end.getX());
        int minZ = Math.min(ref.getZ(), end.getZ());
        int maxZ = Math.max(ref.getZ(), end.getZ());

        // 3. Cap at 10000 columns to prevent lag
        int area = (maxX - minX + 1) * (maxZ - minZ + 1);
        if (area > 10_000) {
            Log.warn(TAG, "Rect {}×{} = {} columns exceeds max (10000), rejecting",
                    maxX - minX + 1, maxZ - minZ + 1, area);
            return;
        }

        // 4. Build break and fill tile arrays
        JsonArray tilesBreak = new JsonArray();
        JsonArray tilesFill = new JsonArray();
        int fillCount = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                int motionY = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1;

                // ── Find topmost non-air block (grass, water, etc. above MOTION_BLOCKING) ──
                int topY = motionY;
                cursor.set(x, motionY + 1, z);
                for (int y = motionY + 1; y < level.getMaxBuildHeight(); y++) {
                    cursor.setY(y);
                    BlockState bs = level.getBlockState(cursor);
                    if (bs.isAir()) break;
                    topY = y;
                }

                // Break: remove blocks from refY+1 up to topY (includes fluids, grass)
                if (topY > refY) {
                    for (int y = refY + 1; y <= topY; y++) {
                        JsonArray posArr = new JsonArray();
                        posArr.add(x);
                        posArr.add(y);
                        posArr.add(z);
                        tilesBreak.add(posArr);
                    }
                }

                // Fill: place refBlock from groundY+1 up to refY (ONLY if fillDepressions is enabled)
                if (fillDepressions) {
                    // ── Find true solid ground below fluids & replaceable blocks ──
                    int groundY = motionY;
                    cursor.set(x, motionY, z);
                    for (int y = motionY; y > level.getMinBuildHeight(); y--) {
                        cursor.setY(y);
                        BlockState bs = level.getBlockState(cursor);
                        if (bs.isAir()) continue;                       // skip air
                        if (!bs.getFluidState().isEmpty()) continue;    // skip water/lava
                        if (bs.canBeReplaced()) continue;               // skip tall grass etc.
                        groundY = y;
                        break;
                    }

                    if (groundY < refY) {
                        for (int y = groundY + 1; y <= refY; y++) {
                            JsonObject tile = new JsonObject();
                            JsonArray posArr = new JsonArray();
                            posArr.add(x);
                            posArr.add(y);
                            posArr.add(z);
                            tile.add("pos", posArr);
                            tile.addProperty("block", refBlockId);
                            tilesFill.add(tile);
                            fillCount++;
                        }
                    }
                }
            }
        }

        if (tilesBreak.isEmpty() && tilesFill.isEmpty()) {
            Log.info(TAG, "No work needed — terrain already flat at refY={}", refY);
            return;
        }

        // 5. Push task via PlayerManualSource
        PlayerManualSource source = WandscapeEngine.getPlayerManualSource();
        if (source == null) {
            Log.warn(TAG, "PlayerManualSource not available — cannot publish terrain task");
            return;
        }

        Map<String, JsonElement> params = new HashMap<>();
        params.put("tiles_break", tilesBreak);
        params.put("tiles_fill", tilesFill);
        params.put("fill_block", new JsonPrimitive(refBlockId));
        params.put("fill_count", new JsonPrimitive(fillCount));

        try {
            long taskId = source.publish(new TaskRequest("terrain:flatten", params, 10,
                    com.wsteam.wandscape.shared.registry.WandscapeApis.colonyAt(player.blockPosition())));
            SoundService.playAt(player.serverLevel(), player.getX(), player.getY(), player.getZ(),
                    WandscapeSounds.TASK_PUBLISH, SoundSource.PLAYERS, 0.4f, 1.0f);
            Log.info(TAG, "[DestroyFill] Published task #{}: ref={} refBlock={} from=({},{})→({},{}) breaks={} fills={} fillDep={}",
                    taskId, ref.toShortString(), refBlockId,
                    minX, minZ, maxX, maxZ,
                    tilesBreak.size(), tilesFill.size(), fillDepressions);
        } catch (Exception e) {
            Log.warn(TAG, "[DestroyFill] Failed to publish terrain task: {}", e.getMessage());
        }
    }

    // ── StreamCodec ──

    static void write(RegistryFriendlyByteBuf buf, DestroyFillPacket pkt) {
        buf.writeBlockPos(pkt.refPos());
        buf.writeBlockPos(pkt.endPos());
        buf.writeBoolean(pkt.fillDepressions());
    }

    static DestroyFillPacket read(RegistryFriendlyByteBuf buf) {
        BlockPos refPos = buf.readBlockPos();
        BlockPos endPos = buf.readBlockPos();
        boolean fillDepressions = buf.readableBytes() > 0 && buf.readBoolean();
        return new DestroyFillPacket(refPos, endPos, fillDepressions);
    }
}
