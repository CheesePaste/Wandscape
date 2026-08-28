package com.wsteam.wandscape.production.data;

import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.wsteam.wandscape.production.ProductionRecipeLoader;
import com.wsteam.wandscape.shared.data.ElementType;

import net.minecraft.nbt.CompoundTag;

/**
 * 制作站统一「craft」配方的运行时视图（法杖/权杖/药水）。
 *
 * <p>制作站（crafting_station）的多种配方共用同一个 {@code production:craft} 蓝图与同一个
 * {@code craft} 动作——workstation 任务流无需区分配方种类，统一按元素成本 + 可选额外物品
 * 原料生产入仓库。解析按 recipe_id 依次查法杖/权杖/药水注册表（同类目 id 全局唯一）。
 * 魔法工坊的卷轴（{@code craft_spell}）是独立建筑的另一条流，不在此解析。
 */
public record CraftRecipeView(
    String id,
    String outputItem,
    @Nullable CompoundTag outputNbt,
    List<String> inputItems,
    Map<ElementType, Long> cost,
    RecipeUnlockRequirement unlockRequirement
) {
    /** 法杖/权杖/药水中查任意一个；查不到返回 null。 */
    @Nullable
    public static CraftRecipeView resolve(@Nullable ProductionRecipeLoader loader, String recipeId) {
        if (loader == null || recipeId == null) return null;

        CraftWandRecipe wand = loader.getCraftWandRecipes().get(recipeId);
        if (wand != null) {
            return new CraftRecipeView(wand.id(), wand.outputItem(), wand.outputNbt(), List.of(),
                    wand.cost(), wand.unlockRequirement());
        }
        ScepterRecipe scepter = loader.getScepterRecipes().get(recipeId);
        if (scepter != null) {
            return new CraftRecipeView(scepter.id(), scepter.outputItem(), null, List.of(),
                    scepter.cost(), scepter.unlockRequirement());
        }
        BrewPotionRecipe potion = loader.getPotionRecipes().get(recipeId);
        if (potion != null) {
            return new CraftRecipeView(potion.id(), potion.outputItem(), potion.outputNbt(),
                    potion.inputItems(), potion.cost(), potion.unlockRequirement());
        }
        return null;
    }
}