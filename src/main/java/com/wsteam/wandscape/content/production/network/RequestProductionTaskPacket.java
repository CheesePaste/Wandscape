package com.wsteam.wandscape.content.production.network;

import com.google.gson.JsonPrimitive;
import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.content.building.internal.BuildingSavedData;
import com.wsteam.wandscape.content.building.internal.BuildingState;
import com.wsteam.wandscape.content.production.ProductionRecipeLoader;
import com.wsteam.wandscape.content.production.data.CraftRecipeView;
import com.wsteam.wandscape.content.production.internal.RecipeUnlockChecker;
import com.wsteam.wandscape.content.warehouse.ColonyItemBank;
import com.wsteam.wandscape.api.BuildingApi;
import com.wsteam.wandscape.content.building.data.WorkItem;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.api.WandscapeApis;
import com.wsteam.wandscape.foundation.registry.WandscapeConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static com.wsteam.wandscape.Wandscape.MODID;

/**
 * Client→server packet requesting creation of a production task
 * (decompose / synthesize / craft / craft_spell).
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
                case "craft" -> "production:craft";
                case "craft_spell" -> "production:craft_spell";
                default -> {
                    Log.warn(TAG, "RequestProductionTask: unknown action {}", pkt.action);
                    yield null;
                }
            };

            if (blueprintId == null) return;

            // Server-side unlock check: guard against client tampering
            UUID colonyId = state.getColonyId();
            if (colonyId == null) colonyId = new UUID(0, 0);
            var loader = Wandscape.PRODUCTION_RECIPE_LOADER;
            if (!"decompose".equals(pkt.action)) {
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
                    case "craft", "craft_spell" -> {
                        var recipe = "craft_spell".equals(pkt.action)
                                ? CraftRecipeView.resolveSpell(loader, pkt.recipeOrItemId)
                                : CraftRecipeView.resolve(loader, pkt.recipeOrItemId);
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
            // 输出物品 id（已注册物品）→ 队列面板图标用；decompose 的图标就是 item_id 本身。
            String outputItem = resolveOutputItem(pkt.action, pkt.recipeOrItemId, loader);
            if (outputItem != null) {
                params.put("output_item", new JsonPrimitive(outputItem));
            }
            params.put("count", new JsonPrimitive(pkt.quantity));
            // Channel duration scales with quantity: workstation 5 ticks/item (<=2 value is instant/0),
            // crafting station 1200 ticks/item (per unit).
            int channelTicks = switch (pkt.action) {
                case "synthesize" ->
                        loader != null
                                ? loader.computeSynthesizeChannelTicks(pkt.recipeOrItemId, pkt.quantity)
                                : WandscapeConstants.WORKSTATION_CRAFT_TICKS_PER_UNIT * pkt.quantity;
                case "decompose" ->
                        WandscapeConstants.WORKSTATION_CRAFT_TICKS_PER_UNIT * pkt.quantity;
                case "craft", "craft_spell" ->
                        WandscapeConstants.CRAFTING_STATION_CRAFT_TICKS_PER_UNIT * pkt.quantity;
                default -> 120; // brew_potion, unchanged
            };
            params.put("channel_ticks", new JsonPrimitive(channelTicks));

            // 玩家手动发布的生产任务进最高优先级段，排在补货/自动合成之前。
            WorkItem work = new WorkItem(blueprintId, params, WandscapeConstants.TASK_PRIORITY_PLAYER);

            BuildingApi api = WandscapeApis.getBuildingApi();
            api.enqueueWork(buildingId, work);

            // A player-published synthesize request counts toward onboarding step 5.
            if ("synthesize".equals(pkt.action) && state.getColonyId() != null) {
                var bank = ColonyItemBank.get(level);
                if (bank != null) bank.recordPlayerSynthesize(state.getColonyId());
                var guideApi = com.wsteam.wandscape.api.WandscapeApis.getGuideProgressApiSilently();
                if (guideApi != null) guideApi.sendToPlayer(sp, state.getColonyId());
            }

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

    /** Resolve the registered output item id for a recipe-based production task (queue icon). */
    @Nullable
    private static String resolveOutputItem(String action, String recipeOrItemId,
                                            @Nullable ProductionRecipeLoader loader) {
        if (loader == null) return null;
        return switch (action) {
            case "synthesize" -> {
                var recipe = loader.getSynthesizeRecipe(recipeOrItemId);
                yield recipe != null ? recipe.outputItem() : null;
            }
            case "craft", "craft_spell" -> {
                var recipe = "craft_spell".equals(action)
                        ? CraftRecipeView.resolveSpell(loader, recipeOrItemId)
                        : CraftRecipeView.resolve(loader, recipeOrItemId);
                yield recipe != null ? recipe.outputItem() : null;
            }
            default -> null;
        };
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
