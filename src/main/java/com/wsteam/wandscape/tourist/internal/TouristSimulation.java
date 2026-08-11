package com.wsteam.wandscape.tourist.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

import com.wsteam.wandscape.Config;
import com.wsteam.wandscape.building.data.BlockOffset;
import com.wsteam.wandscape.building.data.BuildingConfig;
import com.wsteam.wandscape.building.internal.BuildingSavedData;
import com.wsteam.wandscape.building.internal.BuildingState;
import com.wsteam.wandscape.building.internal.ShopInteractionHandler;
import com.wsteam.wandscape.building.internal.ShopStockManager;
import com.wsteam.wandscape.projection.BuildingRotation;
import com.wsteam.wandscape.shared.api.BuildingApi;
import com.wsteam.wandscape.shared.data.Activity;
import com.wsteam.wandscape.shared.data.AtmConfig;
import com.wsteam.wandscape.shared.data.BuildingData;
import com.wsteam.wandscape.shared.data.ElementType;
import com.wsteam.wandscape.shared.data.RelaxConfig;
import com.wsteam.wandscape.shared.data.ServiceConfig;
import com.wsteam.wandscape.shared.data.ShopConfig;
import com.wsteam.wandscape.shared.registry.WandscapeApis;
import com.wsteam.wandscape.shared.registry.WandscapeConstants;
import com.wsteam.wandscape.warehouse.ColonyItemBank;
import com.wsteam.wandscape.shared.log.Log;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

/**
 * Shared tourist interaction economy, operating on {@link TouristStateHost}.
 *
 * <p>Used by both the physical {@code TouristEntity} (via {@code TouristMoveGoal})
 * and the unloaded sim ({@link TouristSimSystem}) so the three bars / energy /
 * wallet / target-selection stay single-source. All side effects go through
 * SavedData-backed systems ({@code ShopStockManager}, {@code ColonyItemBank},
 * {@code BuildingSavedData}) — none require a loaded chunk.
 *
 * <p>Goal 语义：填三条无惩罚；目标选择 = Find-Best-Action（总三值满意度增益 + 精力/钱包紧急加分
 * − 排队惩罚），只看视野内；spot 单点交互、spot 数量 = 同时交互人数上限（全满排队）。
 */
public final class TouristSimulation {

    private static final String TAG = "TouristSimulation";

    /** 精力低于此比例 → relax 建筑紧急加分（与单次满意度增益同量级，不再碾压选店）。 */
    private static final double ENERGY_URGENCY_BONUS = 100;
    /** 钱包低于初始 1/4 → ATM 取现加分（与单次满意度增益同量级，不再碾压选店）。 */
    private static final double WALLET_LOW_BONUS = 50;
    /** 钱包=0 → ATM 取现加分稍高（优先取现继续逛）。 */
    private static final double WALLET_EMPTY_BONUS = 100;

    private TouristSimulation() {
    }

    // ── Building config access ──

    @Nullable
    public static BuildingState getState(ServerLevel level, UUID buildingId) {
        BuildingSavedData sd = BuildingSavedData.get(level);
        if (sd == null) return null;
        return sd.getBuilding(buildingId);
    }

    @Nullable
    public static BuildingConfig getConfig(ServerLevel level, UUID buildingId) {
        BuildingState state = getState(level, buildingId);
        if (state == null) return null;
        return com.wsteam.wandscape.building.internal.BuildingConfigLoader.getInstance().get(state.getBuildingTypeId());
    }

    @Nullable
    public static String getBuildingTypeId(ServerLevel level, UUID buildingId) {
        BuildingState state = getState(level, buildingId);
        return state != null ? state.getBuildingTypeId() : null;
    }

    public static boolean isHotelBuilding(ServerLevel level, UUID buildingId) {
        BuildingConfig cfg = getConfig(level, buildingId);
        return cfg != null && cfg.service() != ServiceConfig.NONE && cfg.service().maxOccupancy() > 0;
    }

    // ── Effective values / three bars ──

