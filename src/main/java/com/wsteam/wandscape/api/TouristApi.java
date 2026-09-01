package com.wsteam.wandscape.api;
import com.wsteam.wandscape.content.task.component.Position;
import com.wsteam.wandscape.content.tourist.event.TouristArrivedEvent;
import com.wsteam.wandscape.content.tourist.event.TouristDepartedEvent;

import com.wsteam.wandscape.content.tourist.data.BarRatio;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.UUID;
/**
 * Public API for the tourist simulation system.
 * Implemented by {@code tourist/internal/TouristApiImpl.java}.
 */
public interface TouristApi {

    /** Number of tourists currently present in a colony. */
    int getTouristCount(UUID colonyId);

    /** UUIDs of all tourist entities in a colony. */
    List<UUID> getTouristsInColony(UUID colonyId);

    /** Request a tourist spawn at the given position for a colony. */
    void spawnTourist(UUID colonyId, BlockPos spawnPos);

    /** Number of tourists who stayed overnight (checked into hotel) in a colony. */
    int getOvernightStayerCount(UUID colonyId);

    // ── 未实现（重设计阶段声明，见 @Unimplemented）──

    /** 清空指定殖民地的全部游客（触发正常离城流程，而非直接删除实体）。 */
    @Unimplemented("重设计阶段——待接入批量离成/实体处理")
    default void despawnAll(UUID colonyId) {
        throw new UnsupportedOperationException("TouristApi.despawnAll not yet implemented");
    }
}
