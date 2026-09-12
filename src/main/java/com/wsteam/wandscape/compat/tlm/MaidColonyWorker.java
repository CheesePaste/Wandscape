package com.wsteam.wandscape.compat.tlm;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.wsteam.wandscape.content.npc.attributes.NpcAttributes.AttributeType;
import com.wsteam.wandscape.content.npc.worker.ColonyWorker;
import net.minecraft.world.entity.Mob;

import java.util.UUID;

/**
 * 车万女仆的 {@link ColonyWorker} 适配器：让女仆接入殖民地工作链。
 *
 * <p>**导航**走 {@link ColonyWorker} 的默认实现（直接驱动原版 {@code getNavigation()}），与法师同机制。
 * 曾经试过走 Brain 的 {@code WALK_TARGET} 记忆通道，被实测否决——原因写在
 * {@link ColonyWorker#moveTo} 上（TLM 的 {@code MaidAwaitTask} 会擦掉超出女仆站位半径的行走目标，
 * 而工地基本都在站位半径之外）。
 *
 * <p>**前置：女仆必须开启 Home 模式**（TLM 的叫法，见其 lang `gui.touhou_little_maid.button.home.true`）。
 * 否则 CORE 里的 {@code MaidFollowOwnerTask} 会一直把她拉向主人（它声明的是
 * {@code WALK_TARGET, REGISTERED} 而非 ABSENT，挡不住），表现为在主人与工地之间来回横跳。
 * 这一点由 {@code ColonyWorkerMaidTask.isEnable} 把关。
 *
 * <p>**只覆写"女仆与法师不一样"的两处**：殖民地归属（由主人推导、不可变）与属性来源
 * （女仆的 vanilla 属性表不含本模组自定义属性，读 {@link MaidColonyState}）。另外三处刻意保持默认：
 * <ul>
 *   <li>{@code setAiWanderingEnabled} 空实现——女仆是 Brain 驱动，随机走动由 TLM 的
 *       {@code IMaidTask#enableLookAndRandomWalk} 统一关掉，不去碰 {@code goalSelector}
 *       （她在那上面没有行为）。</li>
 *   <li>模式标志恒 false（继承默认）= "永远可以接活"——女仆没有"跟随/休息"这对概念，
 *       跟随由 TLM 的 Home 模式决定，这正是调度器想要的。</li>
 *   <li>阶段一没有殖民地法术体系：{@code tryEscapeCast} / {@code canCastColonyMagic} 继承默认的
 *       false，女仆不会接守卫/祭坛任务，导航卡死时也只回退为继续走路、不自传送。阶段二接上魔法
 *       后覆写 {@code canCastColonyMagic} 即可，调度器不用动。</li>
 * </ul>
 *
 * <p>**视觉/动画**：工作动作走默认的 {@code doWorkAnimation}（与法师共用 {@code WorkerFx} 的挥手 +
 * 目标点粒子），**尚未做**朝向与施法射线那类持续表现。若要补，落点在
 * {@link TlmCompatImpl#reconcile} 所在的女仆 tick 路径（可读 ECS 执行器的 {@code currentOpTarget}），
 * 对齐法师的 {@code WandscapeNpc.tickCastingState}。
 */
public final class MaidColonyWorker implements ColonyWorker {

    private final EntityMaid maid;
    private final UUID colonyId;

    /** 工人模式的法杖是不是我们给的（只有是我们给的，离开时才清掉，不碰玩家自己给的东西）。 */
    private boolean gaveWand;

    public MaidColonyWorker(EntityMaid maid, UUID colonyId) {
        this.maid = maid;
        this.colonyId = colonyId;
    }

    public EntityMaid maid() {
        return maid;
    }

    public boolean gaveWand() {
        return gaveWand;
    }

    public void setGaveWand(boolean gaveWand) {
        this.gaveWand = gaveWand;
    }

    @Override
    public Mob entity() {
        return maid;
    }

    @Override
    public UUID colonyId() {
        return colonyId;
    }

    /** 女仆没有自己的殖民地属性表，读挂在实体上的自有状态容器（同 `NpcAttributes.computeEffective` 纯函数）。 */
    @Override
    public float getEffectiveAttribute(AttributeType type) {
        return state().effective(type);
    }

    private MaidColonyState state() {
        return maid.getData(MaidColonyAttachments.MAID_COLONY_STATE.get());
    }
}
