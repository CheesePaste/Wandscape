package com.wsteam.wandscape.api;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Mage Hut（法师小屋）入住绑定契约。
 *
 * <p>绑定以 {@code BuildingSavedData} 的入住记录（buildingId → MageHutResident）为存档权威：
 * 法师战死等待复活时记录保留（resident.npcId 仍指向死者），因此 {@link #getBindingHut} 对
 * "曾绑定、待复活"的法师同样有效——与复活 {@code rebindToMageHut} 的反查一致。
 *
 * <p>获取：{@code WandscapeApis.getMageHutApi()}。
 */
public interface MageHutApi {

    /**
     * 查询一名 NPC 绑定的小屋（存档权威，含战死待复活的法师）。
     *
     * @return 小屋建筑 id；未绑定返回 null
     */
    @Nullable
    UUID getBindingHut(UUID npcId);

    /**
     * 强制绑定：把一名活体殖民地法师安排进小屋。
     *
     * <p>目标小屋已有人则顶替（旧入住记录移除、其活体法师解除 homeHut）；NPC 若已绑他屋先解旧。
     * colony 归属校验保留：NPC 必须属于该小屋所属殖民地，否则拒绝（防止绑定记录在 A 镇、实体属 B 镇的脏数据）。
     * NPC 必须为在世实体（入住记录需现场取属性/等级）；已绑这间小屋则为幂等成功。
     *
     * @return 绑定成功 true；建筑非 mage_hut / 无殖民地 / NPC 不存在或非本殖民地成员 false
     */
    boolean forceBind(UUID buildingId, UUID npcId);

    /**
     * 强制解绑：清空一间小屋的入住记录，并解除其活体入住者的 homeHut。
     *
     * @return 有入住记录被移除 true；小屋无入住记录或非 mage_hut false
     */
    boolean forceUnbind(UUID buildingId);

    /**
     * 强制解绑：把某 NPC 从它绑定的任意小屋上解下（反查入住记录）。
     *
     * @return 解除成功 true；NPC 未绑定 false
     */
    boolean forceUnbindNpc(UUID npcId);

    // ── 可调平衡值（委托 BalanceValues；运行时生效，不追溯受影响实体）──

    /** 法师小屋单次休息时长（tick）。 */
    int getMageHutRestTicks();
    void setMageHutRestTicks(int v);
}