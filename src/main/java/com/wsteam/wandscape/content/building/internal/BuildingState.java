package com.wsteam.wandscape.content.building.internal;
import com.wsteam.wandscape.content.task.ecs.World;

import com.wsteam.wandscape.content.building.data.BuildingData;
import com.wsteam.wandscape.content.building.data.WorkItem;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
/**
 * Mutable building state — replaces all {@code AbstractWandscapeBE} fields.
 * Implements {@link BuildingData} for read-only access.
 */
public class BuildingState implements BuildingData {
    private final UUID buildingId;
    private final String buildingTypeId;
    private final String category;
    private final BlockPos anchor;
    private final BoundingBox bounds;
    private final int comfort;
    private final int magic;
    private final int wonder;

    @Nullable
    private UUID colonyId;
    private boolean structureIntact;
    /** Sticky flag: set once construction completes, never reset (drives the ghost). */
    private boolean hasEverCompleted;
    /** Sticky flag: set once construction work is claimed by an NPC, never reset. */
    private boolean constructionStarted;
    private boolean demolishing;
    /**
     * 已从仓库实际扣除、且还没退还的建造材料（材质 id → 数量），由引擎在
     * {@code request_resource} 提交成功后回填，退还时销账。撤销建造时的退还上限就是这本账：
     * 未开工（账本为空，材料一分没扣）时按图纸退任何东西都是凭空造物。
     */
    private final Map<String, Integer> chargedMaterials = new LinkedHashMap<>();
    private final Deque<WorkItem> taskQueue = new ArrayDeque<>();
    @Nullable
    private Set<BlockPos> patternPositions;
    /**
     * World-space AABB over {@link #patternPositions} (the building's occupied
     * voxels), used as the cheap broad-phase box in two-phase overlap checks.
     * Null when the building occupies no voxels or has no stored pattern.
     */
    @Nullable
    private BoundingBox patternExtent;
    @Nullable
    private UUID currentTaskId;
    private int rotationSteps;

    public BuildingState(UUID buildingId, String buildingTypeId, String category,
                         BlockPos anchor, BoundingBox bounds,
                         int comfort, int magic, int wonder) {
        this.buildingId = buildingId;
        this.buildingTypeId = buildingTypeId;
        this.category = category;
        this.anchor = anchor;
        this.bounds = bounds;
        this.comfort = comfort;
        this.magic = magic;
        this.wonder = wonder;
    }

    // ── BuildingData getters ──

    @Override public UUID getBuildingId() { return buildingId; }
    @Override public String getBuildingTypeId() { return buildingTypeId; }
    public String getDisplayName() {
        var config = BuildingConfigLoader.getInstance().get(buildingTypeId);
        return (config != null && config.displayName() != null && !config.displayName().isEmpty())
                ? config.displayName() : buildingTypeId;
    }
    @Override public String getCategory() { return category; }
    @Override public BlockPos getPosition() { return anchor; }
    @Override public boolean isDemolishing() { return demolishing; }
    @Override public int getComfort() { return comfort; }
    @Override public int getMagic() { return magic; }
    @Override public int getWonder() { return wonder; }

    // ── Extended getters ──

    public BlockPos getAnchor() { return anchor; }
    public BoundingBox getBounds() { return bounds; }
    @Nullable public UUID getColonyId() { return colonyId; }
    @Override public boolean isStructureIntact() { return structureIntact; }
    @Override public boolean hasEverCompleted() { return hasEverCompleted; }
    @Override public boolean isConstructionStarted() { return constructionStarted; }
    @Nullable public UUID getCurrentTaskId() { return currentTaskId; }
    public Deque<WorkItem> getTaskQueue() { return taskQueue; }

    /** 已扣建材账本（材质 id → 已扣且尚未退还的数量）。 */
    public Map<String, Integer> getChargedMaterials() { return chargedMaterials; }

    /** 累加本批实际从仓库扣除的建材（同材质合并计数）。 */
    public void recordChargedMaterials(Map<String, Integer> counts) {
        if (counts == null) return;
        counts.forEach((id, amount) -> {
            if (id != null && amount != null && amount > 0) {
                chargedMaterials.merge(id, amount, Integer::sum);
            }
        });
    }

    /** 把已退还的部分从账本里销掉，同一笔账不会被退第二次。 */
    public void deductChargedMaterials(Map<String, Integer> counts) {
        if (counts == null) return;
        counts.forEach((id, amount) -> {
            if (id == null || amount == null || amount <= 0) return;
            Integer left = chargedMaterials.get(id);
            if (left == null) return;
            int remaining = left - amount;
            if (remaining > 0) chargedMaterials.put(id, remaining);
            else chargedMaterials.remove(id);
        });
    }

    public boolean hasWork() {
        return !taskQueue.isEmpty();
    }

    // ── Pattern positions getter/setter ──

    /** World-space pattern block positions for precise overlap detection. */
    @Nullable
    public Set<BlockPos> getPatternPositions() { return patternPositions; }

    public void setPatternPositions(@Nullable Set<BlockPos> positions) { this.patternPositions = positions; }

    /** Broad-phase AABB over the occupied voxels; null when the building has no stored pattern. */
    @Nullable
    public BoundingBox getPatternExtent() { return patternExtent; }

    public void setPatternExtent(@Nullable BoundingBox extent) { this.patternExtent = extent; }

    // ── Setters ──

    public void setColonyId(@Nullable UUID colonyId) { this.colonyId = colonyId; }
    public void setStructureIntact(boolean intact) { this.structureIntact = intact; }
    public void setHasEverCompleted(boolean completed) { this.hasEverCompleted = completed; }
    public void setConstructionStarted(boolean started) { this.constructionStarted = started; }
    public void setDemolishing(boolean demolishing) { this.demolishing = demolishing; }
    public void setCurrentTaskId(@Nullable UUID taskId) { this.currentTaskId = taskId; }
    public int getRotationSteps() { return rotationSteps; }
    public void setRotationSteps(int steps) { this.rotationSteps = steps & 3; }
}
