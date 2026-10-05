package com.wsteam.wandscape.content.building.internal;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.wsteam.wandscape.content.building.data.WorkItem;
import com.wsteam.wandscape.content.task.engine.pool.TaskRequest;

import javax.annotation.Nullable;
import java.util.*;

/**
 * 宏建筑建造任务的批次切分器：将单条包含大量方块的大型建筑/修复任务拆分成若干批次（≤ {@link #DEFAULT_BATCH_SIZE} 方块），
 * 以便殖民地内多个空闲法师能够同时各领一批，并发施工。
 *
 * <p>两阶段执行契约：
 * <ol>
 *   <li><b>初始准备批次（Initial / Foundation Batch）</b>：承载整栋建筑的全部材料扣款请求（{@code ResourceRequestOp}）
 *       与包围盒清扫（{@code ClearBoxOp}），以及第 0 层地基方块放置。若仓库缺料，该批次自然挂起等待，
 *       绝不提前放行后续批次，防止无料白嫖。</li>
 *   <li><b>并发放置批次（Parallel Placement Batches）</b>：初始批次完工（材料已全额记账、场地已平整）后，
 *       后续所有批次一次性全部释放入全局任务池，多个法师可同时接单，按各自区域并发放置。</li>
 * </ol>
 *
 * <p>纯 Java 逻辑，零 Minecraft 运行时依赖。
 */
public final class ConstructionBatches {

    private ConstructionBatches() {}

    /** 单个建造批次承载的最大方块数。 */
    public static final int DEFAULT_BATCH_SIZE = 32;

    /**
     * 切分结果：
     * <ul>
     *   <li>{@code initialBatch}：首个批次（含全部建材请求与清盒，若需准备）。</li>
     *   <li>{@code remainingBatches}：后续并发放置批次（免材料、无清盒）。</li>
     *   <li>{@code completionData}：所有批次均完成时发射 {@code build_complete} 事件所需的数据载荷。</li>
     * </ul>
     */
    public record SplitResult(
            TaskRequest initialBatch,
            List<TaskRequest> remainingBatches,
            Map<String, String> completionData
    ) {}

    /**
     * 判定任务是否为可切分的宏建筑任务：
     * 只有建造（build:clear_and_build）与结构放置（build:place_structure），且方块数大于单批阈值时才切分。
     */
    public static boolean isSplittable(WorkItem work) {
        if (work == null || work.blueprintId() == null) return false;
        if (!"build:clear_and_build".equals(work.blueprintId())
                && !"build:place_structure".equals(work.blueprintId())) {
            return false;
        }
        if (work.params() == null) return false;
        JsonElement offsetsEl = work.params().get("offsets");
        return offsetsEl != null && offsetsEl.isJsonArray()
                && offsetsEl.getAsJsonArray().size() > DEFAULT_BATCH_SIZE;
    }

    /** 任务是否需要前置准备阶段（有材料消耗需从仓库提取，或有包围盒需清场）。 */
    public static boolean needsPreparation(WorkItem work) {
        return work != null && work.params() != null && needsPreparation(work.params());
    }

    /** 任务是否需要前置准备阶段。 */
    public static boolean needsPreparation(TaskRequest request) {
        return request != null && request.params() != null && needsPreparation(request.params());
    }

    /** 参数集是否包含材料请求或清盒范围。 */
    public static boolean needsPreparation(Map<String, JsonElement> params) {
        if (params.containsKey("boundary_min") && params.containsKey("boundary_max")) {
            return true;
        }
        JsonElement countsEl = params.get("material_counts");
        if (countsEl != null && countsEl.isJsonObject()) {
            return !countsEl.getAsJsonObject().keySet().isEmpty();
        }
        return false;
    }

