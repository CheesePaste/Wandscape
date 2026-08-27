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

    private final Map<Long, Float> npcWorkSpeed = new HashMap<>();

    public void setWorkSpeed(long npcId, float speed) {
        npcWorkSpeed.put(npcId, speed);
    }

    @Override
    public float getWorkSpeed(long npcId) {
        return npcWorkSpeed.getOrDefault(npcId, 1.0f);
    }

    /** 处于跟随模式的 NPC id 集合（跟随门控测试用）。 */
    private final Set<Long> followingNpcs = new HashSet<>();

    public void setFollowing(long npcId, boolean following) {
        if (following) {
            followingNpcs.add(npcId);
        } else {
            followingNpcs.remove(npcId);
        }
    }

    @Override
    public boolean isFollowing(long npcId) {
        return followingNpcs.contains(npcId);
    }

    /** 处于休息（法师小屋）模式的 NPC id 集合（休息门控测试用）。 */
    private final Set<Long> restingNpcs = new HashSet<>();

    public void setResting(long npcId, boolean resting) {
        if (resting) {
            restingNpcs.add(npcId);
        } else {
            restingNpcs.remove(npcId);
        }
    }

    @Override
    public boolean isResting(long npcId) {
        return restingNpcs.contains(npcId);
    }

    /** 冻结（创始人不在线）的殖民地 id 集合：默认空 = 全部激活。 */
    private final Set<UUID> frozenColonies = new HashSet<>();

    public void setColonyFrozen(UUID colonyId, boolean frozen) {
        if (frozen) {
            frozenColonies.add(colonyId);
        } else {
            frozenColonies.remove(colonyId);
        }
    }

    @Override
    public boolean isColonyActive(UUID colonyId) {
        return colonyId == null || !frozenColonies.contains(colonyId);
    }

    /** 模拟注册表：默认所有非占位殖民地都视为已注册（兼容测试用随机 UUID 造殖民地）；
     *  需模拟"未注册/陈旧殖民地"时可显式设置。 */
    private final Set<UUID> unregisteredColonies = new HashSet<>();

    public void setColonyUnregistered(UUID colonyId, boolean unregistered) {
        if (unregistered) {
            unregisteredColonies.add(colonyId);
        } else {
            unregisteredColonies.remove(colonyId);
        }
    }

    @Override
    public boolean isColonyRegistered(UUID colonyId) {
        if (colonyId == null) return false;
        if (FriendlyForce.PLACEHOLDER_COLONY.equals(colonyId)) return false;
        return !unregisteredColonies.contains(colonyId);
    }

    /** 已移除/卸载（幽灵）的 NPC id 集合：`isNpcAlive` 对其返回 false。 */
    private final Set<Long> removedNpcs = new HashSet<>();

    public void setNpcRemoved(long npcId, boolean removed) {
        if (removed) {
            removedNpcs.add(npcId);
        } else {
            removedNpcs.remove(npcId);
        }
    }

    @Override
    public boolean isNpcAlive(long npcId) {
        return !removedNpcs.contains(npcId);
    }

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
