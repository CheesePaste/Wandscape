package com.wsteam.wandscape.api;

import com.wsteam.wandscape.content.npc.data.MageResume;
import com.wsteam.wandscape.content.npc.data.RecruitmentCandidate;

import java.util.List;
import java.util.UUID;
public interface TavernApi {
    /** Returns mage resumes available at a tavern, newest first. */
    List<MageResume> getMageResumes(UUID colonyId);

    /** Recruit a mage by resume index. Returns the resume data or null if invalid. */
    MageResume recruitMage(UUID tavernId, UUID colonyId, int index);

    /** Reject a mage resume by index (removes it without spawning). Returns the removed resume or null if invalid. */
    MageResume rejectMage(UUID colonyId, int index);

    /** 小镇累计成功「招募 NPC」的次数（首次免费，自第二次起收费）。 */
    int getRecruitCount(UUID colonyId);

    /** 能否进行下一次「招募 NPC」：首次免费；之后需小镇每种元素 ≥ 招募成本。 */
    boolean canAffordRecruit(UUID colonyId);

    /** 消耗一次「招募 NPC」代价并计数：首次免费；之后每种元素扣招募成本。生成成功后调用，返回是否扣费成功。 */
    boolean chargeRecruit(UUID colonyId);

    // ── 未实现（重设计阶段声明，见 @Unimplemented）──

    /** 直接向某殖民地的酒馆简历池注入一份简历（任务/奖励用，未生成实体）。 */
    @Unimplemented("重设计阶段——待接入 TavernRecruitStorage.addResume")
    default void addResume(UUID colonyId, MageResume resume) {
        throw new UnsupportedOperationException("TavernApi.addResume not yet implemented");
    }
}
