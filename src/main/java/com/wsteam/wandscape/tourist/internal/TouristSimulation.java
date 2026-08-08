package com.wsteam.wandscape.tourist.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

import com.wsteam.wandscape.Config;
import com.wsteam.wandscape.building.data.BuildingConfig;
import com.wsteam.wandscape.building.internal.BuildingConfigLoader;
import com.wsteam.wandscape.building.internal.BuildingSavedData;
import com.wsteam.wandscape.building.internal.BuildingState;
import com.wsteam.wandscape.building.internal.ShopInteractionHandler;
import com.wsteam.wandscape.building.internal.ShopStockManager;
import com.wsteam.wandscape.shared.api.BuildingApi;
import com.wsteam.wandscape.shared.data.BuildingData;
import com.wsteam.wandscape.shared.data.ElementType;
import com.wsteam.wandscape.shared.registry.WandscapeApis;
import com.wsteam.wandscape.warehouse.ColonyItemBank;
import com.wsteam.wandscape.shared.log.Log;

import net.minecraft.server.level.ServerLevel;

/**
 * Shared tourist interaction economy, operating on {@link TouristStateHost}.
 *
 * <p>Used by both the physical {@code TouristEntity} (via {@code TouristMoveGoal})
 * and the unloaded sim ({@link TouristSimSystem}) so satisfaction / energy /
 * wallet / cooldown / target-selection stay single-source. All side effects go
 * through SavedData-backed systems ({@code ShopStockManager}, {@code ColonyItemBank},
 * {@code BuildingSavedData}) — none require a loaded chunk.
 */
public final class TouristSimulation {

