package com.wsteam.wandscape.compat.ironspellbooks;

import com.wsteam.wandscape.core.types.AttributeType;
import com.wsteam.wandscape.magic.internal.MagicSpellExecutors;
import com.wsteam.wandscape.npc.entity.WandscapeNpc;

import io.redspace.ironsspellbooks.api.events.SpellDamageEvent;
import net.neoforged.bus.api.SubscribeEvent;

/**
 * 铁魔法伤害事件监听器：将 Wandscape NPC 的 {@code SPELL_POWER} 与魔力强化倍率乘入铁魔法伤害。
 */
public final class IronSpellsDamageHandler {

    private IronSpellsDamageHandler() {}

    @SubscribeEvent
    public static void onSpellDamage(SpellDamageEvent event) {
        if (event.getSpellDamageSource() != null
                && event.getSpellDamageSource().getEntity() instanceof WandscapeNpc npc) {
            float spellPower = npc.getEffectiveAttribute(AttributeType.SPELL_POWER);
            float enhance = MagicSpellExecutors.magicEnhanceMultiplier(npc);
            float multiplier = Math.max(0f, spellPower * enhance);
            event.setAmount(event.getAmount() * multiplier);
        }
    }
}
