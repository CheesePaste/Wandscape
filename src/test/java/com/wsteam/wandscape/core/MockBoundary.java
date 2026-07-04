package com.wsteam.wandscape.core;

import com.wsteam.wandscape.shared.log.Log;
import com.wsteam.wandscape.core.boundary.BlockOps;
import com.wsteam.wandscape.core.boundary.ColonyResourceAccess;
import com.wsteam.wandscape.core.boundary.EntityOps;
import com.wsteam.wandscape.core.boundary.RitualOps;
import com.wsteam.wandscape.core.types.*;
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
        Log.debug(TAG, "setBlock %s → %s", pos, type.id());
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
        Log.debug(TAG, "toggle %s", pos);
    }

    @Override
    public void activate(GridPos pos) {
        Log.debug(TAG, "activate %s", pos);
    }

    @Override
    public void openGui(GridPos pos) {
        Log.debug(TAG, "openGui %s", pos);
    }

    // ---- EntityOps ----

    @Override
    public void applyEffect(EntityId target, EffectId effect, int strength, int duration) {
        Log.debug(TAG, "applyEffect %s strength=%d duration=%d on %s",
                effect.id(), strength, duration, target);
    }

    @Override
    public GridPos getPosition(EntityId entity) {
        return GridPos.ORIGIN;
    }

    // ---- RitualOps ----

    @Override
    public CompletableFuture<Void> beginRitual(RitualId ritual, GridPos target, World world,
                                               long casterId,
                                               Map<String, String> params) {
        Log.debug(TAG, "beginRitual %s target=%s caster=%d params=%s → completed (sync)",
                ritual.id(), target, casterId, params);
        // All rituals are sync for headless testing
        return CompletableFuture.completedFuture(null);
    }

    // ---- ColonyResourceAccess ----

    @Override
    public void addResource(ResourceId resource, int amount) {
        warehouse.merge(resource, amount, Integer::sum);
        Log.debug(TAG, "addResource %s: +%d (total %d)", resource.id(), amount,
                warehouse.getOrDefault(resource, 0));
    }

    /** Seed the warehouse with initial resources. */
    public void seedWarehouse(ResourceId resource, int amount) {
        warehouse.merge(resource, amount, Integer::sum);
        Log.debug(TAG, "seedWarehouse %s: +%d (total %d)", resource.id(), amount,
                warehouse.getOrDefault(resource, 0));
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
        Log.debug(TAG, "reserve %s: %d (available after: %d)", resource.id(), amount, available(resource));
        return true;
    }

    @Override
    public boolean commit(ResourceId resource, int amount) {
        Integer res = reserved.get(resource);
        if (res == null || res < amount) return false;
        reserved.merge(resource, -amount, Integer::sum);
        warehouse.merge(resource, -amount, Integer::sum);
        Log.debug(TAG, "commit %s: -%d (remaining: %d)", resource.id(), amount,
                warehouse.getOrDefault(resource, 0));
        return true;
    }

    @Override
    public void release(ResourceId resource, int amount) {
        reserved.merge(resource, -amount, (a, b) -> Math.max(0, a + b));
        Log.debug(TAG, "release %s: %d", resource.id(), amount);
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
