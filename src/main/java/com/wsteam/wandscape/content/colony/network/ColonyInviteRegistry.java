package com.wsteam.wandscape.content.colony.network;

import com.wsteam.wandscape.content.colony.roster.ColonyRole;
import com.wsteam.wandscape.foundation.log.Log;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * 服务端内存里的「待处理邀请」表：invitee → (colonyId → 一条邀请)。
 *
 * <p>**刻意只在内存、不落盘**（2026-10 裁定，别顺手加持久化）：
 * <ul>
 *   <li>本阶段 INVITE 要求目标在线，不存在「离线挂着等上线」的邀请；服务器重启时在线玩家的邀请
 *       本来就已随连接断开，重启即作废是正确语义，不是丢失。</li>
 *   <li>落盘会引入「存档里躺着一条一个月前没人处理的邀请」这类必须配过期/清理策略的状态，
 *       属于本阶段明确不做的事。</li>
 * </ul>
 *
 * <p>因此这里没有 JVM 级泄漏负担：{@link #clear()} 在服务端停止时调用，进程内也不跨存档残留
 * （同一 JVM 换存档会重新触发 server stopped）。
 *
 * <p>线程安全用 ConcurrentHashMap：写入来自服务端主线程的包处理，读取同样在主线程，但保留并发容器
 * 以免将来被异步路径调用时静默出问题。
 */
final class ColonyInviteRegistry {

    private static final String TAG = "ColonyInviteRegistry";

    /**
     * 一条待处理邀请。
     *
     * <p>{@code inviterName} 在邀请时就定死：之后发花名册快照时不必再反查（对方可能已离线、
     * 档案缓存里也未必还有）。
     */
    record PendingInvite(UUID colonyId, ColonyRole role, UUID inviterId, String inviterName) {}

    private static final Map<UUID, Map<UUID, PendingInvite>> PENDING = new ConcurrentHashMap<>();

    private ColonyInviteRegistry() {}

    /** 记录/覆盖一条邀请（同一人对同一镇重复邀请只刷新档位，不堆多条）。 */
    static void put(UUID invitee, UUID colonyId, ColonyRole role, UUID inviterId, String inviterName) {
        if (invitee == null || colonyId == null || role == null) return;
        PENDING.computeIfAbsent(invitee, k -> new ConcurrentHashMap<>())
                .put(colonyId, new PendingInvite(colonyId, role, inviterId, inviterName != null ? inviterName : ""));
    }

    /** 某人在某镇的那条邀请；没有返回 null。 */
    @Nullable
    static PendingInvite get(UUID invitee, UUID colonyId) {
        if (invitee == null || colonyId == null) return null;
        Map<UUID, PendingInvite> invites = PENDING.get(invitee);
        return invites != null ? invites.get(colonyId) : null;
    }

    /** 丢弃一条邀请；确实丢掉了返回 true。 */
    static boolean remove(UUID invitee, UUID colonyId) {
        if (invitee == null || colonyId == null) return false;
        Map<UUID, PendingInvite> invites = PENDING.get(invitee);
        if (invites == null) return false;
        boolean removed = invites.remove(colonyId) != null;
        if (invites.isEmpty()) PENDING.remove(invitee, invites);
        return removed;
    }

    /**
     * 某人的全部待处理邀请，顺带用 {@code stillValid} 剔除已失效的（镇没了 / 人已入伙），
     * 失效条目**同时从表里删掉**——邀请是内存态，不需要保留任何历史。
     */
    static List<PendingInvite> pendingFor(UUID invitee, Predicate<PendingInvite> stillValid) {
        if (invitee == null) return List.of();
        Map<UUID, PendingInvite> invites = PENDING.get(invitee);
        if (invites == null || invites.isEmpty()) return List.of();

        List<PendingInvite> out = new ArrayList<>(invites.size());
        for (PendingInvite invite : new ArrayList<>(invites.values())) {
            boolean valid;
            try {
                valid = stillValid.test(invite);
            } catch (Throwable t) {
                Log.warn(TAG, "Invite validity check failed for colony {}: {}",
                        invite.colonyId(), t.toString());
                valid = false;
            }
            if (valid) {
                out.add(invite);
            } else {
                invites.remove(invite.colonyId(), invite);
                Log.info(TAG, "Dropped stale invite of {} to colony {}",
                        invitee, invite.colonyId().toString().substring(0, 8));
            }
        }
        if (invites.isEmpty()) PENDING.remove(invitee, invites);
        return out;
    }

    /** 全部清空（服务端停止）。 */
    static void clear() {
        PENDING.clear();
    }
}
