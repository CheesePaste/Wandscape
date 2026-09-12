package com.wsteam.wandscape.api;

import net.minecraft.world.entity.LivingEntity;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * 殖民地工作者登记 API：让其它模组把自己的生物变成殖民地工人，参与城镇建设、接取任务。
 *
 * <p>登记后该生物与殖民地法师共用同一条工作链（{@code SchedulerSystem} 派活 →
 * {@code TaskExecutionSystem} 执行原子操作），并一并出现在任务与法师管理面板里。
 *
 * <p>用法（在模组初始化完成、拿到殖民地 UUID 后调用；某个殖民地用
 * {@link ColonyApi#getColonyByFounder(UUID)} 或 {@link WandscapeApis#colonyAt} 解析）：
 * <pre>{@code
 * WandscapeApis.getColonyWorkerApiSilently().ifPresent(api ->
 *     api.enlist(myColonyId, myCreature));
 * }</pre>
 *
 * <p><b>能力与限制</b>（登记前请确认这些对你是可接受的）：
 * <ul>
 *   <li>要求目标的底层实体是 {@link net.minecraft.world.entity.Mob}（需要原版寻路）。非 Mob 返回 false。</li>
 *   <li>通用适配器按「原版寻路 + 中性属性」工作：走位走 {@code getNavigation()}，工作速度为 1、
 *       魔力为 0（即不参与需要魔力门槛的任务）、护甲取原版有效值。没有本模组法师那套
 *       7 项属性 / 已学法术 / 策略槽——那些是阶段二的事。</li>
 *   <li>登记期间会关闭该生物自身的 AI 移动（{@code GoalSelector} 的 MOVE 控制位），
 *       否则它自己的游荡/逃跑会与工作走位互相打架。**这会让它不再自主追击或逃跑**，
 *       是"当工人"的代价；{@link #dismiss} 时恢复。</li>
 *   <li>解除登记（{@link #dismiss}）会注销 ECS 工作者、释放它占用的全局任务、取消在途运输。
 *       生物被其它模组移除（死亡/删除）时也会自动清理，无需手动调用。</li>
 * </ul>
 *
 * <p><b>与 {@link FriendlyForceApi} 的区别</b>：那个管"别打我的人"，这个管"让他替我干活"。
 * 两者独立——把生物登记为工作者并不会让它成为殖民地友军，反之亦然。
 */
public interface ColonyWorkerApi {

    /**
     * 把一个生物登记为某殖民地的工作者。
     *
     * <p>幂等：已登记且殖民地相同 → 返回 true 不做事；已登记但殖民地不同 → 改归属到新殖民地。
     *
     * @param colonyId 目标殖民地（须是已注册的真实殖民地，否则该工作者拿不到任务）
     * @param entity   目标生物（底层须为 {@code Mob}）
     * @return 是否登记成功；参数非法、非 Mob、实体已移除、殖民地无效时返回 false
     */
    boolean enlist(UUID colonyId, LivingEntity entity);

    /**
     * 解除登记：注销 ECS 工作者、释放全局任务（保留进度供重派）、取消资源预留与在途运输、
     * 恢复该生物自身的 AI 移动。对未登记的生物是空操作。
     */
    void dismiss(LivingEntity entity);

    /** 该生物当前是否已登记为工作者。 */
    boolean isEnlisted(LivingEntity entity);

    /** 该生物当前所属殖民地；未登记返回 null。 */
    @Nullable
    UUID getWorkerColony(LivingEntity entity);

    /** 该生物的 ECS 工作者 id（任务面板 / 调试用）；未登记返回 -1。 */
    long getWorkerEcsId(LivingEntity entity);
}
