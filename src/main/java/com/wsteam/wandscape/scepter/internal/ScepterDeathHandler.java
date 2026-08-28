package com.wsteam.wandscape.scepter.internal;

import java.util.UUID;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

/**
 * 强制仇恨目标死亡自动解除：任意殖民地标记的生物死亡即清除该标记，法师恢复普通索敌。
 * （与「不影响其它生物吸引」的强语义配套——标记或目标死亡，仇恨即终结。）
 */
public final class ScepterDeathHandler {

    private ScepterDeathHandler() {}

    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        Entity entity = event.getEntity();
        if (entity == null || entity.level().isClientSide) return;
        if (!(entity.level() instanceof ServerLevel level)) return;

        UUID uuid = entity.getUUID();
        ScepterMarksSavedData data = ScepterMarksSavedData.get(level.getServer());
        if (data.marks().clearForcedHostileByEntity(uuid)) {
            data.setDirty();
        }
    }
}