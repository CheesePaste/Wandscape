package com.wsteam.wandscape.scepter.internal;

import com.wsteam.wandscape.shared.api.ScepterApi;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * {@link ScepterApi} 实现：从 {@link ScepterMarksSavedData}（overworld SavedData）读殖民地标记。
 * 仅服务端生效——客户端/非 ServerLevel 一律返回 false/null（friend 判定与战斗解析均在服务端）。
 */
public final class ScepterApiImpl implements ScepterApi {

    @Override
    public boolean isSheltered(UUID colonyId, UUID entityUuid, Level level) {
        if (colonyId == null || entityUuid == null || !(level instanceof ServerLevel sl)) return false;
        return ScepterMarksSavedData.get(sl.getServer()).marks().isSheltered(colonyId, entityUuid);
    }

    @Override
    public boolean isShelteredForAny(UUID entityUuid, Level level) {
        if (entityUuid == null || !(level instanceof ServerLevel sl)) return false;
        return ScepterMarksSavedData.get(sl.getServer()).marks().isShelteredForAny(entityUuid);
    }

    @Override
    @Nullable
    public LivingEntity forcedHostile(ServerLevel level, UUID colonyId) {
        if (level == null || colonyId == null) return null;
        UUID target = ScepterMarksSavedData.get(level.getServer()).marks().forcedHostile(colonyId);
        if (target == null) return null;
        Entity e = level.getEntity(target);
        return (e instanceof LivingEntity le && le.isAlive() && !le.isRemoved()) ? le : null;
    }
}