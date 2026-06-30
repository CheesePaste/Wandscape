package com.wsteam.wandscape.building.internal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.wsteam.wandscape.Config;
import com.wsteam.wandscape.shared.data.ElementType;
import com.wsteam.wandscape.shared.data.MaintenanceCostConfig;
import com.wsteam.wandscape.shared.data.MaintenancePriority;
import com.wsteam.wandscape.shared.event.DailySettlementEvent;
import com.wsteam.wandscape.shared.registry.WandscapeApis;
import com.wsteam.wandscape.warehouse.ColonyItemBank;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import com.wsteam.wandscape.shared.log.Log;

/**
 * Daily settlement system that replaces the old periodic heartbeat.
 *
 * <p>Fires once per Minecraft day at time-of-day 0 (sunrise).
 * Collects maintenance costs from each colony's buildings in priority order:
 * CRITICAL (node/basic/storage) → HIGH (production) → NORMAL (commerce) → LOW (service/decoration).
 *
 * <p>Buildings that cannot pay are shut down with category-specific graded penalties.
 * When surplus permits, shutdown buildings are automatically restarted.
 */
public final class DailySettlementSystem {
    private static final String TAG = "DailySettlementSystem";

    private static final String SHUTDOWN_REASON_MAINTENANCE = "maintenance";

    /** Cache mapping building category → priority tier. */
    private static final Map<String, MaintenancePriority> CATEGORY_PRIORITY = new HashMap<>();
    static {
        CATEGORY_PRIORITY.put("node", MaintenancePriority.CRITICAL);
        CATEGORY_PRIORITY.put("basic", MaintenancePriority.CRITICAL);
        CATEGORY_PRIORITY.put("storage", MaintenancePriority.CRITICAL);
        CATEGORY_PRIORITY.put("workstation", MaintenancePriority.HIGH);
        CATEGORY_PRIORITY.put("crafting_station", MaintenancePriority.HIGH);
        CATEGORY_PRIORITY.put("potion_station", MaintenancePriority.HIGH);
        CATEGORY_PRIORITY.put("shop", MaintenancePriority.NORMAL);
        CATEGORY_PRIORITY.put("tavern", MaintenancePriority.NORMAL);
        // service, decoration, wonder, and any unknown category → LOW
    }

    private long settledDay = -1;

    private DailySettlementSystem() {}

    public static DailySettlementSystem register() {
        DailySettlementSystem system = new DailySettlementSystem();
        NeoForge.EVENT_BUS.register(system);
        Log.info(TAG, "DailySettlementSystem registered");
        return system;
    }

    // ── Tick handler ──

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        ServerLevel level = server.overworld();
        if (level == null) return;

        long dayTime = level.getDayTime();
        long currentDay = dayTime / 24000;

        // Already settled this day
        if (currentDay == settledDay) return;

        // Wait until we're within the settlement window (time-of-day near 0)
        int tod = (int) (dayTime % 24000);
        int window = Config.SETTLEMENT_WINDOW_TICKS.get();
        if (tod > window) return;

