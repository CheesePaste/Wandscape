package com.wsteam.wandscape.shared.client.bubble;

import javax.annotation.Nullable;
import net.minecraft.world.entity.LivingEntity;

/**
 * Provides text for the speech bubble shown above NPC heads.
 * Called once per animation cycle (every ~30s).
 */
@FunctionalInterface
public interface IBubbleTextProvider {

    @Nullable
    String getText(LivingEntity entity);
}
