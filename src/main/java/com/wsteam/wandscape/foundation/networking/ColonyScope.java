package com.wsteam.wandscape.foundation.networking;

import com.wsteam.wandscape.content.colony.ownership.ColonyOwnership;
import com.wsteam.wandscape.content.colony.roster.ColonyRole;
import com.wsteam.wandscape.foundation.log.Log;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * 一个客户端 → 服务端包声明的**殖民地作用域**：目标镇 + 最低档位 + 拒止文案。
 *
 * <p>由 {@link ColonyScopedPayload} 返回，{@link PayloadRegistry#c2s} 在进 handler 前统一把关：
 * 目标镇与最低档位都声明齐了，就按 {@link ColonyOwnership#hasRole} 判档位，不够则由
 * {@link ColonyOwnership#deny} 给出标准拒止反馈（快捷栏 + Toast + 音效 + 日志），**不进 handler**。
 *
 * <p>为什么带 {@code minRole} 而不是只判「是不是我的镇」：四档模型下「我在这镇能干什么」是档位问题，
 * 不是归属问题——INVITE 要 MANAGER、SET_ROLE / REMOVE 要 OWNER、SELECT 只要是成员。用
 * {@code isOwn}（按 founder 反查）会把 MANAGER / MEMBER / ALLY 全部误拒。
 *
 * <p>{@code whatKey}/{@code whatFallback} 是拒止文案的描述（见 {@link ColonyOwnership#deny}），
 * 走两次查表：{@code whatKey} 定 lang 键，{@code whatFallback} 是键缺失时的中文兜底。
 */
public record ColonyScope(@Nullable UUID colonyId, @Nullable ColonyRole minRole,
                          String whatKey, String whatFallback) {

    private static final String TAG = "ColonyScope";

    /** 无镇作用域：不过网关，由 handler 自行校验（建镇流程、被邀方本人的接受/拒绝等）。 */
    public static final ColonyScope NONE = new ColonyScope(null, null, "", "");

    public ColonyScope {
        whatKey = whatKey == null ? "" : whatKey;
        whatFallback = whatFallback == null ? "" : whatFallback;
    }

    /** 声明「目标镇 + 最低档位」的作用域。 */
    public static ColonyScope atLeast(@Nullable UUID colonyId, ColonyRole minRole,
                                      String whatKey, String whatFallback) {
        return new ColonyScope(colonyId, minRole, whatKey, whatFallback);
    }

    /** 本作用域是否需要网关把关（目标镇与最低档位都齐全）。 */
    public boolean gates() {
        return colonyId != null && minRole != null;
    }

    /**
     * 统一网关判定：放行返回 true；拒止（已反馈玩家）返回 false，调用方必须直接 return。
     *
     * <p>没有作用域、作用域不齐全、参数缺失等异常路径一律**放行**——网关只负责它明确知道答案的
     * 那一种情况，其余交给 handler 的权威重判，绝不因网关自身不确定就误拒正常操作。
     */
    public static boolean admit(@Nullable ColonyScopedPayload payload, @Nullable ServerPlayer player) {
        ColonyScope scope = payload != null ? payload.colonyScope() : null;
        if (scope == null || !scope.gates()) return true;
        if (player == null || player.isRemoved()) return false;

        if (ColonyOwnership.hasRole(player, scope.colonyId(), scope.minRole())) return true;

        ColonyOwnership.deny(player, scope.whatKey(), scope.whatFallback());
        Log.warn(TAG, "Denied {} on colony {} (needs {}): {}",
                payload.type().id(), scope.colonyId(), scope.minRole(),
                player.getGameProfile().getName());
        return false;
    }
}