    public static int[] effectiveValues(ServerLevel level, UUID buildingId) {
        BuildingConfig cfg = getConfig(level, buildingId);
        if (cfg == null) return new int[]{0, 0, 0};
        int c = cfg.comfort();
        int m = cfg.magic();
        int w = cfg.wonder();
        if (cfg.shop() != ShopConfig.NONE) {
            ShopStockManager stock = ShopStockManager.getActive();
            if (stock != null) {
                c += stock.getGoodsBonusComfort(buildingId);
                m += stock.getGoodsBonusMagic(buildingId);
                w += stock.getGoodsBonusWonder(buildingId);
            }
        }
        return new int[]{c, m, w};
    }

    public static int threeValueSum(ServerLevel level, UUID buildingId) {
        int[] v = effectiveValues(level, buildingId);
        return v[0] + v[1] + v[2];
    }

    /**
     * 填三条：sat_d += round(value_d × TOURIST_BAR_GAIN_COEFF)，封顶 need_d。无惩罚（普通建筑也正向涨）。
     *
     * @return 三维增量（用于行程记录；全 0 = 未发生填充）。
     */
    public static int[] fillBars(ServerLevel level, TouristStateHost t, UUID buildingId) {
        int[] v = effectiveValues(level, buildingId);
        double coeff = Config.TOURIST_BAR_GAIN_COEFF.get();
        int[] delta = new int[3];
        delta[0] = fillBar(t.getComfortSat(), t.getComfortNeed(), v[0], coeff, t::setComfortSat);
        delta[1] = fillBar(t.getMagicSat(), t.getMagicNeed(), v[1], coeff, t::setMagicSat);
        delta[2] = fillBar(t.getWonderSat(), t.getWonderNeed(), v[2], coeff, t::setWonderSat);
        return delta;
    }

    private static int fillBar(int sat, int need, int value, double coeff, java.util.function.IntConsumer setter) {
        if (value <= 0 || need <= 0) return 0;
        int add = (int) Math.round(value * coeff);
        int newSat = Math.min(need, sat + add);
        setter.accept(newSat);
        return newSat - sat;
    }

    /** 交互时长 = 该建筑模式预设块的 interaction_duration_ticks（与 spot 无关）。 */
    public static int interactionDuration(ServerLevel level, UUID buildingId) {
        BuildingConfig cfg = getConfig(level, buildingId);
        if (cfg == null) return 0;
        if (cfg.shop() != ShopConfig.NONE) return cfg.shop().interactionDurationTicks();
        if (cfg.service() != ServiceConfig.NONE) return cfg.service().interactionDurationTicks();
        if (cfg.relax() != RelaxConfig.NONE) return cfg.relax().interactionDurationTicks();
        if (cfg.atm() != AtmConfig.NONE) return cfg.atm().interactionDurationTicks();
        return 0;
    }

    // ── Interact spots（寻路目标 = 一个点；spot 数量 = 同时交互人数上限）──

    /** interact_spots 数量（0 = 对游客无效，无兜底）。 */
    public static int interactSpotCount(ServerLevel level, UUID buildingId) {
        BuildingConfig cfg = getConfig(level, buildingId);
        return cfg != null && cfg.interactSpots() != null ? cfg.interactSpots().size() : 0;
    }

    /** 第 index 个 spot 的动作种类（越界兜底 BROWSE）。 */
    public static Activity interactSpotAction(ServerLevel level, UUID buildingId, int index) {
        BuildingConfig cfg = getConfig(level, buildingId);
        if (cfg == null || cfg.interactSpots() == null || index < 0 || index >= cfg.interactSpots().size()) {
            return Activity.BROWSE;
        }
        return cfg.interactSpots().get(index).action();
    }

    /** 第 index 个 spot 的世界坐标（anchor + 旋转偏移）。 */
    @Nullable
    public static BlockPos spotWorldPos(ServerLevel level, UUID buildingId, int index) {
        BuildingState state = getState(level, buildingId);
        if (state == null) return null;
        BuildingConfig cfg = getConfig(level, buildingId);
        if (cfg == null || cfg.interactSpots() == null || index < 0 || index >= cfg.interactSpots().size()) {
            return null;
        }
        BuildingConfig.InteractSpot spot = cfg.interactSpots().get(index);
        BlockOffset rotated = BuildingRotation.rotateOffset(spot.pos(), state.getRotationSteps());
        return state.getAnchor().offset(rotated.x(), rotated.y(), rotated.z());
    }