    private static final String TAG = "TouristSimulation";

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
        return BuildingConfigLoader.getInstance().get(state.getBuildingTypeId());
    }

    @Nullable
    public static String getBuildingTypeId(ServerLevel level, UUID buildingId) {
        BuildingState state = getState(level, buildingId);
        return state != null ? state.getBuildingTypeId() : null;
    }

    public static boolean isHotelBuilding(ServerLevel level, UUID buildingId) {
        BuildingConfig cfg = getConfig(level, buildingId);
        return cfg != null && cfg.service() != null && cfg.service().maxOccupancy() > 0;
    }

    // ── Effective values / match score / satisfaction gain ──

    public static int[] effectiveValues(ServerLevel level, UUID buildingId) {
        BuildingConfig cfg = getConfig(level, buildingId);
        if (cfg == null) return new int[]{0, 0, 0};
        int c = cfg.comfort();
        int m = cfg.magic();
        int w = cfg.wonder();
        if ("shop".equals(cfg.category())) {
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

    public static int matchScore(ServerLevel level, TouristStateHost t, UUID buildingId) {
        String typeId = getBuildingTypeId(level, buildingId);
        if (typeId == null) return 0;
        return t.getTypePreference(typeId) * threeValueSum(level, buildingId);
    }

    public static int satisfactionGain(ServerLevel level, TouristStateHost t, UUID buildingId) {
        int threeSum = threeValueSum(level, buildingId);
        int threshold = t.getLevel() * Config.TOURIST_LEVEL_SATISFACTION_THRESHOLD.get();
        String typeId = getBuildingTypeId(level, buildingId);
        int typePref = typeId != null ? t.getTypePreference(typeId) : 50;

        if (threeSum < threshold) {
            int deficit = threshold - threeSum;
            int baseScore = typePref * (deficit + 1);
            int penalty = -(int) Math.sqrt(baseScore);
            return Math.max(penalty, -15);
        }
        int baseScore = typePref * (threeSum - threshold + 1);
        int gain = (int) Math.sqrt(baseScore);
        return Math.min(gain, Config.TOURIST_MAX_SATISFACTION_PER_VISIT.get());
    }

    public static int interactionDuration(ServerLevel level, UUID buildingId) {
        BuildingConfig cfg = getConfig(level, buildingId);
        if (cfg == null) return 0;
        if ("shop".equals(cfg.category()) && cfg.shop() != null) {
            return cfg.shop().interactionDurationTicks();
        }
        if ("service".equals(cfg.category()) && cfg.service() != null) {
            return cfg.service().interactionDurationTicks();
        }
        return 0;
    }

    public static void applyPreferenceDecay(ServerLevel level, TouristStateHost t, UUID buildingId) {
        int decay = Config.TOURIST_PREFERENCE_DECAY.get();
        if (decay <= 0) return;
        String typeId = getBuildingTypeId(level, buildingId);
        if (typeId == null) return;
        t.adjustTypePreference(typeId, -decay);
    }

    public static void applyInteractionCooldown(ServerLevel level, TouristStateHost t, UUID buildingId) {
        int cooldownTicks = interactionDuration(level, buildingId);
        if (cooldownTicks <= 0) return;
        int end = t.timeBase() + cooldownTicks;
        t.setServiceCooldown(buildingId, end);
        t.setServiceCooldownEndTick(end);
    }

    // ── Interactions ──

    /** Result of a shop/service visit — used for the journey diary, bubbles and narratives. */
    public record InteractionResult(@Nullable ShopStockManager.PurchaseResult purchase,
            int satBefore, int satDelta, int energyDelta, String whatHappened) {
    }

    /** Shop visit: buy with the universal wallet, apply satisfaction/energy/preference/cooldown. */
    @Nullable
    public static InteractionResult performShopInteraction(ServerLevel level,
            TouristStateHost t, UUID buildingId, UUID colonyId) {
        ShopStockManager stock = ShopStockManager.getActive();
        if (stock == null) return null;

        ShopStockManager.PurchaseResult purchase = ShopInteractionHandler.interact(
                stock, idOf(t), buildingId, colonyId, t.getWallet(), t.getInitialWallet());

        // 照常结算：买不起/没货也涨满意度、扣精力、衰减偏好并进入冷却，
        // 只是行程记成「进去逛了一圈，什么也没买」。
        int satBefore = t.getSatisfaction();
        int gain = satisfactionGain(level, t, buildingId);
        t.setSatisfaction(satBefore + gain);
        t.setEnergy(t.getEnergy() - 20);
        applyPreferenceDecay(level, t, buildingId);
        applyInteractionCooldown(level, t, buildingId);

        if (purchase == null) {
            return new InteractionResult(null, satBefore, gain, -20, "进去逛了一圈，什么也没买");
        }
        t.spendWallet(purchase.spent());
        String what = purchase.count() > 1
                ? purchase.itemId() + " ×" + purchase.count()
                : purchase.itemId();
        return new InteractionResult(purchase, satBefore, gain, -20, what);
    }

    /** Service visit: consume energy, gain satisfaction, emit element output to the colony bank. */
    @Nullable
    public static InteractionResult performServiceInteraction(ServerLevel level,
            TouristStateHost t, UUID buildingId, UUID colonyId) {
        BuildingConfig cfg = getConfig(level, buildingId);
        if (cfg == null || cfg.service() == null) return null;

        var svc = cfg.service();
        int satBefore = t.getSatisfaction();
        t.setEnergy(t.getEnergy() - svc.energyPerUse());
        int gain = satisfactionGain(level, t, buildingId);
        t.setSatisfaction(satBefore + gain);
        applyPreferenceDecay(level, t, buildingId);
        applyInteractionCooldown(level, t, buildingId);

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
        return new InteractionResult(null, satBefore, gain, -svc.energyPerUse(), "服务");
    }

    /** Mark a visit memory on the host (journey diary). Returns the memory for narrative use. */
    public static com.wsteam.wandscape.shared.data.VisitMemory addVisitMemory(TouristStateHost t,
            @Nullable String buildingTypeId, @Nullable String displayName, String category, long gameTime,
            int satBefore, int satDelta, int energyDelta, String whatHappened) {
        String type = buildingTypeId != null ? buildingTypeId : "unknown";
        String name = displayName != null && !displayName.isEmpty() ? displayName : type;
        com.wsteam.wandscape.shared.data.VisitMemory memory = new com.wsteam.wandscape.shared.data.VisitMemory(
                type, name, category, gameTime, satBefore, satDelta, energyDelta, whatHappened,
                com.wsteam.wandscape.shared.data.Emotion.fromDelta(satDelta));
        t.addVisitMemory(memory);
        return memory;
    }

    // ── Target selection (mirrors TouristMoveGoal.planNextBuilding) ──

    /**
     * Pick the next shop/service/hotel building for the tourist, weighted by
     * type preference × building stats. Returns null when nothing qualifies
     * (tourist wanders / goes idle until the sim picks again).
     */
    @Nullable
    public static BuildingState selectNextTarget(ServerLevel level, TouristStateHost t) {
        UUID colonyId = t.getColonyId();
        if (colonyId == null) return null;
        BuildingApi api = getBuildingApi();
        if (api == null) return null;

        List<BuildingData> allBuildings = api.getColonyBuildings(colonyId);
        if (allBuildings.isEmpty()) return null;

        long dayTime = level.getDayTime() % 24000;
        boolean isNight = dayTime >= 13000;
        boolean inRestCooldown = t.getServiceCooldownEndTick() > t.timeBase();

        List<BuildingState> shopTargets = new ArrayList<>();
        List<BuildingState> serviceTargets = new ArrayList<>();
        List<BuildingState> hotelTargets = new ArrayList<>();

        for (BuildingData b : allBuildings) {
            String cat = b.getCategory();
            if (!"shop".equals(cat) && !"service".equals(cat)) continue;
            if (b.isShutdown() || !b.isStructureIntact()) continue;
            boolean nightHotel = isNight && "service".equals(cat) && isHotelBuilding(level, b.getBuildingId());
            if (!nightHotel && t.hasVisitedBuilding(b.getBuildingId())) continue;
            if ("service".equals(cat) && t.getServiceCooldown(b.getBuildingId()) > t.timeBase()) continue;

            BuildingState state = getState(level, b.getBuildingId());
            if (state == null) continue;

            if ("shop".equals(cat)) {
                ShopStockManager stock = ShopStockManager.getActive();
                if (stock != null && stock.hasStock(b.getBuildingId())) {
                    shopTargets.add(state);
                }
            } else {
                if (inRestCooldown) continue;
                if (isHotelBuilding(level, b.getBuildingId())) {
                    if (isNight) {
                        HotelStayHandler hotel = HotelStayHandler.getActive();
                        if (hotel != null && hotel.hasVacancy(b.getBuildingId())) {
                            hotelTargets.add(state);
                        }
                    } else {
                        serviceTargets.add(state);
                    }
                } else {
                    serviceTargets.add(state);
                }
            }
        }

        int sat = t.getSatisfaction();
        if (isNight && sat >= 50 && sat < 100 && !hotelTargets.isEmpty()) {
            return weightedPick(level, t, hotelTargets);
        }
        if (!shopTargets.isEmpty()) {
            return weightedPick(level, t, shopTargets);
        }
        if (!serviceTargets.isEmpty()) {
            return weightedPick(level, t, serviceTargets);
        }
        return null;
    }

    @Nullable
    private static BuildingState weightedPick(ServerLevel level, TouristStateHost t,
            List<BuildingState> candidates) {
        if (candidates.isEmpty()) return null;
        int[] weights = new int[candidates.size()];
        int total = 0;
        for (int i = 0; i < candidates.size(); i++) {
            int score = Math.max(1, matchScore(level, t, candidates.get(i).getBuildingId()));
            weights[i] = score;
            total += score;
        }
        int roll = (int) (Math.random() * total);
        for (int i = 0; i < candidates.size(); i++) {
            roll -= weights[i];
            if (roll < 0) return candidates.get(i);
        }
        return candidates.get(candidates.size() - 1);
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
