package com.wsteam.wandscape.content.building.internal;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.wsteam.wandscape.Config;
import com.wsteam.wandscape.content.building.data.WorkItem;
import com.wsteam.wandscape.content.task.engine.pool.TaskRequest;
import com.wsteam.wandscape.foundation.log.Log;

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
 * <p>批次不是「独立的小活」：每条批次都带上整栋的包围盒 {@code params["task_bbox"]}，
 * 让所有批次共用同一个站位，并由 {@code TaskExecutionSystem} 在同一法师身上直接续接，
 * 使拆批在观感与效率上等价于「一个法师站在一处干完整栋」。
 *
 * <p>纯 Java 逻辑，零 Minecraft 运行时依赖。
 */
public final class ConstructionBatches {

    private static final String TAG = "ConstructionBatches";

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
     * 拆批建造的**持久化记录**：只存标量，批次 params 一律不落盘 —— 建筑 JSON 是样式的唯一真源，
     * 读档时据此重建 WorkItem 再重新分批（见 {@code TaskPoolSavedData}）。
     *
     * @param batchSize        首次分批时的尺寸快照。Config 可被玩家中途改，不留快照就会用新尺寸重分，
     *                         chunk 边界错位 → 漏块/铺错
     * @param prepDone         准备批次（扣料 + 清盒）是否已执行完。未执行时读档必须等它跑完再放行
     *                         placement 批次，绝不提前铺（否则白嫖建材且不清场）
     * @param completedBatches **完成游标**：已确认完成的 placement 批次数（下标从 0 起的前缀）。
     *                         读档时游标之前的批次跳过、游标及其之后的全部重建并重新发布
     *                         （含当时在途的那几批——从该批起点重跑，与"重进世界重跑"同语义，幂等）。
     *                         游标只在前缀连续完成时推进（见 {@code BuildingTaskPool#checkBatchesProgress}）：
     *                         乱序完成时宁可让游标落后几批被重跑，也绝不越过尚未完成的批次——
     *                         越过就是读档后永久缺那一片方块
     * @param blockCount       建造开始时该建筑的总方块数，用于检测「中途改了 JSON」的错位
     * @param priority         分批任务的调度优先级（读档重建用）
     * @param clearBox         建造时是否清盒（决定 {@code task_bbox} → 站位，读档重分要一致）
     */
    public record BatchJob(int batchSize, boolean prepDone, int completedBatches, int blockCount,
                           int priority, boolean clearBox) {}

    /**
     * 读档重发范围：跳过完成游标之前的 placement 批次，其余（含当时在途的）全部重新发布。
     * 游标越界（记录错位）由调用方在重建前拦掉，这里只做切片。
     */
    public static List<TaskRequest> batchesFrom(SplitResult split, int completedBatches) {
        List<TaskRequest> remaining = split.remainingBatches();
        if (completedBatches <= 0) return remaining;
        if (completedBatches >= remaining.size()) return List.of();
        return List.copyOf(remaining.subList(completedBatches, remaining.size()));
    }

    /**
     * 这条已发布任务的 params 是否属于**拆批建造的子批次**（c≥1 的 placement 批次）。
     * 判据就是 {@link #split} 自己写的两个标记：{@code omit_complete_event=true}（子批次不发完工广播）
     * 且**没有** {@code pattern_offsets}（那是准备批次 c=0 独有的清盒排除集）。
     *
     * <p>只认这两个标记，不认蓝图 id：准备批次（c=0）、修复任务（{@code build:place_structure}
     * 但无 {@code omit_complete_event}）、生产/采集任务都会被排除。两处依赖它：
     * <ul>
     *   <li>持久化：子批次 params 能从建筑 JSON 重建，不落盘（见 {@code TaskPoolSavedData}）；</li>
     *   <li>完成游标：只有 placement 批次占用批次下标，准备批次/修复任务不能算进去
     *       （否则游标会越过尚未铺的批次，读档后永久缺那一片方块）。</li>
     * </ul>
     */
    public static boolean isPlacementBatch(@Nullable Map<String, JsonElement> params) {
        if (params == null || params.isEmpty()) return false;
        JsonElement omit = params.get("omit_complete_event");
        if (omit == null || !omit.isJsonPrimitive() || !omit.getAsBoolean()) return false;
        return !params.containsKey("pattern_offsets");
    }

    /**
     * 当前配置的单批方块数（≥1）。
     *
     * <p>分批尺寸是**按建筑快照**的：开工时取一次写进 {@link BatchJob#batchSize()}，
     * 之后玩家改配置不再影响已开工建筑。Config 只在 {@link ConstructionBatches} 内读，
     * 让 {@code BuildingTaskPool} 保持零 Minecraft/NeoForge 依赖。
     */
    public static int currentBatchSize() {
        return Math.max(1, Config.constructionBatchSize());
    }

    /**
     * WorkItem 的总方块数（= {@code offsets} 条数）；缺失或形态不对返回 -1。
     * 这个数就是 {@link BatchJob#blockCount()} 记的那个值 —— 读档重分后必须相等。
     */
    public static int blockCount(@Nullable WorkItem work) {
        if (work == null || work.params() == null) return -1;
        JsonElement offsetsEl = work.params().get("offsets");
        return offsetsEl != null && offsetsEl.isJsonArray() ? offsetsEl.getAsJsonArray().size() : -1;
    }

    /**
     * 原始 WorkItem 是否带了清盒范围（{@code boundary_min/max} 的唯一写入点是
     * {@code EnqueueHelper#fillBoundaryParams}，只在「开了清盒且建筑 JSON 有 boundary」时写）。
     * 读档重建要照这个标志重算 {@code task_bbox}，否则法师站位会和开工时不一致。
     */
    public static boolean clearsBox(WorkItem work) {
        return work != null && work.params() != null
                && work.params().containsKey("boundary_min")
                && work.params().containsKey("boundary_max");
    }

    /**
     * 判定任务是否为可切分的宏建筑任务。
     *
     * <p>只认**由建筑模板展开**的建造任务：
     * <ul>
     *   <li>{@code build:clear_and_build} 按定义就是模板建造（修复路径用的是 {@code build:place_structure}），
     *       这条对读档恢复也成立 —— 只靠 WorkItem 上的布尔标记的话，从别的存档路径恢复出来的
     *       建造任务会丢掉标记、静默退回「整栋一条任务」。</li>
     *   <li>{@code build:place_structure} 要求 {@link WorkItem#batchBuild()} 显式标记：
     *       修复任务（{@code BuildingRepairHandler}）也用它，而修复参数来自世界扫描，无法从 JSON 重建。</li>
     * </ul>
     * 生产/采集/拆除类一律不拆批。
     *
     * @param batchSize 分批尺寸快照（见 {@link #currentBatchSize()}），不是现读 Config
     */
    public static boolean isSplittable(WorkItem work, int batchSize) {
        if (!Config.isMultiWorkerConstructionEnabled()) return false;
        if (work == null || work.blueprintId() == null) return false;
        if (!work.batchBuild() && !"build:clear_and_build".equals(work.blueprintId())) {
            return false;
        }
        if (!"build:clear_and_build".equals(work.blueprintId())
                && !"build:place_structure".equals(work.blueprintId())) {
            return false;
        }
        return blockCount(work) > Math.max(1, batchSize);
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
     *
     * @param batchSize 分批尺寸快照 —— 读档重分时必须传当初开工时的那个值（{@link BatchJob#batchSize()}），
     *                  不能现读 Config，否则玩家中途改过配置就会切出不同的 chunk 边界
     */
    public static SplitResult split(WorkItem work, @Nullable UUID colonyId, int batchSize) {
        JsonArray offsets = work.params().get("offsets").getAsJsonArray();
        int totalBlocks = offsets.size();
        int size = Math.max(1, batchSize);

        int chunkCount = (totalBlocks + size - 1) / size;
        int perChunk = (totalBlocks + chunkCount - 1) / chunkCount;

        JsonObject blocks = work.params().containsKey("blocks") && work.params().get("blocks").isJsonObject()
                ? work.params().get("blocks").getAsJsonObject() : new JsonObject();
        JsonObject blockNbt = work.params().containsKey("block_nbt") && work.params().get("block_nbt").isJsonObject()
                ? work.params().get("block_nbt").getAsJsonObject() : new JsonObject();
        JsonArray entities = work.params().containsKey("entities") && work.params().get("entities").isJsonArray()
                ? work.params().get("entities").getAsJsonArray() : new JsonArray();

        String rawName = work.params().containsKey("name") && work.params().get("name").isJsonPrimitive()
                ? work.params().get("name").getAsString() : "Building";
        // 锚点：offsets 是**相对锚点**的偏移，boundary_min/max 是绝对坐标——算整栋包围盒时
        // 必须把锚点加到 offsets 上，否则写出去的站位会落在原点附近（法师跑到世界原点去）。
        int[] anchorArr = anchorOf(work.params());
        String anchorStr = anchorArr[0] + "," + anchorArr[1] + "," + anchorArr[2];
        String buildingIdStr = work.params().containsKey("building_id") && work.params().get("building_id").isJsonPrimitive()
                ? work.params().get("building_id").getAsString() : "";

        // 整栋（pattern 全集 ∪ 清盒范围）的包围盒，写进每条批次的 params["task_bbox"]：
        // 执行侧据此算「整条任务一个」的站位。不写的话，每条批次都会按自己那一小块现算站位，
        // 前排/后排批次的站位能差十几格，法师就得在工地两侧来回跑（实测观感就是「干几秒、跑一段」）。
        int[] taskBbox = taskBbox(offsets, anchorArr, work.params());

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
            if (taskBbox != null) {
                params.put("task_bbox", bboxToJson(taskBbox));
            }

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

    /**
     * 整条建造任务的目标包围盒 {@code [minX, minY, minZ, maxX, maxY, maxZ]}（世界坐标），
     * 供执行侧算「整条任务一个」的站位（{@code TaskExecutionSystem.standoffStance}）。
     *
     * <p>范围取 pattern 全集（相对锚点，先加锚点）并入清盒盒（{@code boundary_min/max}，绝对坐标，
     * 开清盒时）——与不拆批时执行侧按 op 包围盒现算的结果一致，所以拆批前后法师站的是同一个点。
     *
     * @return 包围盒；pattern 为空时返回 null（不写该参数，执行侧退回按 op 现算）
     */
    @Nullable
    private static int[] taskBbox(JsonArray offsets, int[] anchor, Map<String, JsonElement> params) {
        int[] box = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE,
                Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (JsonElement el : offsets) {
            if (el.isJsonArray()) {
                JsonArray arr = el.getAsJsonArray();
                if (arr.size() == 3) {
                    expand(box, anchor[0] + arr.get(0).getAsInt(),
                            anchor[1] + arr.get(1).getAsInt(),
                            anchor[2] + arr.get(2).getAsInt());
                }
            }
        }
        for (String key : List.of("boundary_min", "boundary_max")) {
            JsonElement el = params.get(key);
            if (el != null && el.isJsonArray()) {
                JsonArray arr = el.getAsJsonArray();
                if (arr.size() == 3) {
                    expand(box, arr.get(0).getAsInt(), arr.get(1).getAsInt(), arr.get(2).getAsInt());
                }
            }
        }
        return box[0] > box[3] ? null : box;
    }

    /**
     * 锚点 {@code params["anchor"]}：建造链路写的是 {@code [x,y,z]} 数组，老参数形态也可能写成
     * 逗号串。缺失或读不出时退回原点（与执行侧「无锚点即按原点放」的老口径一致）。
     */
    private static int[] anchorOf(Map<String, JsonElement> params) {
        JsonElement el = params.get("anchor");
        if (el != null) {
            try {
                if (el.isJsonArray()) {
                    JsonArray arr = el.getAsJsonArray();
                    if (arr.size() == 3) {
                        return new int[]{arr.get(0).getAsInt(), arr.get(1).getAsInt(), arr.get(2).getAsInt()};
                    }
                } else if (el.isJsonPrimitive()) {
                    String[] parts = el.getAsString().split(",");
                    if (parts.length == 3) {
                        return new int[]{Integer.parseInt(parts[0].trim()),
                                Integer.parseInt(parts[1].trim()), Integer.parseInt(parts[2].trim())};
                    }
                }
            } catch (RuntimeException e) {
                Log.warn(TAG, "unreadable anchor param '%s' — batching falls back to origin", el);
            }
        }
        return new int[]{0, 0, 0};
    }

    private static void expand(int[] box, int x, int y, int z) {
        if (x < box[0]) box[0] = x;
        if (y < box[1]) box[1] = y;
        if (z < box[2]) box[2] = z;
        if (x > box[3]) box[3] = x;
        if (y > box[4]) box[4] = y;
        if (z > box[5]) box[5] = z;
    }

    private static JsonArray bboxToJson(int[] box) {
        JsonArray arr = new JsonArray();
        for (int v : box) arr.add(v);
        return arr;
    }
}
