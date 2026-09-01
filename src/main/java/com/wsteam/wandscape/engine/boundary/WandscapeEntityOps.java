package com.wsteam.wandscape.engine.boundary;

import com.wsteam.wandscape.core.boundary.EntityOps;
import com.wsteam.wandscape.core.types.EffectId;
import com.wsteam.wandscape.core.types.EntityId;
import com.wsteam.wandscape.core.types.GridPos;
import com.wsteam.wandscape.engine.service.ChunkLoadManager;
import com.wsteam.wandscape.content.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.content.npc.internal.EntityComponentBridge;
import com.wsteam.wandscape.foundation.log.Log;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.BlockAttachedEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import javax.annotation.Nullable;
import java.io.ByteArrayInputStream;
import java.util.Base64;

/**
 * MC implementation of {@link EntityOps}.
 */
public class WandscapeEntityOps implements EntityOps {

    private static final String TAG = "WandscapeEntityOps";

    @Override
    public void applyEffect(EntityId target, EffectId effect, int strength, int duration) {
        // Stage 3+: look up target entity and apply MobEffect
    }

    @Override
    public GridPos getPosition(EntityId entity) {
        // Stage 3+: look up entity and return real position
        return GridPos.ORIGIN;
    }

    @Override
    public float getCurrentMana(long npcId) {
        WandscapeNpc npc = EntityComponentBridge.INSTANCE.getNpc(npcId);
        return npc != null ? npc.getCurrentMana() : 0f;
    }

    @Override
    public float getWorkSpeed(long npcId) {
        WandscapeNpc npc = EntityComponentBridge.INSTANCE.getNpc(npcId);
        return npc != null ? npc.getEffectiveAttribute(com.wsteam.wandscape.core.types.AttributeType.WORK_SPEED) : 1f;
    }

    @Override
    public boolean isFollowing(long npcId) {
        WandscapeNpc npc = EntityComponentBridge.INSTANCE.getNpc(npcId);
        return npc != null && npc.isFollowMode();
    }

    @Override
    public boolean isResting(long npcId) {
        WandscapeNpc npc = EntityComponentBridge.INSTANCE.getNpc(npcId);
        return npc != null && npc.isResting();
    }

    @Override
    public boolean isColonyActive(java.util.UUID colonyId) {
        return com.wsteam.wandscape.engine.colony.ColonyActivation.isColonyActive(colonyId);
    }

    @Override
    public boolean isColonyRegistered(java.util.UUID colonyId) {
        if (colonyId == null) return false;
        var api = com.wsteam.wandscape.api.WandscapeApis.getColonyApiSilently();
        if (api == null) return false;
        return api.getAllColonyIds().contains(colonyId);
    }

    @Override
    public boolean isNpcAlive(long npcId) {
        WandscapeNpc npc = EntityComponentBridge.INSTANCE.getNpc(npcId);
        return npc != null && !npc.isRemoved();
    }

    @Override
    public void spawnDecoration(GridPos pos, String entityType, String facing,
                                @Nullable String nbtBase64) {
        if (nbtBase64 == null || nbtBase64.isEmpty()) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        ServerLevel level = server.overworld();
        BlockPos bp = new BlockPos(pos.x(), pos.y(), pos.z());
        ChunkPos chunkPos = new ChunkPos(bp);
        ChunkLoadManager.get().acquireChunk(chunkPos);
        try {
            // Remove any hanging decoration already occupying the cell — a rebuilt
            // frame and a stale frame share the cell and mutually fail survives().
            for (Entity e : level.getEntities((Entity) null, new AABB(bp),
                    e2 -> e2 instanceof BlockAttachedEntity)) {
                e.discard();
            }

            byte[] data = Base64.getDecoder().decode(nbtBase64);
            CompoundTag tag = NbtIo.readCompressed(new ByteArrayInputStream(data),
                    NbtAccounter.create(0x200000L));
            tag.putString("id", entityType);
            tag.putInt("TileX", bp.getX());
            tag.putInt("TileY", bp.getY());
            tag.putInt("TileZ", bp.getZ());
            tag.put("Pos", doubleList(bp.getX() + 0.5, bp.getY() + 0.5, bp.getZ() + 0.5));

            // Override facing per type — vanilla uses "Facing" (3D value) for item
            // frames but lowercase "facing" (2D value) for paintings.
            Direction dir = Direction.byName(facing);
            if (dir != null && !facing.isEmpty()) {
                switch (entityType) {
                    case "minecraft:item_frame", "minecraft:glow_item_frame" ->
                            tag.putByte("Facing", (byte) dir.get3DDataValue());
                    case "minecraft:painting" ->
                            tag.putByte("facing", (byte) dir.get2DDataValue());
                    default -> { /* keep embedded facing (armor stand / display) */ }
                }
            }

            EntityType.create(tag, level).ifPresent(level::addFreshEntity);
        } catch (Exception e) {
            Log.warn(TAG, "spawnDecoration failed at {} ({}): {}", bp, entityType, e.toString());
        } finally {
            ChunkLoadManager.get().releaseChunk(chunkPos);
        }
    }

    private static ListTag doubleList(double x, double y, double z) {
        ListTag list = new ListTag();
        list.add(DoubleTag.valueOf(x));
        list.add(DoubleTag.valueOf(y));
        list.add(DoubleTag.valueOf(z));
        return list;
    }
}
