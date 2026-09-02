package com.wsteam.wandscape.content.npc.guard;

import com.wsteam.wandscape.api.ColonyApi;
import com.wsteam.wandscape.api.WandscapeApis;
import com.wsteam.wandscape.content.npc.attributes.NpcAttributes.AttributeType;
import com.wsteam.wandscape.content.magic.internal.MagicSpellExecutors;
import com.wsteam.wandscape.content.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.content.npc.internal.EntityComponentBridge;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.UUID;

/**
 * NPC 伤害统一入口：先按友伤边界过滤，再对有效目标按施法 NPC 的 SPELL_POWER 倍率乘算
 * （>1 增伤、<1 减伤、==1 无操作），最后乘魔力强化独立乘区
 * （{@link MagicSpellExecutors#magicEnhanceMultiplier}，每级 +20%，无 buff 时 ×1）。
 *
 * <p>为什么在伤害事件层乘而非每个魔法单独写乘算：NPC 的伤害几乎全部来自魔法
 * （光束/未来法术/铁魔法），在「给目标核算伤害」的唯一入口乘倍率，任何未来新增魔法自动
 * 生效，不会漏写。
 *
 * <p>铁魔法伤害必须也只在此乘一次：铁魔法 {@code applyDamage} 会先发 {@code SpellDamageEvent}
 * 再调用 {@code target.hurt()}（同一伤害会连续触发这两个事件），任何在 SpellDamageEvent 端再乘
 * SPELL_POWER 的监听都会与这里重复乘算，使伤害随法术强度二次方暴涨——曾有的
 * {@code IronSpellsDamageHandler} 已因该 bug 移除，勿在 compat 包重新引入。
 *
 * <p>友伤边界（L0，先于倍率）：**友军名单管辖**——伤害源实体是 {@link WandscapeNpc} 时，
 * 目标为友军（玩家 + 同殖民地 NPC/铁魔法随从/游客，见 {@code WandscapeNpc#isFriendlyForce}）
 * 则整伤取消；非友军一律结算（不再限于 {@link Enemy}，与 {@code canBeamHurt} 放宽一致）。
 * 铁魔法（Iron's Spells）由其库内部结算伤害，不检查此边界，会在 AoE/溅射里打到友军——
 * 这里在伤害入口统一**取消**友军伤害，使铁魔法与原生魔法（施法前已按 canBeamHurt 过滤目标）
 * 边界一致；和平模式同理整伤取消。
 *
 * <p>注意：L2 物理普攻（GuardCombat.normalAttack）也走此钩子（来源是 NPC），因此
 * 会一并被 SPELL_POWER 与魔力强化放大——这是「所有乘 SPELL_POWER 处都乘魔力强化」
 * 的既定行为，普攻兜底本就很低（5 点基础）。
 *
 * <p>击杀归属：同一入口对通过友伤边界的伤害调用 {@link #grantKillCredit}，把
 * lastHurtByPlayer 记为殖民地主人——只影响玩家击杀才掉落的战利品判定，不改伤害来源，
 * 详见该方法注释。
 *
 * <p>契约：任何 NPC 伤害源必须让 {@code source.getEntity()} 解析为施法 NPC——
 * 弹射物/光束类伤害用 {@code damageSources().source(key, 直接实体, 施法NPC)}
 * （光束用自定义类型 {@code wandscape:beam}，走正常护甲流程），施法 NPC 放第二个参数
 * （因 {@code DamageSources.source()} 参数名与 {@code DamageSource} 构造器
 * {@code (directEntity, causingEntity)} 错位，{@code getEntity()} 返回第二个参数）。
 * 漏掉则倍率静默不生效（铁魔法召唤物经 {@code getDamageSource(直接实体, 施法NPC)}
 * 已满足——causing 恒为施法 NPC）。
 */
public final class NpcSpellPowerHandler {
    private static final String TAG = "NpcSpellPower";

    private NpcSpellPowerHandler() {}

