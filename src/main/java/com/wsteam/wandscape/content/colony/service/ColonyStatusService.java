package com.wsteam.wandscape.content.colony.service;

import com.wsteam.wandscape.impl.WandscapeEngine;
import com.wsteam.wandscape.content.colony.ColonyLevelManager;
import com.wsteam.wandscape.api.BuildingApi;
import com.wsteam.wandscape.api.BuildingApi.ColonySnapshot;
import com.wsteam.wandscape.api.ColonyStatusApi;
import com.wsteam.wandscape.content.colony.data.ColonyStatusSnapshot;
import com.wsteam.wandscape.content.element.data.ElementType;
import com.wsteam.wandscape.api.WandscapeApis;

import java.util.List;
import java.util.UUID;

/**
 * Engine-side implementation of {@link ColonyStatusApi}.
 * Aggregates data from all module APIs in a single call.
 */
public final class ColonyStatusService implements ColonyStatusApi {

    private ColonyStatusService() {}

    public static ColonyStatusService create() {
        return new ColonyStatusService();
    }

    @Override
    public ColonyStatusSnapshot getSnapshot(UUID colonyId) {
        if (colonyId == null) return ColonyStatusSnapshot.EMPTY;

        // 1. Building evaluation (single traversal)
        BuildingApi buildingApi = WandscapeApis.getBuildingApi();
        ColonySnapshot eval = buildingApi.getColonySnapshot(colonyId);
        int comfort = eval != null ? eval.comfort() : 0;
        int magic = eval != null ? eval.magic() : 0;
        int wonder = eval != null ? eval.wonder() : 0;

        // 2. Colony level and experience
        ColonyLevelManager levelMgr = WandscapeEngine.getColonyLevelManager();
        int lvl = levelMgr != null ? levelMgr.getLevel(colonyId) : 1;
        int exp = levelMgr != null ? levelMgr.getExperience(colonyId) : 0;
        String name = levelMgr != null ? levelMgr.getColonyName(colonyId) : "";

        // 3. Tourist metrics
        int touristCount = 0;
        int overnightStayerCount = 0;
        var touristApi = WandscapeApis.getTouristApiSilently();
        if (touristApi != null) {
            touristCount = touristApi.getTouristCount(colonyId);
            overnightStayerCount = touristApi.getOvernightStayerCount(colonyId);
        }

        // 4. Building list — 建筑不再因损坏或手动关闭而停摆，异常报告只保留"建造中"类别。
        int underConstructionCount = 0;
        List<UUID> underConstructionBuildingIds = List.of();
        List<String> underConstructionBuildingNames = List.of();
        List<Boolean> underConstructionStarted = List.of();
        try {
            var buildings = buildingApi.getColonyBuildings(colonyId);
            var constructing = buildings.stream().filter(b -> !b.hasEverCompleted()).toList();
            underConstructionCount = constructing.size();
            underConstructionBuildingIds = constructing.stream().map(b -> b.getBuildingId()).toList();
            underConstructionBuildingNames = constructing.stream().map(b -> b.getBuildingTypeId()).toList();
            underConstructionStarted = constructing.stream()
                    .map(b -> b.isConstructionStarted()).toList();
        } catch (Exception ignored) {
            // Building API may throw during early server startup
        }

        // 5. NPC counts
        int npcIdleCount = 0;
        int npcTotalCount = 0;
        try {
            var npcApi = WandscapeApis.getNpcApi();
            npcIdleCount = npcApi.getIdleNpcCount(colonyId);
            npcTotalCount = npcApi.getNpcCount(colonyId);
        } catch (Exception ignored) {
            // NpcApi may throw if module not loaded
        }

        // 6. Element amounts
        int earthAmount = 0, woodAmount = 0, waterAmount = 0;
        int fireAmount = 0, windAmount = 0, metalAmount = 0, darkAmount = 0;
        var warehouseApi = WandscapeApis.getWarehouseApiSilently();
        if (warehouseApi != null) {
            try {
                var elements = warehouseApi.getAllElements(colonyId);
                earthAmount = elements.getOrDefault(ElementType.EARTH, 0L).intValue();
                woodAmount = elements.getOrDefault(ElementType.WOOD, 0L).intValue();
                waterAmount = elements.getOrDefault(ElementType.WATER, 0L).intValue();
                fireAmount = elements.getOrDefault(ElementType.FIRE, 0L).intValue();
                windAmount = elements.getOrDefault(ElementType.WIND, 0L).intValue();
                metalAmount = elements.getOrDefault(ElementType.METAL, 0L).intValue();
                darkAmount = elements.getOrDefault(ElementType.DARK, 0L).intValue();
            } catch (Exception ignored) {
                // Warehouse API may throw if data not loaded yet
            }
        }

        return new ColonyStatusSnapshot(
                colonyId, comfort, magic, wonder,
                name, lvl, exp,
                touristCount, overnightStayerCount,
                npcIdleCount, npcTotalCount,
                earthAmount, woodAmount, waterAmount, fireAmount, windAmount, metalAmount, darkAmount,
                underConstructionCount, underConstructionBuildingIds,
                underConstructionBuildingNames, underConstructionStarted);
    }
}