    /** 第 index 个 spot 的朝向（anchor + 旋转偏移）；游客在位上做动作时面向此方向。 */
    public static Direction spotFacing(ServerLevel level, UUID buildingId, int index) {
        BuildingState state = getState(level, buildingId);
        if (state == null) return Direction.SOUTH;
        BuildingConfig cfg = getConfig(level, buildingId);
        if (cfg == null || cfg.interactSpots() == null || index < 0 || index >= cfg.interactSpots().size()) {
            return Direction.SOUTH;
        }
        Direction facing = cfg.interactSpots().get(index).facing();
        if (facing == null) return Direction.SOUTH;
        return BuildingRotation.rotateDirection(facing, state.getRotationSteps());
    }

    /** 认领一个空 spot 并占用；全满返回 -1（游客排队等待）。记录占用者供幽灵自愈探测。 */
    public static int claimSpot(ServerLevel level, UUID buildingId, UUID touristId) {
        int total = interactSpotCount(level, buildingId);
        if (total <= 0) return -1;
        return TouristSpotManager.getActive().claim(buildingId, total, touristId);
    }

    /** 认领指定 spot（供排在该 spot 队首的游客用）；已被占返回 -1。记录占用者供幽灵自愈探测。 */
    public static int claimSpotAt(ServerLevel level, UUID buildingId, int spotIndex, UUID touristId) {
        int total = interactSpotCount(level, buildingId);
        if (total <= 0) return -1;
        return TouristSpotManager.getActive().claimAt(buildingId, spotIndex, total, touristId);
    }

    /** 释放已占用的 spot。 */
    public static void releaseSpot(UUID buildingId, int spotIndex) {
        TouristSpotManager.getActive().release(buildingId, spotIndex);
    }

    /** 该建筑当前空闲 spot 数（全满 → 排队）。 */
    public static int freeSpotCount(ServerLevel level, UUID buildingId) {
        int total = interactSpotCount(level, buildingId);
        if (total <= 0) return 0;
        return TouristSpotManager.getActive().freeSpotCount(buildingId, total);
    }

    // ── 排队站位 ──

    /**
     * 排在第 {@code spotIndex} 个 spot 后、队序 {@code queuePosition} 的站位世界坐标：
     * 队首（0）= spot 背后 1 个间距，之后沿 **spot 的 facing 方向的反方向** 逐个排开，
     * 即游客面朝与交互游客相同的方向、一个贴一个。
     *
     * @param queuePosition 队序（0 = 紧贴正在交互的游客）
     * @return 该站位；spot 坐标缺失时返回 null
     */
    @Nullable
    public static BlockPos queueSlotPos(ServerLevel level, UUID buildingId, int spotIndex, int queuePosition) {
        BlockPos spot = spotWorldPos(level, buildingId, spotIndex);
        if (spot == null || queuePosition < 0) return null;
        Direction facing = spotFacing(level, buildingId, spotIndex);
        double d = Config.TOURIST_QUEUE_SLOT_SPACING.get() * (queuePosition + 1);
        // 沿 facing 反方向向后排（facing 恒为水平轴，队列是一条直线、非斜线）
        int dx = -facing.getStepX();
        int dz = -facing.getStepZ();
        return new BlockPos(
                (int) Math.round(spot.getX() + dx * d),
                spot.getY(),
                (int) Math.round(spot.getZ() + dz * d));
    }

    // ── Interactions ──

    /** Result of a building visit — used for the journey diary, bubbles and narratives. */
    public record InteractionResult(@Nullable ShopStockManager.PurchaseResult purchase,
            int comfortDelta, int magicDelta, int wonderDelta, int energyDelta, String whatHappened) {
    }

