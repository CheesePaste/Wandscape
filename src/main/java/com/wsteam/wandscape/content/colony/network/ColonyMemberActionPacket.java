package com.wsteam.wandscape.content.colony.network;

import com.wsteam.wandscape.api.ColonyApi;
import com.wsteam.wandscape.api.WandscapeApis;
import com.wsteam.wandscape.content.colony.ActiveColonyTracker;
import com.wsteam.wandscape.content.colony.ownership.ColonyOwnership;
import com.wsteam.wandscape.content.colony.roster.ColonyRole;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.foundation.networking.ColonyScope;
import com.wsteam.wandscape.foundation.networking.ColonyScopedPayload;
import com.wsteam.wandscape.foundation.networking.ScreenFeedbackPacket;
import com.wsteam.wandscape.foundation.ui.I18n;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.UUID;

import static com.wsteam.wandscape.Wandscape.MODID;

/**
 * Client→Server: 花名册的一次动作（发起邀请 / 接受 / 拒绝 / 调档位 / 移除成员 / 切换查看的小镇）。
 *
 * <p>客户端只发**意图**：包里的 colonyId / target / role 一律视为不可信输入，服务端在 handler 里
 * 按花名册与内存邀请记录**权威重判**每一支动作的权限与参数。档位粗筛由统一网关
 * （{@link ColonyScope} + {@code PayloadRegistry.c2s}）完成，细筛（OWNER 专属、防自锁、不得设 OWNER、
 * 只能授予低于自己的档位……）在本类里，两道都不能省。
 *
 * <p>档位一律用 {@link ColonyRole}，不另立枚举。
 */
