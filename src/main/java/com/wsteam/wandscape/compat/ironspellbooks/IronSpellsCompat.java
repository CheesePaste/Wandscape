package com.wsteam.wandscape.compat.ironspellbooks;

import com.wsteam.wandscape.shared.log.Log;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Wandscape × Iron's Spells 'n Spellbooks 兼容总入口。
 *
 * <p>所有铁魔法相关类均封装在此包内。当 {@code irons_spellbooks} 未加载时，
 * 此包内的逻辑不会被执行，保证零硬编码依赖与优雅降级。
 */
public final class IronSpellsCompat {

    private static final String TAG = "IronSpellsCompat";
    public static final String MOD_ID = "irons_spellbooks";

    private static boolean loaded = false;

    private IronSpellsCompat() {}

    /** 是否已安装并加载铁魔法模组。 */
    public static boolean isLoaded() {
        return loaded;
    }

    /** 在模组初始化阶段调用（Wandscape 主类）。 */
    public static void init(IEventBus modEventBus) {
        loaded = ModList.get().isLoaded(MOD_ID);
        if (!loaded) {
            Log.info(TAG, "Iron's Spells 'n Spellbooks not detected, compat disabled.");
            return;
        }

        Log.info(TAG, "Iron's Spells 'n Spellbooks detected! Initializing compat layer...");
        // 注册伤害与治疗加成监听器
        NeoForge.EVENT_BUS.register(IronSpellsDamageHandler.class);
    }
}
