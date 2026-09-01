package com.wsteam.wandscape.content.production.internal;

import com.wsteam.wandscape.api.ProductionApi;
import com.wsteam.wandscape.foundation.util.BalanceValues;

/**
 * {@link ProductionApi} 实现：craft 耗时可调值，委托 {@link BalanceValues}。
 */
public final class ProductionApiImpl implements ProductionApi {

    @Override
    public int getWorkstationCraftTicksPerUnit() { return BalanceValues.workstationCraftTicksPerUnit(); }
    @Override
    public void setWorkstationCraftTicksPerUnit(int v) { BalanceValues.setWorkstationCraftTicksPerUnit(v); }
    @Override
    public int getCraftingStationCraftTicksPerUnit() { return BalanceValues.craftingStationCraftTicksPerUnit(); }
    @Override
    public void setCraftingStationCraftTicksPerUnit(int v) { BalanceValues.setCraftingStationCraftTicksPerUnit(v); }
}