    /** Shop visit: buy with the universal wallet, fill bars, consume energy. */
    @Nullable
    public static InteractionResult performShopInteraction(ServerLevel level,
            TouristStateHost t, UUID buildingId, UUID colonyId) {
        BuildingConfig cfg = getConfig(level, buildingId);
        if (cfg == null || cfg.shop() == ShopConfig.NONE) return null;

        ShopStockManager stock = ShopStockManager.getActive();
        ShopStockManager.PurchaseResult purchase = null;
        if (stock != null) {
            purchase = ShopInteractionHandler.interact(
                    stock, idOf(t), buildingId, colonyId, t.getWallet(), t.getInitialWallet());
        }
        if (purchase != null) {
            t.spendWallet(purchase.spent());
        }

        // 照常结算：买不起/没货也填条、扣精力，只是行程记成「进去逛了一圈，什么也没买」。
        int[] delta = fillBars(level, t, buildingId);
        t.setEnergy(t.getEnergy() - 20);
        String what = purchase != null
                ? (purchase.count() > 1 ? purchase.itemId() + " ×" + purchase.count() : purchase.itemId())
                : "进去逛了一圈，什么也没买";
        return new InteractionResult(purchase, delta[0], delta[1], delta[2], -20, what);
    }

    /** Service visit: consume energy, fill bars, emit element output to the colony bank. */
    @Nullable
    public static InteractionResult performServiceInteraction(ServerLevel level,
            TouristStateHost t, UUID buildingId, UUID colonyId) {
        BuildingConfig cfg = getConfig(level, buildingId);
        if (cfg == null || cfg.service() == ServiceConfig.NONE) return null;

        var svc = cfg.service();
        t.setEnergy(t.getEnergy() - svc.energyPerUse());
        int[] delta = fillBars(level, t, buildingId);

        if (colonyId != null && !svc.elementOutput().isEmpty()) {
            ColonyItemBank bank = ColonyItemBank.get(level);
            if (bank != null) {
                for (var entry : svc.elementOutput().entrySet()) {
                    try {
                        bank.addElement(colonyId, ElementType.fromId(entry.getKey()), entry.getValue());
                    } catch (IllegalArgumentException e) {
                        Log.warn(TAG, "[Tourist] Unknown element type '{}' in service {} elementOutput",
                                entry.getKey(), shortId(buildingId));
                    }
                }
            }
        }
        return new InteractionResult(null, delta[0], delta[1], delta[2], -svc.energyPerUse(), "服务");
    }

    /** Relax visit: restore energy (clamped to TOURIST_MAX_ENERGY), fill bars. */
    @Nullable
    public static InteractionResult performRelaxInteraction(ServerLevel level,
            TouristStateHost t, UUID buildingId, UUID colonyId) {
        BuildingConfig cfg = getConfig(level, buildingId);
        if (cfg == null || cfg.relax() == RelaxConfig.NONE) return null;

        var r = cfg.relax();
        int energyBefore = t.getEnergy();
        t.setEnergy(t.getEnergy() + r.energyRestore());
        int gained = t.getEnergy() - energyBefore;
        int[] delta = fillBars(level, t, buildingId);
        return new InteractionResult(null, delta[0], delta[1], delta[2], gained, "歇脚恢复精力");
    }

    /** ATM 单次取现 = 初始钱包的随机 [min, max] 比例（新模型；池子 travelFund 封顶防无限取现）。 */
    private static final double ATM_WITHDRAW_MIN_RATIO = 0.2;
    private static final double ATM_WITHDRAW_MAX_RATIO = 0.5;

    /** ATM visit: withdraw from travelFund into the wallet (capped), fill bars. */
    @Nullable
    public static InteractionResult performAtmInteraction(ServerLevel level,
            TouristStateHost t, UUID buildingId, UUID colonyId) {
        BuildingConfig cfg = getConfig(level, buildingId);
        if (cfg == null || cfg.atm() == AtmConfig.NONE) return null;

        // 单次取现 = 初始钱包的随机 20%~50%（封顶 travelFund 池子）——单次取不完，配合取现冷却分批取。
        double ratio = ATM_WITHDRAW_MIN_RATIO + level.getRandom().nextDouble()
                * (ATM_WITHDRAW_MAX_RATIO - ATM_WITHDRAW_MIN_RATIO);
        int desired = Math.max(1, (int) Math.round(t.getInitialWallet() * ratio));
        int amount = Math.min(desired, t.getTravelFund());
        if (amount > 0) {
            t.setWallet(t.getWallet() + amount);
            t.setTravelFund(t.getTravelFund() - amount);
            t.setLastAtmWithdrawTime(t.timeBase());
        }
        int[] delta = fillBars(level, t, buildingId);
        return new InteractionResult(null, delta[0], delta[1], delta[2], 0, "取钱 " + amount);
    }

