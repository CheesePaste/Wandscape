package com.wsteam.wandscape.api;

/**
 * 生产（配方/合成）域公开契约：craft 耗时等可调平衡值程序化访问。
 * 实现方 {@code production/internal/ProductionApiImpl}，经 {@code WandscapeApis.getProductionApi()} 装配。
 */
public interface ProductionApi {

    // ── 可调平衡值（委托 BalanceValues；运行时生效，不追溯已生成的进行中任务）──

    int getWorkstationCraftTicksPerUnit();
    void setWorkstationCraftTicksPerUnit(int v);

    int getCraftingStationCraftTicksPerUnit();
    void setCraftingStationCraftTicksPerUnit(int v);
}
