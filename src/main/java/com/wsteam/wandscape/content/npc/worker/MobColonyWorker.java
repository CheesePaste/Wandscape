package com.wsteam.wandscape.content.npc.worker;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.UUID;

/**
 * 通用工作者适配器：把**任意普通 {@link Mob}** 登记为殖民地工人（{@code api/ColonyWorkerApi} 的默认实现）。
 *
 * <p>它**几乎不覆写 {@link ColonyWorker} 的任何方法**，因为接口的 default 就是这只生物的行为本身：
 * 原版寻路走位、中性属性（工作速度等恒 1）、魔力 0（即不参与需要魔力门槛的任务）、护甲取原版有效值、
 * 没有脱困施法、不能承担守卫/祭坛任务（{@code canCastColonyMagic} 恒 false）、共用
 * {@link WorkerFx} 的工作动作。这些生物没有本模组注册的自定义属性，也不该被写进它们的 vanilla
 * 属性表（{@code EntityAttributeCreationEvent} 只在注册期生效）。将来若要给第三方工作者真正的属性
 * 成长，按 UUID 挂一份自有存储即可（先例：车万女仆的 {@code MaidColonyState}），接口不用改。
 *
 * <p>本类只补两件接口答不出来的事：殖民地的**可变归属**（换镇不重建 ECS 实体）与**移动抑制**
 * （关掉 {@code goalSelector} 的 MOVE 控制位，否则它自己的游荡/逃跑/追击会与工作走位打架；
 * 代价是它不再自主追击或逃跑，{@link #setAiWanderingEnabled(boolean)} 传 true 时恢复）。
 */
public final class MobColonyWorker implements ColonyWorker {

    private final Mob mob;
    private UUID colonyId;
    private boolean movementSuppressed;

    public MobColonyWorker(Mob mob, UUID colonyId) {
        this.mob = mob;
        this.colonyId = colonyId;
    }

    public Mob mob() {
        return mob;
    }

    /** 换镇：原地改归属，不重建 ECS 实体、不打断在途任务。 */
    public void setColonyId(UUID colonyId) {
        this.colonyId = colonyId;
    }

    @Override
    public Mob entity() {
        return mob;
    }

    @Override
    public UUID colonyId() {
        return colonyId;
    }

    /**
     * 压制/恢复该生物自身的移动 AI。
     *
     * <p>已知边界：原版 {@code GoalSelector} 没有读取控制位的访问器，所以这里无法记录"进入前的原值"。
     * 若某生物的 MOVE 控制位**在登记前就已被它自己或别的模组关掉**，这里的一次压制+恢复会把它打开
     * （原版默认四类标志位全开，故对绝大多数生物是安全的）。要修得靠访问器或 mixin，暂不为此开口子。
     */
    @Override
    public void setAiWanderingEnabled(boolean enabled) {
        if (enabled == !movementSuppressed) return;
        movementSuppressed = !enabled;
        mob.goalSelector.setControlFlag(Goal.Flag.MOVE, enabled);
        if (!enabled) {
            mob.getNavigation().stop();
        }
    }
}