    /**
     * ATM 可重新取现（豁免 visitedBuildings 的条件）：池子有余额 + 钱包低于初始 1/4 + 冷却已过。
     * 只豁免不重置——visitedBuildings 仍累计（红线 #8），靠本判定让游客整段停留分批取现，
     * 而不是清空已逛集合。cooldownTicks 由调用方注入（Config），便于纯函数单测。
     */
    private static boolean atmReusable(TouristStateHost t, AtmConfig atm, int cooldownTicks) {
        if (atm == null || atm == AtmConfig.NONE) return false;
        return atmReusable(t.getTravelFund(), t.getWallet(), t.getInitialWallet(),
                t.getLastAtmWithdrawTime(), t.timeBase(), cooldownTicks);
    }

    /** 纯判定（可 JUnit）：lastWithdrawTime==0（从未取现）恒可去；否则取现间隔需 ≥ cooldownTicks。 */
    static boolean atmReusable(int travelFund, int wallet, int initialWallet,
            int lastWithdrawTime, int timeBase, int cooldownTicks) {
        if (travelFund <= 0) return false;
        if (wallet >= Math.max(1, initialWallet / 4)) return false;
        return lastWithdrawTime == 0 || timeBase - lastWithdrawTime >= cooldownTicks;
    }

    /**
     * Relax 可重复逛（豁免 visited 门）：精力低于恢复阈值时，relax 建筑可反复歇脚回精力。
     * 否则精力耗尽后唯一能去的 relax 逛过一次就被 visited 门挡死，游客闲逛至精力 0 卡死。
     * 只豁免不重置——visitedBuildings 仍累计（红线 #8），靠精力比本判定让游客在真正需要时回 relax。
     */
    private static boolean relaxReusable(TouristStateHost t, RelaxConfig relax) {
        if (relax == null || relax == RelaxConfig.NONE) return false;
        if (relax.energyRestore() <= 0) return false;
        return relaxReusable(t.getEnergy(), WandscapeConstants.TOURIST_MAX_ENERGY,
                Config.TOURIST_ENERGY_RESTORE_THRESHOLD.get());
    }

    /** 纯判定（可 JUnit）：精力 0 恒可去（精力耗尽必须能自救，不受阈值影响）；否则精力比 < threshold 时可重复去。 */
    static boolean relaxReusable(int energy, int maxEnergy, double threshold) {
        return energy <= 0 || energy < threshold * maxEnergy;
    }

    /** 该建筑当前可否豁免 visited 门（ATM 缺钱分批取现 / 精力低重复歇脚 relax）。 */
    private static boolean exemptFromVisited(TouristStateHost t, BuildingConfig cfg, int atmCooldownTicks) {
        return atmReusable(t, cfg.atm(), atmCooldownTicks)
                || relaxReusable(t, cfg.relax());
    }

    /** Mark a visit memory on the host (journey diary). Returns the memory for narrative use. */
    public static com.wsteam.wandscape.shared.data.VisitMemory addVisitMemory(TouristStateHost t,
            @Nullable String buildingTypeId, @Nullable String displayName, String category, long gameTime,
            int comfortDelta, int magicDelta, int wonderDelta, int energyDelta, String whatHappened) {
        String type = buildingTypeId != null ? buildingTypeId : "unknown";
        String name = displayName != null && !displayName.isEmpty() ? displayName : type;
        com.wsteam.wandscape.shared.data.VisitMemory memory = new com.wsteam.wandscape.shared.data.VisitMemory(
                type, name, category, gameTime, comfortDelta, magicDelta, wonderDelta, energyDelta, whatHappened,
                com.wsteam.wandscape.shared.data.Emotion.fromDelta(comfortDelta + magicDelta + wonderDelta));
        t.addVisitMemory(memory);
        return memory;
    }

