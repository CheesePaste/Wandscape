package com.wsteam.wandscape.compat.goety;

import com.Polarice3.Goety.api.magic.IBreathingSpell;
import com.Polarice3.Goety.api.magic.IChargingSpell;
import com.Polarice3.Goety.api.magic.ISpell;
import com.Polarice3.Goety.common.magic.SpellStat;
import com.Polarice3.Goety.utils.MobUtil;
import com.wsteam.wandscape.content.npc.attributes.NpcAttributes.AttributeType;
import com.wsteam.wandscape.content.npc.component.MagicState;
import com.wsteam.wandscape.content.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.foundation.util.BalanceValues;
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
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 诡厄巫法聚晶施法执行器。
 *
 * <p>瞬发法术（非 {@code IChargingSpell}）：即发一次 {@code SpellResult}，短锁手势 + 每魔法 CD。
 *
 * <p>{@code IChargingSpell}（EverCharge 持续 / 蓄力连发如 Steam / 呼吸）一律按 Goety 玩家侧「volley」语义驱动：
 * 充能（{@code castUp/speed}）→ 按 {@code IChargingSpell.Cooldown(caster,staff,shots)} 节拍逐发 {@code SpellResult}
 * → 打到 {@code shotsNumber} 或单轮齐射硬顶（{@code BalanceValues.sustainedCastMaxTicks}）自然收尾。
 * <ul>
 *   <li>**不占施法互斥锁**（volley 全程），各魔法 CD 照常走；让位由 GuardCombat 每轮复选打断（严格更高优先）。</li>
 *   <li>魔力按 Goety 扣费节拍：EverCharge/呼吸每 20 发扣一次 {@code manaCost}，Steam 类每发扣一次；
 *       不足即自动停，不罚 CD。</li>
 *   <li>冷却只在**自然打满**后上（= {@code defaultSpellCooldown} 换算值）；让位/蓝尽/目标消失停都不上。</li>
 * </ul>
 */
public final class GoetyCaster {

    private static final String TAG = "GoetyCaster";

    /** EverCharge/呼吸灵魂扣费节拍：每 20 发扣一次（对齐 Goety DarkWand SECONDS 计数）。 */
    private static final int COST_BLOCK = 20;

    /**
     * 单个活跃 volley 会话（按 NPC UUID 一一对应；纯运行时内存态，不持久化）。
     */
    private static final class ActiveVolley {
        final WandscapeNpc npc;
        final String focusId;
        final ISpell spell;
        final ItemStack staff;
        final SpellStat stat;
        final boolean everStyle;      // true=每 COST_BLOCK 发扣一次 manaUnit；false=每发扣一次
        final int cdAfterRound;       // 自然打满后上 CD 的基础值（SPELL_SPEED 缩短在 applyCooldown 内做）
        final float manaUnit;         // 每次计费的魔力单位
        final int chargeTicks;        // 充能前摇 tick（castUp/speed）
        final int shotLimit;          // shotsNumber(caster,staff)，>0 才计上限；0=无限（由硬顶束缚）
        int chargeElapsed;            // 已充能 tick
        int dischargeTicks;           // 已齐射 tick（硬顶按此计）
        int shotsFired;               // 已发放次数
        int cooldownLeft;             // 距下一次齐射的节拍 tick（对齐 Cooldown()）
        int sinceCost;                // 距上次计费的发放数（everStyle）
        boolean discharging;          // charge 是否完成、进入齐射

        ActiveVolley(WandscapeNpc npc, String focusId, ISpell spell, ItemStack staff, SpellStat stat,
                     boolean everStyle, int cdAfterRound, float manaUnit, int chargeTicks, int shotLimit) {
            this.npc = npc;
            this.focusId = focusId;
            this.spell = spell;
            this.staff = staff;
            this.stat = stat;
            this.everStyle = everStyle;
            this.cdAfterRound = cdAfterRound;
            this.manaUnit = manaUnit;
            this.chargeTicks = chargeTicks;
            this.shotLimit = shotLimit;
        }
    }

    private static final Map<UUID, ActiveVolley> ACTIVE_VOLLEYS = new HashMap<>();

    private GoetyCaster() {}

    // ── 供 GuardCombat / 决策循环查询与打断 ──

    /** 该 NPC 当前是否有活跃 volley。 */
    public static boolean isActive(WandscapeNpc npc) {
        return npc != null && ACTIVE_VOLLEYS.containsKey(npc.getUUID());
    }

