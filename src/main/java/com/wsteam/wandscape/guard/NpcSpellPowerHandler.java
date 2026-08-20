package com.wsteam.wandscape.guard;

import com.wsteam.wandscape.core.types.AttributeType;
import com.wsteam.wandscape.magic.internal.MagicSpellExecutors;
import com.wsteam.wandscape.npc.entity.WandscapeNpc;

import net.minecraft.world.entity.monster.Enemy;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

/**
 * SPELL_POWER 统一伤害钩子：NPC 对可伤害目标造成的魔法伤害按施法 NPC 的 SPELL_POWER
 * 倍率乘算（>1 增伤、<1 减伤、==1 无操作），再乘魔力强化独立乘区
 * （{@link MagicSpellExecutors#magicEnhanceMultiplier}，每级 +20%，无 buff 时 ×1）。
 *
 * <p>为什么在伤害事件层乘而非每个魔法单独写乘算：NPC 的伤害几乎全部来自魔法
 * （光束/未来法术），在「给怪物核算伤害」的唯一入口乘倍率，任何未来新增魔法自动
 * 生效，不会漏写。判定：伤害源实体是 {@link WandscapeNpc} 且目标可被该法师伤害
 * （{@link Enemy} 或 {@code npc.canBeamHurt(target)}——普通 NPC 只伤 Enemy，
 * 敌对法师覆盖为也伤生存玩家）。玩家施法（伤害源是玩家）不经过这里，保持原倍率 1.0。
 *
 * <p>注意：L2 物理普攻（GuardCombat.normalAttack）也走此钩子（来源是 NPC），因此
 * 会一并被 SPELL_POWER 与魔力强化放大——这是「所有乘 SPELL_POWER 处都乘魔力强化」
 * 的既定行为，普攻兜底本就很低（5 点基础）。
 *
 * <p>契约：任何 NPC 伤害源必须让 {@code source.getEntity()} 解析为施法 NPC——
 * 弹射物/光束类伤害用 {@code damageSources().source(key, 直接实体, 施法NPC)}
 * （光束用自定义类型 {@code wandscape:beam}，走正常护甲流程），施法 NPC 放第二个参数
 * （因 {@code DamageSources.source()} 参数名与 {@code DamageSource} 构造器
 * {@code (directEntity, causingEntity)} 错位，{@code getEntity()} 返回第二个参数）。
 * 漏掉则倍率静默不生效。
 */
public final class NpcSpellPowerHandler {
    private static final String TAG = "NpcSpellPower";

    private NpcSpellPowerHandler() {}

    @SubscribeEvent
    public static void onLivingDamage(LivingIncomingDamageEvent event) {
        if (event.getEntity().level().isClientSide) return;
        if (!(event.getSource().getEntity() instanceof WandscapeNpc npc)) return;
        if (npc.isRemoved()) return;
        // 和平模式：不造成任何魔法伤害（兜底，光束伤害入口另有门控）
        if (npc.isPeaceMode()) return;
        // 目标必须可被该法师伤害：Enemy 恒可（伤害按 Enemy 结算，可能误伤和平中立生物），
        // 其余按 canBeamHurt（普通 NPC 只伤 Enemy，敌对法师覆盖为也伤生存玩家）。NPC 的魔法永远伤不到普通玩家。
        if (!(event.getEntity() instanceof Enemy) && !npc.canBeamHurt(event.getEntity())) return;

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
    }
}
