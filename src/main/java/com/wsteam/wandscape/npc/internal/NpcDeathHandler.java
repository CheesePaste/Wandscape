package com.wsteam.wandscape.npc.internal;

import java.util.List;
import java.util.UUID;

import com.wsteam.wandscape.core.component.Inventory;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.core.types.ResourceStack;
import com.wsteam.wandscape.engine.WandscapeEngine;
import com.wsteam.wandscape.npc.data.DeathRecord;
import com.wsteam.wandscape.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.shared.log.Log;

import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

/**
 * NPC 死亡留存钩子：WandscapeNpc 战死瞬间抓快照写入 {@link ColonyDeathRegistry}。
 * 必须在实体清理（ECS remove）前抓——LivingDeathEvent 时实体尚在、ECS 组件未删。
 * 任何死因都记录（战死/摔死/环境）；复活魔法据此恢复。
 */
public final class NpcDeathHandler {

    private static final String TAG = "NpcDeath";

    private NpcDeathHandler() {}

    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        if (event.getEntity().level().isClientSide) return;
        if (!(event.getEntity() instanceof WandscapeNpc npc)) return;
        if (npc.isRemoved()) return;
        // 敌对测试法师等非殖民地 NPC 不留死亡记录（不被复活魔法找回）
        if (!npc.isColonyNpc()) return;
        if (!(npc.level() instanceof ServerLevel level)) return;

        List<ResourceStack> inv = List.of();
        World world = WandscapeEngine.getWorld();
        if (world != null && npc.ecsEntityId > 0) {
            Inventory ecsInv = world.get(npc.ecsEntityId, Inventory.class);
            if (ecsInv != null) {
                inv = List.copyOf(ecsInv.items());
            }
        }

        UUID colony = npc.colonyId != null ? npc.colonyId : EntityComponentBridge.PLACEHOLDER_COLONY;
        DeathRecord rec = new DeathRecord(
                npc.getUUID(),
                npc.getNpcName(),
                level.dimension().location().toString(),
                npc.getBlockX(), npc.getBlockY(), npc.getBlockZ(),
                level.getGameTime(),
                colony,
                npc.getSkinVariant(), npc.getHatColor(),
                npc.hasDefaultWand(),
                npc.maxHp, npc.moveSpeed, npc.spellPower, npc.workSpeed,
                npc.spellSpeed, npc.armorValue, npc.maxMana,
                inv);
        ColonyDeathRegistry.get(level).add(rec);
        Log.info(TAG, "NPC {} ({}) died at {},{},{} — death record saved, inventory {} stacks",
                rec.npcId().toString().substring(0, 8), rec.name(),
                rec.x(), rec.y(), rec.z(), inv.size());
    }
}
