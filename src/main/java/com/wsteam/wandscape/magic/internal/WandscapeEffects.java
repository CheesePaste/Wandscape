package com.wsteam.wandscape.magic.internal;

import com.wsteam.wandscape.Wandscape;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.neoforged.neoforge.registries.DeferredHolder;

public final class WandscapeEffects {
    private WandscapeEffects() {}

    public static final DeferredHolder<MobEffect, PetrificationEffect> PETRIFICATION =
            Wandscape.MOB_EFFECTS.register("petrification", PetrificationEffect::new);

    public static final DeferredHolder<MobEffect, ArmorShredEffect> ARMOR_SHRED =
            Wandscape.MOB_EFFECTS.register("armor_shred", ArmorShredEffect::new);

    public static final DeferredHolder<MobEffect, FortificationEffect> FORTIFICATION =
            Wandscape.MOB_EFFECTS.register("fortification", FortificationEffect::new);

    public static final DeferredHolder<MobEffect, ConversionEffect> CONVERSION =
            Wandscape.MOB_EFFECTS.register("conversion", ConversionEffect::new);

    public static final DeferredHolder<MobEffect, DesperationEffect> DESPERATION =
            Wandscape.MOB_EFFECTS.register("desperation", DesperationEffect::new);

    public static class PetrificationEffect extends MobEffect {
        public PetrificationEffect() {
            super(MobEffectCategory.BENEFICIAL, 0x7F8C8D);
        }
    }

    /**
     * 护甲削减效果：减益（HARMFUL），深紫色粒子。
     * 伤害公式修正由 {@link MagicEventHandler#onLivingDamage} 处理——
     * 有效护甲 = 当前护甲 − shredAmount（可负），低于 0 时产生增伤。
     */
    public static class ArmorShredEffect extends MobEffect {
        public ArmorShredEffect() {
            super(MobEffectCategory.HARMFUL, 0x7C3AED);
        }
    }

    /**
     * 战争赐福效果：增益（BENEFICIAL），金色粒子。
     * 护甲 +4 通过 attribute modifier 直接叠加，vanilla 公式自动处理正护甲减伤。
     */
    public static class FortificationEffect extends MobEffect {
        private static final String MODIFIER_UUID = "f1e2d3c4-b5a6-7890-abcd-ef1234567891";

        public FortificationEffect() {
            super(MobEffectCategory.BENEFICIAL, 0xF59E0B);
            addAttributeModifier(Attributes.ARMOR,
                    java.util.UUID.fromString(MODIFIER_UUID),
                    4.0, AttributeModifier.Operation.ADD_VALUE);
        }
    }

    /**
     * 感化效果：减益（HARMFUL），粉色粒子。
     * 使敌对生物倒戈攻击附近其他敌对生物。目标重定向由
     * {@link MagicEventHandler#tickConversions} 每 0.5s 处理。
     */
    public static class ConversionEffect extends MobEffect {
        public ConversionEffect() {
            super(MobEffectCategory.HARMFUL, 0xF472B6);
        }
    }

    /**
     * 背水效果：增益（BENEFICIAL），深红色粒子。
     * 有效护甲 = −当前护甲/2（装备越好反噬越重但获得力量补偿）。
     * 护甲公式修正与力量等级均由外部处理：
     * {@link MagicEventHandler#onLivingDamage} 处理护甲反转，
     * {@code MagicSpellExecutors} 施放时按 {@code amplifier = floor(armor² / 48)}
     * 计算力量等级（≤5 甲无奖励，二次增长匹配承伤的指数上升）。
    public static class DesperationEffect extends MobEffect {
        public DesperationEffect() {
            super(MobEffectCategory.BENEFICIAL, 0xDC2626);
        }
    }
}
