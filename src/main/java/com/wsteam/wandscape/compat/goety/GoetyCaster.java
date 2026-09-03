package com.wsteam.wandscape.compat.goety;

import com.Polarice3.Goety.api.magic.IBreathingSpell;
import com.Polarice3.Goety.api.magic.IChargingSpell;
import com.Polarice3.Goety.api.magic.ISpell;
import com.Polarice3.Goety.common.magic.SpellStat;
import com.Polarice3.Goety.utils.MobUtil;
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
        final ItemStack staff;
        final SpellStat stat;
        int remainingTicks;
        final int totalDuration;

        ActiveCast(ServerLevel level, WandscapeNpc npc, @Nullable LivingEntity target,
                   String focusId, ISpell spell, ItemStack staff, SpellStat stat, int lockTicks) {
            this.level = level;
            this.npc = npc;
            this.target = target;
            this.focusId = focusId;
            this.spell = spell;
            this.staff = staff;
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
        ItemStack staff = GoetyHelper.getStaffForSpell(spell, focusStack);

        // 基础消耗与冷却换算
        int rawSoul = spell.defaultSoulCost();
        double soulRatio = Config.GOETY_SOUL_TO_MANA_MULTIPLIER.get();
        int manaCost = Math.max(1, (int) Math.round(rawSoul * soulRatio));

        int rawCooldown = spell.defaultSpellCooldown();
        double cdRatio = Config.GOETY_COOLDOWN_MULTIPLIER.get();
        int baseCooldown = Math.max(10, (int) Math.round(rawCooldown * cdRatio));

        float spellSpeed = Math.max(0.1f, npc.getEffectiveAttribute(AttributeType.SPELL_SPEED));
        SpellStat stat = GoetyHelper.buildSpellStat(level, npc, spell, focusStack);

        // 目标与朝向：优先设置目标并同步视线，使 Goety 的 getTarget 与弹道/尖牙射线能够正确命中
        if (target != null && target.isAlive()) {
            npc.setTarget(target);
            MobUtil.instaLook(npc, target, true);
        }

        if (!spell.conditionsMet(level, npc)) {
            return false;
        }

        boolean isContinuous = spell instanceof IChargingSpell;

        if (!isContinuous) {
            // 瞬发法术（绝大多数 Goety 法术：牙刺、尖刺、火球、召唤僵尸/骷髅、风弹等）
            int lockTicks = Math.max(10, (int) Math.ceil(15.0 / spellSpeed));
            if (!npc.tryCastSpell(focusId, baseCooldown, manaCost, lockTicks)) {
                return false;
            }

            npc.swing(InteractionHand.MAIN_HAND, true);
            playCastingSound(level, npc, spell);

            spell.SpellResult(level, npc, staff, stat);
            Log.info(TAG, "NPC {} cast instant goety spell '{}'",
                    npc.getUUID().toString().substring(0, 8), focusId);
            return true;
        } else {
            // 持续引导或蓄力法术（如喷吐法术等）
            int channelTicks = Math.min(60, Math.max(20, (int) Math.ceil(30.0 / spellSpeed)));
            if (!npc.tryCastSpell(focusId, baseCooldown, manaCost, channelTicks)) {
                return false;
            }

            playCastingSound(level, npc, spell);
            spell.startSpell(level, npc, staff, stat);

            ACTIVE_CASTS.add(new ActiveCast(level, npc, target, focusId, spell, staff, stat, channelTicks));
            Log.info(TAG, "NPC {} began channeling goety spell '{}' (duration={})",
                    npc.getUUID().toString().substring(0, 8), focusId, channelTicks);
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
                cast.npc.setTarget(cast.target);
                MobUtil.instaLook(cast.npc, cast.target, true);
            }

            int currentDuration = cast.totalDuration - cast.remainingTicks;
            cast.spell.useSpell(cast.level, cast.npc, cast.staff, currentDuration, cast.stat);
            if (cast.spell instanceof IBreathingSpell breathing) {
                breathing.showWandBreath(cast.npc, cast.staff, cast.stat);
            }

            cast.remainingTicks--;
            if (cast.remainingTicks <= 0) {
                cast.spell.stopSpell(cast.level, cast.npc, cast.staff, ItemStack.EMPTY, currentDuration, cast.stat);
                cast.spell.SpellResult(cast.level, cast.npc, cast.staff, cast.stat);
                cast.npc.swing(InteractionHand.MAIN_HAND, true);
                it.remove();
            }
        }
    }
}
