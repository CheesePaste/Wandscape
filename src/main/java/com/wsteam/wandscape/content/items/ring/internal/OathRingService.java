package com.wsteam.wandscape.ring.internal;

import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.ring.RingTier;
import com.wsteam.wandscape.shared.log.Log;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * 盟誓戒指服务端业务：存入/放出法师 + 权限校验 + 玩家反馈。
 *
 * <p>规则：只能与「玩家自己创建殖民地」的法师交互；无殖民地玩家禁止使用（存取都拒绝）。
 * 槽位由 {@link RingTier} 容量决定（固定槽，见 {@link OathRingStorage}）。
 */
public final class OathRingService {
    private static final String TAG = "OathRingService";

    private static final int MAX_VERTICAL_SEARCH = 8;
    private static final int SPIRAL_RADIUS = 6;

    private OathRingService() {}

    // ════════════════════════════════════════════════════════════
    //  存入
    // ════════════════════════════════════════════════════════════

    /**
     * 把法师存入戒指对应档位的首个空槽。先落存储再移除实体（原子性：存储失败则法师不动）。
     */
    public static void tryStore(ServerPlayer player, WandscapeNpc mage, RingTier tier) {
        UUID playerColony = ownColony(player);
        if (playerColony == null) {
            fail(player, "message.wandscape.ring.no_colony");
            return;
        }
        if (!mage.isColonyNpc() || !playerColony.equals(mage.colonyId)) {
            fail(player, "message.wandscape.ring.other_colony");
            return;
        }

        OathRingSavedData data = OathRingSavedData.get(player.getServer());
        OathRingStorage storage = data.storageFor(player.getUUID());
        int slot = storage.findStoreSlot(tier.capacity());
        if (slot < 0) {
            fail(player, "message.wandscape.ring.slots_full", tier.capacity());
            return;
        }

        CompoundTag nbt = new CompoundTag();
        if (!mage.save(nbt)) {
            fail(player, "message.wandscape.ring.store_failed");
            return;
        }
        Component name = mage.getDisplayName();
        storage.put(slot, nbt);
        data.setDirty();
        mage.discard();

        Log.info(TAG, "Player {} stored mage {} into slot {} (tier {})",
                shortId(player.getUUID()), shortId(mage.getUUID()), slot, tier.name());
        syncToClient(player);
        ok(player, "message.wandscape.ring.store.success", name);
    }

    // ════════════════════════════════════════════════════════════
    //  放出
    // ════════════════════════════════════════════════════════════

    /**
     * 从戒指对应档位取第一个已占槽的法师放到目标位置附近。
     */
    public static void tryRelease(ServerPlayer player, BlockPos start, RingTier tier) {
        UUID playerColony = ownColony(player);
        if (playerColony == null) {
            fail(player, "message.wandscape.ring.no_colony");
            return;
        }

        OathRingSavedData data = OathRingSavedData.get(player.getServer());
        OathRingStorage storage = data.storageFor(player.getUUID());
        int slot = storage.findReleaseSlot(tier.capacity());
        if (slot < 0) {
            if (storage.hasAnyStored()) {
                fail(player, "message.wandscape.ring.inaccessible", tier.capacity());
            } else {
                fail(player, "message.wandscape.ring.empty");
            }
            return;
        }
        CompoundTag nbt = storage.get(slot);
        if (nbt == null) {
            storage.remove(slot);
            data.setDirty();
            fail(player, "message.wandscape.ring.release_failed");
            return;
        }

        ServerLevel level = player.serverLevel();
        BlockPos pos = findSpawnPos(level, start);
        if (pos == null) {
            fail(player, "message.wandscape.ring.no_spot");
            return;
        }

        try {
            WandscapeNpc npc = Wandscape.WANDSCAPE_NPC.get().create(level);
            if (npc == null) {
                fail(player, "message.wandscape.ring.release_failed");
                return;
            }
            npc.load(nbt);
            npc.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, player.getYRot(), 0.0F);
            if (!level.addFreshEntity(npc)) {
                fail(player, "message.wandscape.ring.release_failed");
                return;
            }
            Component name = npc.getDisplayName();
            storage.remove(slot);
            data.setDirty();
            Log.info(TAG, "Player {} released mage {} from slot {} at {}", shortId(player.getUUID()),
                    shortId(npc.getUUID()), slot, pos);
            syncToClient(player);
            ok(player, "message.wandscape.ring.release.success", name);
        } catch (RuntimeException e) {
            Log.warn(TAG, "Failed to restore mage from oath ring slot {}: {}", slot, e.toString());
            fail(player, "message.wandscape.ring.release_failed");
        }
    }

    // ════════════════════════════════════════════════════════════
    //  辅助
    // ════════════════════════════════════════════════════════════

    /** 玩家创建殖民地的 UUID；无殖民地（含 API 未就绪）返回 null。 */
    @Nullable
    private static UUID ownColony(ServerPlayer player) {
        try {
            var api = com.wsteam.wandscape.shared.registry.WandscapeApis.getColonyApiSilently();
            return api != null ? api.getColonyByFounder(player.getUUID()) : null;
        } catch (RuntimeException e) {
            Log.warn(TAG, "Failed to resolve own colony for {}: {}", shortId(player.getUUID()), e.toString());
            return null;
        }
    }

    /**
     * 目标点附近的安全落点：下落有碰撞体 + 站立两层空气 + chunk 已加载。
     * 先试目标点，再垂直向上最多 8 格，最后水平切比雪夫螺旋半径 6 格；全失败返回 null。
     */
    @Nullable
    private static BlockPos findSpawnPos(ServerLevel level, BlockPos start) {
        if (isSafe(level, start)) return start;
        for (int dy = 1; dy <= MAX_VERTICAL_SEARCH; dy++) {
            BlockPos candidate = start.above(dy);
            if (isSafe(level, candidate)) return candidate;
        }
        for (int r = 1; r <= SPIRAL_RADIUS; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    BlockPos candidate = start.offset(dx, 0, dz);
                    if (isSafe(level, candidate)) return candidate;
                }
            }
        }
        return null;
    }

    private static boolean isSafe(ServerLevel level, BlockPos pos) {
        if (!level.hasChunkAt(pos)) return false;
        if (!level.getBlockState(pos).isAir() || !level.getBlockState(pos.above()).isAir()) return false;
        BlockState below = level.getBlockState(pos.below());
        return !below.getCollisionShape(level, pos.below()).isEmpty();
    }

    private static void ok(ServerPlayer player, String key, Object... args) {
        player.displayClientMessage(Component.translatable(key, args), true);
    }

    private static void fail(ServerPlayer player, String key, Object... args) {
        player.displayClientMessage(Component.translatable(key, args), true);
    }

    /** 推送玩家当前占用掩码到客户端（tooltip 实时数量）。 */
    private static void syncToClient(ServerPlayer player) {
        byte mask = OathRingSavedData.get(player.getServer()).maskFor(player.getUUID());
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                player, new com.wsteam.wandscape.ring.network.OathRingDataPacket(mask));
    }

    private static String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }
}