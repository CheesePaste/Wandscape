package com.wsteam.wandscape.content.items.compass;

import com.wsteam.wandscape.content.items.compass.network.CompassTargetPacket;
import com.wsteam.wandscape.content.colony.raid.RaidTownHall;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.api.WandscapeApis;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * 魔法指南针服务端业务：解析玩家自己殖民地的市政厅 + 同步到客户端 + 终极右键传送。
 *
 * <p>「本殖民地」与盟誓戒指同语义（{@code ColonyApi.getColonyByFounder}）；市政厅定位复用
 * {@link RaidTownHall}（category=government 且结构完整）。无殖民地 / 无市政厅时给出上屏提示。
 */
public final class CompassService {
    private static final String TAG = "CompassService";

    private static final int MAX_VERTICAL_SEARCH = 8;
    private static final int SPIRAL_RADIUS = 6;

    private CompassService() {}

    /** 玩家自己殖民地的市政厅 GlobalPos（坐标在主世界）；无殖民地/无市政厅返回 null。 */
    @Nullable
    public static GlobalPos resolveTownHall(ServerPlayer player) {
        UUID colonyId = ownColony(player);
        if (colonyId == null) return null;
        BlockPos hall = RaidTownHall.findTownHall(colonyId);
        if (hall == null) return null;
        ServerLevel overworld = player.getServer() != null ? player.getServer().overworld() : null;
        if (overworld == null) return null;
        return GlobalPos.of(overworld.dimension(), hall);
    }

    /** 推送玩家当前市政厅目标到客户端（供指南针指向 / tooltip 坐标）。 */
    public static void syncFor(ServerPlayer player) {
        GlobalPos target = resolveTownHall(player);
        PacketDistributor.sendToPlayer(player, new CompassTargetPacket(target != null, target));
    }

    /** 终极指南针右键：传送到自己殖民地的市政厅安全落点。 */
    public static void teleportToTownHall(ServerPlayer player) {
        UUID colonyId = ownColony(player);
        if (colonyId == null) {
            fail(player, "message.wandscape.compass.no_colony");
            return;
        }
        BlockPos hall = RaidTownHall.findTownHall(colonyId);
        if (hall == null) {
            fail(player, "message.wandscape.compass.no_town_hall");
            return;
        }
        ServerLevel overworld = player.getServer() != null ? player.getServer().overworld() : null;
        if (overworld == null) {
            fail(player, "message.wandscape.compass.no_town_hall");
            return;
        }
        BlockPos spot = findSafeSpawn(overworld, hall);
        if (spot == null) {
            // 远距离回城时目标区块未加载，isSafe 会对整片区域判 false（hasChunkAt）——
            // 先同步加载市政厅周边区块再搜。服务端传送本就会加载目标区块，这里只是提前加载判定所需的地形；
            // 已加载时 getChunk 是缓存命中，开销可忽略。
            loadAround(overworld, hall);
            spot = findSafeSpawn(overworld, hall);
        }
        if (spot == null) {
            fail(player, "message.wandscape.compass.no_spot");
            return;
        }
        player.teleportTo(overworld, spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5,
                player.getYRot(), player.getXRot());
        ok(player, "message.wandscape.compass.tp", spot.getX(), spot.getY(), spot.getZ());
        syncFor(player); // 传送后把最新市政厅坐标同步回客户端
        Log.info(TAG, "Player {} teleported to town hall at {}", shortId(player.getUUID()), spot);
    }

    /** 玩家创建殖民地的 UUID；无殖民地（含 API 未就绪）返回 null。 */
    @Nullable
    private static UUID ownColony(ServerPlayer player) {
        try {
            var api = WandscapeApis.getColonyApiSilently();
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
    private static BlockPos findSafeSpawn(ServerLevel level, BlockPos start) {
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
        return !level.getBlockState(pos.below()).getCollisionShape(level, pos.below()).isEmpty();
    }

    /** 同步加载锚点周边 3×3 区块：搜索半径 6 的落点可能落在相邻区块，缺块会整片判 false。 */
    private static void loadAround(ServerLevel level, BlockPos center) {
        int chunkX = center.getX() >> 4;
        int chunkZ = center.getZ() >> 4;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                level.getChunk(chunkX + dx, chunkZ + dz);
            }
        }
    }

    private static void ok(ServerPlayer player, String key, Object... args) {
        player.displayClientMessage(Component.translatable(key, args), true);
    }

    private static void fail(ServerPlayer player, String key) {
        player.displayClientMessage(Component.translatable(key), true);
    }

    private static String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }
}
