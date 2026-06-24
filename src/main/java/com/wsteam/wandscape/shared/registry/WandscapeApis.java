package com.wsteam.wandscape.shared.registry;

import com.wsteam.wandscape.shared.api.*;

public final class WandscapeApis {
    private static WandApi wandApi;
    private static ElementApi elementApi;
    private static WarehouseApi warehouseApi;
    private static TaskApi taskApi;
    private static NpcApi npcApi;
    private static BuildingApi buildingApi;
    private static HouseApi houseApi;
    private static TavernApi tavernApi;
    private static AtomicExecutor atomicExecutor;
    private static ColonyApi colonyApi;
    private static ManaPoolApi manaPoolApi;
    private static RoadApi roadApi;

    private WandscapeApis() {}

    public static WandApi getWandApi() {
        if (wandApi == null) throw new IllegalStateException("Module WandSystem not loaded");
        return wandApi;
    }
    public static void setWandApi(WandApi api) { wandApi = api; }

    public static ElementApi getElementApi() {
        if (elementApi == null) throw new IllegalStateException("Module ElementSystem not loaded");
        return elementApi;
    }
    public static void setElementApi(ElementApi api) { elementApi = api; }

    public static WarehouseApi getWarehouseApi() {
        if (warehouseApi == null) throw new IllegalStateException("Module WarehouseSystem not loaded");
        return warehouseApi;
    }
    @javax.annotation.Nullable
    public static WarehouseApi getWarehouseApiSilently() { return warehouseApi; }
    public static void setWarehouseApi(WarehouseApi api) { warehouseApi = api; }

    public static TaskApi getTaskApi() {
        if (taskApi == null) throw new IllegalStateException("Module TaskSystem not loaded");
        return taskApi;
    }
    @javax.annotation.Nullable
    public static TaskApi getTaskApiSilently() { return taskApi; }
    public static void setTaskApi(TaskApi api) { taskApi = api; }

    public static NpcApi getNpcApi() {
        if (npcApi == null) throw new IllegalStateException("Module NpcSystem not loaded");
        return npcApi;
    }
    public static void setNpcApi(NpcApi api) { npcApi = api; }

    public static BuildingApi getBuildingApi() {
        if (buildingApi == null) throw new IllegalStateException("Module BuildingCore not loaded");
        return buildingApi;
    }
    public static void setBuildingApi(BuildingApi api) { buildingApi = api; }

    public static HouseApi getHouseApi() {
        if (houseApi == null) throw new IllegalStateException("Module HousingManaPool not loaded");
        return houseApi;
    }
    public static void setHouseApi(HouseApi api) { houseApi = api; }

    public static TavernApi getTavernApi() {
        if (tavernApi == null) throw new IllegalStateException("Module TavernRecruitment not loaded");
        return tavernApi;
    }
    public static void setTavernApi(TavernApi api) { tavernApi = api; }

    public static AtomicExecutor getAtomicExecutor() {
        if (atomicExecutor == null) throw new IllegalStateException("Module AtomicOperations not loaded");
        return atomicExecutor;
    }
    public static void setAtomicExecutor(AtomicExecutor executor) { atomicExecutor = executor; }

    public static ColonyApi getColonyApi() {
        if (colonyApi == null) throw new IllegalStateException("Module ColonyLifecycle not loaded");
        return colonyApi;
    }
    @javax.annotation.Nullable
    public static ColonyApi getColonyApiSilently() { return colonyApi; }
    public static void setColonyApi(ColonyApi api) { colonyApi = api; }

    public static ManaPoolApi getManaPoolApi() {
        if (manaPoolApi == null) throw new IllegalStateException("Module HousingManaPool not loaded");
        return manaPoolApi;
    }
    public static void setManaPoolApi(ManaPoolApi api) { manaPoolApi = api; }

    public static RoadApi getRoadApi() {
        if (roadApi == null) throw new IllegalStateException("Module RoadSystem not loaded");
        return roadApi;
    }
    public static void setRoadApi(RoadApi api) { roadApi = api; }
}
