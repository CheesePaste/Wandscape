package com.wsteam.wandscape.content.colony.raid;

import com.wsteam.wandscape.content.building.data.BuildingData;
import com.wsteam.wandscape.api.WandscapeApis;
import com.wsteam.wandscape.foundation.registry.WandscapeConstants;
import net.minecraft.core.BlockPos;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * 市政厅定位与"小镇位于村庄内"判定。
 *
 * <p>配合 {@code MixinServerLevel}：让原版 {@code ServerLevel.isVillage} 在市政厅
 * {@code raid.villageRange} 内返回 true，原版 {@code Raid} 因此把小镇当作村庄——
 * 中心不被挪走、不判 STOP/LOSS、波次刷新在小镇周边。
 */
public final class RaidTownHall {

    private RaidTownHall() {}

    /** 某小镇的市政厅锚点；未建/已拆/损坏返回 null。 */
    @Nullable
    public static BlockPos findTownHall(UUID colonyId) {
        for (BuildingData b : WandscapeApis.getBuildingApi().getColonyBuildings(colonyId)) {
            if (WandscapeConstants.BUILDING_CATEGORY_GOVERNMENT.equals(b.getCategory())
                    && b.isStructureIntact()) {
                return b.getPosition();
            }
        }
        return null;
    }

    /** pos 是否在任一小镇市政厅水平 ±range 内（mixin 的 isVillage 判定）。 */
    public static boolean isNearTownHall(BlockPos pos, int range) {
        var colonyApi = WandscapeApis.getColonyApiSilently();
        if (colonyApi == null) return false;
        for (UUID colonyId : colonyApi.getAllColonyIds()) {
            BlockPos townHall = findTownHall(colonyId);
            if (townHall != null && withinHorizontalRange(pos.getX(), pos.getZ(), townHall.getX(), townHall.getZ(), range)) {
                return true;
            }
        }
        return false;
    }

    /** 水平（X/Z）切比雪夫距离 ≤ range。纯逻辑，可单测。 */
    public static boolean withinHorizontalRange(int px, int pz, int tx, int tz, int range) {
        return Math.abs(px - tx) <= range && Math.abs(pz - tz) <= range;
    }
}
