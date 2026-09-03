package com.wsteam.wandscape.compat.goety;

import com.Polarice3.Goety.api.entities.IOwned;
import com.wsteam.wandscape.api.NpcMainHandApi;
import com.wsteam.wandscape.foundation.log.Log;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;

import javax.annotation.Nullable;

/**
 * Wandscape × 诡厄巫法 (Goety) 兼容总入口。
 *
 * <p>所有 Goety 相关类的直接引用均封装在此包内。当 {@code goety} 未加载时，
 * 此包内的逻辑不会被执行，保证零硬编码依赖与优雅降级。
 */
public final class GoetyCompat {

    private static final String TAG = "GoetyCompat";
    public static final String MOD_ID = "goety";

    private static boolean loaded = false;

    private GoetyCompat() {}

    /** 是否已安装并加载诡厄巫法模组。 */
    public static boolean isLoaded() {
        return loaded;
    }

    /**
     * 法师主手（法杖）槽的诡厄巫法施法杖判定：官方物品标签 {@code goety:wands}
     * （= {@code dark_wand} + {@code #goety:staffs} 全 11 把权杖）。纯标签判定、零引用 Goety 类，
     * 未装模组时标签恒空 → 恒 false。
     */
    public static void registerAllowedMainHandItems(NpcMainHandApi api) {
        if (!loaded || api == null) return;
        TagKey<Item> wands = TagKey.create(Registries.ITEM,
                ResourceLocation.fromNamespaceAndPath(MOD_ID, "wands"));
        api.registerAllowedItem(stack -> stack.is(wands));
    }

    /**
     * 若实体是 Goety 的仆从/被拥有实体（IOwned），返回其真实召唤者/主人实体；否则返回 null。
     * 前置 {@link #isLoaded()} 守卫，避免未装 Goety 时直接类加载抛 NoClassDefFoundError。
     */
    @Nullable
    public static Entity getMasterOwner(Entity entity) {
        if (!loaded || !(entity instanceof IOwned owned)) return null;
        LivingEntity master = owned.getMasterOwner();
        return master != null ? master : owned.getTrueOwner();
    }

    /** 在模组初始化阶段调用（Wandscape 主类）。 */
    public static void init(IEventBus modEventBus) {
        loaded = ModList.get().isLoaded(MOD_ID);
        if (!loaded) {
            Log.info(TAG, "Goety not detected, compat disabled.");
            return;
        }
        Log.info(TAG, "Goety detected! Initializing compat layer...");
    }
}
