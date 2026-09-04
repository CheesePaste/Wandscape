package com.wsteam.wandscape.api;

import com.wsteam.wandscape.content.npc.data.MageResume;
import com.wsteam.wandscape.content.npc.data.RecruitmentCandidate;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.UUID;
public interface TavernApi {
    /** Returns mage resumes available at a tavern, newest first. */
    List<MageResume> getMageResumes(UUID colonyId);

    /**
     * Recruit a mage by resume index: the resume is consumed and a real {@code WandscapeNpc}
     * is generated at the tavern (via {@link NpcApi#spawnNpc}), carrying the resume's stats.
     *
     * @return the consumed resume on success, or null if the index is invalid / spawning failed
     *         (in which case the resume is NOT consumed)
     */
    MageResume recruitMage(UUID tavernId, UUID colonyId, int index);

    /**
     * Recruit by resume index with {@link NpcSpawnSpec} overrides merged on top of the resume.
     * Overridden fields (attributes/name/level/skin/spells/…) win over the resume's values.
     *
     * @return the consumed resume on success, or null on invalid index / spawn failure
     */
    MageResume recruitMage(UUID tavernId, UUID colonyId, int index, NpcSpawnSpec spec);

    /** Reject a mage resume by index (removes it without spawning). Returns the removed resume or null if invalid. */
    MageResume rejectMage(UUID colonyId, int index);

    /** 小镇累计成功「招募 NPC」的次数（首次免费，自第二次起收费）。 */
    int getRecruitCount(UUID colonyId);

    /** 能否进行下一次「招募 NPC」：首次免费；之后需小镇每种元素 ≥ 招募成本。 */
    boolean canAffordRecruit(UUID colonyId);

    /**
     * 消耗一次「招募 NPC」代价并计数：首次免费；之后每种元素扣 {@code Config.TAVERN_RECRUIT_COST_PER_ELEMENT}。
     * 生成成功后调用，返回是否扣费成功。
     */
    boolean chargeRecruit(UUID colonyId);

    /** 按自定义花费消耗一次「招募 NPC」代价（整合包可调便宜/贵）。 */
    boolean chargeRecruit(UUID colonyId, int costPerElement);

    /**
     * 付费招募一名法师（默认：掷点真实档案 + 默认花费），生成并计入招募次数。
     *
     * @return 生成 NPC 的 UUID；元素不足（第二次起）、系统未就绪或生成失败返回 null（且不计费）
     */
    UUID recruitForColony(UUID colonyId, BlockPos spawnPos);

    /**
     * 付费招募，传入自定义 {@link NpcSpawnSpec}（可做更强的特殊 NPC）与自定义花费。
     *
     * @return 生成 NPC 的 UUID；元素不足（第二次起）、系统未就绪或生成失败返回 null（且不计费）
     */
    UUID recruitForColony(UUID colonyId, BlockPos spawnPos, NpcSpawnSpec spec, int costPerElement);

    // ── 未实现（重设计阶段声明，见 @Unimplemented）──

    /** 直接向某殖民地的酒馆简历池注入一份简历（任务/奖励用，未生成实体）。 */
    @Unimplemented("重设计阶段——待接入 TavernRecruitStorage.addResume")
    default void addResume(UUID colonyId, MageResume resume) {
        throw new UnsupportedOperationException("TavernApi.addResume not yet implemented");
    }
}