    /**
     * 将一条大型建造任务切分成若干批次。
     * 方块按天然的 Y 轴升序（由 PatternCodec 解码序保证）等比切块，使地基最先下发，屋顶最后施工。
     */
    public static SplitResult split(WorkItem work, @Nullable UUID colonyId) {
        JsonArray offsets = work.params().get("offsets").getAsJsonArray();
        int totalBlocks = offsets.size();

        int chunkCount = (totalBlocks + DEFAULT_BATCH_SIZE - 1) / DEFAULT_BATCH_SIZE;
        int perChunk = (totalBlocks + chunkCount - 1) / chunkCount;

        JsonObject blocks = work.params().containsKey("blocks") && work.params().get("blocks").isJsonObject()
                ? work.params().get("blocks").getAsJsonObject() : new JsonObject();
        JsonObject blockNbt = work.params().containsKey("block_nbt") && work.params().get("block_nbt").isJsonObject()
                ? work.params().get("block_nbt").getAsJsonObject() : new JsonObject();
        JsonArray entities = work.params().containsKey("entities") && work.params().get("entities").isJsonArray()
                ? work.params().get("entities").getAsJsonArray() : new JsonArray();

        String rawName = work.params().containsKey("name") && work.params().get("name").isJsonPrimitive()
                ? work.params().get("name").getAsString() : "Building";
        String anchorStr = "0,0,0";
        if (work.params().containsKey("anchor")) {
            JsonElement el = work.params().get("anchor");
            if (el.isJsonPrimitive()) {
                anchorStr = el.getAsString();
            } else if (el.isJsonArray()) {
                JsonArray arr = el.getAsJsonArray();
                if (arr.size() == 3) {
                    anchorStr = arr.get(0).getAsInt() + "," + arr.get(1).getAsInt() + "," + arr.get(2).getAsInt();
                }
            }
        }
        String buildingIdStr = work.params().containsKey("building_id") && work.params().get("building_id").isJsonPrimitive()
                ? work.params().get("building_id").getAsString() : "";

        Map<String, String> completionData = new LinkedHashMap<>();
        completionData.put("building_name", rawName);
        completionData.put("blocks_placed", String.valueOf(totalBlocks));
        completionData.put("anchor", anchorStr);
        if (!buildingIdStr.isEmpty()) {
            completionData.put("building_id", buildingIdStr);
        }

        TaskRequest initialBatch = null;
        List<TaskRequest> remainingBatches = new ArrayList<>();

        for (int c = 0; c < chunkCount; c++) {
            int start = c * perChunk;
            int end = Math.min(start + perChunk, totalBlocks);
            if (start >= end) break;

            JsonArray offsetsChunk = new JsonArray();
            JsonObject blocksChunk = new JsonObject();
            JsonObject blockNbtChunk = new JsonObject();

            for (int i = start; i < end; i++) {
                JsonElement offEl = offsets.get(i);
                offsetsChunk.add(offEl);
                if (offEl.isJsonArray()) {
                    JsonArray arr = offEl.getAsJsonArray();
                    if (arr.size() == 3) {
                        String k = arr.get(0).getAsInt() + "," + arr.get(1).getAsInt() + "," + arr.get(2).getAsInt();
                        if (blocks.has(k)) blocksChunk.add(k, blocks.get(k));
                        if (blockNbt.has(k)) blockNbtChunk.add(k, blockNbt.get(k));
                    }
                }
            }

            Map<String, JsonElement> params = new LinkedHashMap<>(work.params());
            params.put("offsets", offsetsChunk);
            params.put("blocks", blocksChunk);
            params.put("block_nbt", blockNbtChunk);
            params.put("name", new JsonPrimitive(rawName + " (" + (c + 1) + "/" + chunkCount + ")"));
            // 子批次自身不发射 build_complete 事件，由 BuildingTaskPool 聚合后在全量完工时统一发射
            params.put("omit_complete_event", new JsonPrimitive(true));

            if (c == 0) {
                // 首个批次：保留全部建材请求与清盒，但清盒排除集需覆盖全建筑 pattern
                params.put("pattern_offsets", offsets);
                params.put("entities", new JsonArray());
                initialBatch = new TaskRequest(work.blueprintId(), params, work.priority(), colonyId);
            } else {
                // 后续批次：建材已在首批一次性扣除，场地已清，不再重复扣料或清盒
                params.put("material_list", new JsonArray());
                params.put("material_counts", new JsonObject());
                params.remove("boundary_min");
                params.remove("boundary_max");
                params.remove("pattern_offsets");
                // 装饰实体附在最后一个批次
                if (c == chunkCount - 1) {
                    params.put("entities", entities);
                } else {
                    params.put("entities", new JsonArray());
                }
                remainingBatches.add(new TaskRequest("build:place_structure", params, work.priority(), colonyId));
            }
        }

        return new SplitResult(initialBatch, remainingBatches, completionData);
    }
}
