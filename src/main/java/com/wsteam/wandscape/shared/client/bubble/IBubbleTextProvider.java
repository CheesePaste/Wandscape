package com.wsteam.wandscape.shared.client.bubble;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;

import javax.annotation.Nullable;

/**
 * Provides text for the speech bubble shown above NPC heads.
 * Called once per animation cycle (every ~30s).
 */
@FunctionalInterface
public interface IBubbleTextProvider {

    @Nullable
    Component getText(LivingEntity entity);
}
