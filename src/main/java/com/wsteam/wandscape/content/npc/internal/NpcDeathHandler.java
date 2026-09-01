package com.wsteam.wandscape.content.npc.internal;
import com.wsteam.wandscape.content.npc.types.AttributeType;

import com.wsteam.wandscape.content.task.component.Inventory;
import com.wsteam.wandscape.content.task.ecs.World;
import com.wsteam.wandscape.content.task.types.ResourceStack;
import com.wsteam.wandscape.impl.WandscapeEngine;
import com.wsteam.wandscape.content.npc.data.DeathRecord;
import com.wsteam.wandscape.content.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.foundation.log.Log;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

import java.util.List;
import java.util.UUID;

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
        // 敌对测试法师等非小镇 NPC 不留死亡记录（不被复活魔法找回）
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
                npc.getBaseAttributeValue(com.wsteam.wandscape.content.npc.types.AttributeType.MAX_HP),
                npc.getBaseAttributeValue(com.wsteam.wandscape.content.npc.types.AttributeType.MOVE_SPEED),
                npc.getBaseAttributeValue(com.wsteam.wandscape.content.npc.types.AttributeType.SPELL_POWER),
                npc.getBaseAttributeValue(com.wsteam.wandscape.content.npc.types.AttributeType.WORK_SPEED),
                npc.getBaseAttributeValue(com.wsteam.wandscape.content.npc.types.AttributeType.SPELL_SPEED),
                npc.getBaseAttributeValue(com.wsteam.wandscape.content.npc.types.AttributeType.ARMOR_VALUE),
                npc.getBaseAttributeValue(com.wsteam.wandscape.content.npc.types.AttributeType.MAX_MANA),
                inv,
                npc.equippedMagic.flattenedQualified());
        ColonyDeathRegistry.get(level).add(rec);
        Log.info(TAG, "NPC {} ({}) died at {},{},{} — death record saved, inventory {} stacks",
                rec.npcId().toString().substring(0, 8), rec.name(),
                rec.x(), rec.y(), rec.z(), inv.size());

        // 保卫殖民地复活：阵亡于距本殖民地建筑 ≤REVIVE_NEAR_BUILDING_RANGE 格 → 直接在市政厅门口复活
        //（复用全灭保底的市政厅门口定位 + 虚弱复活；复活后该法师存活，下方全灭检测自然不触发）
        ReviveHandler.checkAndReviveNearColonyBuilding(level, rec);
        // NPC 阵亡时立即轮询全灭检测：若全员阵亡，自动在市政厅门口释放复活魔法
        ReviveHandler.checkAndAutoReviveColony(level, colony);
    }
}