        settledDay = currentDay;
        doSettlement(level, currentDay);
    }

    // ── Core settlement logic ──

    private void doSettlement(ServerLevel level, long day) {
        BuildingSavedData savedData = BuildingSavedData.get(level);
        ColonyItemBank bank = ColonyItemBank.get(level);
        long currentTick = level.getGameTime();
        long gracePeriodTicks = Config.MAINTENANCE_GRACE_PERIOD_TICKS.get();

        // Group non-shutdown buildings by colony
        Map<UUID, List<BuildingState>> byColony = new HashMap<>();
        for (BuildingState state : savedData.getAllBuildings()) {
            if (state.isShutdown()) continue;
            UUID colonyId = state.getColonyId();
            if (colonyId == null) continue;

            byColony.computeIfAbsent(colonyId, k -> new ArrayList<>()).add(state);
        }

        // Settle each colony independently
        for (var entry : byColony.entrySet()) {
            UUID colonyId = entry.getKey();
            List<BuildingState> buildings = entry.getValue();
            settleColony(colonyId, buildings, savedData, bank, currentTick, day);
        }
    }

    private void settleColony(UUID colonyId, List<BuildingState> buildings,
                              BuildingSavedData savedData, ColonyItemBank bank,
                              long currentTick, long day) {
        long gracePeriodTicks = Config.MAINTENANCE_GRACE_PERIOD_TICKS.get();
        boolean autoRestart = Config.AUTO_RESTART_SHUTDOWN.get();

        // Snapshot reserves before settlement
        Map<ElementType, Long> reservesBefore = bank.getElementSnapshot(colonyId);

        // Sort active buildings by priority, then by building type ID for determinism
        buildings.sort(Comparator
                .comparingInt((BuildingState b) -> priorityFor(b).ordinal())
                .thenComparing(BuildingState::getBuildingTypeId));

        // Track results
        Map<ElementType, Long> totalConsumed = new HashMap<>();
        List<DailySettlementEvent.BuildingSettlementResult> results = new ArrayList<>();
        List<BuildingState> maintenanceShutdownBuildings = new ArrayList<>();

        // Phase 1: Pay maintenance for active buildings
        for (BuildingState state : buildings) {
            if (state.isShutdown()) continue;

            MaintenanceCostConfig cost = state.getMaintenanceCost();
            if (cost.costs().isEmpty()) {
                results.add(new DailySettlementEvent.BuildingSettlementResult(
                        state.getBuildingId(), state.getBuildingTypeId(), state.getCategory(),
                        true, false, false));
                state.setLastSettlementDay(day);
                state.setMaintenancePaid(true);
                savedData.setDirty();
                continue;
            }

            // Grace period: skip buildings placed recently
            if (state.getLastSettlementDay() == 0) {
                if (state.getLastMaintenanceTick() == 0) {
                    // Brand new: mark placement time, skip this settlement
                    state.setLastMaintenanceTick(currentTick);
                    state.setLastSettlementDay(day);
                    savedData.setDirty();
                    results.add(new DailySettlementEvent.BuildingSettlementResult(
                            state.getBuildingId(), state.getBuildingTypeId(), state.getCategory(),
                            true, false, false));
                    continue;
                }
                if (currentTick - state.getLastMaintenanceTick() < gracePeriodTicks) {
                    // Still in grace period
                    state.setLastSettlementDay(day);
                    savedData.setDirty();
                    results.add(new DailySettlementEvent.BuildingSettlementResult(
                            state.getBuildingId(), state.getBuildingTypeId(), state.getCategory(),
                            true, false, false));
                    continue;
                }
            }

            // Check if colony can afford this building's maintenance
            boolean canPay = true;
            for (var costEntry : cost.costs().entrySet()) {
                ElementType elem = costEntry.getKey();
                long needed = costEntry.getValue();
                if (bank.countElement(colonyId, elem) < needed) {
                    canPay = false;
                    break;
                }
            }

            if (canPay) {
                // Deduct elements
                for (var costEntry : cost.costs().entrySet()) {
                    ElementType elem = costEntry.getKey();
                    int amount = costEntry.getValue();
                    bank.consumeElement(colonyId, elem, amount);
                    totalConsumed.merge(elem, (long) amount, Long::sum);
                }
                state.setMaintenancePaid(true);
                state.setLastMaintenanceTick(currentTick);
                state.setLastSettlementDay(day);
                savedData.setDirty();

                results.add(new DailySettlementEvent.BuildingSettlementResult(
                        state.getBuildingId(), state.getBuildingTypeId(), state.getCategory(),
                        true, false, false));

                Log.debug(TAG, "[Settlement] {} paid maintenance: {}",
                        state.getBuildingId().toString().substring(0, 8), cost.costs());
            } else {
                // Shutdown
                com.wsteam.wandscape.shared.registry.WandscapeApis.getBuildingApi()
                        .shutdown(state.getBuildingId(), SHUTDOWN_REASON_MAINTENANCE);
                maintenanceShutdownBuildings.add(state);

                results.add(new DailySettlementEvent.BuildingSettlementResult(
                        state.getBuildingId(), state.getBuildingTypeId(), state.getCategory(),
                        false, true, false));

                Log.warn(TAG, "[Settlement] {} cannot pay maintenance — shut down",
                        state.getBuildingId().toString().substring(0, 8));
            }
        }

        // Phase 2: Auto-restart shutdown buildings if surplus elements available
        List<BuildingState> restartedBuildings = new ArrayList<>();
        if (autoRestart && !maintenanceShutdownBuildings.isEmpty()) {
            // Collect all shutdown buildings in this colony with reason=maintenance and intact structure
            List<BuildingState> restartable = new ArrayList<>();
            for (BuildingState state : savedData.getAllBuildings()) {
                if (!state.isShutdown()) continue;
                if (!SHUTDOWN_REASON_MAINTENANCE.equals(state.getShutdownReason())) continue;
                if (!state.isStructureIntact()) continue;
                if (!colonyId.equals(state.getColonyId())) continue;
                if (state.getMaintenanceCost().costs().isEmpty()) continue;
                restartable.add(state);
            }

            // Sort by priority (most important first)
            restartable.sort(Comparator
                    .comparingInt((BuildingState b) -> priorityFor(b).ordinal())
                    .thenComparing(BuildingState::getBuildingTypeId));

            for (BuildingState state : restartable) {
                MaintenanceCostConfig cost = state.getMaintenanceCost();
                boolean canAfford = true;
                for (var costEntry : cost.costs().entrySet()) {
                    if (bank.countElement(colonyId, costEntry.getKey()) < costEntry.getValue()) {
                        canAfford = false;
                        break;
                    }
                }
                if (!canAfford) break; // No more surplus for further restarts

                // Deduct maintenance and restart
                for (var costEntry : cost.costs().entrySet()) {
                    bank.consumeElement(colonyId, costEntry.getKey(), costEntry.getValue());
                    totalConsumed.merge(costEntry.getKey(), (long) costEntry.getValue(), Long::sum);
                }
                com.wsteam.wandscape.shared.registry.WandscapeApis.getBuildingApi()
                        .restart(state.getBuildingId());
                state.setLastMaintenanceTick(currentTick);
                state.setLastSettlementDay(day);
                state.setMaintenancePaid(true);
                restartedBuildings.add(state);

                Log.info(TAG, "[Settlement] {} restarted (surplus elements available)",
                        state.getBuildingId().toString().substring(0, 8));
            }
        }

        // Snapshot reserves after settlement
        Map<ElementType, Long> reservesAfter = bank.getElementSnapshot(colonyId);

        // Fire settlement event
        DailySettlementEvent.SettlementReport report = new DailySettlementEvent.SettlementReport(
                colonyId, day, Map.copyOf(totalConsumed),
                List.copyOf(results), reservesBefore, reservesAfter);
        NeoForge.EVENT_BUS.post(new DailySettlementEvent(report));

        if (!results.isEmpty()) {
            long paidCount = results.stream().filter(r -> r.paid() && !r.wasRestarted()).count();
            Log.info(TAG, "[Settlement] Colony {} day {}: {} buildings paid, {} shutdown, {} restarted",
                    colonyId.toString().substring(0, 8), day,
                    paidCount, maintenanceShutdownBuildings.size(), restartedBuildings.size());
        }
    }

    // ── Priority helpers ──

    static MaintenancePriority priorityFor(BuildingState state) {
        return CATEGORY_PRIORITY.getOrDefault(state.getCategory(), MaintenancePriority.LOW);
    }

    static MaintenancePriority priorityForCategory(String category) {
        return CATEGORY_PRIORITY.getOrDefault(category, MaintenancePriority.LOW);
    }
}
