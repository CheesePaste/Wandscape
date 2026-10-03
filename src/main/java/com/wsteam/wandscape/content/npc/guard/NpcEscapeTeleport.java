package com.wsteam.wandscape.content.npc.guard;
import com.wsteam.wandscape.content.npc.system.NavigationSystem;
import com.wsteam.wandscape.content.task.boundary.RitualOps;

import com.wsteam.wandscape.content.task.ecs.World;
import com.wsteam.wandscape.content.task.types.GridPos;
import com.wsteam.wandscape.content.task.types.RitualId;
import com.wsteam.wandscape.content.task.boundary.WandscapeRitualOps;
import com.wsteam.wandscape.content.magic.data.MagicDef;
import com.wsteam.wandscape.content.magic.internal.SpellbookLoader;
import com.wsteam.wandscape.content.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.content.task.component.TaskExecutor;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.foundation.log.LogCategory;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 环境伤害逃生：NPC 受窒息/岩浆/火烧等非生物伤害时，用传送魔法离开危险区域。
 *
 * <p>由 {@link SelfDefenseHandler} 在无活体攻击者的伤害事件里调用。**工作中的 NPC 也救**：
 * 唯一不放行的是「正卡在异步 op 的 future 上」——传送后任务会重放该 op，而 ResourceRequestOp
 * 这类重放会二次扣资源，不保证幂等。导航 future 与「无 future」两种情形都放行。
 *
 * <p>传送前做交接三步（清 {@code pendingFuture} / 取消 ECS 导航 / 把 ritual future 交回执行器），
 * 与 {@link NavigationSystem}{@code #switchToRitualTeleport} 同一套；不做的话任务执行系统会一直等那个
 * 已被取消的导航 future，NPC 传走了、任务却卡住。交接后任务在落地时从当前步骤继续。
 *
 * <p>门控复用 {@link WandscapeNpc#tryCastSpell}（施法互斥锁 + 每魔法 CD + 魔力），
 * 引导期间 {@link WandscapeNpc#markTeleportChanneling} 定身 + 减伤 75%（SelfDefenseHandler 消费）。
 */
public final class NpcEscapeTeleport {

    private static final String TAG = "NpcEscapeTeleport";

    /** spec 缺失时 teleport 的 CD 兜底（tick），与 NavigationSystem 一致。 */
    private static final int TELEPORT_COOLDOWN_FALLBACK = 150;
    /** spec 缺失时 teleport 的魔力兜底，与 NavigationSystem 一致。 */
    private static final int TELEPORT_MANA_FALLBACK = 30;

    private NpcEscapeTeleport() {
    }

    /**
     * 尝试发起一次逃生传送。任一步不满足（非空闲/门控/无安全落点）静默返回 false，
     * 不消耗任何资源（扫描有节流）。
     *
     * @return true 表示已发起逃生传送（引导期间环境伤害由调用方 shield 屏蔽）
     */
    public static boolean attempt(ServerLevel level, WandscapeNpc npc) {
        World world = com.wsteam.wandscape.content.task.ecs.World.getActive();
        if (world == null || world.ritualOps == null) return false;

        TaskExecutor exec = npc.ecsEntityId >= 0 ? world.get(npc.ecsEntityId, TaskExecutor.class) : null;

        // 唯一不放行：正卡在**异步 op 的 future** 上。传送后任务会重放该 op，而 ResourceRequestOp
        // 这类重放会二次扣资源、不保证幂等；导航 future 与「无 future」都放行（导航类由下面交接处理）。
        // 原先是 isEngineIdle()——只要身上有任务就不救，导致正在干活的法师掉进岩浆只能死。
        if (exec != null && exec.pendingFuture != null && !exec.pendingFutureIsNav) {
            Log.debug(LogCategory.NPC, "escape", "NPC {} — 正在异步 op 中，跳过逃生传送（重放不安全）",
                    npc.getUUID().toString().substring(0, 8));
            return false;
        }

        long gameTime = level.getGameTime();
        if (!npc.consumeEscapeScan(gameTime)) return false;

        // 门控预检（不扣蓝）：锁/CD/蓝任一不足则跳过，避免每次环境伤害都全量扫目标点
        MagicDef tp = SpellbookLoader.getSpec("teleport");
        int tpCd = tp != null ? tp.baseCooldown() : TELEPORT_COOLDOWN_FALLBACK;
        int tpMana = tp != null ? tp.manaCost() : TELEPORT_MANA_FALLBACK;
        if (!npc.magic.canCast("teleport") || npc.getCurrentMana() < tpMana) return false;

        int lockTicks = WandscapeRitualOps.channelTicks(RitualId.SELF_TELEPORT);
        BlockPos here = npc.blockPosition();
        Vec3 landing = WandscapeRitualOps.findSafeEscapeLanding(level,
                new GridPos(here.getX(), here.getY(), here.getZ()));
        if (landing == null) {
            Log.debug(LogCategory.NPC, "escape", "NPC {} — 无安全落点，逃生传送放弃",
                    npc.getUUID().toString().substring(0, 8));
            return false;
        }

        // 原子门控（真正扣蓝/占锁/设 CD）；失败则放弃（理论上预检已通过，防御性兜底）
        if (!npc.tryCastSpell("teleport", tpCd, tpMana, lockTicks)) return false;

        // 交接三步：清掉失效的 pendingFuture、取消 ECS 导航、把传送 ritual 的 future 交回执行器。
        // 缺了这步，TaskExecutionSystem 见到未完成的 pendingFuture 就 return，任务会卡在旧 future 上。
        if (exec != null) {
            if (exec.pendingFutureIsNav && world.movementOps != null) {
                world.movementOps.cancelNavigation(npc.ecsEntityId);
            }
            exec.pendingFuture = null;
            exec.pendingFutureIsNav = false;
        }

        BlockPos dest = BlockPos.containing(landing);
        npc.getNavigation().stop();
        CompletableFuture<Void> ritualFuture = world.ritualOps.beginRitual(RitualId.SELF_TELEPORT,
                new GridPos(dest.getX(), dest.getY(), dest.getZ()),
                world, npc.ecsEntityId, Map.of());
        if (exec != null) {
            // 导航语义：future 解析后不推进步骤，落点就绪即继续执行当前 op
            exec.pendingFuture = ritualFuture;
            exec.pendingFutureIsNav = true;
        }
        npc.startManualCast(lockTicks);
        // 引导期间定身 + 减伤 75%（SelfDefenseHandler 消费）；替代原「屏蔽环境伤害」免疫
        npc.markTeleportChanneling(gameTime, lockTicks);
        Log.info(TAG, "NPC {} — environmental damage, teleport escape → ({},{},{})",
                npc.getUUID().toString().substring(0, 8), dest.getX(), dest.getY(), dest.getZ());
        return true;
    }
}
