package com.wsteam.wandscape.compat.ironspellbooks;

import com.wsteam.wandscape.api.NpcMainHandApi;
import com.wsteam.wandscape.foundation.log.Log;
import io.redspace.ironsspellbooks.entity.mobs.IMagicSummon;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;

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

    /**
     * 法师主手（法杖）槽的铁魔法施法杖判定：铁魔法官方物品标签 {@code irons_spellbooks:staff}
     * （灰胡子杖/匠心杖/寒冰杖/闪电杆/血杖/焰火杖；Hither-Thither 传送杖与九权杖不在官方标签，
     * 按设计不放开）。纯标签判定、零引用铁魔法类，未装模组时标签恒空 → 恒 false。
     */
    public static void registerAllowedMainHandItems(NpcMainHandApi api) {
        if (!loaded || api == null) return;
        TagKey<Item> staff = TagKey.create(Registries.ITEM,
                ResourceLocation.fromNamespaceAndPath(MOD_ID, "staff"));
        api.registerAllowedItem(stack -> stack.is(staff));
    }

    /**
     * 若实体是铁魔法召唤物（仅当模组已加载），返回其召唤者实体；否则 {@code null}。
     * 前置 {@link #isLoaded()} 守卫——未加载时 {@code IMagicSummon} 不在类路径，直接
     * {@code instanceof} 会抛 {@code NoClassDefFoundError}。调用方据 null 走降级分支。
     */
    public static Entity getSummoner(Entity entity) {
        if (!loaded) return null;
        return entity instanceof IMagicSummon summon ? summon.getSummoner() : null;
    }

    /** 在模组初始化阶段调用（Wandscape 主类）。 */
    public static void init(IEventBus modEventBus) {
        loaded = ModList.get().isLoaded(MOD_ID);
        if (!loaded) {
            Log.info(TAG, "Iron's Spells 'n Spellbooks not detected, compat disabled.");
            return;
        }

        Log.info(TAG, "Iron's Spells 'n Spellbooks detected! Initializing compat layer...");
        // 伤害/治疗倍率统一走 NpcSpellPowerHandler（LivingIncomingDamageEvent）单入口——铁魔法
        // applyDamage 发完 SpellDamageEvent 后仍会调用 target.hurt()，若再在此监听 SpellDamageEvent
        // 乘 SPELL_POWER 会与 NpcSpellPowerHandler 重复乘算（曾导致铁魔法伤害按法术强度二次方增长）。
    }
}