    /** 该 NPC 当前活跃 volley 的 focusId；无则 null。 */
    @Nullable
    public static String activeFocusId(WandscapeNpc npc) {
        if (npc == null) return null;
        ActiveVolley v = ACTIVE_VOLLEYS.get(npc.getUUID());
        return v != null ? v.focusId : null;
    }

    /** 打断 NPC 当前 volley（让位/脱离战斗等）：stopSpell、不上 CD。无活跃 volley 时静默。 */
    public static void interrupt(WandscapeNpc npc) {
        if (npc == null) return;
        ActiveVolley v = ACTIVE_VOLLEYS.get(npc.getUUID());
        if (v != null) endVolley(v, false);
    }

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
            if (!spell.conditionsMet(level, npc, stat)) {
                Log.info(TAG, "Spell '{}' stat-conditions not met on NPC {} (target={}, dist={})",
                        focusId, npc.getUUID().toString().substring(0, 8),
                        target != null ? target.getName().getString() : "null",
                        target != null ? String.format("%.2f", npc.distanceTo(target)) : "N/A");
                return false;
            }
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
     *
     * @return 是否成功发起（或同 focus volley 已在放）；失败/门控不过返回 false。
     */
    public static boolean cast(ServerLevel level, WandscapeNpc npc, @Nullable LivingEntity target,
                               String focusId, @Nullable String customData) {
        if (!GoetyCompat.isLoaded()) return false;
        ISpell spell = GoetyHelper.getSpell(focusId);
        if (spell == null) return false;

        try {
            ItemStack focusStack = GoetyHelper.deserializeFocus(focusId, customData);
            ItemStack staff = GoetyHelper.getStaffForSpell(spell, focusStack);
            float spellSpeed = Math.max(0.1f, npc.getEffectiveAttribute(AttributeType.SPELL_SPEED));
            SpellStat stat = GoetyHelper.buildSpellStat(level, npc, spell, focusStack);

            boolean isCharging = spell instanceof IChargingSpell;
            if (!isCharging) {
                return castInstant(level, npc, target, focusId, spell, staff, stat, spellSpeed);
            }
            return castVolley(level, npc, focusId, spell, staff, stat, spellSpeed);
        } catch (Throwable t) {
            Log.error(TAG, "Exception during casting Goety spell '{}' on NPC {}: {}",
                    focusId, npc.getUUID().toString().substring(0, 8), t.getMessage(), t);
            return false;
        }
    }

    /** 瞬发（非 IChargingSpell）：维持即发语义；CD/锁口径经 GoetyHelper 统一换算。 */
    private static boolean castInstant(ServerLevel level, WandscapeNpc npc, @Nullable LivingEntity target,
                                       String focusId, ISpell spell, ItemStack staff, SpellStat stat,
                                       float spellSpeed) {
        int manaCost = GoetyHelper.manaCost(spell);
        int baseCooldown = GoetyHelper.baseCooldown(spell);
        int lockTicks = Math.max(10, (int) Math.ceil(15.0 / spellSpeed));
        if (target != null && target.isAlive()) {
            lookAtTarget(npc, target);
        }
        if (!safeConditionsMet(spell, level, npc, stat, focusId, target)) {
            return false;
        }
        if (!npc.tryCastSpell(focusId, baseCooldown, manaCost, lockTicks)) {
            return false;
        }

        npc.swing(InteractionHand.MAIN_HAND, true);
        playCastingSound(level, npc, spell);

        spell.SpellResult(level, npc, staff, stat);
        Log.info(TAG, "NPC {} cast instant goety spell '{}'",
                npc.getUUID().toString().substring(0, 8), focusId);
        return true;
    }