    // ── Target selection (Find-Best-Action，视野内；镜像 TouristMoveGoal.planNextBuilding) ──

    /**
     * Pick the next tourist target building for the tourist.
     *
     * <p>规则（goal.md 非协商项）：
     * <ul>
     *   <li>只看视野内（TOURIST_VISION_RADIUS 且 requireLoaded 时区块已加载）的可交互建筑；视野内无合适目标 → 返回 null（调用方闲逛）。</li>
     *   <li>评分 = Σ(该维实际增益 min(缺口, round(值×coeff))) + 精力紧急加分（relax）+ 钱包紧急加分（atm）− 排队惩罚（spot 全满）。</li>
     *   <li>精力 0 → 只能去 relax.energyRestore()>0 建筑。</li>
     *   <li>夜晚且未满条 → 优先旅店（service.maxOccupancy>0 且有空位）；视野内无旅店 → 回退普通建筑（傍晚不干晃），18000 后由离场窗口接管（入旅店/离场）；满条夜晚由离场逻辑处理。</li>
     *   <li>0-spot 建筑对游客无效（无兜底）。</li>
     * </ul>
     *
     * @param requireLoaded 实体寻路需要目标区块已加载；sim（直线移动）传 false。
     */
    @Nullable
    public static BuildingState selectNextTarget(ServerLevel level, TouristStateHost t, boolean requireLoaded) {
        UUID colonyId = t.getColonyId();
        if (colonyId == null) return null;
        BuildingApi api = getBuildingApi();
        if (api == null) return null;

        List<BuildingData> allBuildings = api.getColonyBuildings(colonyId);
        if (allBuildings.isEmpty()) return null;

        BlockPos touristPos = t.touristPos();
        if (touristPos == null) return null;

        long dayTime = level.getDayTime() % 24000;
        boolean isNight = dayTime >= Config.TOURIST_NIGHT_START.get();
        boolean energyEmpty = t.getEnergy() <= 0;
        boolean nightHotel = isNight && !t.isFullySatisfied();

        int visionSq = Config.TOURIST_VISION_RADIUS.get() * Config.TOURIST_VISION_RADIUS.get();
        int atmCooldown = Config.TOURIST_ATM_WITHDRAW_COOLDOWN_TICKS.get();

        List<BuildingState> normal = new ArrayList<>();
        List<BuildingState> hotels = new ArrayList<>();
        for (BuildingData b : allBuildings) {
            if (b.isShutdown() || !b.isStructureIntact()) continue;
            BuildingState state = getState(level, b.getBuildingId());
            if (state == null) continue;
            BuildingConfig cfg = getConfig(level, b.getBuildingId());
            if (cfg == null || !cfg.isTouristTarget()) continue;
            if (cfg.interactSpots() == null || cfg.interactSpots().isEmpty()) continue; // 0-spot 无兜底

            // 视野（距离）过滤
            double dx = state.getAnchor().getX() - touristPos.getX();
            double dz = state.getAnchor().getZ() - touristPos.getZ();
            if (dx * dx + dz * dz > visionSq) continue;
            if (requireLoaded && !level.isLoaded(state.getAnchor())) continue;

            boolean hotel = isHotelBuilding(level, b.getBuildingId());
            if (nightHotel) {
                // 夜晚 + 未满条：优先旅店（不查 visited，白天逛过不阻挡夜晚入住）；
                // 视野内无旅店 → 回退普通建筑（尊重 visited、精力 0 只去 relax），傍晚不干晃。
                if (hotel) {
                    if (hasHotelVacancy(level, b.getBuildingId())) hotels.add(state);
                } else {
                    if (energyEmpty && (cfg.relax() == RelaxConfig.NONE || cfg.relax().energyRestore() <= 0)) continue;
                    // ATM 可重新取现 / 精力低可重复歇脚 relax 时豁免 visited；其余按 visited 门
                    if (!exemptFromVisited(t, cfg, atmCooldown) && t.hasVisitedBuilding(b.getBuildingId())) continue;
                    normal.add(state);
                }
                continue;
            }

            if (energyEmpty) {
                // 精力 0 → 只能去恢复建筑（relax.energyRestore>0）；无恢复建筑 → 闲逛（不离场）
                if (cfg.relax() == RelaxConfig.NONE || cfg.relax().energyRestore() <= 0) continue;
            }
            // ATM 可重新取现 / 精力低可重复歇脚 relax 时豁免 visited；其余按 visited 门
            if (!exemptFromVisited(t, cfg, atmCooldown) && t.hasVisitedBuilding(b.getBuildingId())) continue;
            normal.add(state);
        }
        List<BuildingState> candidates = nightHotel && !hotels.isEmpty() ? hotels : normal;
        if (candidates.isEmpty()) return null;
        return weightedPick(level, t, candidates);
    }

