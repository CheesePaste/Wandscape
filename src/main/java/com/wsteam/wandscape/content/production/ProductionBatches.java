package com.wsteam.wandscape.content.production;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.wsteam.wandscape.content.building.data.WorkItem;
import com.wsteam.wandscape.foundation.util.BalanceValues;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 生产任务的批量上限：一条队列条目最多承载 {@link #maxPerBatch()} 个单位。
 *
 * <p>为什么：工作站族的共享队列允许每个空闲成员各领一条任务，但一条 x30000 的合成
 * （如铺平任务触发的大宗补料）连同它几万 tick 的通道时长会整块锁死在一座工作站上，
 * 其余工作站无活可领。拆成多条 x1000 后各站可并发各领一批；只有一座站时总时长不变，
 * 只是从「一条大任务」变成「一串小任务」。
 *
 * <p>两道闸口，缺一不可：入队时 {@link #split} 拆批，
 * {@code BuildingApiImpl.enqueueWork} 的合并路径用 {@link #mergedWithinLimit} 拦住
 * 「同配方同优先级可合并」把刚拆开的批次又并回一条超大任务。
 *
 * <p>纯 Java，零 MC 运行时依赖。
 */
public final class ProductionBatches {

    private ProductionBatches() {}

    /** 当前每批单位数上限（可被 data/wandscape/wandscape_balance.json 覆盖）。 */
    public static int maxPerBatch() {
        return Math.max(1, BalanceValues.productionBatchMax());
    }

    /**
     * 把超限的一条生产任务拆成若干条 ≤ {@link #maxPerBatch()} 的条目并保持队列顺序；
     * 非生产任务或未超限时原样返回单元素列表，调用方无需分支。
     *
     * <p>{@code channel_ticks} 按数量比例分摊、最后一条吃掉取整余数，所以拆批前后的
     * 总通道时长完全一致——单站产能不变，多站才分摊提速。
     */
    public static List<WorkItem> split(WorkItem work) {
        if (!isProduction(work)) return List.of(work);
        int total = paramInt(work.params(), "count");
        int cap = maxPerBatch();
        if (total <= cap) return List.of(work);

        int totalTicks = paramInt(work.params(), "channel_ticks");
        List<WorkItem> batches = new ArrayList<>();
        int remaining = total;
        int ticksLeft = totalTicks;
        while (remaining > 0) {
            int chunk = Math.min(cap, remaining);
            remaining -= chunk;
            int ticks;
            if (totalTicks <= 0) {
                ticks = 0;
            } else if (remaining == 0) {
                ticks = Math.max(1, ticksLeft);            // 最后一条拿走余数
            } else {
                ticks = (int) Math.max(1, (long) totalTicks * chunk / total);
            }
            ticksLeft -= ticks;

            Map<String, JsonElement> params = new LinkedHashMap<>(work.params());
            params.put("count", new JsonPrimitive(chunk));
            params.put("channel_ticks", new JsonPrimitive(ticks));
            batches.add(new WorkItem(work.blueprintId(), params, work.priority()));
        }
        return batches;
    }

    /** 两条生产任务合并后是否仍在上限内；超出即不许合并，否则拆批等于白拆。 */
    public static boolean mergedWithinLimit(WorkItem base, WorkItem incoming) {
        if (!isProduction(base) || !isProduction(incoming)) return true;
        return paramInt(base.params(), "count") + paramInt(incoming.params(), "count") <= maxPerBatch();
    }

    /** 是否是可拆批的生产蓝图（production:decompose / synthesize / craft / craft_spell）。 */
    private static boolean isProduction(WorkItem work) {
        return work != null && work.blueprintId() != null
                && work.blueprintId().startsWith("production:");
    }

    private static int paramInt(Map<String, JsonElement> params, String key) {
        if (params == null) return 0;
        JsonElement el = params.get(key);
        return (el instanceof JsonPrimitive p && p.isNumber()) ? p.getAsInt() : 0;
    }
}
