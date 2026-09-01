package com.wsteam.wandscape.content.npc.component;
import com.wsteam.wandscape.content.npc.attributes.NpcAttributes.AttributeType;

import java.util.HashMap;
import java.util.Map;

/**
 * 魔力资源 + 每魔法独立冷却 + 施法互斥锁。
 *
 * <p>纯 Java 零 MC 依赖，由 {@code WandscapeNpc} 持有并在 tick 时推进。
 * 魔力上限不是本类的字段——它是第 7 属性（{@code AttributeType.MAX_MANA}），
 * 由 {@code EquipmentComponent} 权威计算，调用方（实体）每 tick 传入。
 *
 * <p>冷却在施法互斥锁占用期间冻结、锁释放后才倒计时——CD 表示「施法结束后的恢复
 * 间隔」，施法时间（法阵/引导/光束全程）不计入 CD。总间隔 = 锁时长 + 冷却。
 */
public class MagicState {

    private float currentMana;
    private int manaRegenAccum;
    private int lockTicks;
    private boolean manaSeeded;
    private final Map<String, Integer> cooldowns = new HashMap<>();

    /** 测试模式：无冷却、无法力消耗全局开关（纯运行时调试，不持久化）。 */
    private static volatile boolean freeCast = false;

    public static boolean isFreeCast() {
        return freeCast;
    }

    public static void setFreeCast(boolean enabled) {
        freeCast = enabled;
    }

    public static boolean toggleFreeCast() {
        freeCast = !freeCast;
        return freeCast;
    }

    /** 是否可施该魔法：互斥锁未占用且该魔法冷却已过（测试模式下忽略冷却）。 */
    public boolean canCast(String magicId) {
        if (freeCast) return lockTicks <= 0;
        return lockTicks <= 0 && cooldowns.getOrDefault(magicId, 0) <= 0;
    }

    /**
     * 原子尝试施放：锁/CD/蓝任一不满足即拒绝；成功则扣蓝、置该魔法 CD、占互斥锁。
     * （测试模式下不扣蓝、不增加冷却）
     *
     * @param baseCooldown 基础冷却 tick（按 spellSpeed 缩短，向上取整）；锁占用期间冻结，
     *                     锁释放后才开始倒计时
     * @param manaCost     固定魔力消耗
     * @param lockTicks    施法期间占用的互斥锁时长（该魔法施放全程）
     * @param spellSpeed   SPELL_SPEED 有效值（&gt;1 时缩短 CD）
     */
    public boolean tryCast(String magicId, int baseCooldown, int manaCost,
                           int lockTicks, float spellSpeed) {
        if (freeCast) {
            if (this.lockTicks > 0) return false;
            this.lockTicks = Math.max(this.lockTicks, lockTicks);
            return true;
        }
        if (!canCast(magicId) || currentMana < manaCost) return false;
        currentMana -= manaCost;
        int eff = spellSpeed > 1f ? (int) Math.ceil(baseCooldown / spellSpeed) : baseCooldown;
        cooldowns.merge(magicId, eff, Math::max);
        this.lockTicks = Math.max(this.lockTicks, lockTicks);
        return true;
    }

    /**
     * 祭坛施法：扣蓝 + 占互斥锁，但**不设置本 NPC 的每魔法 CD**。
     *
     * <p>祭坛施法的冷却按祭坛（building）独立存放（见 AltarCastState），与 NPC 自身的
     * 每魔法 CD 解耦——否则同一 NPC 在 A 祭坛施法会被自身 CD 挡住 B 祭坛，
     * 违反"不同祭坛之间 CD 不共享"。锁仍占用，保证引导期间 NPC 不并发施法/战斗施法。
     *
     * @param manaCost  固定魔力消耗
     * @param lockTicks 引导期间占用的互斥锁时长（= 祭坛魔法时长）
     */
    public boolean tryAltarCast(int manaCost, int lockTicks) {
        if (freeCast) {
            if (this.lockTicks > 0) return false;
            this.lockTicks = Math.max(this.lockTicks, lockTicks);
            return true;
        }
        if (this.lockTicks > 0 || currentMana < manaCost) return false;
        currentMana -= manaCost;
        this.lockTicks = Math.max(this.lockTicks, lockTicks);
        return true;
    }

    /**
     * 每 server tick 推进：锁递减；锁占用期间每魔法 CD 冻结，锁释放后才递减。
     * 每 {@code regenIntervalTicks} 结算回 {@code maxMana * regenFraction} 点魔力，封顶 maxMana。
     */
    public void tickRegen(float maxMana, int regenIntervalTicks, float regenFraction) {
        if (lockTicks > 0) {
            lockTicks--;
        } else {
            cooldowns.replaceAll((k, v) -> Math.max(0, v - 1));
        }
        cooldowns.entrySet().removeIf(e -> e.getValue() <= 0);
        if (currentMana >= maxMana) {
            manaRegenAccum = 0;
            return;
        }
        manaRegenAccum++;
        if (manaRegenAccum >= regenIntervalTicks) {
            manaRegenAccum = 0;
            currentMana = Math.min(currentMana + maxMana * regenFraction, maxMana);
        }
    }

    // ---- 存取（NBT 由持有方序列化） ----

    public float getMana() { return currentMana; }

    public void setMana(float v) { currentMana = Math.max(0f, v); }

    public boolean isManaSeeded() { return manaSeeded; }

    public void markManaSeeded() { manaSeeded = true; }

    public int getManaRegenAccum() { return manaRegenAccum; }

    public void setManaRegenAccum(int v) { manaRegenAccum = v; }

    public int getLockTicks() { return lockTicks; }

    public void setLockTicks(int v) { lockTicks = v; }

    public int getCooldown(String magicId) { return cooldowns.getOrDefault(magicId, 0); }

    /** 全部魔法冷却（magicId → 剩余 tick），供序列化。 */
    public Map<String, Integer> getCooldowns() { return cooldowns; }

    /** NBT 反序列化入口：覆盖全部可持久字段（cooldowns 先清空再填充）。 */
    public void load(float currentMana, int manaRegenAccum, int lockTicks,
                     boolean manaSeeded, Map<String, Integer> cooldowns) {
        this.currentMana = Math.max(0f, currentMana);
        this.manaRegenAccum = manaRegenAccum;
        this.lockTicks = lockTicks;
        this.manaSeeded = manaSeeded;
        this.cooldowns.clear();
        if (cooldowns != null) this.cooldowns.putAll(cooldowns);
    }
}