    /** IChargingSpell：注册一个 volley 会话。门控不过 / 同 focus 已在放 → false。 */
    private static boolean castVolley(ServerLevel level, WandscapeNpc npc, String focusId,
                                      ISpell spell, ItemStack staff, SpellStat stat, float spellSpeed) {
        IChargingSpell charging = (IChargingSpell) spell;
        UUID npcId = npc.getUUID();

        ActiveVolley existing = ACTIVE_VOLLEYS.get(npcId);
        if (existing != null) {
            if (existing.focusId.equals(focusId)) {
                // 同 focus 已在放：幂等不重起（guard 同 id 复选走 keep 分支，这里双保险）。
                return false;
            }
            // 异 focus（正常不该发生——策略栏诡厄聚晶 cap=1 且 dispatch 已打断）：保险起见打断旧的。
            endVolley(existing, false);
        }

        // 别的法术正处于施法锁手势中（普通魔法前摇）→ 拒绝并发起手；freeCast 调试模式豁免。
        if (npc.magic.getLockTicks() > 0 && !MagicState.isFreeCast()) {
            return false;
        }
        // volley 全程不占锁，故 CD 只在自然打满后才上；起手时该 focus 应为可施放（CD 无残留）。
        if (!npc.magic.canCast(focusId)) {
            return false;
        }
        if (!MagicState.isFreeCast() && npc.magic.getMana() < GoetyHelper.manaCost(spell)) {
            return false;
        }

        LivingEntity aim = npc.getTarget();
        if (aim == null || aim.isRemoved() || !aim.isAlive()) {
            return false;
        }
        lookAtTarget(npc, aim);
        if (!safeConditionsMet(spell, level, npc, stat, focusId, aim)) {
            return false;
        }

        int chargeTicks = Math.max(0, (int) Math.ceil(charging.defaultCastUp() / spellSpeed));
        int shotLimit = Math.max(0, charging.shotsNumber(npc, staff));

        npc.startManualCast(Math.max(20, chargeTicks + 20));
        playCastingSound(level, npc, spell);
        spell.startSpell(level, npc, staff, stat);

        ActiveVolley v = new ActiveVolley(npc, focusId, spell, staff, stat,
                GoetyHelper.everStyle(spell), GoetyHelper.baseCooldown(spell),
                GoetyHelper.manaCost(spell), chargeTicks, shotLimit);
        ACTIVE_VOLLEYS.put(npcId, v);
        Log.info(TAG, "NPC {} began goety volley '{}' (everStyle={}, charge={}, shots={}, cdAfterRound={})",
                npc.getUUID().toString().substring(0, 8), focusId, v.everStyle, chargeTicks, shotLimit, v.cdAfterRound);
        return true;
    }

    /**
     * 每 server tick 调用：推进所有活跃 volley（充能 → 逐发 → 自然收尾 / 自停）。
     * 用值快照遍历，允许本 tick 内任意处 endVolley 删 map。
     */
    public static void tickAll() {
        if (!GoetyCompat.isLoaded() || ACTIVE_VOLLEYS.isEmpty()) return;
        for (ActiveVolley v : new ArrayList<>(ACTIVE_VOLLEYS.values())) {
            if (ACTIVE_VOLLEYS.get(v.npc.getUUID()) != v) continue; // 已被打断/替换
            try {
                tickVolley(v);
            } catch (Throwable t) {
                Log.warn(TAG, "Error ticking goety volley '{}' on NPC {}: {}",
                        v.focusId, v.npc.getUUID().toString().substring(0, 8), t.getMessage());
                endVolley(v, false);
            }
        }
    }

