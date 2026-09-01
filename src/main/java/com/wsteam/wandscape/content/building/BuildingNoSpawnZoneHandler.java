package com.wsteam.wandscape.content.building;
import com.wsteam.wandscape.content.task.boundary.EventBus;
import com.wsteam.wandscape.content.task.ecs.World;

import com.wsteam.wandscape.Config;
import com.wsteam.wandscape.content.building.data.BuildingData;
import com.wsteam.wandscape.api.WandscapeApis;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.MobSpawnType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.MobSpawnEvent;

/**
 * 建筑防刷怪区：自然刷怪候选位置落在完好且运营中（未停摆）建筑包围盒内时拒绝该位置。
 *
 * <p>挂在 {@link MobSpawnEvent.SpawnPlacementCheck}——自然刷怪每个候选位置做刷怪规则
 * 判定（{@code SpawnPlacements.checkSpawnRules}）时触发，比 {@code PositionCheck} 更前置：
 * 位置一进包围盒即 FAIL，实体根本不会被创建。只拦 {@link MobSpawnType#NATURAL}，
 * 刷怪笼/结构刷怪/指令/spawn egg 走原版机制，NPC/游客经 {@code addFreshEntity} 生成也不受影响。
 *
 * <p>用 {@code WandscapeApis.getBuildingApi()} 而非直连 SavedData：跨模块事件订阅统一走
 * API + EventBus，不跨包引用内部类。只查询建筑是否存在，完整/停摆过滤在本类判断。
 */
public final class BuildingNoSpawnZoneHandler {

    private BuildingNoSpawnZoneHandler() {}

    @SubscribeEvent
    public static void onSpawnPlacementCheck(MobSpawnEvent.SpawnPlacementCheck event) {
        if (!Config.BUILDING_NO_SPAWN_IN_AREA.get()) return;
        if (event.getSpawnType() != MobSpawnType.NATURAL) return;

        BlockPos pos = event.getPos();
        BuildingData building = WandscapeApis.getBuildingApi().getBuildingAt(pos);
        if (building != null && building.isStructureIntact()) {
            event.setResult(MobSpawnEvent.SpawnPlacementCheck.Result.FAIL);
        }
    }
}
