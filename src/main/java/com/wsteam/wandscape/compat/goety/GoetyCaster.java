package com.wsteam.wandscape.compat.goety;

import com.Polarice3.Goety.api.magic.ISpell;
import com.Polarice3.Goety.common.magic.SpellStat;
import com.wsteam.wandscape.Config;
import com.wsteam.wandscape.content.npc.attributes.NpcAttributes.AttributeType;
import com.wsteam.wandscape.content.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.foundation.log.Log;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 诡厄巫法聚晶施法执行器：处理瞬发与持续引导/长蓄力法术的生命周期推进。
 */
public final class GoetyCaster {

    private static final String TAG = "GoetyCaster";

    private static final class ActiveCast {
        final ServerLevel level;
        final WandscapeNpc npc;
        @Nullable
        final LivingEntity target;
        final String focusId;
        final ISpell spell;
        final ItemStack focusStack;
        final SpellStat stat;
        int remainingTicks;
        final int totalDuration;

        ActiveCast(ServerLevel level, WandscapeNpc npc, @Nullable LivingEntity target,
                   String focusId, ISpell spell, ItemStack focusStack, SpellStat stat, int lockTicks) {
            this.level = level;
            this.npc = npc;
            this.target = target;
            this.focusId = focusId;
            this.spell = spell;
            this.focusStack = focusStack;
            this.stat = stat;
            this.remainingTicks = lockTicks;
            this.totalDuration = lockTicks;
        }
    }

    private static final List<ActiveCast> ACTIVE_CASTS = new ArrayList<>();

    private GoetyCaster() {}

    /**
     * 为 NPC 施放诡厄巫法聚晶法术。
     */
    public static boolean cast(ServerLevel level, WandscapeNpc npc, @Nullable LivingEntity target,
                               String focusId, @Nullable String customData) {
        if (!GoetyCompat.isLoaded()) return false;
        ISpell spell = GoetyHelper.getSpell(focusId);
        if (spell == null) return false;

        ItemStack focusStack = GoetyHelper.deserializeFocus(focusId, customData);

        // 基础消耗与冷却换算
        int rawSoul = spell.defaultSoulCost();
        double soulRatio = Config.GOETY_SOUL_TO_MANA_MULTIPLIER.get();
        int manaCost = Math.max(1, (int) Math.round(rawSoul * soulRatio));

        int rawCooldown = spell.defaultSpellCooldown();
        double cdRatio = Config.GOETY_COOLDOWN_MULTIPLIER.get();
        int baseCooldown = Math.max(10, (int) Math.round(rawCooldown * cdRatio));

        int rawCastDuration = spell.defaultCastDuration();

        if (target != null && target.isAlive()) {
            npc.faceTarget(target.getEyePosition());
        }

        float spellSpeed = Math.max(0.1f, npc.getEffectiveAttribute(AttributeType.SPELL_SPEED));
        SpellStat stat = GoetyHelper.buildSpellStat(level, spell, focusStack);

        if (rawCastDuration <= 0) {
            // 瞬发法术
            int lockTicks = Math.max(10, (int) Math.ceil(10.0 / spellSpeed));
            if (!npc.tryCastSpell(focusId, baseCooldown, manaCost, lockTicks)) {
                return false;
            }

            npc.swing(InteractionHand.MAIN_HAND, true);
            playCastingSound(level, npc, spell);

            spell.SpellResult(level, npc, focusStack, stat);
            Log.info(TAG, "NPC {} cast instant goety spell '{}'",
                    npc.getUUID().toString().substring(0, 8), focusId);
            return true;
        } else {
            // 持续引导或蓄力法术
            int lockTicks = Math.max(10, (int) Math.ceil(rawCastDuration / spellSpeed));
            if (!npc.tryCastSpell(focusId, baseCooldown, manaCost, lockTicks)) {
                return false;
            }

            playCastingSound(level, npc, spell);
            spell.startSpell(level, npc, focusStack, stat);

            ACTIVE_CASTS.add(new ActiveCast(level, npc, target, focusId, spell, focusStack, stat, lockTicks));
            Log.info(TAG, "NPC {} began channeling goety spell '{}' (lockTicks={})",
                    npc.getUUID().toString().substring(0, 8), focusId, lockTicks);
            return true;
        }
    }

    private static void playCastingSound(ServerLevel level, WandscapeNpc npc, ISpell spell) {
        SoundEvent sound = spell.CastingSound(npc);
        if (sound == null) {
            sound = spell.CastingSound();
        }
        if (sound != null) {
            level.playSound(null, npc.getX(), npc.getY(), npc.getZ(),
                    sound, SoundSource.NEUTRAL, spell.castingVolume(), spell.castingPitch());
        }
    }

    /**
     * 每 server tick 调用：推进持续施法与长蓄力法术。
     */
    public static void tickAll() {
        if (!GoetyCompat.isLoaded() || ACTIVE_CASTS.isEmpty()) return;

        Iterator<ActiveCast> it = ACTIVE_CASTS.iterator();
        while (it.hasNext()) {
            ActiveCast cast = it.next();
            if (cast.npc.isRemoved() || !cast.npc.isAlive()) {
                it.remove();
                continue;
            }

            if (cast.target != null && cast.target.isAlive() && !cast.target.isRemoved()) {
                cast.npc.faceTarget(cast.target.getEyePosition());
            }

            int currentDuration = cast.totalDuration - cast.remainingTicks;
            cast.spell.useSpell(cast.level, cast.npc, cast.focusStack, currentDuration, cast.stat);

            cast.remainingTicks--;
            if (cast.remainingTicks <= 0) {
                cast.spell.stopSpell(cast.level, cast.npc, cast.focusStack, ItemStack.EMPTY, currentDuration, cast.stat);
                cast.spell.SpellResult(cast.level, cast.npc, cast.focusStack, cast.stat);
                it.remove();
            }
        }
    }
}
