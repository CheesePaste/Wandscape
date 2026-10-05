package com.wsteam.wandscape.content.colony.network;

import com.wsteam.wandscape.api.ColonyStatusApi;
import com.wsteam.wandscape.api.TutorialApi;
import com.wsteam.wandscape.api.WandscapeApis;
import com.wsteam.wandscape.content.building.network.BuildingAreaSyncPacket;
import com.wsteam.wandscape.content.colony.ownership.ColonyOwnership;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.foundation.networking.Net;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * 「当前小镇」上下文的统一推送接缝 —— 切换 / 接受邀请 / 建镇成功后**唯一**要调用的入口。
 *
 * <p>客户端要显示一座镇的顶栏数字、建筑边界框、教程步骤、花名册与小镇列表。多殖民地之前
 * 这些推送散落在各处（登录事件、面板开包、花名册变更事件），谁都按「我创始的那座」解析；
 * 有了「当前镇」概念之后，切换镇必须把整套上下文一次换掉，散落的推送点没有单一入口可用。
 * 本类把它们收成一条 {@link #push(ServerPlayer)}。
 *
 * <p>语义铁律：推送内容**只按当前镇**（{@code ColonyOwnership.activeColony}）解析，
 * 没有当前镇 = 建镇引导态，发**空包清客户端缓存**（统计 colonyId = null、建筑边界空表、
 * 教程只回放已存进度），**绝不回退空间「最近小镇」**——那是跨镇泄密的根因。
 *
 * <p>放在 {@code content.colony.network} 与 {@link ColonyRosterSyncService} 同包，直接复用它
 * 的包私有推送（{@code pushRoster} / {@code pushList} / {@code pushFor}）：花名册快照的构建
 * 规则仍然只有那一处真源，本类不新开 API、也不复制拼包逻辑。
 *
 * <p>为什么 stats 包是这套推送的**必备项**：客户端唯一的「当前镇」是
 * {@code WandscapePanelState.colonyId}，而它只由 {@code ColonyStatsSyncPacket} 写入；顶栏、
 * 面板选中高亮、设置页与各子面板都读这一份，{@code ColonyPanelClientState} 刻意不保存
 * 「当前是哪座镇」（见其类注释）。所以少发 stats 包 = 客户端根本不知道该显示哪座镇，
 * 面板会停在上一座镇上。
 *
 * <p>失败兜底：整段推送包在 try/catch 里，任何一段异常都只记 {@link Log#warn} 并放弃本次推送，
 * 绝不让异常冒泡打断调用方（切换包 handler、建镇流程、接受邀请流程）。
 */
public final class ColonyContextSync {

    private static final String TAG = "ColonyContextSync";

    private ColonyContextSync() {}

    /**
     * 把玩家「当前小镇」的整套上下文推给客户端。
     *
     * <p>调用方**不需要**先解析镇 id，也**不需要**自己判空：无当前镇时本方法按引导态发空包，
     * 把客户端上一座镇的缓存清掉。
     *
     * <p>顺序要求：**stats 必须最先发**——它是客户端当前镇的唯一真源，后面的花名册/列表缓存
     * 都要按这个 id 去取，先落地才能保证同一批包到达后各处读到的当前镇一致。
     * 花名册与列表之间已无先后依赖（客户端不再持有可 latch 的「选中项」），
     * 保留「先花名册后列表」只是让日志与抓包里的顺序稳定好读。
     *
     * @param player 收件人；null 或已移除则直接返回（登录/登出竞态，不算失败）
     */
    public static void push(ServerPlayer player) {
        if (player == null || player.isRemoved()) return;
        try {
            UUID colonyId = ColonyOwnership.activeColony(player);

            pushStats(player, colonyId);
            // 无当前镇时该方法自己发空包：上一座镇的建筑边界不会滞留在客户端缓存里，
            // 否则新存档首次放置建筑会对着旧边界误报重叠。
            BuildingAreaSyncPacket.sendToPlayer(player, colonyId);
            pushTutorial(player, colonyId);
            pushRosterAndList(player, colonyId);
        } catch (Throwable t) {
            Log.warn(TAG, "Failed to push colony context to {}: {}",
                    player.getGameProfile().getName(), t.toString());
        }
    }

    /**
     * 顶栏统计。
     *
     * <p>无当前镇时 {@code getSnapshotSafe(null)} 直接给 {@code ColonyStatusSnapshot.EMPTY}
     * （colonyId = null、名字空串），发出去客户端就把「本镇」清空——正是引导态要的空包，
     * 不必手拼那个 24 参构造。
     *
     * @param colonyId 当前镇；null = 建镇引导态（发空包清顶栏）
     */
    private static void pushStats(ServerPlayer player, @Nullable UUID colonyId) {
        ColonyStatusApi statusApi = WandscapeApis.getColonyStatusApiSilently();
        if (statusApi == null) {
            Log.warn(TAG, "Colony status API unavailable; skipped stats push for {}",
                    player.getGameProfile().getName());
            return;
        }
        Net.toPlayer(player, ColonyStatsSyncPacket.fromSnapshot(statusApi.getSnapshotSafe(colonyId)));
    }

    /**
     * 教程进度：无当前镇时只回放已存进度，预建镇阶段点掉的引导不会因切换而回退。
     *
     * @param colonyId 当前镇；null = 建镇引导态（只回放已存进度）
     */
    private static void pushTutorial(ServerPlayer player, @Nullable UUID colonyId) {
        TutorialApi tutorialApi = WandscapeApis.getTutorialApiSilently();
        if (tutorialApi == null) {
            Log.warn(TAG, "Tutorial API unavailable; skipped tutorial push for {}",
                    player.getGameProfile().getName());
            return;
        }
        tutorialApi.sendToPlayer(player, colonyId);
    }

    /**
     * 花名册 + 小镇列表。
     *
     * <p>有当前镇：推该镇花名册（成员/档位，客户端按当前镇 id 取用；也是面板成员页的数据源），
     * 再推列表（可切换项）。当前镇必然是玩家档位 ≥ MEMBER 的镇（{@code ActiveColonyTracker}
     * 的解析规则），所以它一定在列表里。
     *
     * <p>无当前镇：走 {@code pushFor} —— 它仍会推「我的小镇」列表（可能是空表，正好清掉旧列表）
     * 以及**发给该玩家的待处理邀请**。邀请是挂在花名册包上的，所以这里不能简单跳过，
     * 否则一座镇都没加入、只被邀请的玩家在引导态里根本看不到邀请。
     *
     * @param colonyId 当前镇；null = 建镇引导态（走 pushFor 以保留邀请）
     */
    private static void pushRosterAndList(ServerPlayer player, @Nullable UUID colonyId) {
        if (colonyId != null) {
            ColonyRosterSyncService.pushRoster(player, colonyId);
            ColonyRosterSyncService.pushList(player);
        } else {
            ColonyRosterSyncService.pushFor(player);
        }
    }
}
