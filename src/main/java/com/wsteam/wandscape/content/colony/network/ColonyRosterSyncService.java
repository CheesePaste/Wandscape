package com.wsteam.wandscape.content.colony.network;

import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.api.ColonyApi;
import com.wsteam.wandscape.api.WandscapeApis;
import com.wsteam.wandscape.content.colony.roster.ColonyRole;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.foundation.networking.Net;
import com.wsteam.wandscape.foundation.ui.I18n;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 服务端花名册快照的构建与推送：谁在什么时候收到 {@link ColonyRosterSyncPacket} /
 * {@link ColonyListSyncPacket}，只在本类决定。
 *
 * <p>推送时机是「事件驱动 + 登录补基线」，不是客户端来要（三个冻结包里没有请求包）：
 * <ul>
 *   <li>玩家登录：推全量（列表 + 他参与的每座镇的花名册 + 发给他的待处理邀请）。</li>
 *   <li>每一次花名册改动后：由 {@link ColonyMemberActionPacket} 调 {@link #pushColony} /
 *       {@link #pushFor} 推最新快照。</li>
 * </ul>
 *
 * <p>一个刻意的取舍：{@link #pushFor} 会把玩家**参与的每一座镇**都单独推一份花名册。客户端因此
 * 手里有全部镇的花名册缓存，侧边栏点「切换小镇」时可以直接换视图，不必再开一个「请给我那座镇的成员」
 * 的请求包（包体契约已冻结，不多开）。镇数量是玩家数量级（个位数），多推几个包的代价可以忽略。
 *
 * <p>所有方法都只在服务端主线程调用（包处理器 / 事件），且全部自带 try/catch：
 * 快照构建失败绝不能让登录或成员操作本身失败。
 */
@EventBusSubscriber(modid = Wandscape.MODID)
public final class ColonyRosterSyncService {

    private static final String TAG = "ColonyRosterSyncService";

    private ColonyRosterSyncService() {}

    // ══════════════════════════════════════════════════════════════
    //  事件
    // ══════════════════════════════════════════════════════════════

    /** 入服补基线：不发这一下，面板要等玩家先做一次成员操作才有内容。 */
    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            pushFor(player);
        }
    }

    /**
     * 服务端停止：清空内存里的待处理邀请。
     *
     * <p>邀请**刻意不落盘**（见 {@link ColonyInviteRegistry}），所以同一 JVM 换存档时必须清干净，
     * 否则上一局的邀请会跟着新世界冒出来。
     */
    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        ColonyInviteRegistry.clear();
    }

    // ══════════════════════════════════════════════════════════════
    //  推送
    // ══════════════════════════════════════════════════════════════

    /** 向一个玩家推全量：列表 + 他参与的每座镇的花名册 +（若有）发给他的待处理邀请。 */
    static void pushFor(ServerPlayer player) {
        if (player == null || player.isRemoved()) return;
        try {
            MinecraftServer server = player.getServer();
            ColonyApi api = WandscapeApis.getColonyApiSilently();
            if (server == null || api == null) return;

            pushList(player);
            Set<UUID> pushed = new HashSet<>();
            for (UUID colonyId : sortedColonies(api, player.getUUID()).keySet()) {
                sendRoster(player, server, api, colonyId);
                pushed.add(colonyId);
            }
            // 一座镇都没加入、但有人邀他入伙：邀请挂在花名册包里，必须至少送一个包，
            // 否则被邀方根本看不到邀请（这正是不用再开一个邀请包的原因，也是它的代价）。
            for (UUID invitedColony : invitedColonyIds(api, player.getUUID())) {
                if (pushed.add(invitedColony)) sendRoster(player, server, api, invitedColony);
            }
        } catch (Throwable t) {
            Log.warn(TAG, "Failed to push colony roster to {}: {}",
                    player.getGameProfile().getName(), t.toString());
        }
    }

    /** 花名册变更后：向该镇所有在线成员推最新花名册 + 列表。 */
    static void pushColony(@Nullable MinecraftServer server, @Nullable UUID colonyId) {
        if (server == null || colonyId == null) return;
        try {
            ColonyApi api = WandscapeApis.getColonyApiSilently();
            if (api == null) return;
            for (UUID memberId : new ArrayList<>(api.getRoster(colonyId).keySet())) {
                ServerPlayer member = server.getPlayerList().getPlayer(memberId);
                if (member != null && !member.isRemoved()) {
                    sendRoster(member, server, api, colonyId);
                    pushList(member, server, api);
                }
            }
        } catch (Throwable t) {
            Log.warn(TAG, "Failed to push roster of colony {}: {}", shortId(colonyId), t.toString());
        }
    }

    /** 单发一座镇的花名册（含收件人的全部待处理邀请）；用于被邀方入选/切换镇时立刻可见。 */
    static void pushRoster(ServerPlayer viewer, @Nullable UUID colonyId) {
        if (viewer == null || viewer.isRemoved() || colonyId == null) return;
        MinecraftServer server = viewer.getServer();
        ColonyApi api = WandscapeApis.getColonyApiSilently();
        if (server == null || api == null) return;
        sendRoster(viewer, server, api, colonyId);
    }

    /**
     * 被移出该镇的人：推一份 {@code myRole=null}、成员为空的快照。
     *
     * <p>为什么不是普通快照：移出后他已不是成员，再给他完整成员名单既无必要也违背「非成员零权限」；
     * 而客户端必须收到一个「你不在里面了」的信号才能清掉该镇缓存。
     */
    static void pushDetached(ServerPlayer viewer, @Nullable UUID colonyId) {
        if (viewer == null || viewer.isRemoved() || colonyId == null) return;
        MinecraftServer server = viewer.getServer();
        ColonyApi api = WandscapeApis.getColonyApiSilently();
        if (server == null || api == null) return;
        Net.toPlayer(viewer, new ColonyRosterSyncPacket(colonyId, safeName(api, colonyId), null,
                List.of(), invitesFor(api, viewer.getUUID())));
    }

    /** 单发「我的小镇」列表。 */
    static void pushList(ServerPlayer viewer) {
        if (viewer == null || viewer.isRemoved()) return;
        MinecraftServer server = viewer.getServer();
        ColonyApi api = WandscapeApis.getColonyApiSilently();
        if (server == null || api == null) return;
        pushList(viewer, server, api);
    }

    private static void pushList(ServerPlayer viewer, MinecraftServer server, ColonyApi api) {
        List<ColonyListSyncPacket.Entry> entries = new ArrayList<>();
        for (Map.Entry<UUID, ColonyRole> entry : sortedColonies(api, viewer.getUUID()).entrySet()) {
            UUID colonyId = entry.getKey();
            entries.add(new ColonyListSyncPacket.Entry(colonyId, safeName(api, colonyId),
                    api.getColonyLevel(colonyId), entry.getValue()));
        }
        Net.toPlayer(viewer, new ColonyListSyncPacket(entries));
    }

    // ══════════════════════════════════════════════════════════════
    //  快照构建
    // ══════════════════════════════════════════════════════════════

    private static void sendRoster(ServerPlayer viewer, MinecraftServer server, ColonyApi api, UUID colonyId) {
        Net.toPlayer(viewer, buildRoster(server, api, colonyId, viewer));
    }

    private static ColonyRosterSyncPacket buildRoster(MinecraftServer server, ColonyApi api,
                                                     UUID colonyId, ServerPlayer viewer) {
        Map<UUID, ColonyRole> roster = api.getRoster(colonyId);
        List<ColonyRosterSyncPacket.Member> members = new ArrayList<>(roster.size());
        for (Map.Entry<UUID, ColonyRole> entry : roster.entrySet()) {
            members.add(new ColonyRosterSyncPacket.Member(entry.getKey(),
                    displayName(server, entry.getKey()), entry.getValue()));
        }
        // 档位从高到低、同级按名字：面板按行渲染，顺序必须稳定，否则每次同步行都会跳。
        members.sort(Comparator.comparingInt((ColonyRosterSyncPacket.Member m) -> -rank(m.role()))
                .thenComparing(ColonyRosterSyncPacket.Member::name, String.CASE_INSENSITIVE_ORDER));

        return new ColonyRosterSyncPacket(colonyId, safeName(api, colonyId),
                api.getRole(colonyId, viewer.getUUID()), members, invitesFor(api, viewer.getUUID()));
    }

    /** 玩家参与的镇，按「我在该镇的档位」从高到低排序（OWNER 的镇在前）。 */
    private static Map<UUID, ColonyRole> sortedColonies(ColonyApi api, UUID playerId) {
        List<Map.Entry<UUID, ColonyRole>> entries = new ArrayList<>(api.getColoniesOf(playerId).entrySet());
        entries.sort(Comparator.comparingInt((Map.Entry<UUID, ColonyRole> e) -> -rank(e.getValue()))
                .thenComparing((Map.Entry<UUID, ColonyRole> e) -> safeName(api, e.getKey()),
                        String.CASE_INSENSITIVE_ORDER));
        Map<UUID, ColonyRole> sorted = new LinkedHashMap<>();
        for (Map.Entry<UUID, ColonyRole> entry : entries) {
            sorted.put(entry.getKey(), entry.getValue());
        }
        return sorted;
    }

    /**
     * 发给该玩家的待处理邀请（跨镇）。
     *
     * <p>顺带让 {@link ColonyInviteRegistry} 剔除已失效的邀请：镇没了、或人已经入伙了。
     * 邀请是内存态，没有「历史记录」这回事，失效即删。
     */
    private static List<ColonyRosterSyncPacket.Invite> invitesFor(ColonyApi api, UUID invitee) {
        List<ColonyInviteRegistry.PendingInvite> pending = ColonyInviteRegistry.pendingFor(invitee,
                invite -> api.getRole(invite.colonyId(), invitee) == null
                        && !api.getRoster(invite.colonyId()).isEmpty());
        List<ColonyRosterSyncPacket.Invite> invites = new ArrayList<>(pending.size());
        for (ColonyInviteRegistry.PendingInvite invite : pending) {
            invites.add(new ColonyRosterSyncPacket.Invite(invite.colonyId(), safeName(api, invite.colonyId()),
                    invite.inviterName(), invite.role()));
        }
        return invites;
    }

    private static List<UUID> invitedColonyIds(ColonyApi api, UUID invitee) {
        List<UUID> ids = new ArrayList<>();
        for (ColonyRosterSyncPacket.Invite invite : invitesFor(api, invitee)) {
            if (invite.colonyId() != null) ids.add(invite.colonyId());
        }
        return ids;
    }

    // ══════════════════════════════════════════════════════════════
    //  小工具
    // ══════════════════════════════════════════════════════════════

    /** 玩家显示名：在线直接取，离线走档案缓存，都查不到退化成短 id（不显示空白行）。 */
    static String displayName(@Nullable MinecraftServer server, UUID id) {
        if (id == null) return "";
        if (server != null) {
            ServerPlayer online = server.getPlayerList().getPlayer(id);
            if (online != null) return online.getGameProfile().getName();
            try {
                var profileCache = server.getProfileCache();
                if (profileCache != null) {
                    var profile = profileCache.get(id);
                    if (profile.isPresent()) return profile.get().getName();
                }
            } catch (Throwable t) {
                Log.warn(TAG, "Profile lookup failed for {}: {}", shortId(id), t.toString());
            }
        }
        return shortId(id);
    }

    /** 档位显示名（lang 键 + 英文兜底）；{@code null} = 非成员。 */
    public static MutableComponent roleLabel(@Nullable ColonyRole role) {
        if (role == null) return I18n.name("gui.wandscape.colony_network.role.none", "Non-member");
        return I18n.name("gui.wandscape.colony_network.role." + role.name().toLowerCase(Locale.ROOT), role.name());
    }

    private static String safeName(ColonyApi api, UUID colonyId) {
        try {
            String name = api.getColonyName(colonyId);
            return name != null ? name : "";
        } catch (Throwable t) {
            Log.warn(TAG, "Failed to read colony name {}: {}", shortId(colonyId), t.toString());
            return "";
        }
    }

    private static int rank(@Nullable ColonyRole role) {
        return role != null ? role.rank() : 0;
    }

    private static String shortId(UUID id) {
        return id != null ? id.toString().substring(0, 8) : "none";
    }
}
