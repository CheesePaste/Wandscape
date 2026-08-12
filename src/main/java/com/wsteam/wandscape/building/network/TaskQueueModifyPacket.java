package com.wsteam.wandscape.building.network;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.wsteam.wandscape.building.internal.BuildingSavedData;
import com.wsteam.wandscape.building.internal.BuildingState;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.core.types.GridPos;
import com.wsteam.wandscape.engine.WandscapeEngine;
import com.wsteam.wandscape.shared.api.BuildingApi;
import com.wsteam.wandscape.shared.data.WorkItem;
import com.wsteam.wandscape.shared.registry.WandscapeApis;
import com.wsteam.wandscape.task.engine.pool.GlobalTask;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import static com.wsteam.wandscape.Wandscape.MODID;
import com.wsteam.wandscape.shared.log.Log;

/**
 * Client→server packet to modify a building's task queue:
 * refresh / delete / move_up / move_down.
 */
public record TaskQueueModifyPacket(
    BlockPos stationPos,
    String action,
    int index
) implements CustomPacketPayload {

    private static final String TAG = "TaskQueueModifyPacket";

    public static final Type<TaskQueueModifyPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "task_queue_modify"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TaskQueueModifyPacket> STREAM_CODEC =
            StreamCodec.of(TaskQueueModifyPacket::write, TaskQueueModifyPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Server-side handler. */
    public static void handleServer(TaskQueueModifyPacket pkt, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer sp)) return;

        sp.getServer().execute(() -> {
            Log.info(TAG, "TaskQueueModify: received {} action index={} pos={} from player {}",
                    pkt.action, pkt.index, pkt.stationPos, sp.getName().getString());

            BuildingSavedData data = BuildingSavedData.get(sp.serverLevel());
            if (data == null) {
                Log.warn(TAG, "TaskQueueModify: no BuildingSavedData — action={} index={} pos={}",
                        pkt.action, pkt.index, pkt.stationPos);
                return;
            }

            UUID buildingId = data.getBuildingIdAt(pkt.stationPos);
            if (buildingId == null) {
                Log.warn(TAG, "TaskQueueModify: no building at pos={} — action={} index={}",
                        pkt.stationPos, pkt.action, pkt.index);
                return;
            }
            Log.info(TAG, "TaskQueueModify: buildingId={} action={} index={}",
                    buildingId.toString().substring(0, 8), pkt.action, pkt.index);

            BuildingApi api = WandscapeApis.getBuildingApi();
            boolean changed = false;

            switch (pkt.action) {
                case "refresh" -> {
                    Log.info(TAG, "TaskQueueModify: refresh requested for building {}", buildingId.toString().substring(0, 8));
                    changed = true;
                }
                case "delete" -> {
                    boolean ok = api.removeFromQueue(buildingId, pkt.index);
                    changed = ok;
                }
                case "move_up" -> {
                    boolean ok = api.moveUp(buildingId, pkt.index);
                    changed = ok;
                }
                case "move_down" -> {
                    boolean ok = api.moveDown(buildingId, pkt.index);
                    changed = ok;
                }
                default -> Log.warn(TAG, "TaskQueueModify: unknown action '{}' index={} pos={}",
                        pkt.action, pkt.index, pkt.stationPos);
            }

            // Always send back queue data (refresh or after modification)
            List<WorkItem> queue = api.getQueue(buildingId);
            List<TaskQueueDataPacket.QueueEntry> entries = new ArrayList<>();
            for (int i = 0; i < queue.size(); i++) {
                WorkItem item = queue.get(i);
                // Extract structured fields from WorkItem params for client-side icon rendering
                String bid = item.blueprintId();
                Map<String, JsonElement> params = item.params();
                String category = categorize(bid);
                String itemOrRecipeId = extractItemId(bid, params);
                int quantity = paramInt(params, "count", 0);
                entries.add(new TaskQueueDataPacket.QueueEntry(
                        i, category, itemOrRecipeId, quantity, bid, summarizeWorkItem(bid, params)
                ));
            }

            // ── Current (executing) task — the building head, tracked separately from the queue ──
            TaskQueueDataPacket.CurrentTask current = buildCurrentTask(data.getBuilding(buildingId));

            TaskQueueDataPacket response = new TaskQueueDataPacket(pkt.stationPos, entries, current);
            PacketDistributor.sendToPlayer(sp, response);
        });
    }

    /** Format a human-readable summary for a blueprint + params (WorkItem or GlobalTask). */
    static String summarizeWorkItem(String bid, Map<String, JsonElement> params) {
        return switch (bid) {
            case "production:decompose" -> {
                String id = paramStr(params, "item_id");
                int count = paramInt(params, "count", 0);
                yield "Decompose " + id + " x" + count;
            }
            case "production:synthesize" -> {
                String id = paramStr(params, "recipe_id");
                int count = paramInt(params, "count", 0);
                yield "Synthesize " + id + " x" + count;
            }
            case "production:craft_wand" -> {
                String id = paramStr(params, "recipe_id");
                int count = paramInt(params, "count", 0);
                yield "Craft " + id + " x" + count;
            }
            case "production:brew_potion" -> {
                String id = paramStr(params, "recipe_id");
                int count = paramInt(params, "count", 0);
                yield "Brew " + id + " x" + count;
            }
            case String b when b.startsWith("build:") -> {
                String name = paramStr(params, "name");
                yield name != null ? "Build " + name : "Build (" + bid + ")";
            }
            case "node:gather" -> {
                String el = paramStr(params, "element");
                int amount = paramInt(params, "amount", 0);
                yield "Gather " + el + " x" + amount;
            }
            default -> bid;
        };
    }

    /** Short category key for client-side icon lookup. */
    static String categorize(String blueprintId) {
        if (blueprintId.equals("production:decompose")) return "decompose";
        if (blueprintId.equals("production:synthesize")) return "synthesize";
        if (blueprintId.equals("production:craft_wand")) return "craft";
        if (blueprintId.equals("production:brew_potion")) return "brew";
        if (blueprintId.startsWith("build:")) return "build";
        if (blueprintId.equals("node:gather")) return "gather";
        return "other";
    }

    /**
     * Extract the primary item/recipe resource id from WorkItem params.
     * Falls back to a best-effort guess from the blueprintId.
     */
    static String extractItemId(String blueprintId, Map<String, JsonElement> params) {
        if (blueprintId.equals("production:decompose")) {
            String id = paramStr(params, "item_id");
            if (id != null) return id;
        }
        if (blueprintId.equals("production:synthesize")
                || blueprintId.equals("production:craft_wand")
                || blueprintId.equals("production:brew_potion")) {
            String id = paramStr(params, "recipe_id");
            if (id != null) return id;
        }
        if (blueprintId.startsWith("build:")) {
            String name = paramStr(params, "name");
            if (name != null) return name;
        }
        if (blueprintId.equals("node:gather")) {
            String el = paramStr(params, "element");
            if (el != null) return el;
        }
        return "";
    }

    /**
     * Build the currently executing (head) task for a building, with progress.
     * Returns null when no head task is active or it can't be resolved.
     */
    @Nullable
    private static TaskQueueDataPacket.CurrentTask buildCurrentTask(@Nullable BuildingState state) {
        if (state == null) return null;
        UUID currentTaskUuid = state.getCurrentTaskId();
        if (currentTaskUuid == null) return null;

        World world = WandscapeEngine.getWorld();
        GlobalTask gt = world != null ? world.taskPool.get(currentTaskUuid.getMostSignificantBits()) : null;
        if (gt == null || gt.blueprintId == null) return null;

        String bid = gt.blueprintId;
        Map<String, JsonElement> params = gt.taskParams;
        int totalSteps = Math.max(1, gt.sequence.size());
        int stepIndex = Math.max(0, Math.min(gt.stepIndex, totalSteps));

        int channelTotal = paramInt(params, "channel_ticks", 0);
        int channelRemaining = channelTotal;
        boolean channelActive = false;
        if (channelTotal > 0) {
            GridPos anchor = anchorOf(params, state);
            if (anchor != null) {
                var exec = WandscapeEngine.getBlockInteractExec();
                int[] prog = exec != null ? exec.getChannelProgress(anchor) : new int[]{-1, -1};
                if (prog[0] >= 0) {
                    channelRemaining = Math.max(0, prog[0]);
                    if (prog[1] > 0) channelTotal = prog[1];
                    channelActive = true;
                }
            }
        }
        // Channel task accepted but not started (NPC en route): show "waiting" instead of a fake countdown.
        boolean pending = channelTotal > 0 && !channelActive;

        TaskQueueDataPacket.QueueEntry entry = new TaskQueueDataPacket.QueueEntry(
                0, categorize(bid), extractItemId(bid, params),
                paramInt(params, "count", 0), bid, summarizeWorkItem(bid, params));
        return new TaskQueueDataPacket.CurrentTask(
                entry, stepIndex, totalSteps, channelRemaining, channelTotal, pending);
    }

    /** Resolve the channel op anchor: the "anchor" param (same source as the compiled op), else the building anchor. */
    @Nullable
    private static GridPos anchorOf(Map<String, JsonElement> params, @Nullable BuildingState state) {
        JsonElement anchorEl = params.get("anchor");
        if (anchorEl instanceof JsonArray arr && arr.size() >= 3) {
            try {
                return new GridPos(
                        arr.get(0).getAsInt(), arr.get(1).getAsInt(), arr.get(2).getAsInt());
            } catch (NumberFormatException | IllegalStateException ignored) {
                // fall through to building anchor
            }
        }
        if (state != null && state.getAnchor() != null) {
            BlockPos p = state.getAnchor();
            return new GridPos(p.getX(), p.getY(), p.getZ());
        }
        return null;
    }

    @Nullable
    private static String paramStr(Map<String, JsonElement> params, String key) {
        JsonElement el = params.get(key);
        return (el instanceof JsonPrimitive p && p.isString()) ? p.getAsString() : null;
    }

    private static int paramInt(Map<String, JsonElement> params, String key, int fallback) {
        JsonElement el = params.get(key);
        if (el instanceof JsonPrimitive p && p.isNumber()) return p.getAsInt();
        return fallback;
    }

    static void write(RegistryFriendlyByteBuf buf, TaskQueueModifyPacket pkt) {
        buf.writeBlockPos(pkt.stationPos);
        buf.writeUtf(pkt.action);
        buf.writeVarInt(pkt.index);
    }

    static TaskQueueModifyPacket read(RegistryFriendlyByteBuf buf) {
        return new TaskQueueModifyPacket(
                buf.readBlockPos(),
                buf.readUtf(),
                buf.readVarInt()
        );
    }
}
