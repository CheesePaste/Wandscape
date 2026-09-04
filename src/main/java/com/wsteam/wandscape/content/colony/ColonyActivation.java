package com.wsteam.wandscape.content.colony;

import com.wsteam.wandscape.Config;
import com.wsteam.wandscape.api.ColonyApi;
import com.wsteam.wandscape.api.WandscapeApis;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 小镇自动化激活判定 + 离线收益折减。
 *
 * <p>创始人不在线时小镇照常运行（NPC 建造/生产、游客经济、每日结算），但收益侧
 * 按 {@code Config.COLONY_OFFLINE_INCOME_MULTIPLIER} 折减：商店利润、服务设施
 * 元素产出、殖民地经验获取都 × 该系数（默认 0.2 = 20%）。消耗侧（NPC 建造、
 * 商店补货的元素消耗）不打折——离线挂机净收益自然低于在线。
 *
 * <p>{@link #isColonyActive} 是冻结判定：离线收益系数为 0 时该小镇整体冻结
 * （NPC 建造/生产、游客经济、每日结算暂停，创始人上线后恢复）；系数 > 0 即运行。
 * {@link #setForcedActive} 提供 per-colony 强制覆盖（优先于派生判定），由
 * {@code ColonyApi.setActive} 使用；覆盖只在 JVM 生命周期内驻留，重启后回到派生判定。
 *
 * <p>无创始人（历史小镇/命令创建时未指定）无法判定在线状态，视为始终满收益，
 * 避免小镇被误冻结。
 */
public final class ColonyActivation {

    /** per-colony 强制覆盖（null=按派生判定）。仅 JVM 生命周期内驻留。 */
    private static final Map<UUID, Boolean> FORCED_ACTIVE = new ConcurrentHashMap<>();

    private ColonyActivation() {
    }

    /** 该小镇的自动化是否应继续运行（冻结判定）：强制覆盖优先，否则 离线收益系数 > 0 即运行。 */
    public static boolean isColonyActive(@Nullable UUID colonyId) {
        Boolean forced = colonyId != null ? FORCED_ACTIVE.get(colonyId) : null;
        if (forced != null) return forced;
        return getIncomeMultiplier(colonyId) > 0.0;
    }

    /** 强制冻结/解冻一个小镇（覆盖派生判定）。{@code active=false} 强制冻结，{@code true} 强制解冻。 */
    public static void setForcedActive(UUID colonyId, boolean active) {
        if (colonyId == null) return;
        FORCED_ACTIVE.put(colonyId, active);
    }

    /** 清除强制覆盖，回到派生判定。 */
    public static void clearForcedActive(UUID colonyId) {
        if (colonyId != null) FORCED_ACTIVE.remove(colonyId);
    }

    /**
     * 收益侧应使用的离线系数：创始人在线/无创始人/无服务器 → 1.0（满收益）；
     * 创始人离线 → 配置的 {@code colony.offlineIncomeMultiplier}。
     * 用于商店利润、服务设施元素产出、殖民地经验获取的离线折减。
     */
    public static double getIncomeMultiplier(@Nullable UUID colonyId) {
        if (colonyId == null) return 1.0;
        if (isFounderOnline(colonyId)) return 1.0;
        return Config.COLONY_OFFLINE_INCOME_MULTIPLIER.get();
    }

    /** 创始人玩家是否在线；无服务器/无创始人 → 视为在线（满收益，避免误判冻结）。 */
    private static boolean isFounderOnline(@Nullable UUID colonyId) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return true;
        ColonyApi api = WandscapeApis.getColonyApiSilently();
        if (api == null) return true;
        UUID founder = api.getFounder(colonyId);
        if (founder == null) return true; // 无创始人 → 无法判定 → 保持满收益
        return server.getPlayerList().getPlayer(founder) != null;
    }

    /**
     * 收益折减：{@code value} × 系数，四舍五入，结果不超过原值。
     * 系数 ≥ 1 原样返回；≤ 0（或值非正）返回 0。
     */
    public static long scaleIncome(long value, double multiplier) {
        if (value <= 0) return 0;
        if (multiplier <= 0.0) return 0;
        if (multiplier >= 1.0) return value;
        long scaled = Math.round(value * multiplier);
        return Math.min(value, scaled);
    }

    /**
     * 利润折减：成本不变、利润 × 系数，返回折减后的总收入（= 成本 + 折减利润，
     * 恒 ≥ 成本，商店离线时按进价出售、不会亏损）。
     */
    public static long scaleProfit(long cost, long profit, double multiplier) {
        return cost + scaleIncome(profit, multiplier);
    }
}