    /** 推进单个 volley 一 tick；内部分支结束时调 endVolley（natural 才 applyCooldown）。 */
    private static void tickVolley(ActiveVolley v) {
        WandscapeNpc npc = v.npc;
        if (npc.isRemoved() || !npc.isAlive()) {
            endVolley(v, false);
            return;
        }
        if (!(npc.level() instanceof ServerLevel level)) {
            endVolley(v, false);
            return;
        }

        // 目标消失 / 脱离战斗（决策循环 markCombatEnd 置 target null）/ 传送引导 / 和平 → 立即停、不上 CD
        LivingEntity aim = npc.getTarget();
        if (aim == null || aim.isRemoved() || !aim.isAlive()) {
            endVolley(v, false);
            return;
        }
        if (npc.isPeaceMode() || npc.isTeleportChanneling(level.getGameTime())) {
            endVolley(v, false);
            return;
        }
        // 魔力不足以付下一计费单位 → 自动停（仿玩家每 tick 灵魂校验），不欠费、不上 CD
        if (!MagicState.isFreeCast() && npc.magic.getMana() < v.manaUnit) {
            endVolley(v, false);
            return;
        }

        lookAtTarget(npc, aim);
        int castTime = v.chargeElapsed + v.dischargeTicks;
        try {
            v.spell.useSpell(level, npc, v.staff, castTime, v.stat);
            if (v.spell instanceof IBreathingSpell breathing) {
                breathing.showWandBreath(npc, v.staff, v.stat);
            }
        } catch (Throwable t) {
            Log.warn(TAG, "Error ticking continuous spell '{}' on NPC {}: {}",
                    v.focusId, npc.getUUID().toString().substring(0, 8), t.getMessage());
        }

        if (!v.discharging) {
            v.chargeElapsed++;
            if (v.chargeTicks <= 0 || v.chargeElapsed >= v.chargeTicks) {
                v.discharging = true; // 充能完成；同一 tick 即尝试首发（对齐 Goety castUp 到达当刻触发）
            }
        }

        if (v.discharging) {
            v.dischargeTicks++;
            v.cooldownLeft--;
            if (v.cooldownLeft <= 0) {
                if (!trySpendCost(v)) {
                    endVolley(v, false); // 蓝不足，停
                    return;
                }
                try {
                    v.spell.SpellResult(level, npc, v.staff, v.stat);
                } catch (Throwable t) {
                    Log.warn(TAG, "Error on goety volley result '{}' on NPC {}: {}",
                            v.focusId, npc.getUUID().toString().substring(0, 8), t.getMessage());
                }
                v.shotsFired++;
                v.cooldownLeft = nextCadence(v);
                // 自然打满：打满 shotsNumber 或到单轮齐射硬顶 → 收尾并上 CD
                if ((v.shotLimit > 0 && v.shotsFired >= v.shotLimit)
                        || v.dischargeTicks >= BalanceValues.sustainedCastMaxTicks()) {
                    endVolley(v, true);
                }
            }
        }
    }

    /** 距下一次齐射的节拍（Goety Cooldown(caster,staff,已发数)，Steam 每 5 发歇 8t 由此驱动）。 */
    private static int nextCadence(ActiveVolley v) {
        try {
            return Math.max(0, ((IChargingSpell) v.spell).Cooldown(v.npc, v.staff, v.shotsFired));
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 一次齐射的计费：先查够再扣（扣够才放）。everStyle 每 COST_BLOCK 发扣一次；其余每发扣一次。 */
    private static boolean trySpendCost(ActiveVolley v) {
        if (MagicState.isFreeCast()) return true;
        if (v.everStyle) {
            v.sinceCost++;
            if (v.sinceCost >= COST_BLOCK) {
                if (v.npc.magic.getMana() < v.manaUnit) return false;
                v.npc.magic.spendMana(v.manaUnit);
                v.sinceCost = 0;
            }
            return true;
        }
        if (v.npc.magic.getMana() < v.manaUnit) return false;
        v.npc.magic.spendMana(v.manaUnit);
        return true;
    }

    /** 结束一个 volley：从 map 移除 + stopSpell + 落施法手势；自然打满才上每魔法 CD。 */
    private static void endVolley(ActiveVolley v, boolean applyCooldown) {
        if (v == null) return;
        if (ACTIVE_VOLLEYS.get(v.npc.getUUID()) == v) {
            ACTIVE_VOLLEYS.remove(v.npc.getUUID());
        }
        WandscapeNpc npc = v.npc;
        try {
            v.spell.stopSpell((ServerLevel) npc.level(), npc, v.staff, ItemStack.EMPTY,
                    v.chargeElapsed + v.dischargeTicks, v.stat);
        } catch (Throwable t) {
            Log.error(TAG, "Error stopping goety volley '{}' on NPC {}: {}",
                    v.focusId, npc.getUUID().toString().substring(0, 8), t.getMessage());
        }
        npc.endManualCast();

        if (applyCooldown && !MagicState.isFreeCast() && v.cdAfterRound > 0) {
            float speed = Math.max(0.1f, npc.getEffectiveAttribute(AttributeType.SPELL_SPEED));
            npc.magic.applyCooldown(v.focusId, v.cdAfterRound, speed);
            Log.info(TAG, "NPC {} finished goety volley '{}' after {} shots / {} ticks → cooldown {}",
                    npc.getUUID().toString().substring(0, 8), v.focusId, v.shotsFired,
                    v.chargeElapsed + v.dischargeTicks, v.cdAfterRound);
        } else {
            Log.info(TAG, "NPC {} ended goety volley '{}' (interrupted, no cooldown)",
                    npc.getUUID().toString().substring(0, 8), v.focusId);
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
}
