package com.wsteam.wandscape.core.boundary;

import javax.annotation.Nullable;

import com.wsteam.wandscape.core.types.*;
import com.wsteam.wandscape.core.boundary.BlockOps;
import com.wsteam.wandscape.core.boundary.ColonyResourceAccess;
import com.wsteam.wandscape.core.boundary.EntityOps;
import com.wsteam.wandscape.core.boundary.RitualOps;
import com.wsteam.wandscape.core.ecs.World;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Mock implementations of all boundary interfaces for headless testing/demo.
 */
public class MockBoundary implements BlockOps, EntityOps, RitualOps, ColonyResourceAccess {

    // Simulated world blocks
    private final Map<GridPos, BlockType> blocks = new HashMap<>();
    // Simulated warehouse
    private final Map<ResourceId, Integer> warehouse = new HashMap<>();
    private final Map<ResourceId, Integer> reserved = new HashMap<>();

    // ---- BlockOps ----

    private static final String TAG = "MockBoundary";

    @Override
    public void setBlock(GridPos pos, BlockType type) {
        if (type.equals(BlockType.AIR)) {
            blocks.remove(pos);
        } else {
            blocks.put(pos, type);
        }
    }

    @Override
    public BlockType getBlock(GridPos pos) {
        return blocks.getOrDefault(pos, BlockType.AIR);
    }

    @Override
    public boolean isAir(GridPos pos) {
        return getBlock(pos).equals(BlockType.AIR);
    }

    @Override
    public void toggle(GridPos pos) {
    }

    @Override
    public void activate(GridPos pos) {
    }

    @Override
    public void openGui(GridPos pos) {
    }

    @Override
    public void setBlockEntityData(GridPos pos, @Nullable String nbtBase64) {
        if (nbtBase64 != null && !nbtBase64.isEmpty()) {
        }
    }

    // ---- EntityOps ----

    /** 模拟 NPC 当前魔力（调度器魔力门槛用）。 */
    private float npcMana = 200f;

    public void setNpcMana(float v) { this.npcMana = v; }

    /** 最近一次 spawnDecoration 调用（测试断言用）。 */
    @Nullable
    public SpawnDecorationCall lastSpawnDecoration;

    public record SpawnDecorationCall(GridPos pos, String entityType, String facing,
                                      @Nullable String nbtBase64) {}

    @Override
    public void applyEffect(EntityId target, EffectId effect, int strength, int duration) {
    }

    @Override
    public GridPos getPosition(EntityId entity) {
        return GridPos.ORIGIN;
    }

    @Override
    public float getCurrentMana(long npcId) {
        return npcMana;
    }

    @Override
    public void spawnDecoration(GridPos pos, String entityType, String facing,
                                @Nullable String nbtBase64) {
        lastSpawnDecoration = new SpawnDecorationCall(pos, entityType, facing, nbtBase64);
    }

    // ---- RitualOps ----

    @Override
    public CompletableFuture<Void> beginRitual(RitualId ritual, GridPos target, World world,
                                               long casterId,
                                               Map<String, String> params) {
        // All rituals are sync for headless testing
        return CompletableFuture.completedFuture(null);
    }

    // ---- ColonyResourceAccess ----

    @Override
    public void addResource(ResourceId resource, int amount) {
        warehouse.merge(resource, amount, Integer::sum);
    }

    /** Seed the warehouse with initial resources. */
    public void seedWarehouse(ResourceId resource, int amount) {
        warehouse.merge(resource, amount, Integer::sum);
    }

    @Override
    public boolean hasEnough(ResourceId resource, int amount) {
        int avail = available(resource);
        return avail >= amount;
    }

    @Override
    public boolean reserve(ResourceId resource, int amount) {
        int avail = available(resource);
        if (avail < amount) return false;
        reserved.merge(resource, amount, Integer::sum);
        return true;
    }

    @Override
    public boolean commit(ResourceId resource, int amount) {
        Integer res = reserved.get(resource);
        if (res == null || res < amount) return false;
        reserved.merge(resource, -amount, Integer::sum);
        warehouse.merge(resource, -amount, Integer::sum);
        return true;
    }

    @Override
    public void release(ResourceId resource, int amount) {
        reserved.merge(resource, -amount, (a, b) -> Math.max(0, a + b));
    }

    @Override
    public int available(ResourceId resource) {
        int total = warehouse.getOrDefault(resource, 0);
        int res = reserved.getOrDefault(resource, 0);
        return Math.max(0, total - res);
    }

    /** Get total warehouse stock (including reserved). For UI display only. */
    public int warehouseTotal(ResourceId resource) {
        return warehouse.getOrDefault(resource, 0);
    }

    /** Get a copy of the block map for rendering. */
    public Map<GridPos, BlockType> allBlocks() {
        return new HashMap<>(blocks);
    }
}
