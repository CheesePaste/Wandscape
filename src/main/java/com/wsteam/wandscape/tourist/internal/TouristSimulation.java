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
 * <p>Goal 语义：填三条无惩罚；目标选择 = Find-Best-Action（需求缺口 × 建筑值 + 精力/钱包紧急加分
 * − 排队惩罚），只看视野内；spot 单点交互、spot 数量 = 同时交互人数上限（全满排队）。
 */
public final class TouristSimulation {

    private static final String TAG = "TouristSimulation";

    /** 精力低于此比例 → relax 建筑紧急加分（Config.TOURIST_ENERGY_RESTORE_THRESHOLD 的补充启发值）。 */
    private static final double ENERGY_URGENCY_BONUS = 2000;
    /** 钱包低于初始 1/4 → ATM 紧急加分。 */
    private static final double WALLET_LOW_BONUS = 2000;
    /** 钱包=0 → ATM 大幅加分（优先取现继续逛）。 */
    private static final double WALLET_EMPTY_BONUS = 4000;
    /** spot 全满 → 排队惩罚（有空位建筑优先；全满仍可排队）。 */
    private static final double QUEUE_PENALTY = 1500;

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

    /** 认领一个空 spot 并占用；全满返回 -1（游客排队等待）。 */
    public static int claimSpot(ServerLevel level, UUID buildingId) {
        int total = interactSpotCount(level, buildingId);
        if (total <= 0) return -1;
        return TouristSpotManager.getActive().claim(buildingId, total);
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

    /** ATM visit: withdraw from travelFund into the wallet (capped), fill bars. */
    @Nullable
    public static InteractionResult performAtmInteraction(ServerLevel level,
            TouristStateHost t, UUID buildingId, UUID colonyId) {
        BuildingConfig cfg = getConfig(level, buildingId);
        if (cfg == null || cfg.atm() == AtmConfig.NONE) return null;

        var a = cfg.atm();
        int amount = Math.min(a.withdrawAmount(), t.getTravelFund());
        if (amount > 0) {
            t.setWallet(t.getWallet() + amount);
            t.setTravelFund(t.getTravelFund() - amount);
        }
        int[] delta = fillBars(level, t, buildingId);
        return new InteractionResult(null, delta[0], delta[1], delta[2], 0, "取钱 " + amount);
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
     *   <li>评分 = Σ(需求缺口 × 建筑该维值) + 精力紧急加分（relax）+ 钱包紧急加分（atm）− 排队惩罚（spot 全满）。</li>
     *   <li>精力 0 → 只能去 relax.energyRestore()>0 建筑。</li>
     *   <li>夜晚且未满条 → 去旅店（service.maxOccupancy>0 且有空位）；满条夜晚由离场逻辑处理。</li>
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
        boolean isNight = dayTime >= 13000;
        boolean energyEmpty = t.getEnergy() <= 0;
        boolean nightHotel = isNight && !t.isFullySatisfied();

        int visionSq = Config.TOURIST_VISION_RADIUS.get() * Config.TOURIST_VISION_RADIUS.get();

        List<BuildingState> candidates = new ArrayList<>();
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

            // visited（旅店豁免：夜晚入住不应被白天逛过阻挡）
            boolean hotel = isHotelBuilding(level, b.getBuildingId());
            if (!nightHotel && t.hasVisitedBuilding(b.getBuildingId())) continue;

            if (nightHotel) {
                if (!hotel || !hasHotelVacancy(level, b.getBuildingId())) continue;
            } else if (energyEmpty) {
                // 精力 0 → 只能去恢复建筑（relax.energyRestore>0）；无恢复建筑 → 闲逛（不离场）
                if (cfg.relax() == RelaxConfig.NONE || cfg.relax().energyRestore() <= 0) continue;
            }
            candidates.add(state);
        }
        if (candidates.isEmpty()) return null;
        return weightedPick(level, t, candidates);
    }

    /** Find-Best-Action 评分：Σ(需求缺口×建筑值) + 精力/钱包紧急加分 − 排队惩罚。 */
    public static double buildingScore(ServerLevel level, TouristStateHost t, BuildingState state) {
        int[] v = effectiveValues(level, state.getBuildingId());
        int[] need = {t.getComfortNeed(), t.getMagicNeed(), t.getWonderNeed()};
        int[] sat = {t.getComfortSat(), t.getMagicSat(), t.getWonderSat()};
        double score = 0;
        for (int d = 0; d < 3; d++) {
            int gap = Math.max(0, need[d] - sat[d]);
            score += gap * v[d];
        }

        BuildingConfig cfg = getConfig(level, state.getBuildingId());
        if (cfg != null) {
            // 精力低 → 强烈偏向恢复（relax）建筑
            double energyRatio = t.getEnergy() / (double) WandscapeConstants.TOURIST_MAX_ENERGY;
            boolean isRelax = cfg.relax() != RelaxConfig.NONE && cfg.relax().energyRestore() > 0;
            if (isRelax && energyRatio < Config.TOURIST_ENERGY_RESTORE_THRESHOLD.get()) {
                score += ENERGY_URGENCY_BONUS;
            }
            // 钱包低/空 → ATM 取现（取现补钱包继续逛）
            boolean isAtm = cfg.atm() != AtmConfig.NONE && cfg.atm().withdrawAmount() > 0;
            if (isAtm) {
                if (t.getWallet() <= 0) {
                    score += WALLET_EMPTY_BONUS;
                } else if (t.getWallet() < Math.max(1, t.getInitialWallet() / 4)) {
                    score += WALLET_LOW_BONUS;
                }
            }
            // 排队惩罚：spot 全满减分（多建同类型 = 有空位 → 排队短）
            if (cfg.interactSpots() != null && !cfg.interactSpots().isEmpty()
                    && TouristSpotManager.getActive().isFull(state.getBuildingId(), cfg.interactSpots().size())) {
                score -= QUEUE_PENALTY;
            }
        }
        return score;
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