    @SubscribeEvent
    public static void onLivingDamage(LivingIncomingDamageEvent event) {
        if (event.getEntity().level().isClientSide) return;
        if (!(event.getSource().getEntity() instanceof WandscapeNpc npc)) return;
        if (npc.isRemoved()) return;
        // 和平模式：不造成任何伤害（兜底，光束伤害入口另有门控；铁魔法持续引导中的残余施放也在此拦截）
        if (npc.isPeaceMode()) {
            event.setCanceled(true);
            return;
        }
        // 友军名单管辖：友军（玩家 + 同殖民地 NPC/铁魔法随从/游客）以外皆可伤。铁魔法内部
        // 结算不检查此边界，这里在伤害入口统一取消友军伤害（原生魔法施法前已按 canBeamHurt
        // 过滤目标，边界一致）。
        if (npc.isFriendlyForce(event.getEntity())) {
            event.setCanceled(true);
            return;
        }

        float power = npc.getEffectiveAttribute(AttributeType.SPELL_POWER);
        // 倍率双向生效：>1 增伤、<1 减伤、==1 无操作。法术强度 0.5 必须真的减半，
        // 不能只乘 >1 的（否则低强度法师伤害打满，强度属性形同虚设）。
        if (power > 0f && power != 1f) {
            event.setAmount(event.getAmount() * power);
        }
        // 魔力强化独立乘区（每级 +20%，与 SPELL_POWER 各自乘算）；无 buff 时 ×1。
        float enhance = MagicSpellExecutors.magicEnhanceMultiplier(npc);
        if (enhance != 1f) {
            event.setAmount(event.getAmount() * enhance);
        }

        // 玩家击杀归属：只对真正结算的伤害（友伤/和平已在上方拦截）挂主人归属，
        // 让 killed_by_player 掉落与经验按玩家击杀结算。不改 damage source，仇恨与
        // 上方倍率判定不受影响。
        grantKillCredit(event.getEntity(), npc);
    }

    /**
     * 把「最近被玩家击伤」标志写到受击目标，使 killed_by_player 掉落（烈焰棒、凋灵骷髅头、
     * 亡灵装备掉落率，见 {@code LivingEntity#dropFromLootTable} → LAST_DAMAGE_PLAYER）
     * 与经验球在 NPC 击杀时按玩家击杀结算。刻意不改 damage source——{@code source.getEntity()}
     * 仍是施法 NPC：怪物仇恨（hurt → setLastHurtByMob）与上方 SPELL_POWER/魔力强化倍率判定
     * 均不受影响，只补 vanilla 独独认玩家来源的 lastHurtByPlayer。
     *
     * <p>归属：殖民地主人 {@link ColonyApi#getFounder} 优先；无殖民地记录（自由法师）或
     * 主人无效时，有且仅有一名在线玩家则记给他（与 AchievementService 兜底同口径）；
     * 敌对测试法师等 {@code isColonyNpc()==false} 不授予。目标已有同一玩家归属则跳过
     * （光束每 tick 结算时避免重复刷新）。
     */
    private static void grantKillCredit(LivingEntity target, WandscapeNpc npc) {
        if (!npc.isColonyNpc()) return; // 敌对法师等不授击杀归属
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;

        ServerPlayer credited = null;
        UUID colonyId = npc.colonyId;
        if (colonyId != null && !EntityComponentBridge.PLACEHOLDER_COLONY.equals(colonyId)) {
            ColonyApi colonyApi = WandscapeApis.getColonyApiSilently();
            if (colonyApi != null) {
                UUID founder = colonyApi.getFounder(colonyId);
                if (founder != null) {
                    credited = server.getPlayerList().getPlayer(founder);
                }
            }
        }
        if (credited == null && server.getPlayerList().getPlayers().size() == 1) {
            credited = server.getPlayerList().getPlayers().getFirst();
        }
        if (credited == null || target.getKillCredit() == credited) return;
        target.setLastHurtByPlayer(credited);
    }
}
