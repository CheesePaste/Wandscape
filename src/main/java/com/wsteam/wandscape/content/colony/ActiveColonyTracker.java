package com.wsteam.wandscape.content.colony;

import com.wsteam.wandscape.api.ColonyApi;
import com.wsteam.wandscape.api.WandscapeApis;
import com.wsteam.wandscape.content.colony.roster.ColonyRole;
import com.wsteam.wandscape.foundation.log.Log;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 玩家「当前操作的小镇」的唯一真源。
 *
 * <p>多殖民地之前，「我在哪座镇」等于「我创始的那座」（{@code getColonyByFounder}）。一人可拥有多座镇、
 * 又可参与别人的镇之后，这个等式不再成立：**当前镇是一件独立的、可显式切换的状态**。
 *
 * <p>裁定规则（用户确认）：
 * <ul>
 *   <li>**可切换 = 在该镇档位 ≥ {@link ColonyRole#MEMBER}**；{@link ColonyRole#ALLY} 不可切换
 *       —— 它没有任何操作权限，切过去也是白切。</li>
 *   <li>每座镇恒有一个 OWNER，但**一人可拥有多座镇**。</li>
 *   <li>**跨重连持久化**：选过的镇记进存档（{@code ColonySavedData} v3），重连后还在那座。</li>
 *   <li>默认解析顺序：已存且仍可切 → 自己拥有的镇 → 档位最高者 → 无（null）。</li>
 *   <li>**绝不回退「空间最近小镇」**——那是跨镇泄密的根因，也是本类存在的意义。</li>
 * </ul>
 *
 * <p>只读入口是 {@code ColonyOwnership.activeColony(ServerPlayer)}（各域统一调它，不要自备副本）；
 * 本类是那个入口的实现，并额外提供「可切换列表」给面板与切换包使用。
 */
public final class ActiveColonyTracker {

    private static final String TAG = "ActiveColonyTracker";

    /** 可切换所需的最低档位（ALLY 不可切）。 */
    public static final ColonyRole MIN_SWITCH_ROLE = ColonyRole.MEMBER;

    private ActiveColonyTracker() {}

    /**
     * 玩家当前操作的小镇；没有任何可切换的小镇时返回 {@code null}（= 建镇引导态）。
     *
     * <p>解析失败一律降级为 null 并记警告，绝不抛给调用方（调用方遍布 UI 与事件路径）。
     */
    @Nullable
    public static UUID activeColony(@Nullable ServerPlayer player) {
        if (player == null) return null;
        try {
            ColonyApi api = WandscapeApis.getColonyApiSilently();
            if (api == null) return null;

            UUID stored = api.getActiveColony(player.getUUID());
            if (stored != null && canSwitch(api, player.getUUID(), stored)) {
                return stored;
            }
            // 没选过 / 选了但已不可切（被降为 ALLY、被移出、镇被删）→ 按默认规则重解析并落盘，
            // 这样后续调用是 O(1)，且重连行为稳定。
            UUID resolved = resolveDefault(api, player.getUUID());
            if (resolved != null) {
                api.setActiveColony(player.getUUID(), resolved);
            } else if (stored != null) {
                api.setActiveColony(player.getUUID(), null); // 清掉已失效的记录
            }
            return resolved;
        } catch (RuntimeException e) {
            Log.warn(TAG, "Failed to resolve active colony for {}: {}",
                    player.getUUID(), e.toString());
            return null;
        }
    }

    /**
     * 玩家**可切换**的小镇（档位 ≥ MEMBER），按档位从高到低排序。
     *
     * <p>面板据此渲染：列表里的镇可以直接点；不在其中的（ALLY / 非成员）不可切、应置灰。
     */
    public static Map<UUID, ColonyRole> switchable(@Nullable ServerPlayer player) {
        Map<UUID, ColonyRole> out = new LinkedHashMap<>();
        if (player == null) return out;
        try {
            ColonyApi api = WandscapeApis.getColonyApiSilently();
            if (api == null) return out;
            switchable(api, player.getUUID()).forEach(out::put);
        } catch (RuntimeException e) {
            Log.warn(TAG, "Failed to list switchable colonies for {}: {}",
                    player.getUUID(), e.toString());
        }
        return out;
    }

    /**
     * 把 {@code colonyId} 设为当前镇。
     *
     * <p>不满足「成员且档位 ≥ MEMBER」或镇不存在 → 拒绝且**不改动已存的值**（失败方向安全）。
     * {@code colonyId == null} 表示清除当前镇。
     */
    public static boolean setActive(@Nullable ServerPlayer player, @Nullable UUID colonyId) {
        if (player == null) return false;
        try {
            ColonyApi api = WandscapeApis.getColonyApiSilently();
            if (api == null) return false;

            if (colonyId == null) {
                api.setActiveColony(player.getUUID(), null);
                return true;
            }
            if (!canSwitch(api, player.getUUID(), colonyId)) {
                Log.warn(TAG, "[Colony] Refused switch of {} to colony {} (role={}, colonyExists={})",
                        player.getUUID(), colonyId.toString().substring(0, 8),
                        api.getRole(colonyId, player.getUUID()),
                        api.getAllColonyIds().contains(colonyId));
                return false;
            }
            api.setActiveColony(player.getUUID(), colonyId);
            Log.info(TAG, "[Colony] Player {} switched active colony to {}",
                    player.getUUID(), colonyId.toString().substring(0, 8));
            return true;
        } catch (RuntimeException e) {
            Log.warn(TAG, "Failed to set active colony for {}: {}", player.getUUID(), e.toString());
            return false;
        }
    }

    /** 默认解析（不落盘）：自己拥有的镇 → 档位最高者 → null。 */
    @Nullable
    public static UUID resolveDefault(@Nullable ServerPlayer player) {
        if (player == null) return null;
        ColonyApi api = WandscapeApis.getColonyApiSilently();
        return api != null ? resolveDefault(api, player.getUUID()) : null;
    }

    // ── 内部 ─────────────────────────────────────────────────────────

    @Nullable
    private static UUID resolveDefault(ColonyApi api, UUID playerId) {
        // switchable 已按档位降序 → 第一个 OWNER 就是「我拥有的镇」中排序最前的那座
        for (var entry : switchable(api, playerId).entrySet()) {
            if (entry.getValue() == ColonyRole.OWNER) return entry.getKey();
        }
        for (var entry : switchable(api, playerId).entrySet()) {
            return entry.getKey();
        }
        return null;
    }

    /** 档位 ≥ MEMBER 的镇，按档位从高到低。 */
    private static Map<UUID, ColonyRole> switchable(ColonyApi api, UUID playerId) {
        Map<UUID, ColonyRole> out = new LinkedHashMap<>();
        Map<UUID, ColonyRole> mine = api.getColoniesOf(playerId);
        if (mine == null || mine.isEmpty()) return out;
        mine.entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue().atLeast(MIN_SWITCH_ROLE))
                .sorted((a, b) -> Integer.compare(b.getValue().rank(), a.getValue().rank()))
                .forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    /** 可切换判定：镇存在 + 该玩家档位 ≥ MEMBER。 */
    private static boolean canSwitch(ColonyApi api, UUID playerId, UUID colonyId) {
        if (colonyId == null || !api.getAllColonyIds().contains(colonyId)) return false;
        ColonyRole role = api.getRole(colonyId, playerId);
        return role != null && role.atLeast(MIN_SWITCH_ROLE);
    }
}