    /** Find-Best-Action 评分：满意度偏好（总三值增益） + 精力/钱包紧急加分 − 排队惩罚。 */
    public static double buildingScore(ServerLevel level, TouristStateHost t, BuildingState state) {
        int[] v = effectiveValues(level, state.getBuildingId());
        int[] need = {t.getComfortNeed(), t.getMagicNeed(), t.getWonderNeed()};
        int[] sat = {t.getComfortSat(), t.getMagicSat(), t.getWonderSat()};
        double score = satisfactionGain(need, sat, v, Config.TOURIST_BAR_GAIN_COEFF.get());

        BuildingConfig cfg = getConfig(level, state.getBuildingId());
        if (cfg != null) {
            // 精力低 → 偏向恢复（relax）建筑（加分与单次满意度增益同量级）
            double energyRatio = t.getEnergy() / (double) WandscapeConstants.TOURIST_MAX_ENERGY;
            boolean isRelax = cfg.relax() != RelaxConfig.NONE && cfg.relax().energyRestore() > 0;
            if (isRelax && energyRatio < Config.TOURIST_ENERGY_RESTORE_THRESHOLD.get()) {
                score += ENERGY_URGENCY_BONUS;
            }
            // 钱包低/空 + 池子有余额 + 冷却已过 → ATM 取现（取现补钱包继续逛）；
            // 池子空/冷却中不加分——免得游客因偏好跑去 ATM 却一分钱取不到。
            boolean isAtm = cfg.atm() != AtmConfig.NONE
                    && atmReusable(t, cfg.atm(), Config.TOURIST_ATM_WITHDRAW_COOLDOWN_TICKS.get());
            if (isAtm) {
                if (t.getWallet() <= 0) {
                    score += WALLET_EMPTY_BONUS;
                } else if (t.getWallet() < Math.max(1, t.getInitialWallet() / 4)) {
                    score += WALLET_LOW_BONUS;
                }
            }
            // 排队惩罚 = 等比例降权：spot 全满时按总排队人数等比缩小
            // （1 人 -25%、2 人 -50%、3 人 -75%，封顶 -75%）。多建同类型 = 排队短 = 降权轻；
            // 不再像固定 -3000 那样把满店压到权重地板，排队短的好店仍比空置低价值建筑更受欢迎。
            if (cfg.interactSpots() != null && !cfg.interactSpots().isEmpty()
                    && TouristSpotManager.getActive().isFull(state.getBuildingId(), cfg.interactSpots().size())) {
                score *= queuePenaltyMultiplier(TouristSpotManager.getActive().totalQueueLength(state.getBuildingId()));
            }
        }
        return score;
    }

    /**
     * 满意度偏好 = 这次访问能把「总三值满意度」提升多少（潜在总三值 − 现在三值）。
     * 每维增益 = min(需求缺口, round(建筑该维值 × coeff))，与 {@link #fillBars} 实际结算一致。
     *
     * <p>相比旧式「Σ 需求缺口 × 建筑值」：不会因某一维数值夸张就过度吸走游客（例如 Comfort
     * 满条的游客仍去高 Comfort 建筑——那是浪费访问，另两条永远填不满）；均衡建筑常比单维夸张
     * 建筑总增益更高，游客会优先去能把三条总满意度抬得更高的地方。
     *
     * <p>纯计算（不依赖 MC 运行时），可 JUnit 单测。
     */
    static double satisfactionGain(int[] need, int[] sat, int[] values, double coeff) {
        double gain = 0;
        for (int d = 0; d < 3; d++) {
            int gap = Math.max(0, need[d] - sat[d]);
            if (gap <= 0 || values[d] <= 0) continue;
            gain += Math.min(gap, (int) Math.round(values[d] * coeff));
        }
        return gain;
    }

