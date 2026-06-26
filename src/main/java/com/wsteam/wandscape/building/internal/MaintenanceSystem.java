package com.wsteam.wandscape.building.internal;

import java.util.UUID;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.wsteam.wandscape.Config;
import com.wsteam.wandscape.shared.data.ElementType;
import com.wsteam.wandscape.shared.data.MaintenanceCostConfig;
import com.wsteam.wandscape.shared.event.MaintenanceDueEvent;
import com.wsteam.wandscape.shared.event.MaintenanceTickEvent;
import com.wsteam.wandscape.warehouse.ColonyItemBank;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * Periodic maintenance cost system.
 *
 * <p>Every {@link Config#MAINTENANCE_HEARTBEAT_TICKS}, scans all non-shutdown
 * buildings and deducts element costs from the colony bank. Buildings within
 * their grace period are skipped. If a building cannot pay, it is shut down
 * with category-specific graded penalties.
 */
public final class MaintenanceSystem {
    private static final Logger LOGGER = LogUtils.getLogger();

    private int tickCounter;

    private MaintenanceSystem() {}

    public static void register() {
        NeoForge.EVENT_BUS.register(new MaintenanceSystem());
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        ServerLevel level = server.overworld();
        if (level == null) return;

        tickCounter++;
        int heartbeat = Config.MAINTENANCE_HEARTBEAT_TICKS.get();
        if (tickCounter % heartbeat != 0) return;

        BuildingSavedData savedData = BuildingSavedData.get(level);
        ColonyItemBank bank = ColonyItemBank.get(level);
        long currentTick = level.getGameTime();
        long gracePeriod = Config.MAINTENANCE_GRACE_PERIOD_TICKS.get();

        for (BuildingState state : savedData.getAllBuildings()) {
            if (state.isShutdown()) continue;

            UUID buildingId = state.getBuildingId();
            UUID colonyId = state.getColonyId();
            if (colonyId == null) continue;

            // Grace period: skip newly placed buildings
            long lastTick = state.getLastMaintenanceTick();
            if (lastTick == 0) {
                // First time: set last tick to now so grace period starts from placement
                state.setLastMaintenanceTick(currentTick);
                savedData.setDirty();
                continue;
            }
            if (currentTick - lastTick < gracePeriod) continue;

            MaintenanceCostConfig cost = state.getMaintenanceCost();
            if (cost.costs().isEmpty()) continue;

            // Check if a payment cycle is due
            if (currentTick - lastTick < cost.intervalTicks()) continue;

            // Fire pre-payment event
            NeoForge.EVENT_BUS.post(new MaintenanceDueEvent(buildingId, colonyId, cost));
            NeoForge.EVENT_BUS.post(new MaintenanceTickEvent(colonyId));

            // Attempt to deduct each element cost
            boolean canPay = true;
            for (var entry : cost.costs().entrySet()) {
                ElementType element = entry.getKey();
                int amount = entry.getValue();
                if (bank.countElement(colonyId, element) < amount) {
                    canPay = false;
                    break;
                }
            }

            if (canPay) {
                for (var entry : cost.costs().entrySet()) {
                    bank.consumeElement(colonyId, entry.getKey(), entry.getValue());
                }
                state.setMaintenancePaid(true);
                state.setLastMaintenanceTick(currentTick);
                savedData.setDirty();
                LOGGER.debug("[Maintenance] Building {} paid maintenance: {}",
                        buildingId.toString().substring(0, 8), cost.costs());
            } else {
                LOGGER.warn("[Maintenance] Building {} cannot pay maintenance — shutting down",
                        buildingId.toString().substring(0, 8));
                // Delegate shutdown to BuildingApi so graded penalties are applied
                com.wsteam.wandscape.shared.registry.WandscapeApis.getBuildingApi()
                        .shutdown(buildingId);
            }
        }
    }
}
