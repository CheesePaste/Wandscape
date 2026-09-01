package com.wsteam.wandscape.content.items.scepter.internal;

import com.wsteam.wandscape.content.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.content.items.scepter.ScepterKind;
import com.wsteam.wandscape.foundation.log.Log;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * 玩家权杖服务端业务：应用右键命令 + 本殖民地校验 + 玩家上屏反馈。
 *
 * <p>范围归属「本殖民地」（与盟誓戒指同语义）：和平/跟随要求目标法师属于玩家自己殖民地的法师；
 * 庇护/敌对要求玩家有殖民地（标记存该殖民地名下，只指挥该殖民地法师）。无殖民地/跨殖民地拒绝
 * 并反馈。庇护与强制仇恨的标记落 {@link ScepterMarksSavedData}（长期持久化，退出重进生效）。
 */
public final class ScepterService {
    private static final String TAG = "ScepterService";

    private ScepterService() {}

    /** 玩家右键法师（{@code MageWandItem} 转交）：四把权杖都适用。 */
    public static void onInteractNpc(ServerPlayer player, LivingEntity mage, ScepterKind kind) {
        if (!(mage instanceof WandscapeNpc npc)) return;
        switch (kind) {
            case PEACE -> togglePeace(player, npc);
            case FOLLOW -> toggleFollow(player, npc);
            case SHELTER -> toggleShelter(player, npc);
            case HOSTILE -> toggleHostile(player, npc);
        }
    }

    /** 玩家右键非法师生物（{@code ScepterInteractHandler} 转交）：仅庇护/敌对适用。 */
    public static void onInteractCreature(ServerPlayer player, LivingEntity target, ScepterKind kind) {
        switch (kind) {
            case SHELTER -> toggleShelter(player, target);
            case HOSTILE -> toggleHostile(player, target);
            default -> { /* 和平/跟随仅对法师目标（onInteractNpc 路径） */ }
        }
    }

    // ── 和平 / 跟随：切换目标法师实体字段（与 NpcTogglePacket handleServer 同语义）──

    private static void togglePeace(ServerPlayer player, WandscapeNpc npc) {
        if (!requireOwnMage(player, npc)) return;
        boolean now = !npc.isPeaceMode();
        npc.setPeaceMode(now);
        if (now) npc.clearHatedAttacker(); // 开和平清除仇恨，避免解除和平后立刻寻仇
        ok(player, now
                        ? "message.wandscape.scepter.peace_on"
                        : "message.wandscape.scepter.peace_off", npc.getDisplayName());
        log(player, "toggle peace", now, npc);
    }

    private static void toggleFollow(ServerPlayer player, WandscapeNpc npc) {
        if (!requireOwnMage(player, npc)) return;
        boolean now = !npc.isFollowMode();
        npc.setFollowMode(now);
        npc.setFollowerUuid(now ? player.getUUID() : null);
        ok(player, now
                        ? "message.wandscape.scepter.follow_on"
                        : "message.wandscape.scepter.follow_off", npc.getDisplayName());
        log(player, "toggle follow", now, npc);
    }

    // ── 庇护 / 敌对：切换殖民地标记（持久化到 ScepterMarksSavedData）──

    private static void toggleShelter(ServerPlayer player, LivingEntity target) {
        UUID colonyId = ownColony(player);
        if (colonyId == null) {
            fail(player, "message.wandscape.scepter.no_colony");
            return;
        }
        ScepterMarksSavedData data = ScepterMarksSavedData.get(player.getServer());
        boolean now = data.marks().toggleShelter(colonyId, target.getUUID());
        data.setDirty();
        ok(player, now
                        ? "message.wandscape.scepter.shelter_on"
                        : "message.wandscape.scepter.shelter_off", target.getDisplayName());
        Log.info(TAG, "Player {} {} target {} for colony {}",
                shortId(player.getUUID()), now ? "sheltered" : "unsheltered",
                shortId(target.getUUID()), shortId(colonyId));
    }

    private static void toggleHostile(ServerPlayer player, LivingEntity target) {
        UUID colonyId = ownColony(player);
        if (colonyId == null) {
            fail(player, "message.wandscape.scepter.no_colony");
            return;
        }
        // 防误点：盟友（玩家/同殖民地法师/同殖民地游客/庇护名单等 isFriendlyForce）不能被标记为
        // 强制仇恨——法师本就不攻击友军，标记只会制造无意义状态。校验按目标所在殖民地（本殖民地）。
        if (WandscapeNpc.isFriendlyForce(target, colonyId)) {
            fail(player, "message.wandscape.scepter.hostile_ally", target.getDisplayName());
            return;
        }
        ScepterMarksSavedData data = ScepterMarksSavedData.get(player.getServer());
        ScepterMarks marks = data.marks();
        UUID prev = marks.forcedHostile(colonyId);
        boolean now = marks.toggleForcedHostile(colonyId, target.getUUID());
        data.setDirty();
        if (now) {
            if (prev != null && !prev.equals(target.getUUID())) {
                ok(player, "message.wandscape.scepter.hostile_transfer", target.getDisplayName());
            } else {
                ok(player, "message.wandscape.scepter.hostile_on", target.getDisplayName());
            }
        } else {
            ok(player, "message.wandscape.scepter.hostile_off", target.getDisplayName());
        }
        Log.info(TAG, "Player {} {} target {} for colony {} (prev={})",
                shortId(player.getUUID()), now ? "marked-hostile" : "cleared-hostile",
                shortId(target.getUUID()), shortId(colonyId),
                prev != null ? shortId(prev) : "none");
    }

    // ── 校验与辅助 ──

    /** 目标法师必须属于玩家自己创建的殖民地；否则拒绝并反馈。 */
    private static boolean requireOwnMage(ServerPlayer player, WandscapeNpc npc) {
        UUID colonyId = ownColony(player);
        if (colonyId == null) {
            fail(player, "message.wandscape.scepter.no_colony");
            return false;
        }
        if (!npc.isColonyNpc() || !colonyId.equals(npc.colonyId)) {
            fail(player, "message.wandscape.scepter.other_colony");
            return false;
        }
        return true;
    }

    /** 玩家创建殖民地的 UUID；无殖民地（含 API 未就绪）返回 null。 */
    @Nullable
    private static UUID ownColony(ServerPlayer player) {
        try {
            var api = com.wsteam.wandscape.api.WandscapeApis.getColonyApiSilently();
            return api != null ? api.getColonyByFounder(player.getUUID()) : null;
        } catch (RuntimeException e) {
            Log.warn(TAG, "Failed to resolve own colony for {}: {}", shortId(player.getUUID()), e.toString());
            return null;
        }
    }

    private static void ok(ServerPlayer player, String key, Object... args) {
        player.displayClientMessage(Component.translatable(key, args), true);
    }

    private static void fail(ServerPlayer player, String key, Object... args) {
        player.displayClientMessage(Component.translatable(key, args), true);
    }

    private static void log(ServerPlayer player, String action, boolean value, WandscapeNpc npc) {
        Log.info(TAG, "Player {} {} -> {} for mage {} (colony {})",
                shortId(player.getUUID()), action, value,
                shortId(npc.getUUID()),
                npc.colonyId != null ? shortId(npc.colonyId) : "none");
    }

    private static String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }
}