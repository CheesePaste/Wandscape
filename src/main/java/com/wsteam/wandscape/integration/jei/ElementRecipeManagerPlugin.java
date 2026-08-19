package com.wsteam.wandscape.integration.jei;

import java.util.List;

import javax.annotation.Nullable;

import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.shared.data.ElementType;
import com.wsteam.wandscape.shared.log.Log;

import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.advanced.ISimpleRecipeManagerPlugin;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * 懒查询式 JEI 配方提供者：每次调用都从数据加载器实时收集，天然支持 /reload 刷新。
 *
 * <p>原料匹配语义：聚焦一个物品时列出它作为输入（被消耗）与作为输出（被产出）的全部配方。
 * 元素物品额外匹配消耗/产出该元素的合成/分解配方。
 */
public class ElementRecipeManagerPlugin implements ISimpleRecipeManagerPlugin<ElementRecipe> {

    private static final String TAG = "ElementRecipeManagerPlugin";

    @Override
    public boolean isHandledInput(ITypedIngredient<?> ingredient) {
        try {
            return isHandled(ingredient);
        } catch (RuntimeException e) {
            Log.warn(TAG, "isHandledInput 失败，返回 false", e);
            return false;
        }
    }

    @Override
    public boolean isHandledOutput(ITypedIngredient<?> ingredient) {
        try {
            return isHandled(ingredient);
        } catch (RuntimeException e) {
            Log.warn(TAG, "isHandledOutput 失败，返回 false", e);
            return false;
        }
    }

    private static boolean isHandled(ITypedIngredient<?> ingredient) {
        String itemId = resolveItemId(ingredient);
        if (itemId == null) return false;
        return isElementItem(itemId) || isMappedItem(itemId)
                || isRecipeSubject(itemId) || isExtraInput(itemId);
    }

    @Override
    public List<ElementRecipe> getRecipesForInput(ITypedIngredient<?> ingredient) {
        try {
            String itemId = resolveItemId(ingredient);
            if (itemId == null) return List.of();
            List<ElementRecipe> all = allRecipes();
            ElementType element = elementTypeOf(itemId);
            return all.stream().filter(r -> {
                if (element != null && r.elements().containsKey(element)) return true;      // 元素被消耗
                if (r.extraInputs().stream().anyMatch(x -> ElementRecipeCollector.itemIdEquals(x, itemId))) return true; // 额外原料
                return r.kind() == ElementRecipeKind.DECOMPOSE
                        && ElementRecipeCollector.itemIdEquals(r.itemId(), itemId);          // 物品被分解
            }).toList();
        } catch (RuntimeException e) {
            Log.warn(TAG, "getRecipesForInput 失败，返回空", e);
            return List.of();
        }
    }

    @Override
    public List<ElementRecipe> getRecipesForOutput(ITypedIngredient<?> ingredient) {
        try {
            String itemId = resolveItemId(ingredient);
            if (itemId == null) return List.of();
            List<ElementRecipe> all = allRecipes();
            ElementType element = elementTypeOf(itemId);
            return all.stream().filter(r -> {
                if (element != null && r.kind() == ElementRecipeKind.DECOMPOSE
                        && r.elements().containsKey(element)) return true;                   // 元素被分解产出
                return r.kind() == ElementRecipeKind.SYNTHESIZE
                        && ElementRecipeCollector.itemIdEquals(r.itemId(), itemId);          // 物品被合成产出
            }).toList();
        } catch (RuntimeException e) {
            Log.warn(TAG, "getRecipesForOutput 失败，返回空", e);
            return List.of();
        }
    }

    @Override
    public List<ElementRecipe> getAllRecipes() {
        try {
            return allRecipes();
        } catch (RuntimeException e) {
            Log.warn(TAG, "getAllRecipes 失败，返回空", e);
            return List.of();
        }
    }

    private static List<ElementRecipe> allRecipes() {
        return ElementRecipeCollector.collectAll(
                Wandscape.ELEMENT_MAPPING_LOADER.getAllConfigs(),
                Wandscape.PRODUCTION_RECIPE_LOADER.getCraftWandRecipes().getAll().values(),
                Wandscape.PRODUCTION_RECIPE_LOADER.getPotionRecipes().getAll().values());
    }

    private static boolean isElementItem(String itemId) {
        return elementTypeOf(itemId) != null;
    }

    private static boolean isMappedItem(String itemId) {
        return Wandscape.ELEMENT_MAPPING_LOADER.hasMapping(itemId);
    }

    /** 是否是某一配方的主物品（输出条目 / 分解输入物品）。 */
    private static boolean isRecipeSubject(String itemId) {
        return allRecipes().stream().anyMatch(r ->
                ElementRecipeCollector.itemIdEquals(r.itemId(), itemId));
    }

    /** 是否是某配方的额外原料（如药剂的玻璃瓶）。 */
    private static boolean isExtraInput(String itemId) {
        return allRecipes().stream().anyMatch(r ->
                r.extraInputs().stream().anyMatch(x -> ElementRecipeCollector.itemIdEquals(x, itemId)));
    }

    @Nullable
    private static ElementType elementTypeOf(String itemId) {
        if (itemId == null) return null;
        for (var entry : Wandscape.ELEMENT_ITEMS.entrySet()) {
            ResourceLocation key = BuiltInRegistries.ITEM.getKey(entry.getValue().get());
            if (key != null && ElementRecipeCollector.itemIdEquals(String.valueOf(key), itemId)) {
                return entry.getKey();
            }
        }
        return null;
    }

    @Nullable
    private static String resolveItemId(ITypedIngredient<?> ingredient) {
        var stackOpt = ingredient.getItemStack();
        if (stackOpt.isEmpty()) return null;
        ItemStack stack = stackOpt.get();
        Item item = stack.getItem();
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(item);
        return key != null ? key.toString() : null;
    }
}
