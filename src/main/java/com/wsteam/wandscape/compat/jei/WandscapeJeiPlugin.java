package com.wsteam.wandscape.compat.jei;

import com.wsteam.wandscape.Wandscape;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.registration.IAdvancedRegistration;
import mezz.jei.api.registration.IRecipeCategoryRegistration;

import net.minecraft.resources.ResourceLocation;

/**
 * Wandscape 与 JEI 的集成插件（可选依赖，无 JEI 时不加载该类）。
 *
 * <p>注册单个「Wandscape 元素」配方分类，配方通过懒查询插件动态提供，
 * 元素映射数据 `/reload` 后自动同步刷新。
 */
@JeiPlugin
public class WandscapeJeiPlugin implements IModPlugin {

    @Override
    public ResourceLocation getPluginUid() {
        return ResourceLocation.fromNamespaceAndPath(Wandscape.MODID, "jei_plugin");
    }

    @Override
    public void registerCategories(IRecipeCategoryRegistration registration) {
        registration.addRecipeCategories(
                new ElementRecipeCategory(registration.getJeiHelpers().getGuiHelper()));
    }

    @Override
    public void registerAdvanced(IAdvancedRegistration registration) {
        registration.addTypedRecipeManagerPlugin(ElementRecipeCategory.TYPE,
                new ElementRecipeManagerPlugin());
    }
}
