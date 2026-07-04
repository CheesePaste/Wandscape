package com.wsteam.wandscape.production.network;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.google.gson.JsonPrimitive;
import com.wsteam.wandscape.building.internal.BuildingSavedData;
import com.wsteam.wandscape.building.internal.BuildingState;
import com.wsteam.wandscape.production.internal.RecipeUnlockChecker;
import com.wsteam.wandscape.shared.api.BuildingApi;
import com.wsteam.wandscape.shared.data.WorkItem;
import com.wsteam.wandscape.shared.registry.WandscapeApis;
import com.wsteam.wandscape.Wandscape;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import static com.wsteam.wandscape.Wandscape.MODID;
import com.wsteam.wandscape.shared.log.Log;

/**
 * Client→server packet requesting creation of a production task
 * (decompose / synthesize / craft_wand / brew_potion).
 */
public record RequestProductionTaskPacket(
    BlockPos stationPos,
    String action,
    String recipeOrItemId,
    int quantity
) implements CustomPacketPayload {

    private static final String TAG = "RequestProductionTaskPacket";

    public static final Type<RequestProductionTaskPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "request_production_task"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RequestProductionTaskPacket> STREAM_CODEC =
            StreamCodec.of(RequestProductionTaskPacket::write, RequestProductionTaskPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Server-side handler. */
    public static void handleServer(RequestProductionTaskPacket pkt, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer sp)) return;

        sp.getServer().execute(() -> {
            var level = sp.serverLevel();
            BuildingSavedData data = BuildingSavedData.get(level);
            UUID buildingId = data.getBuildingIdAt(pkt.stationPos);
            if (buildingId == null) {
                Log.warn(TAG, "RequestProductionTask: no building at {}", pkt.stationPos);
                return;
            }

            BuildingState state = data.getBuilding(buildingId);
            if (state == null) {
                Log.warn(TAG, "RequestProductionTask: building state null for {}", buildingId);
                return;
            }

            // Determine blueprint
            String blueprintId = switch (pkt.action) {
                case "decompose" -> "production:decompose";
                case "synthesize" -> "production:synthesize";
                case "craft_wand" -> "production:craft_wand";
                case "brew_potion" -> "production:brew_potion";
                default -> {
                    Log.warn(TAG, "RequestProductionTask: unknown action {}", pkt.action);
                    yield null;
                }
            };

            if (blueprintId == null) return;

            // Server-side unlock check: guard against client tampering
            UUID colonyId = state.getColonyId();
            if (colonyId == null) colonyId = new UUID(0, 0);
            if (!"decompose".equals(pkt.action)) {
                var loader = Wandscape.PRODUCTION_RECIPE_LOADER;
                if (loader == null) {
                    Log.warn(TAG, "RequestProductionTask: PRODUCTION_RECIPE_LOADER not available");
                    return;
                }
                boolean unlocked = switch (pkt.action) {
                    case "synthesize" -> {
                        var recipe = loader.getSynthesizeRecipe(pkt.recipeOrItemId);
                        yield recipe != null
                                && RecipeUnlockChecker.isUnlocked(colonyId, recipe.unlockRequirement());
                    }
                    case "craft_wand" -> {
                        var recipe = loader.getCraftWandRecipes().get(pkt.recipeOrItemId);
                        yield recipe != null
                                && RecipeUnlockChecker.isUnlocked(colonyId, recipe.unlockRequirement());
                    }
                    case "brew_potion" -> {
                        var recipe = loader.getPotionRecipes().get(pkt.recipeOrItemId);
                        yield recipe != null
                                && RecipeUnlockChecker.isUnlocked(colonyId, recipe.unlockRequirement());
                    }
                    default -> true;
                };
                if (!unlocked) {
                    Log.warn(TAG, "RequestProductionTask: recipe '{}' action={} is not unlocked for colony={} — rejected",
                            pkt.recipeOrItemId, pkt.action,
                            colonyId.toString().substring(0, 8));
                    return;
                }
            }

            // Build WorkItem params
            Map<String, com.google.gson.JsonElement> params = new LinkedHashMap<>();
            params.put("anchor", posToJsonArray(pkt.stationPos));
            if ("decompose".equals(pkt.action)) {
                params.put("item_id", new JsonPrimitive(pkt.recipeOrItemId));
            } else {
                params.put("recipe_id", new JsonPrimitive(pkt.recipeOrItemId));
            }
            params.put("count", new JsonPrimitive(pkt.quantity));
            params.put("channel_ticks", new JsonPrimitive(1200)); // 60s
            params.put("mana_cost", new JsonPrimitive(5));

            WorkItem work = new WorkItem(blueprintId, params, 10);

            BuildingApi api = WandscapeApis.getBuildingApi();
            api.enqueueWork(buildingId, work);

            int queueSize = state.getTaskQueue().size();
            Log.info(TAG, "[ProdTask] enqueued action={} recipe={} x{} at building {} "
                            + "(colony={} blueprint={} queue_size_after={})",
                    pkt.action, pkt.recipeOrItemId, pkt.quantity,
                    buildingId.toString().substring(0, 8),
                    state.getColonyId() != null
                            ? state.getColonyId().toString().substring(0, 8) : "null",
                    blueprintId, queueSize);
        });
    }

    private static com.google.gson.JsonArray posToJsonArray(BlockPos pos) {
        com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
        arr.add(pos.getX());
        arr.add(pos.getY());
        arr.add(pos.getZ());
        return arr;
    }

    static void write(RegistryFriendlyByteBuf buf, RequestProductionTaskPacket pkt) {
        buf.writeBlockPos(pkt.stationPos);
        buf.writeUtf(pkt.action);
        buf.writeUtf(pkt.recipeOrItemId);
        buf.writeVarInt(pkt.quantity);
    }

    static RequestProductionTaskPacket read(RegistryFriendlyByteBuf buf) {
        return new RequestProductionTaskPacket(
                buf.readBlockPos(),
                buf.readUtf(),
                buf.readUtf(),
                buf.readVarInt()
        );
    }
}
