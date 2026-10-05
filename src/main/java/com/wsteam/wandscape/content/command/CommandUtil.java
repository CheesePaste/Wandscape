package com.wsteam.wandscape.content.command;

import com.wsteam.wandscape.api.WandscapeApis;
import com.wsteam.wandscape.content.colony.ownership.ColonyOwnership;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * 命令域共享小工具：殖民地归属解析、玩家获取、失败反馈的公共兜底。
 *
 * <p>解析优先级（多殖民地后「我的镇」= 玩家**当前操作**的小镇，不再等于「我创始的那座」）：
 * 当前镇 → 位置所在殖民地（256 格内，仅 OP）→ 任意第一个殖民地 → null。
 */
final class CommandUtil {

    private CommandUtil() {}

    /** 取执行者玩家；非玩家（控制台/命令方块）返回 null 且不重复报错（由调用方给出玩家专属提示）。 */
    @Nullable
    static ServerPlayer player(CommandSourceStack src) {
        return src.getPlayer();
    }

    /**
     * 解析 `src` 执行者当前应绑定的殖民地 id。
     * 玩家：当前镇 → 若为 OP 允许所在位置殖民地 → 否则 null（绝不回退至其他玩家小镇）。
     * 控制台/命令方块：位置所在 → 任意第一个 → null。
     */
    @Nullable
    static UUID resolveColony(CommandSourceStack src) {
        var colonyApi = WandscapeApis.getColonyApiSilently();
        if (colonyApi == null) return null;
        ServerPlayer p = src.getPlayer();
        if (p != null) {
            // 「我当前操作的小镇」（可显式切换、跨重连持久化），不再等于「我创始的那座」。
            UUID active = ColonyOwnership.activeColony(p);
            if (active != null) return active;
            UUID at = colonyApi.getColonyId(BlockPos.containing(src.getPosition()));
            if (at != null && p.hasPermissions(2)) return at;
            return null;
        }
        UUID at = colonyApi.getColonyId(BlockPos.containing(src.getPosition()));
        if (at != null) return at;
        var ids = colonyApi.getAllColonyIds();
        return ids.isEmpty() ? null : ids.iterator().next();
    }

    /** 简化 id 短串（前 8 位）。 */
    static String shortId(UUID id) {
        return id == null ? "?" : id.toString().substring(0, 8);
    }
}
