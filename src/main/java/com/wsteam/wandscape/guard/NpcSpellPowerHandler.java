package com.wsteam.wandscape.guard;

import com.wsteam.wandscape.core.types.AttributeType;
import com.wsteam.wandscape.npc.entity.WandscapeNpc;

import net.minecraft.world.entity.monster.Enemy;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

/**
 * SPELL_POWER 统一伤害钩子：NPC 对敌对生物造成的魔法伤害按施法 NPC 的 SPELL_POWER
 * 倍率放大。
 *
 * <p>为什么在伤害事件层乘而非每个魔法单独写乘算：NPC 的伤害几乎全部来自魔法
 * （光束/未来法术），在「给怪物核算伤害」的唯一入口乘倍率，任何未来新增魔法自动
 * 生效，不会漏写。判定：伤害源实体是 {@link WandscapeNpc} 且目标是 {@link Enemy}。
 * 玩家施法（伤害源是玩家）不经过这里，保持原倍率 1.0。
 *
 * <p>契约：任何 NPC 伤害源必须让 {@code source.getEntity()} 解析为施法 NPC——
 * 弹射物/光束类伤害用 {@code damageSources().indirectMagic(直接实体, 施法NPC)}，
 * 施法 NPC 放第二个参数（因 {@code DamageSources.source()} 参数名与
 * {@code DamageSource} 构造器 {@code (directEntity, causingEntity)} 错位，
 * {@code getEntity()} 返回第二个参数）。漏掉则倍率静默不生效。
 */
public final class NpcSpellPowerHandler {
    private static final String TAG = "NpcSpellPower";

    private NpcSpellPowerHandler() {}

    @SubscribeEvent
    public static void onLivingDamage(LivingIncomingDamageEvent event) {
        if (event.getEntity().level().isClientSide) return;
        if (!(event.getEntity() instanceof Enemy)) return;
        if (!(event.getSource().getEntity() instanceof WandscapeNpc npc)) return;
        if (npc.isRemoved()) return;

        float power = npc.getEffectiveAttribute(AttributeType.SPELL_POWER);
        if (power > 1f) {
            event.setAmount(event.getAmount() * power);
        }
    }
}
