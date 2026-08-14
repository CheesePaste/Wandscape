package com.wsteam.wandscape.engine.colony;

import java.util.UUID;

import javax.annotation.Nullable;

import com.wsteam.wandscape.Config;
import com.wsteam.wandscape.shared.api.ColonyApi;
import com.wsteam.wandscape.shared.registry.WandscapeApis;

import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * 殖民地自动化激活判定：控制「玩家不在线时殖民地是否运行」。
 *
 * <p>{@code Config.COLONY_RUN_WHEN_PLAYER_OFFLINE} 为 true（默认）时恒激活——
 * 服务器无人也在运行；为 false 时，仅当殖民地创始人玩家在线才激活，否则该殖民地
 * 的自动化（NPC 建造/生产、游客经济、每日结算）原地冻结，创始人上线后恢复。
 *
 * <p>无创始人（历史殖民地/命令创建时未指定）无法判定在线状态，视为始终激活，
 * 避免殖民地被误冻结。
 */
public final class ColonyActivation {

    private ColonyActivation() {
    }

    /** 该殖民地的自动化是否应继续运行（冻结判定）。 */
    public static boolean isColonyActive(@Nullable UUID colonyId) {
        if (colonyId == null) return true;
        if (Config.COLONY_RUN_WHEN_PLAYER_OFFLINE.get()) return true;

        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return true;
        ColonyApi api = WandscapeApis.getColonyApiSilently();
        if (api == null) return true;

        UUID founder = api.getFounder(colonyId);
        if (founder == null) return true; // 无创始人 → 无法判定 → 保持运行
        return server.getPlayerList().getPlayer(founder) != null;
    }
}
