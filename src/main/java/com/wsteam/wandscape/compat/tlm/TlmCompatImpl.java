package com.wsteam.wandscape.compat.tlm;

import com.github.tartaricacid.touhoulittlemaid.api.event.MaidTickEvent;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.wsteam.wandscape.api.WandscapeApis;
import com.wsteam.wandscape.content.npc.internal.EntityComponentBridge;
import com.wsteam.wandscape.content.task.ecs.World;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.foundation.log.LogCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 车万女仆兼容的实际实现（唯一碰 TLM 类型的地方之一）。
 *
 * <p>职责：对账女仆的登记状态。**不做精确生命周期挂钩**——TLM 的任务没有 onStart/onStop 回调，
 * 女仆又可能被魂符收走、被其它模组杀死、随区块卸载，逐条追消失路径必然漏。改成每次女仆 tick
 * 对账「应当登记」与「实际登记」的差异，漏不掉：
 *
 * <ul>
 *   <li>当前任务是「殖民地工作」+ 主人有殖民地 → 登记（已在册则跳过，每
 *       {@value #COLONY_RECHECK_INTERVAL} tick 复查一次殖民地是否变了）。</li>
 *   <li>任务换走 / 主人没殖民地 / 实体已移除 → 注销。</li>
 * </ul>
 *
 * <p>注销统一走 {@code EntityComponentBridge.onWorkerLeaveWorld}——它会释放全局任务
 * （保留进度供重派）、取消资源预留与在途运输、移除 ECS 组件。这正是"不能漏"的部分：
 * 漏了会留下幽灵工作者占着全局任务不干活。
 */
public final class TlmCompatImpl {

    private static final String TAG = "TlmCompat";

    /** 已登记的女仆：实体 UUID → 适配器。 */
    private static final Map<UUID, MaidColonyWorker> ENLISTED = new ConcurrentHashMap<>();

    /**
     * 已登记女仆复查殖民地归属的间隔（tick）。殖民地↔创始人是 1:1、不会频繁变，
     * 而 {@code getColonyByFounder} 要查 SavedData——不必每 tick 都做。
     */
    private static final int COLONY_RECHECK_INTERVAL = 40;

    private static int tickCounter;

    public static void init(IEventBus modEventBus) {
        // MaidTickEvent 在 EntityMaid.tick() 里发出（双端），只有 TLM 在场时该事件类才存在，
        // 所以监听器注册必须放在这里而不是门面里。
        NeoForge.EVENT_BUS.addListener(TlmCompatImpl::onMaidTick);
    }

    /** 女仆主人所属殖民地（殖民地↔创始人 1:1）；无主 / 主人没小镇 → null。 */
    @Nullable
    public static UUID ownerColonyOf(EntityMaid maid) {
        UUID owner = maid.getOwnerUUID();
        if (owner == null) return null;
        var api = WandscapeApis.getColonyApiSilently();
        return api != null ? api.getColonyByFounder(owner) : null;
    }

    private static void onMaidTick(MaidTickEvent event) {
        EntityMaid maid = event.getMaid();
        // EntityMaid.tick() 客户端也跑；ECS / 登记只在服务端有意义
        if (maid.level().isClientSide) return;
        tickCounter++;
        reconcile(maid, tickCounter % COLONY_RECHECK_INTERVAL == 0);
    }

    private static void reconcile(EntityMaid maid, boolean recheckColony) {
        UUID uuid = maid.getUUID();
        MaidColonyWorker existing = ENLISTED.get(uuid);

        if (maid.isRemoved()) {
            if (existing != null) {
                teardown(uuid, existing, World.getActive());
                Log.debug(LogCategory.NPC, "worker", "maid {} removed — dismissed", shortId(uuid));
            }
            return;
        }

        if (!(maid.getTask() instanceof ColonyWorkerMaidTask)) {
            if (existing != null) {
                teardown(uuid, existing, World.getActive());
                Log.info(TAG, "maid {} left colony work mode — dismissed", shortId(uuid));
            }
            return;
        }

        // 已登记且本轮不复查：跳过下面那次 SavedData 查询
        if (existing != null && !recheckColony) return;

        UUID colony = ownerColonyOf(maid);
        if (colony == null) {
            if (existing != null) {
                teardown(uuid, existing, World.getActive());
                Log.info(TAG, "maid {} owner has no colony — dismissed", shortId(uuid));
            }
            return;
        }

        World world = World.getActive();
        if (world == null) return; // 引擎未就绪：下 tick 再来

        if (existing != null && colony.equals(existing.colonyId())) return; // 归属没变

        if (existing != null) {
            // 主人换镇：适配器里的 colonyId 是 final，重建一个（同一实体 UUID，重连分支会复用 ECS 实体）
            teardown(uuid, existing, world);
        }
        MaidColonyWorker worker = new MaidColonyWorker(maid, colony);
        ENLISTED.put(uuid, worker);
        EntityComponentBridge.INSTANCE.onWorkerJoinWorld(worker, world);
        Log.info(TAG, "maid {} enlisted as colony worker of {}", shortId(uuid), shortId(colony));
    }

    private static void teardown(UUID uuid, MaidColonyWorker worker, @Nullable World world) {
        ENLISTED.remove(uuid);
        if (world != null) {
            EntityComponentBridge.INSTANCE.onWorkerLeaveWorld(worker, world);
        }
    }

    private static String shortId(UUID uuid) {
        return uuid.toString().substring(0, 8);
    }

    private TlmCompatImpl() {}
}
