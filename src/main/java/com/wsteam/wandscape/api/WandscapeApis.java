package com.wsteam.wandscape.api;
import com.wsteam.wandscape.content.colony.service.ColonyStatusService;

import com.wsteam.wandscape.api.*;

import java.util.UUID;
public final class WandscapeApis {
    private static WandApi wandApi;
    private static ElementApi elementApi;
    private static WarehouseApi warehouseApi;
    private static NpcApi npcApi;
    private static BuildingApi buildingApi;
    private static TavernApi tavernApi;
    private static ColonyApi colonyApi;
    private static RoadApi roadApi;
    private static TouristApi touristApi;
    private static ColonyStatusApi colonyMetricsApi;
    private static ScepterApi scepterApi;

    private WandscapeApis() {}

    public static WandApi getWandApi() {
        if (wandApi == null) throw new IllegalStateException("Module WandSystem not loaded");
        return wandApi;
    }
    @javax.annotation.Nullable
    public static WandApi getWandApiSilently() { return wandApi; }
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

    public static NpcApi getNpcApi() {
        if (npcApi == null) throw new IllegalStateException("Module NpcSystem not loaded");
        return npcApi;
    }
    @javax.annotation.Nullable
    public static NpcApi getNpcApiSilently() { return npcApi; }
    public static void setNpcApi(NpcApi api) { npcApi = api; }

    private static NpcAttributesApi npcAttributesApi;
    public static NpcAttributesApi getNpcAttributesApi() {
        if (npcAttributesApi == null) throw new IllegalStateException("Module NpcSystem not loaded");
        return npcAttributesApi;
    }
    @javax.annotation.Nullable
    public static NpcAttributesApi getNpcAttributesApiSilently() { return npcAttributesApi; }
    public static void setNpcAttributesApi(NpcAttributesApi api) { npcAttributesApi = api; }

    public static BuildingApi getBuildingApi() {
        if (buildingApi == null) throw new IllegalStateException("Module BuildingCore not loaded");
        return buildingApi;
    }
    @javax.annotation.Nullable
    public static BuildingApi getBuildingApiSilently() { return buildingApi; }
    public static void setBuildingApi(BuildingApi api) { buildingApi = api; }

    public static TavernApi getTavernApi() {
        if (tavernApi == null) throw new IllegalStateException("Module TavernRecruitment not loaded");
        return tavernApi;
    }
    public static void setTavernApi(TavernApi api) { tavernApi = api; }

    public static ColonyApi getColonyApi() {
        if (colonyApi == null) throw new IllegalStateException("Module ColonyLifecycle not loaded");
        return colonyApi;
    }
    @javax.annotation.Nullable
    public static ColonyApi getColonyApiSilently() { return colonyApi; }
    public static void setColonyApi(ColonyApi api) { colonyApi = api; }

    public static RoadApi getRoadApi() {
        if (roadApi == null) throw new IllegalStateException("Module RoadSystem not loaded");
        return roadApi;
    }
    public static void setRoadApi(RoadApi api) { roadApi = api; }

    public static TouristApi getTouristApi() {
        if (touristApi == null) throw new IllegalStateException("Module TouristSystem not loaded");
        return touristApi;
    }
    @javax.annotation.Nullable
    public static TouristApi getTouristApiSilently() { return touristApi; }
    public static void setTouristApi(TouristApi api) { touristApi = api; }

    public static ColonyStatusApi getColonyStatusApi() {
        if (colonyMetricsApi == null) throw new IllegalStateException("ColonyStatusService not loaded");
        return colonyMetricsApi;
    }
    @javax.annotation.Nullable
    public static ColonyStatusApi getColonyStatusApiSilently() { return colonyMetricsApi; }
    public static void setColonyStatusApi(ColonyStatusApi api) { colonyMetricsApi = api; }

    private static ProductionApi productionApi;
    public static ProductionApi getProductionApi() {
        if (productionApi == null) throw new IllegalStateException("ProductionApi not loaded");
        return productionApi;
    }
    public static void setProductionApi(ProductionApi api) { productionApi = api; }

    /** 玩家权杖（庇护/敌对）殖民地标记查询。npc/guard 跨模块读取统一走这里（未装配返回 null 安全）。 */
    @javax.annotation.Nullable
    public static ScepterApi getScepterApiSilently() { return scepterApi; }
    public static void setScepterApi(ScepterApi api) { scepterApi = api; }

    private static GuideProgressApi guideProgressApi;
    @javax.annotation.Nullable
    public static GuideProgressApi getGuideProgressApiSilently() { return guideProgressApi; }
    public static void setGuideProgressApi(GuideProgressApi api) { guideProgressApi = api; }

    private static MagicApi magicApi;
    public static MagicApi getMagicApi() {
        if (magicApi == null) throw new IllegalStateException("MagicApi not loaded");
        return magicApi;
    }
    @javax.annotation.Nullable
    public static MagicApi getMagicApiSilently() { return magicApi; }
    public static void setMagicApi(MagicApi api) { magicApi = api; }

    /** 法师饰品（Curios）槽位/装备。仅 Curios 已加载时装配；未安装时 {@code getCurioApi} 抛、Silently 返 null。 */
    private static CurioApi curioApi;
    public static CurioApi getCurioApi() {
        if (curioApi == null) throw new IllegalStateException("CurioApi not loaded — Curios not installed or not initialized");
        return curioApi;
    }
    @javax.annotation.Nullable
    public static CurioApi getCurioApiSilently() { return curioApi; }
    public static void setCurioApi(CurioApi api) { curioApi = api; }

    /**
     * 位置所在殖民地 id（位置检测，256 格内最近殖民地原点）；殖民地 API 未就绪或位置不在
     * 任何殖民地范围内返回 null。玩家/道路/地形/调试命令发布任务时统一用此解析任务殖民地
     * 归属——保证"只能叫自己殖民地的 NPC 干活"、多殖民地不串仓库。
     */
    @javax.annotation.Nullable
    public static UUID colonyAt(net.minecraft.core.BlockPos pos) {
        if (colonyApi == null || pos == null) return null;
        return colonyApi.getColonyId(pos);
    }
}
