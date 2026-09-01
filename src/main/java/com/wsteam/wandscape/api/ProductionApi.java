package com.wsteam.wandscape.api;

import com.wsteam.wandscape.content.element.data.ElementType;

import java.util.List;
import java.util.Map;
import java.util.UUID;

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

    // ── 未实现（重设计阶段声明，见 @Unimplemented）──

    /** 殖民地当前已解锁的配方 id 列表。 */
    @Unimplemented("重设计阶段——待接入 RecipeUnlockChecker/ProductionRecipeLoader")
    default List<String> getUnlockedRecipes(UUID colonyId) {
        throw new UnsupportedOperationException("ProductionApi.getUnlockedRecipes not yet implemented");
    }

    /** 配方所需元素成本（空 map 表示配方未知）。 */
    @Unimplemented("重设计阶段——待接入 CraftRecipeView 成本")
    default Map<ElementType, Long> getRecipeCost(String recipeId) {
        throw new UnsupportedOperationException("ProductionApi.getRecipeCost not yet implemented");
    }

    /** 程序化向建筑队列提交一次合成生产。 */
    @Unimplemented("重设计阶段——待接入 ResourceSupplySystem.enqueueSynthesize")
    default boolean enqueueSynthesize(UUID buildingId, String recipeId, int count) {
        throw new UnsupportedOperationException("ProductionApi.enqueueSynthesize not yet implemented");
    }
}