public record ColonyMemberActionPacket(Action action, UUID colonyId, UUID target, ColonyRole role)
        implements ColonyScopedPayload {

    /**
     * 成员动作。
     *
     * <p>{@code SELECT} 不是花名册变更，而是「把这座镇设为我的当前镇」：客户端在小镇列表里点一行时发它，
     * 服务端校验「可切换」（档位 ≥ MEMBER，见 {@code ActiveColonyTracker}）后 setActive 并统一推送。
     * {@link ColonyRole#ALLY} 不可切换——它零操作权限，切过去也什么都做不了，故网关就按 MEMBER 拦掉。
     */
    public enum Action { INVITE, ACCEPT, DECLINE, SET_ROLE, REMOVE, SELECT }

    private static final String TAG = "ColonyMemberActionPacket";

    /** 拒止反馈的 lang 键前缀（{@link ColonyOwnership#deny} 的两次查表）。 */
    private static final String MSG = "message.wandscape.colony_network.";
    private static final String WHAT_KEY = "colony_member";
    private static final String WHAT_FALLBACK = "小镇成员";

    public static final Type<ColonyMemberActionPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "colony_member_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ColonyMemberActionPacket> STREAM_CODEC =
            StreamCodec.of(ColonyMemberActionPacket::write, ColonyMemberActionPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    /**
     * 网关作用域：目标镇 + 该动作要求的最低档位。
     *
     * <ul>
     *   <li>{@code INVITE} → MANAGER；{@code SET_ROLE} / {@code REMOVE} → OWNER（仅镇长）；</li>
     *   <li>{@code SELECT} → MEMBER：可切换的下限。ALLY 只有白名单权限，切过去没有意义，网关直接拒；</li>
     *   <li>{@code ACCEPT} / {@code DECLINE} → **不过网关**：执行者是被邀方本人，此刻还不在花名册上、
     *       没有档位可判，必须由 handler 按「服务端内存里发给本人的待处理邀请」校验，
     *       且**绝不能采信客户端传来的 colonyId / role**（否则改包就能以 OWNER 入伙）。</li>
     * </ul>
     */
    @Override
    public ColonyScope colonyScope() {
        if (colonyId == null || action == null) return ColonyScope.NONE;
        return switch (action) {
            case INVITE -> ColonyScope.atLeast(colonyId, ColonyRole.MANAGER, WHAT_KEY, WHAT_FALLBACK);
            case SET_ROLE, REMOVE -> ColonyScope.atLeast(colonyId, ColonyRole.OWNER, WHAT_KEY, WHAT_FALLBACK);
            case SELECT -> ColonyScope.atLeast(colonyId, ColonyRole.MEMBER, WHAT_KEY, WHAT_FALLBACK);
            // ACCEPT / DECLINE：被邀方本人，还不是成员（见上）。将来新增动作若忘记在此声明，
            // 默认不过网关——漏声明只会少一层兜底，不会误拒正常操作，权威判定仍在 handler。
            default -> ColonyScope.NONE;
        };
    }

    public static void handleServer(ColonyMemberActionPacket packet, ServerPlayer player) {
        if (player == null || player.isRemoved()) return;
        MinecraftServer server = player.getServer();
        if (server == null) return;
        // 与既有包一致：真正改动放到服务端线程上做（快照推送也要求在主线程）。
        server.execute(() -> route(packet, player, server));
    }

    private static void route(ColonyMemberActionPacket packet, ServerPlayer actor, MinecraftServer server) {
        try {
            Action action = packet.action();
            if (action == null) {
                toast(actor, true, "invalid", "§c[魔法小镇] 无效的成员操作请求。");
                Log.warn(TAG, "{} sent a member action without an action", name(actor));
                return;
            }
            switch (action) {
                case INVITE -> invite(actor, server, packet.colonyId(), packet.target(), packet.role());
                case ACCEPT -> accept(actor, server, packet.colonyId());
                case DECLINE -> decline(actor, packet.colonyId());
                case SET_ROLE -> setRole(actor, server, packet.colonyId(), packet.target(), packet.role());
                case REMOVE -> remove(actor, server, packet.colonyId(), packet.target());
                case SELECT -> select(actor, packet.colonyId());
            }
        } catch (Throwable t) {
            Log.warn(TAG, "Member action {} from {} failed: {}", packet.action(), name(actor), t.toString());
            toast(actor, true, "failed", "§c[魔法小镇] 成员操作失败，请查看服务端日志。");
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  INVITE：≥ MANAGER 可邀，只能授予严格低于自己的档位，不得授予 OWNER
    // ══════════════════════════════════════════════════════════════

    private static void invite(ServerPlayer actor, MinecraftServer server,
                               @Nullable UUID colonyId, @Nullable UUID targetId, @Nullable ColonyRole requested) {
        ColonyApi api = colonyApi();
        if (api == null || colonyId == null || targetId == null) {
            toast(actor, true, "invalid", "§c[魔法小镇] 无效的成员操作请求。");
            return;
        }
        // 权威重判（网关已按 MANAGER 兜底；这里不依赖网关单独下结论，网关被绕过也不能越权）。
        if (!ColonyOwnership.hasRole(actor, colonyId, ColonyRole.MANAGER)) {
            ColonyOwnership.deny(actor, WHAT_KEY, WHAT_FALLBACK);
            return;
        }
        if (targetId.equals(actor.getUUID())) {
            toast(actor, true, "invalid", "§c[魔法小镇] 无效的成员操作请求。");
            return;
        }
        ColonyRole grant = requested != null ? requested : ColonyRole.MEMBER;
        if (grant == ColonyRole.OWNER) {
            toast(actor, true, "cannot_grant_owner",
                    "§c[魔法小镇] 不能通过邀请授予 OWNER；所有权转让请用转让功能。");
            return;
        }
        ColonyRole actorRole = api.getRole(colonyId, actor.getUUID());
        if (actorRole == null || !actorRole.atLeast(ColonyRole.MANAGER)) {
            // 花名册读不到档位时按无权限拒——绝不允许「读不到就当有权」。
            ColonyOwnership.deny(actor, WHAT_KEY, WHAT_FALLBACK);
            return;
        }
        // 只能授予严格低于自己的档位：MANAGER 可授 MEMBER/ALLY，OWNER 可授 MANAGER/MEMBER/ALLY。
        if (grant.rank() >= actorRole.rank()) {
            toast(actor, true, "cannot_grant_higher",
                    "§c[魔法小镇] 只能授予严格低于自己档位的档位。");
            return;
        }
        ServerPlayer target = server.getPlayerList().getPlayer(targetId);
        if (target == null || target.isRemoved()) {
            toast(actor, true, "target_offline", "§c[魔法小镇] 该玩家不在线。");
            return;
        }
        if (api.getRole(colonyId, targetId) != null) {
            toast(actor, true, "already_member", "§c[魔法小镇] %s 已在此小镇花名册中。",
                    target.getGameProfile().getName());
            return;
        }

        String colonyName = safeName(api, colonyId);
        ColonyInviteRegistry.put(targetId, colonyId, grant, actor.getUUID(), actor.getGameProfile().getName());

        // 被邀方：一条聊天提示（邀请要引他去别的界面操作，不能只用会消失的 Action Bar）
        // + 立刻推一份花名册，让他的面板当场出现这条邀请。
        target.sendSystemMessage(I18n.name(MSG + "invited",
                "§e[魔法小镇] %s 邀请你加入「%s」，档位 %s。到侧边栏「小镇」页可以接受或拒绝。",
                actor.getGameProfile().getName(), colonyName, ColonyRosterSyncService.roleLabel(grant)));
        ColonyRosterSyncService.pushRoster(target, colonyId);

        toast(actor, false, "invite_sent", "§a[魔法小镇] 已邀请 %s 加入「%s」，档位 %s。",
                target.getGameProfile().getName(), colonyName, ColonyRosterSyncService.roleLabel(grant));
        Log.info(TAG, "{} invited {} to colony {} as {}",
                name(actor), target.getGameProfile().getName(), shortId(colonyId), grant);
    }

    // ══════════════════════════════════════════════════════════════
    //  ACCEPT / DECLINE：只认「服务端内存里发给本人的邀请」
    // ══════════════════════════════════════════════════════════════

    private static void accept(ServerPlayer actor, MinecraftServer server, @Nullable UUID colonyId) {
        ColonyApi api = colonyApi();
        if (api == null) {
            toast(actor, true, "failed", "§c[魔法小镇] 成员操作失败，请查看服务端日志。");
            return;
        }
        if (colonyId == null) {
            toast(actor, true, "invalid", "§c[魔法小镇] 无效的成员操作请求。");
            return;
        }

        // 权威性核心：colonyId 只用来**定位**邀请；入伙用的 colonyId 与档位都取自服务端那条记录，
        // 客户端传来的 role 直接忽略。篡改包体无法把自己写成 OWNER。
        ColonyInviteRegistry.PendingInvite invite = ColonyInviteRegistry.get(actor.getUUID(), colonyId);
        if (invite == null) {
            toast(actor, true, "no_invite",
                    "§c[魔法小镇] 没有找到发给你的该小镇邀请（可能已处理或已过期）。");
            return;
        }

        UUID invitedColony = invite.colonyId();
        if (!colonyExists(api, invitedColony)) {
            ColonyInviteRegistry.remove(actor.getUUID(), invitedColony);
            toast(actor, true, "colony_gone", "§c[魔法小镇] 该小镇已不存在。");
            ColonyRosterSyncService.pushFor(actor);
            return;
        }
        if (api.getRole(invitedColony, actor.getUUID()) != null) {
            // 已在花名册（例如被别的路径先加进去了）：邀请作废，不覆盖已有档位。
            ColonyInviteRegistry.remove(actor.getUUID(), invitedColony);
            toast(actor, true, "you_already_member", "§c[魔法小镇] 你已是「%s」的成员。",
                    safeName(api, invitedColony));
            ColonyRosterSyncService.pushFor(actor);
            return;
        }
        if (!api.setRole(invitedColony, actor.getUUID(), invite.role())) {
            toast(actor, true, "write_failed", "§c[魔法小镇] 花名册写入失败，请重试。");
            Log.warn(TAG, "setRole failed for {} accepting invite to colony {}",
                    name(actor), shortId(invitedColony));
            return;
        }

        ColonyInviteRegistry.remove(actor.getUUID(), invitedColony);
        String colonyName = safeName(api, invitedColony);
        actor.sendSystemMessage(I18n.name(MSG + "joined", "§a[魔法小镇] 你已加入「%s」，档位 %s。",
                colonyName, ColonyRosterSyncService.roleLabel(invite.role())));
        Log.info(TAG, "{} joined colony {} as {}", name(actor), shortId(invitedColony), invite.role());

        // 接受邀请后**自动切到该镇**（用户明确要求），但**不传送**——位置的改变只能由玩家自己决定，
        // 这里只换「当前镇」上下文。
        // 邀请档位可能是 ALLY：ALLY 不可切换，那就只入伙、不动当前镇——绝不因此把邀请判为失败。
        ColonyRole granted = invite.role();
        if (granted != null && granted.atLeast(ActiveColonyTracker.MIN_SWITCH_ROLE)) {
            if (!ActiveColonyTracker.setActive(actor, invitedColony)) {
                Log.warn(TAG, "Accepted the invite to {} but could not make it the active colony for {}",
                        shortId(invitedColony), name(actor));
            }
        } else {
            Log.info(TAG, "{} joined colony {} as {}; active colony unchanged (ALLY is not switchable)",
                    name(actor), shortId(invitedColony), granted);
        }

        // 其余成员：花名册变了，推最新快照 + 列表。
        ColonyRosterSyncService.pushColony(server, invitedColony);
        // 本人：走统一入口一次推齐（切换后顶栏/边界/教程/花名册/列表必须同时换）。
        ColonyContextSync.push(actor);
    }

    private static void decline(ServerPlayer actor, @Nullable UUID colonyId) {
        if (colonyId == null) {
            toast(actor, true, "invalid", "§c[魔法小镇] 无效的成员操作请求。");
            return;
        }
        ColonyInviteRegistry.PendingInvite invite = ColonyInviteRegistry.get(actor.getUUID(), colonyId);
        if (invite == null) {
            toast(actor, true, "no_invite",
                    "§c[魔法小镇] 没有找到发给你的该小镇邀请（可能已处理或已过期）。");
            // 客户端手上可能还挂着一条已失效的邀请，推一次权威快照收敛。
            ColonyRosterSyncService.pushFor(actor);
            return;
        }
        ColonyInviteRegistry.remove(actor.getUUID(), invite.colonyId());
        ColonyApi api = colonyApi();
        String colonyName = api != null ? safeName(api, invite.colonyId()) : "";
        toast(actor, false, "declined", "§7[魔法小镇] 已拒绝加入「%s」。", colonyName);
        Log.info(TAG, "{} declined the invite to colony {}", name(actor), shortId(invite.colonyId()));
        ColonyRosterSyncService.pushFor(actor);
    }

    // ══════════════════════════════════════════════════════════════
    //  SET_ROLE / REMOVE：仅 OWNER，且防自锁、不动 OWNER
    // ══════════════════════════════════════════════════════════════

    private static void setRole(ServerPlayer actor, MinecraftServer server,
                                @Nullable UUID colonyId, @Nullable UUID targetId, @Nullable ColonyRole newRole) {
        ColonyApi api = colonyApi();
        if (api == null || colonyId == null || targetId == null || newRole == null) {
            toast(actor, true, "invalid", "§c[魔法小镇] 无效的成员操作请求。");
            return;
        }
        if (newRole == ColonyRole.OWNER) {
            // 所有权变更只有一个入口：ColonyApi.transferOwner（保证「一座镇恒有且仅有一个 OWNER」）。
            toast(actor, true, "cannot_set_owner",
                    "§c[魔法小镇] 不能把他人设为 OWNER；所有权转让请用转让功能。");
            return;
        }
        if (!ColonyOwnership.hasRole(actor, colonyId, ColonyRole.OWNER)) {
            ColonyOwnership.deny(actor, WHAT_KEY, WHAT_FALLBACK);
            return;
        }
        // 防自锁：镇长不能给自己降级（要交接请走 transferOwner）。
        if (targetId.equals(actor.getUUID())) {
            toast(actor, true, "self_lock", "§c[魔法小镇] 不能降级或移除自己。");
            return;
        }
        ColonyRole targetRole = api.getRole(colonyId, targetId);
        if (targetRole == null) {
            toast(actor, true, "target_not_member", "§c[魔法小镇] 该玩家不在此小镇花名册中。");
            return;
        }
        if (targetRole == ColonyRole.OWNER) {
            toast(actor, true, "cannot_touch_owner", "§c[魔法小镇] 不能改动或移除 OWNER 的档位。");
            return;
        }
        String targetName = ColonyRosterSyncService.displayName(server, targetId);
        if (targetRole == newRole) {
            // setRole 返回 false 同时也是「无变化」，所以先分流，避免把「本来就是这档」报成写入失败。
            toast(actor, false, "unchanged", "§7[魔法小镇] %s 的档位已是 %s，未做改动。",
                    targetName, ColonyRosterSyncService.roleLabel(newRole));
            return;
        }
        if (!api.setRole(colonyId, targetId, newRole)) {
            toast(actor, true, "write_failed", "§c[魔法小镇] 花名册写入失败，请重试。");
            Log.warn(TAG, "setRole failed: colony {} player {} → {}",
                    shortId(colonyId), shortId(targetId), newRole);
            return;
        }
        toast(actor, false, "role_changed", "§a[魔法小镇] 已将 %s 的档位改为 %s。",
                targetName, ColonyRosterSyncService.roleLabel(newRole));
        Log.info(TAG, "{} set {} of colony {} to {}", name(actor), targetName, shortId(colonyId), newRole);

        ColonyRosterSyncService.pushColony(server, colonyId);
        ColonyRosterSyncService.pushFor(actor);
    }

    private static void remove(ServerPlayer actor, MinecraftServer server,
                               @Nullable UUID colonyId, @Nullable UUID targetId) {
        ColonyApi api = colonyApi();
        if (api == null || colonyId == null || targetId == null) {
            toast(actor, true, "invalid", "§c[魔法小镇] 无效的成员操作请求。");
            return;
        }
        if (!ColonyOwnership.hasRole(actor, colonyId, ColonyRole.OWNER)) {
            ColonyOwnership.deny(actor, WHAT_KEY, WHAT_FALLBACK);
            return;
        }
        if (targetId.equals(actor.getUUID())) {
            toast(actor, true, "self_lock", "§c[魔法小镇] 不能降级或移除自己。");
            return;
        }
        ColonyRole targetRole = api.getRole(colonyId, targetId);
        if (targetRole == null) {
            toast(actor, true, "target_not_member", "§c[魔法小镇] 该玩家不在此小镇花名册中。");
            return;
        }
        if (targetRole == ColonyRole.OWNER) {
            toast(actor, true, "cannot_touch_owner", "§c[魔法小镇] 不能改动或移除 OWNER 的档位。");
            return;
        }
        if (!api.removeMember(colonyId, targetId)) {
            toast(actor, true, "write_failed", "§c[魔法小镇] 花名册写入失败，请重试。");
            Log.warn(TAG, "removeMember failed: colony {} player {}", shortId(colonyId), shortId(targetId));
            return;
        }

        String colonyName = safeName(api, colonyId);
        // 顺手作废他可能还挂着的那条邀请，避免「被移出后又冒出一条旧邀请」。
        ColonyInviteRegistry.remove(targetId, colonyId);

        ServerPlayer target = server.getPlayerList().getPlayer(targetId);
        if (target != null && !target.isRemoved()) {
            target.sendSystemMessage(I18n.name(MSG + "you_removed", "§c[魔法小镇] 你已被移出「%s」。", colonyName));
            // 先送「你不在里面了」的空快照清缓存，再推他自己的列表/其它镇。
            ColonyRosterSyncService.pushDetached(target, colonyId);
            ColonyRosterSyncService.pushFor(target);
        }

        toast(actor, false, "member_removed", "§a[魔法小镇] 已将 %s 移出「%s」。",
                ColonyRosterSyncService.displayName(server, targetId), colonyName);
        Log.info(TAG, "{} removed {} from colony {}", name(actor), shortId(targetId), shortId(colonyId));

        ColonyRosterSyncService.pushColony(server, colonyId);
        ColonyRosterSyncService.pushFor(actor);
    }

    // ══════════════════════════════════════════════════════════════
    //  SELECT：把该镇设为当前镇（可切换 = MEMBER+），随后统一推送
    // ══════════════════════════════════════════════════════════════

    /**
     * 切换当前镇：侧边栏「小镇」面板点一行即走到这里。
     *
     * <p>这是**唯一**的切换入口（用户明确要求只在面板里切，不用跑到镇上）。服务端权威判定：
     * {@link ActiveColonyTracker#setActive} 自己校验「镇存在 + 档位 ≥ MEMBER」，ALLY 会被它拒，
     * 且拒绝时**不改动已存的当前镇**（失败方向安全）。网关已按 MEMBER 先拦一层，这里不依赖它单独下结论。
     */
    private static void select(ServerPlayer actor, @Nullable UUID colonyId) {
        if (colonyId == null) {
            toast(actor, true, "invalid", "§c[魔法小镇] 无效的成员操作请求。");
            return;
        }
        if (!ActiveColonyTracker.setActive(actor, colonyId)) {
            toast(actor, true, "switch_failed",
                    "§c[魔法小镇] 无法切换到该小镇：你已不是可切换的成员（盟友档位不可切换）。");
            Log.warn(TAG, "{} failed to switch the active colony to {}", name(actor), shortId(colonyId));
            return;
        }

        // 一次推齐：顶栏统计 + 建筑边界 + 教程 + 花名册 + 列表。切换后客户端各处必须同时换上下文，
        // 散着推必漏一处，表现就是「切了但顶栏/边界还是上一座镇」。
        ColonyContextSync.push(actor);

        ColonyApi api = colonyApi();
        toast(actor, false, "switched", "§a[魔法小镇] 已切换到「%s」。",
                api != null ? safeName(api, colonyId) : "");
        Log.info(TAG, "{} switched the active colony to {}", name(actor), shortId(colonyId));
    }

    // ══════════════════════════════════════════════════════════════
    //  小工具
    // ══════════════════════════════════════════════════════════════

    @Nullable
    private static ColonyApi colonyApi() {
        try {
            return WandscapeApis.getColonyApiSilently();
        } catch (Throwable t) {
            Log.warn(TAG, "Colony API unavailable: {}", t.toString());
            return null;
        }
    }

    /** 小镇是否仍存在：花名册至少有一人（建镇即写入唯一 OWNER；镇被删时花名册一并清空）。 */
    private static boolean colonyExists(ColonyApi api, @Nullable UUID colonyId) {
        return colonyId != null && !api.getRoster(colonyId).isEmpty();
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

    /** 瞬时反馈（屏幕 Toast / Action Bar）：操作成败只此一发，聊天只留给「别人对你做的事」。 */
    private static void toast(ServerPlayer player, boolean error, String key, String fallback, Object... args) {
        if (player == null || player.isRemoved()) return;
        ScreenFeedbackPacket.send(player, I18n.name(MSG + key, fallback, args), error);
    }

    private static String name(ServerPlayer player) {
        return player != null ? player.getGameProfile().getName() : "?";
    }

    private static String shortId(@Nullable UUID id) {
        return id != null ? id.toString().substring(0, 8) : "none";
    }

    // ══════════════════════════════════════════════════════════════
    //  线格式
    // ══════════════════════════════════════════════════════════════

    static void write(RegistryFriendlyByteBuf buf, ColonyMemberActionPacket pkt) {
        ColonyWireCodecs.writeAction(buf, pkt.action);
        ColonyWireCodecs.writeNullableUuid(buf, pkt.colonyId);
        ColonyWireCodecs.writeNullableUuid(buf, pkt.target);
        ColonyWireCodecs.writeRole(buf, pkt.role);
    }

    static ColonyMemberActionPacket read(RegistryFriendlyByteBuf buf) {
        return new ColonyMemberActionPacket(
                ColonyWireCodecs.readAction(buf),
                ColonyWireCodecs.readNullableUuid(buf),
                ColonyWireCodecs.readNullableUuid(buf),
                ColonyWireCodecs.readRole(buf));
    }
}
