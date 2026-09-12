package com.wsteam.wandscape.compat.tlm;

import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.datafixers.util.Pair;
import com.wsteam.wandscape.Wandscape;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

/**
 * 官方女仆任务「小镇工作」：玩家在女仆界面选中它，女仆即进入**工作模式**——
 * 被登记为殖民地工作者（见 {@link TlmCompatImpl}），与殖民地法师共用同一条工作链
 * （调度器派活 → 原子操作执行器 → 产出进殖民地仓库），并在任务与法师管理面板里并排显示。
 *
 * <p>**为什么需要 Home 模式**：TLM 在恒活跃的 CORE 活动里挂了 {@code MaidFollowOwnerTask}，
 * 它声明的是 {@code WALK_TARGET, REGISTERED}（不是 ABSENT），挡不住；没开 Home 模式时女仆会
 * 一路跟着主人跑，表现为在主人与工地之间来回横跳。所以 {@link #isEnable} 要求 Home 模式已开，
 * 并用 {@link #getEnableConditionDesc} 明确告诉玩家缺什么。
 *
 * <p>**为什么 {@code createBrainTasks} 是空的**：工作移动不走 brain 行为，而是由 ECS 的
 * {@code NavigationSystem} 经 {@code MaidColonyWorker.moveTo} 直接驱动原版寻路——与法师完全同机制。
 * 返回空列表时 TLM 会补上一个"按作息切活动"的行为，正是我们想要的。
 * 面朝工作点与手到工作点的粒子线由 {@link TlmCompatImpl#syncWorkVisual} 每 tick 同步。
 */
public final class ColonyWorkerMaidTask implements IMaidTask {

    public static final ResourceLocation UID =
            ResourceLocation.fromNamespaceAndPath(Wandscape.MODID, "colony_worker");

    @Override
    public ResourceLocation getUid() {
        return UID;
    }

    @Override
    public ItemStack getIcon() {
        return new ItemStack(Wandscape.WAND.get());
    }

    @Override
    @Nullable
    public SoundEvent getAmbientSound(EntityMaid maid) {
        return null;
    }

    @Override
    public List<Pair<Integer, BehaviorControl<? super EntityMaid>>> createBrainTasks(EntityMaid maid) {
        return Collections.emptyList();
    }

    /** 工作态不随机走动：否则她在工地附近溜达会与工作走位抢导航。 */
    @Override
    public boolean enableLookAndRandomWalk(EntityMaid maid) {
        return false;
    }

    /**
     * 只有**已开站位模式**的女仆才能选中这个任务。
     *
     * <p>⚠️ 本方法会在**客户端**被调用（TLM 的女仆 GUI 用它决定按钮是否可点，
     * `AbstractMaidContainerGui.drawPerTaskButton`），所以条件必须是客户端拿得到的同步数据。
     * 「主人有小镇」**刻意不放进这里**：它要查殖民地 SavedData（`ColonyApi.getColonyByFounder`
     * 走 `ServerLifecycleHooks.getCurrentServer()`），在**专用服务器的客户端恒为 null**——
     * 放进来会让任务在多人游戏里永久置灰、点不动。那条限制改由服务端对账把关（见
     * {@link TlmCompatImpl#reconcile}），并写进任务描述让玩家事先看得到。
     */
    @Override
    public boolean isEnable(EntityMaid maid) {
        return maid.isHomeModeEnable();
    }

    /**
     * 启用条件的逐条说明（未启用时由女仆界面强制显示，绿的满足、红的未满足）。
     * 语言键形如 {@code task.wandscape.colony_worker.enable_condition.<key>}。
     *
     * <p>同样只列客户端可判定的条件——原因见 {@link #isEnable}。
     */
    @Override
    public List<Pair<String, Predicate<EntityMaid>>> getEnableConditionDesc(EntityMaid maid) {
        return List.of(Pair.of("home_mode", EntityMaid::isHomeModeEnable));
    }

    /** 供 AI 对话/概览面板读取的一句话动作摘要。 */
    @Override
    public String getMaidActionSummary() {
        return "colony_work";
    }
}
