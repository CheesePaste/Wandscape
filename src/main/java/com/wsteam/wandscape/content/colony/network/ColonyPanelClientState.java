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
 * <p>扁平视图（{@link #getColonyName()} / {@link #getMyRole()} / {@link #getMembers()}）永远描述
 * **当前选中的那座镇**：内部按 colonyId 缓存每座镇的花名册，{@link #setSelectedColony} 切镇时立刻
 * 用缓存换视图，所以点列表切换不需要再向服务端要一次数据。
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

    /** 单座镇的花名册缓存。 */
    private record RosterSnapshot(String colonyName, ColonyRole myRole, List<MemberEntry> members) {}

    private static volatile List<ColonyEntry> colonies = List.of();
    private static volatile UUID selectedColony = null;
    private static volatile String colonyName = "";
    private static volatile ColonyRole myRole = null;
    private static volatile List<MemberEntry> members = List.of();
    private static volatile List<InviteEntry> pendingInvites = List.of();
    private static final Map<UUID, RosterSnapshot> rosters = new ConcurrentHashMap<>();

    private ColonyPanelClientState() {}

    // ── 扁平视图（永远对应 selectedColony）──

    /** 我的小镇列表；未收到同步时空表（永不 null）。 */
    public static List<ColonyEntry> getColonies() { return colonies; }

    public static void setColonies(@Nullable List<ColonyEntry> value) {
        colonies = value != null ? List.copyOf(value) : List.of();
    }

    /** 当前选中的小镇；尚未选中返回 null。 */
    @Nullable
    public static UUID getSelectedColony() { return selectedColony; }

    /** 切换当前小镇：立刻用该镇已缓存的花名册换视图（没缓存到就显示空表，等包到达再填）。 */
    public static void setSelectedColony(@Nullable UUID colonyId) {
        selectedColony = colonyId;
        RosterSnapshot snapshot = colonyId != null ? rosters.get(colonyId) : null;
        if (snapshot != null) {
            colonyName = snapshot.colonyName();
            myRole = snapshot.myRole();
            members = snapshot.members();
        } else {
            colonyName = "";
            myRole = null;
            members = List.of();
        }
    }

    /** 当前选中镇的显示名；无选中或未知返回空串（永不 null）。 */
    public static String getColonyName() { return colonyName; }

    public static void setColonyName(@Nullable String value) { colonyName = value != null ? value : ""; }

    /** 我在当前选中镇的档位；非成员/无选中返回 null。 */
    @Nullable
    public static ColonyRole getMyRole() { return myRole; }

    public static void setMyRole(@Nullable ColonyRole value) { myRole = value; }

    /** 当前选中镇的成员；无选中返回空表（永不 null）。 */
    public static List<MemberEntry> getMembers() { return members; }

    public static void setMembers(@Nullable List<MemberEntry> value) {
        members = value != null ? List.copyOf(value) : List.of();
    }

    /** 发给我的待处理邀请（全部小镇，跨镇合并）；无邀请时空表（永不 null）。 */
    public static List<InviteEntry> getPendingInvites() { return pendingInvites; }

    public static void setPendingInvites(@Nullable List<InviteEntry> value) {
        pendingInvites = value != null ? List.copyOf(value) : List.of();
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
        // 选中的镇已不在列表里（被移出/镇被删）：退回未选中，避免面板继续显示一座不再属于我的镇。
        UUID selected = selectedColony;
        if (selected != null && converted.stream().noneMatch(e -> selected.equals(e.colonyId()))) {
            setSelectedColony(null);
        }
    }

    /** 落地某座镇的花名册快照（成员 + 我的档位 + 我的全部待处理邀请）。 */
    public static void applyRoster(@Nullable ColonyRosterSyncPacket packet) {
        if (packet == null || packet.colonyId() == null) return;

        List<MemberEntry> converted = new ArrayList<>(packet.members().size());
        for (ColonyRosterSyncPacket.Member member : packet.members()) {
            converted.add(new MemberEntry(member.id(), member.name(), member.role()));
        }
        List<MemberEntry> snapshotMembers = List.copyOf(converted);
        rosters.put(packet.colonyId(),
                new RosterSnapshot(packet.colonyName(), packet.myRole(), snapshotMembers));

        // 待处理邀请与「哪座镇」无关：它是发给我的邀请总表，任何一个花名册包都带全量，直接覆盖。
        List<InviteEntry> invites = new ArrayList<>(packet.pendingInvites().size());
        for (ColonyRosterSyncPacket.Invite invite : packet.pendingInvites()) {
            invites.add(new InviteEntry(invite.colonyId(), invite.colonyName(), invite.inviterName(), invite.role()));
        }
        pendingInvites = List.copyOf(invites);

        // 首次收到自身成员身份的花名册：默认选中它，打开面板即有内容（OWNER 镇排在推送序列最前）。
        if (selectedColony == null && packet.myRole() != null) {
            selectedColony = packet.colonyId();
        }
        if (packet.colonyId().equals(selectedColony)) {
            colonyName = packet.colonyName();
            myRole = packet.myRole();
            members = snapshotMembers;
        }
    }

    /**
     * 断开连接时清零：上一个世界的花名册不得泄漏到下一个世界。
     * 由客户端断开链路调用（与 {@code WandscapePanelState.reset()} 同批）。
     */
    public static void reset() {
        colonies = List.of();
        selectedColony = null;
        colonyName = "";
        myRole = null;
        members = List.of();
        pendingInvites = List.of();
        rosters.clear();
    }
}
