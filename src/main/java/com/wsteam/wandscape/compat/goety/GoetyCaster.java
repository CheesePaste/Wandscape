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
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

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
     * 将 NPC 的头部偏航角（Yaw）与俯仰角（Pitch）精准对准目标中心，使弹道与射线法术正中目标。
     */
    private static void lookAtTarget(WandscapeNpc npc, LivingEntity target) {
        npc.setTarget(target);
        MobUtil.instaLook(npc, target, true);
        Vec3 eye = npc.getEyePosition();
        Vec3 targetCenter = target.getBoundingBox().getCenter();
        double dx = targetCenter.x - eye.x;
        double dy = targetCenter.y - eye.y;
        double dz = targetCenter.z - eye.z;
        double horizDist = Math.sqrt(dx * dx + dz * dz);
        float pitch = (float) (-(Mth.atan2(dy, horizDist) * (180.0 / Math.PI)));
        npc.setXRot(pitch);
        npc.xRotO = pitch;
    }

    /**
     * 安全校验 Goety 施法条件：优先调用带 SpellStat 的重载，捕获潜在缺失属性/类型异常并容错。
     */
    private static boolean safeConditionsMet(ISpell spell, ServerLevel level, WandscapeNpc npc,
                                             SpellStat stat, String focusId, @Nullable LivingEntity target) {
        try {
            // 优先检查带 SpellStat 的条件（喷吐射程、冲击波等）
            if (!spell.conditionsMet(level, npc, stat)) {
                Log.info(TAG, "Spell '{}' stat-conditions not met on NPC {} (target={}, dist={})",
                        focusId, npc.getUUID().toString().substring(0, 8),
                        target != null ? target.getName().getString() : "null",
                        target != null ? String.format("%.2f", npc.distanceTo(target)) : "N/A");
                return false;
            }
            // 检查无 stat 重载（召唤上限、自愈血量、护盾排斥等）
            if (!spell.conditionsMet(level, npc)) {
                Log.info(TAG, "Spell '{}' entity-conditions not met on NPC {}",
                        focusId, npc.getUUID().toString().substring(0, 8));
                return false;
            }
            return true;
        } catch (Throwable t) {
            // 若抛出异常（如缺少 Goety 属性注册），做降级容错
            Log.warn(TAG, "Spell '{}' conditionsMet threw exception on NPC {}: {}",
                    focusId, npc.getUUID().toString().substring(0, 8), t.getMessage());
            try {
                return spell.conditionsMet(level, npc, stat);
            } catch (Throwable ignored) {
                return true;
            }
        }
    }

    /**
     * 为 NPC 施放诡厄巫法聚晶法术。
     */
    public static boolean cast(ServerLevel level, WandscapeNpc npc, @Nullable LivingEntity target,
                               String focusId, @Nullable String customData) {
        if (!GoetyCompat.isLoaded()) return false;
        ISpell spell = GoetyHelper.getSpell(focusId);
        if (spell == null) return false;

        try {
            ItemStack focusStack = GoetyHelper.deserializeFocus(focusId, customData);
            ItemStack staff = GoetyHelper.getStaffForSpell(spell, focusStack);

            // 基础消耗与冷却换算
            int rawSoul = spell.defaultSoulCost();
            double soulRatio = Config.GOETY_SOUL_TO_MANA_MULTIPLIER.get();
            int manaCost = Math.max(1, (int) Math.round(rawSoul * soulRatio));

            int rawCooldown = spell.defaultSpellCooldown();
            if (spell instanceof IChargingSpell charging && rawCooldown <= 0) {
                rawCooldown = charging.Cooldown();
            }
            double cdRatio = Config.GOETY_COOLDOWN_MULTIPLIER.get();
            int baseCooldown = Math.max(10, (int) Math.round(rawCooldown * cdRatio));

            float spellSpeed = Math.max(0.1f, npc.getEffectiveAttribute(AttributeType.SPELL_SPEED));
            SpellStat stat = GoetyHelper.buildSpellStat(level, npc, spell, focusStack);

            // 目标与朝向：精确计算三维偏航角与俯仰角，使射线与弹道直指目标
            if (target != null && target.isAlive()) {
                lookAtTarget(npc, target);
            }

            if (!safeConditionsMet(spell, level, npc, stat, focusId, target)) {
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
                // TODO：诡厄巫法的持续聚晶（EverChargeSpell，如箭雨聚晶 goety:arrow_rain_focus）无法正确释放——
                //  玩家侧需持续蓄力施法、过程中逐释放周期触发 SpellResult 分批放出（见 Goety DarkWand.MagicResults），
                //  这里的「一次 startSpell + 固定 channelTicks 引导 + 每 20t 补一发 SpellResult」模型对这类法术不成立，
                //  表现为箭雨聚晶引导到点即 stopSpell，放不出应有的持续箭雨。仅记录问题，不在此讨论解法。
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
        } catch (Throwable t) {
            Log.error(TAG, "Exception during casting Goety spell '{}' on NPC {}: {}",
                    focusId, npc.getUUID().toString().substring(0, 8), t.getMessage(), t);
            return false;
        }
    }

    private static void playCastingSound(ServerLevel level, WandscapeNpc npc, ISpell spell) {
        try {
            SoundEvent sound = spell.CastingSound(npc);
            if (sound == null) {
                sound = spell.CastingSound();
            }
            if (sound != null) {
                level.playSound(null, npc.getX(), npc.getY(), npc.getZ(),
                        sound, SoundSource.NEUTRAL, spell.castingVolume(), spell.castingPitch());
            }
        } catch (Throwable ignored) {}
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
                lookAtTarget(cast.npc, cast.target);
            }

            int currentDuration = cast.totalDuration - cast.remainingTicks;
            try {
                cast.spell.useSpell(cast.level, cast.npc, cast.staff, currentDuration, cast.stat);
                if (cast.spell instanceof IBreathingSpell breathing) {
                    breathing.showWandBreath(cast.npc, cast.staff, cast.stat);
                }

                // 持续引导与喷吐法术：每 20 tick 周期性结算一次 SpellResult 伤害
                if (cast.spell instanceof IChargingSpell charging
                        && (charging.everCharge() || cast.spell instanceof IBreathingSpell)) {
                    if (currentDuration > 0 && currentDuration % 20 == 0) {
                        cast.spell.SpellResult(cast.level, cast.npc, cast.staff, cast.stat);
                    }
                }
            } catch (Throwable t) {
                Log.warn(TAG, "Error ticking continuous spell '{}' on NPC {}: {}",
                        cast.focusId, cast.npc.getUUID().toString().substring(0, 8), t.getMessage());
            }

            cast.remainingTicks--;
            if (cast.remainingTicks <= 0) {
                try {
                    cast.spell.stopSpell(cast.level, cast.npc, cast.staff, ItemStack.EMPTY, currentDuration, cast.stat);
                    cast.spell.SpellResult(cast.level, cast.npc, cast.staff, cast.stat);
                    cast.npc.swing(InteractionHand.MAIN_HAND, true);
                } catch (Throwable t) {
                    Log.error(TAG, "Error finishing spell '{}' on NPC {}: {}",
                            cast.focusId, cast.npc.getUUID().toString().substring(0, 8), t.getMessage());
                }
                it.remove();
            }
        }
    }
}
