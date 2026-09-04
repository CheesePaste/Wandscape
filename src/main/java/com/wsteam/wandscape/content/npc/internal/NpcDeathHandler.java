package com.wsteam.wandscape.content.npc.internal;
import com.wsteam.wandscape.Config;
import com.wsteam.wandscape.api.NpcApi;
import com.wsteam.wandscape.api.WandscapeApis;
import com.wsteam.wandscape.content.npc.attributes.NpcAttributes.AttributeType;

import com.wsteam.wandscape.content.task.component.NpcInventory;
import com.wsteam.wandscape.content.task.ecs.World;
import com.wsteam.wandscape.content.task.types.ResourceStack;
import com.wsteam.wandscape.content.npc.data.DeathRecord;
import com.wsteam.wandscape.content.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.foundation.log.Log;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameRules;
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
        World world = com.wsteam.wandscape.content.task.ecs.World.getActive();
        if (world != null && npc.ecsEntityId > 0) {
            NpcInventory ecsInv = world.get(npc.ecsEntityId, NpcInventory.class);
            if (ecsInv != null) {
                inv = List.copyOf(ecsInv.items());
            }
        }

        NpcApi npcApi = WandscapeApis.getNpcApiSilently();
        UUID colony = npcApi != null ? npcApi.getNpcColony(npc.getUUID()) : null;
        if (colony == null) colony = EntityComponentBridge.PLACEHOLDER_COLONY;
        DeathRecord rec = new DeathRecord(
                npc.getUUID(),
                npc.getNpcName(),
                level.dimension().location().toString(),
                npc.getBlockX(), npc.getBlockY(), npc.getBlockZ(),
                level.getGameTime(),
                colony,
                npc.getSkinVariant(), npc.getHatColor(),
                npc.hasDefaultWand(),
                npc.getBaseAttributeValue(com.wsteam.wandscape.content.npc.attributes.NpcAttributes.AttributeType.MAX_HP),
                npc.getBaseAttributeValue(com.wsteam.wandscape.content.npc.attributes.NpcAttributes.AttributeType.MOVE_SPEED),
                npc.getBaseAttributeValue(com.wsteam.wandscape.content.npc.attributes.NpcAttributes.AttributeType.SPELL_POWER),
                npc.getBaseAttributeValue(com.wsteam.wandscape.content.npc.attributes.NpcAttributes.AttributeType.WORK_SPEED),
                npc.getBaseAttributeValue(com.wsteam.wandscape.content.npc.attributes.NpcAttributes.AttributeType.SPELL_SPEED),
                npc.getBaseAttributeValue(com.wsteam.wandscape.content.npc.attributes.NpcAttributes.AttributeType.ARMOR_VALUE),
                npc.getBaseAttributeValue(com.wsteam.wandscape.content.npc.attributes.NpcAttributes.AttributeType.MAX_MANA),
                inv,
                npc.equippedMagic.flattenedQualified());
        ColonyDeathRegistry.get(level).add(rec);
        Log.info(TAG, "NPC {} ({}) died at {},{},{} — death record saved, inventory {} stacks",
                rec.npcId().toString().substring(0, 8), rec.name(),
                rec.x(), rec.y(), rec.z(), inv.size());

        // 像玩家/驯养宠物一样把阵亡消息送上聊天区（文案用原版战斗记录，受众受 Config 控制）
        broadcastDeathMessage(level, npc, colony);

        // 保卫殖民地复活：阵亡于距本殖民地建筑 ≤REVIVE_NEAR_BUILDING_RANGE 格 → 直接在市政厅门口复活
        //（复用全灭保底的市政厅门口定位 + 虚弱复活；复活后该法师存活，下方全灭检测自然不触发）
        ReviveHandler.checkAndReviveNearColonyBuilding(level, rec);
        // NPC 阵亡时立即轮询全灭检测：若全员阵亡，自动在市政厅门口释放复活魔法
        ReviveHandler.checkAndAutoReviveColony(level, colony);
    }

    /**
     * 法师阵亡消息上聊天区：文案复用原版战斗记录 {@code CombatTracker#getDeathMessage}
     * （含死因与凶手，随各客户端语言本地化，含摔死/溺亡/自定义 magic/mob 伤害源兜底）。
     * 必须在本事件（die() 开头）内抓取——随后的 {@code recheckStatus()} 会清空战斗记录。
     * 受众受 {@link Config#NPC_DEATH_MESSAGE_GLOBAL} 控制：开启 → 全服广播（同玩家死亡）；
     * 关闭 → 仅发所属小镇创建者（同驯养宠物死亡只通知主人）。受 showDeathMessages 游戏规则门控。
     */
    private static void broadcastDeathMessage(ServerLevel level, WandscapeNpc npc, UUID colonyId) {
        if (!level.getGameRules().getBoolean(GameRules.RULE_SHOWDEATHMESSAGES)) return;
        Component message = npc.getCombatTracker().getDeathMessage();
        if (Config.NPC_DEATH_MESSAGE_GLOBAL.get()) {
            level.getServer().getPlayerList().broadcastSystemMessage(message, false);
            return;
        }
        // 仅 owner：所属小镇创建者在线才发；无创建者或不在线则不打扰
        ServerPlayer owner = resolveOnlineFounder(level, colonyId);
        if (owner != null) {
            owner.sendSystemMessage(message);
        }
    }

    /** 当前在线的小镇创建者玩家；无创建者/不在线/占位殖民地返回 null。 */
    @javax.annotation.Nullable
    private static ServerPlayer resolveOnlineFounder(ServerLevel level, UUID colonyId) {
        if (colonyId == null || colonyId.equals(EntityComponentBridge.PLACEHOLDER_COLONY)) return null;
        var api = WandscapeApis.getColonyApiSilently();
        UUID founder = api != null ? api.getFounder(colonyId) : null;
        if (founder == null) return null;
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            if (founder.equals(player.getUUID())) return player;
        }
        return null;
    }
}
