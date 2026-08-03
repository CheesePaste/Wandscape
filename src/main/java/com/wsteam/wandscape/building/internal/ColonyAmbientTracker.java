package com.wsteam.wandscape.building.internal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.wsteam.wandscape.shared.log.Log;
import com.wsteam.wandscape.shared.network.ColonyAmbientPacket;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 殖民地环境音近距门控（服务端）。
 *
 * <p>每 20 tick 评估：玩家是否位于任一建筑包围盒向外延伸 {@link #TOWN_RADIUS} 格
 * 的范围内（视为"在城镇内"）。进入/离开范围或昼夜相位切换时，向该玩家发送
 * {@link ColonyAmbientPacket}；在城玩家周期性心跳重发，防止丢包导致循环音卡死。
 *
 * <p>相位分界对齐 Config 游客窗口：白天 [1000, 18000)，其余夜晚。
 * 由 {@code Wandscape.onServerTick} 调用。
 */
public final class ColonyAmbientTracker {

    private static final String TAG = "ColonyAmbientTracker";

    /** 建筑包围盒向外延伸的格数，此范围视为城镇。 */
    private static final int TOWN_RADIUS = 20;
    /** 白天开始（游客开始出现）。 */
    private static final int DAY_START_TICK = 1000;
    /** 夜晚开始（游客离场窗口）。 */
    private static final int NIGHT_START_TICK = 18000;
    /** 评估间隔（tick）。 */
    private static final int TICK_INTERVAL = 20;
    /** 在城玩家心跳重发间隔（tick），防丢包。 */
    private static final int HEARTBEAT_INTERVAL = 120;
    /** 定期清理已下线玩家的状态。 */
    private static final int CLEANUP_INTERVAL = 1200;

    private static int counter;
    private static final Map<UUID, Boolean> lastInTown = new HashMap<>();
    private static final Map<UUID, Boolean> lastDay = new HashMap<>();
    private static final Map<UUID, Long> lastSentTick = new HashMap<>();

    private ColonyAmbientTracker() {}

    public static void tick(MinecraftServer server) {
        if (server == null) return;
        if (++counter % TICK_INTERVAL != 0) return;

        ServerLevel level = server.overworld();
        if (level == null) return;
        BuildingSavedData sd = BuildingSavedData.get(level);
        if (sd == null) return;

        long dayTime = level.getDayTime() % 24000L;
        boolean day = dayTime >= DAY_START_TICK && dayTime < NIGHT_START_TICK;
        long now = level.getGameTime();

        List<BoundingBox> townBoxes = new ArrayList<>();
        for (BuildingState b : sd.getAllBuildings()) {
            BoundingBox bounds = b.getBounds();
            if (bounds != null) {
                townBoxes.add(bounds.inflatedBy(TOWN_RADIUS));
            }
        }
        if (counter % 200 == 0) {
            Log.info(TAG, "ambient scan: {} buildings, {} town boxes (day={})",
                    sd.getAllBuildings().size(), townBoxes.size(), day);
        }

        java.util.Set<UUID> active = new java.util.HashSet<>();
        for (ServerPlayer player : level.players()) {
            UUID id = player.getUUID();
            active.add(id);

            boolean inTown = false;
            BlockPos pp = player.blockPosition();
            for (BoundingBox box : townBoxes) {
                if (box.isInside(pp)) {
                    inTown = true;
                    break;
                }
            }

            Boolean lastIt = lastInTown.get(id);
            Boolean lastD = lastDay.get(id);
            boolean stateChanged = lastIt == null || lastIt != inTown || lastD == null || lastD != day;
            boolean heartbeat = inTown && now - lastSentTick.getOrDefault(id, 0L) >= HEARTBEAT_INTERVAL;

            if (stateChanged || heartbeat) {
                lastInTown.put(id, inTown);
                lastDay.put(id, day);
                lastSentTick.put(id, now);
                PacketDistributor.sendToPlayer(player, new ColonyAmbientPacket(inTown, day));
                Log.info(TAG, "player {} -> {} ({} {})", id.toString().substring(0, 8),
                        inTown ? "IN_TOWN" : "OUTSIDE", inTown ? (day ? "DAY" : "NIGHT") : "-",
                        stateChanged ? "changed" : "heartbeat");
            }
        }

        // 定期清理已离开/下线的玩家状态
        if (counter % CLEANUP_INTERVAL == 0) {
            lastInTown.keySet().removeIf(k -> !active.contains(k));
            lastDay.keySet().removeIf(k -> !active.contains(k));
            lastSentTick.keySet().removeIf(k -> !active.contains(k));
        }
    }
}
