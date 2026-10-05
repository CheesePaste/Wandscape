package com.wsteam.wandscape.content.colony.network;

import com.wsteam.wandscape.content.colony.roster.ColonyRole;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 侧边栏「小镇」页的客户端镜像状态（服务端权威数据的只读落地）。
 *
 * <p>两个包往里写：{@link ColonyListSyncPacket} 写「我的小镇」列表，
 * {@link ColonyRosterSyncPacket} 写某座镇的成员/档位/待处理邀请。写入即覆盖，客户端不缓存写入意图
 * ——它自己不发请求，只消费服务端推来的快照（登录时 + 每次花名册变更后，见 {@code ColonyRosterSyncService}）。
 *
 * <p>**本类刻意不保存「当前是哪座镇」**（[别顺手加回来] 这里此前有过
 * {@code selectedColony} + 一份扁平视图）。全客户端唯一的「当前镇」是
 * {@code WandscapePanelState.colonyId}——由服务端 {@code ColonyStatsSyncPacket} 推送的当前镇，
 * 顶栏 / 边界 / 设置 / 子面板共用同一份。面板的选中高亮与成员列表都拿那个 id 来这里取缓存，
 * 于是「面板切了但顶栏不变」在结构上不可能发生：根本不存在第二份状态可以漂移。
 * 缺了这份数据只会让面板显示空态，绝不会让两处显示不一致。
 *
 * <p>因此对外的读取一律要求调用方显式给出 colonyId：{@link #roleOf} / {@link #nameOf} /
 * {@link #membersOf}。反过来说，任何形如「把选中项存起来」的字段都是这类 bug 的复发点。
 *
 * <p>字段全部 volatile + ConcurrentHashMap：网络线程写、渲染线程读。零 MC 依赖，可在服务端加载。
 */
public final class ColonyPanelClientState {

    /** 「我的小镇」列表一行。 */
    public record ColonyEntry(UUID colonyId, String name, int level, ColonyRole myRole) {}

    /** 成员列表一行。 */
    public record MemberEntry(UUID id, String name, ColonyRole role) {}

    /** 待处理邀请（跨小镇）。 */
    public record InviteEntry(UUID colonyId, String colonyName, String inviterName, ColonyRole role) {}

    /** 单座镇的花名册缓存（按 colonyId 取，不绑定「选中」概念）。 */
    private record RosterSnapshot(String colonyName, ColonyRole myRole, List<MemberEntry> members) {}

    private static volatile List<ColonyEntry> colonies = List.of();
    private static volatile List<InviteEntry> pendingInvites = List.of();
    private static final Map<UUID, RosterSnapshot> rosters = new ConcurrentHashMap<>();

    private ColonyPanelClientState() {}

    // ── 列表 / 邀请（与「当前是哪座镇」无关，无需 colonyId）──

    /** 我的小镇列表；未收到同步时空表（永不 null）。 */
    public static List<ColonyEntry> getColonies() { return colonies; }

    /** 发给我的待处理邀请（全部小镇，跨镇合并）；无邀请时空表（永不 null）。 */
    public static List<InviteEntry> getPendingInvites() { return pendingInvites; }

    // ── 按 colonyId 取花名册（当前镇由调用方给，见类注释）──

    /** 我在该镇的档位；无该镇快照或非成员返回 null。 */
    @Nullable
    public static ColonyRole roleOf(@Nullable UUID colonyId) {
        RosterSnapshot snapshot = snapshot(colonyId);
        return snapshot != null ? snapshot.myRole() : null;
    }

    /** 该镇显示名；无快照返回空串（永不 null）。 */
    public static String nameOf(@Nullable UUID colonyId) {
        RosterSnapshot snapshot = snapshot(colonyId);
        return snapshot != null && snapshot.colonyName() != null ? snapshot.colonyName() : "";
    }

    /** 该镇成员列表；无快照返回空表（永不 null）。 */
    public static List<MemberEntry> membersOf(@Nullable UUID colonyId) {
        RosterSnapshot snapshot = snapshot(colonyId);
        return snapshot != null ? snapshot.members() : List.of();
    }

    @Nullable
    private static RosterSnapshot snapshot(@Nullable UUID colonyId) {
        return colonyId != null ? rosters.get(colonyId) : null;
    }

    // ── 服务端快照落地 ──

    /** 落地「我的小镇」列表快照。 */
    public static void applyList(@Nullable ColonyListSyncPacket packet) {
        if (packet == null) return;
        List<ColonyEntry> converted = new ArrayList<>(packet.colonies().size());
        for (ColonyListSyncPacket.Entry entry : packet.colonies()) {
            converted.add(new ColonyEntry(entry.colonyId(), entry.name(), entry.level(), entry.myRole()));
        }
        colonies = List.copyOf(converted);
    }

    /** 落地某座镇的花名册快照（成员 + 我的档位 + 我的全部待处理邀请）。 */
    public static void applyRoster(@Nullable ColonyRosterSyncPacket packet) {
        if (packet == null || packet.colonyId() == null) return;

        List<MemberEntry> converted = new ArrayList<>(packet.members().size());
        for (ColonyRosterSyncPacket.Member member : packet.members()) {
            converted.add(new MemberEntry(member.id(), member.name(), member.role()));
        }
        rosters.put(packet.colonyId(),
                new RosterSnapshot(packet.colonyName(), packet.myRole(), List.copyOf(converted)));

        // 待处理邀请与「哪座镇」无关：它是发给我的邀请总表，任何一个花名册包都带全量，直接覆盖。
        List<InviteEntry> invites = new ArrayList<>(packet.pendingInvites().size());
        for (ColonyRosterSyncPacket.Invite invite : packet.pendingInvites()) {
            invites.add(new InviteEntry(invite.colonyId(), invite.colonyName(), invite.inviterName(), invite.role()));
        }
        pendingInvites = List.copyOf(invites);
    }

    /**
     * 断开连接时清零：上一个世界的花名册不得泄漏到下一个世界。
     * 由客户端断开链路调用（与 {@code WandscapePanelState.reset()} 同批）。
     */
    public static void reset() {
        colonies = List.of();
        pendingInvites = List.of();
        rosters.clear();
    }
}