    /**
     * 排队惩罚乘数：spot 全满时，按该建筑总排队人数等比降权。
     * 1 人 ×0.75、2 人 ×0.5、3 人 ×0.25，封顶 ×0.25（人再多不再加深）；0 人 ×1.0（无惩罚）。
     * 纯计算（不依赖 MC 运行时），可 JUnit 单测。
     */
    static double queuePenaltyMultiplier(int queueLen) {
        if (queueLen <= 0) return 1.0;
        return Math.max(0.25, 1.0 - 0.25 * queueLen);
    }

    @Nullable
    private static BuildingState weightedPick(ServerLevel level, TouristStateHost t,
            List<BuildingState> candidates) {
        if (candidates.isEmpty()) return null;
        double[] weights = new double[candidates.size()];
        double total = 0;
        for (int i = 0; i < candidates.size(); i++) {
            double score = Math.max(0.5, buildingScore(level, t, candidates.get(i)));
            weights[i] = score;
            total += score;
        }
        double roll = Math.random() * total;
        for (int i = 0; i < candidates.size(); i++) {
            roll -= weights[i];
            if (roll < 0) return candidates.get(i);
        }
        return candidates.get(candidates.size() - 1);
    }

    /** 旅店有空位（入住游客数 < maxOccupancy）。 */
    private static boolean hasHotelVacancy(ServerLevel level, UUID buildingId) {
        HotelStayHandler hotel = HotelStayHandler.getActive();
        if (hotel == null) return false;
        return hotel.hasVacancy(buildingId);
    }

    /**
     * 找一个可入住的旅店（实体与 sim 共用）：在殖民地全部 intact 旅店中选**水平距离最近**、
     * 有空位的（需已加载的由 {@code requireLoaded} 把关）。
     * 住店客回**自己**旅店不经过这里（各自在调用方直接解析，需区分「旅店失效解除登记」）。
     *
     * @param requireLoaded 实体寻路需要目标区块已加载；sim（直线移动）传 false
     * @return 目标旅店 BuildingState；无可用旅店返回 null
     */
    @javax.annotation.Nullable
    public static BuildingState findHotelTarget(ServerLevel level, TouristStateHost t,
            boolean requireLoaded) {
        UUID colonyId = t.getColonyId();
        if (colonyId == null) return null;
        BuildingApi api = getBuildingApi();
        if (api == null) return null;

        // 任意可用旅店：最近优先
        BlockPos touristPos = t.touristPos();
        BuildingState best = null;
        int bestDist = Integer.MAX_VALUE;
        for (BuildingData b : api.getColonyBuildings(colonyId)) {
            if (!"service".equals(b.getCategory())) continue;
            if (b.isShutdown() || !b.isStructureIntact()) continue;
            if (!isHotelBuilding(level, b.getBuildingId())) continue;
            if (requireLoaded && !level.isLoaded(b.getPosition())) continue;
            if (!hasHotelVacancy(level, b.getBuildingId())) continue;
            int d = touristPos != null
                    ? Math.abs(touristPos.getX() - b.getPosition().getX())
                      + Math.abs(touristPos.getZ() - b.getPosition().getZ())
                    : 0;
            if (d < bestDist) {
                bestDist = d;
                best = getState(level, b.getBuildingId());
            }
        }
        return best;
    }

    // ── Helpers ──

    private static UUID idOf(TouristStateHost t) {
        return t instanceof TouristShadow s ? s.getTouristId() : UUID.randomUUID();
    }

    private static String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }

    @Nullable
    private static BuildingApi getBuildingApi() {
        try {
            return WandscapeApis.getBuildingApi();
        } catch (IllegalStateException e) {
            return null;
        }
    }
}
