package com.wsteam.wandscape.magic.internal;

import com.wsteam.wandscape.Wandscape;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.neoforged.neoforge.registries.DeferredHolder;

public final class WandscapeEffects {
    private WandscapeEffects() {}

    public static final DeferredHolder<MobEffect, PetrificationEffect> PETRIFICATION =
            Wandscape.MOB_EFFECTS.register("petrification", PetrificationEffect::new);

    public static class PetrificationEffect extends MobEffect {
        public PetrificationEffect() {
            super(MobEffectCategory.BENEFICIAL, 0x7F8C8D);
        }
    }
}
