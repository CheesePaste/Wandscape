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
import com.wsteam.wandscape.foundation.log.Log;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.Map;

/**
 * 环境伤害逃生：NPC 受窒息/岩浆/火烧等非生物伤害时，用传送魔法离开危险区域。
 *
 * <p>由 {@link SelfDefenseHandler} 在无活体攻击者的伤害事件里调用。只救空闲 NPC
 * （任务中的 NPC 由 {@code NavigationSystem} 的卡住检测→传送兜底，这里不打断任务）。
 * 门控复用 {@link WandscapeNpc#tryCastSpell}（施法互斥锁 + 每魔法 CD + 魔力），
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
        if (!npc.isEngineIdle()) return false;
        long gameTime = level.getGameTime();
        if (!npc.consumeEscapeScan(gameTime)) return false;

        World world = com.wsteam.wandscape.content.task.ecs.World.getActive();
        if (world == null || world.ritualOps == null) return false;

        // 门控预检（不扣蓝）：锁/CD/蓝任一不足则跳过，避免每次环境伤害都全量扫目标点
        MagicDef tp = SpellbookLoader.getSpec("teleport");
        int tpCd = tp != null ? tp.baseCooldown() : TELEPORT_COOLDOWN_FALLBACK;
        int tpMana = tp != null ? tp.manaCost() : TELEPORT_MANA_FALLBACK;
        if (!npc.magic.canCast("teleport") || npc.getCurrentMana() < tpMana) return false;

        int lockTicks = WandscapeRitualOps.channelTicks(RitualId.SELF_TELEPORT);
        BlockPos here = npc.blockPosition();
        Vec3 landing = WandscapeRitualOps.findSafeEscapeLanding(level,
                new GridPos(here.getX(), here.getY(), here.getZ()));
        if (landing == null) return false;

        // 原子门控（真正扣蓝/占锁/设 CD）；失败则放弃（理论上预检已通过，防御性兜底）
        if (!npc.tryCastSpell("teleport", tpCd, tpMana, lockTicks)) return false;

        BlockPos dest = BlockPos.containing(landing);
        npc.getNavigation().stop();
        world.ritualOps.beginRitual(RitualId.SELF_TELEPORT,
                new GridPos(dest.getX(), dest.getY(), dest.getZ()),
                world, npc.ecsEntityId, Map.of());
        npc.startManualCast(lockTicks);
        // 引导期间定身 + 减伤 75%（SelfDefenseHandler 消费）；替代原「屏蔽环境伤害」免疫
        npc.markTeleportChanneling(gameTime, lockTicks);
        Log.info(TAG, "NPC {} — environmental damage, teleport escape → ({},{},{})",
                npc.getUUID().toString().substring(0, 8), dest.getX(), dest.getY(), dest.getZ());
        return true;
    }
}
