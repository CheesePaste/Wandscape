package com.wsteam.wandscape.content.command;

import com.wsteam.wandscape.api.WandscapeApis;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * 命令域共享小工具：殖民地归属解析、玩家获取、失败反馈的公共兜底。
 *
 * <p>解析优先级（与 ColonyCommand/ColonyApi 一人一小镇规则一致）：
 * 创始人所拥有小镇 → 位置所在殖民地（256 格内）→ 任意第一个殖民地 → null。
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
     * 按「创始人所拥有 → 位置所在 → 任意第一个」解析；殖民地系统未就绪或全空返回 null。
     */
    @Nullable
    static UUID resolveColony(CommandSourceStack src) {
        var colonyApi = WandscapeApis.getColonyApiSilently();
        if (colonyApi == null) return null;
        ServerPlayer p = src.getPlayer();
        if (p != null) {
            UUID owned = colonyApi.getColonyByFounder(p.getUUID());
            if (owned != null) return owned;
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
