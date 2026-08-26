package com.wsteam.wandscape.shared.entity;

import java.util.UUID;

/**
 * 标记接口：属于某殖民地的访客类实体（游客），暴露其所属殖民地。
 *
 * <p>供友军名单派生（{@code WandscapeNpc#isFriendlyForce}）判定「同殖民地游客」——
 * 避免战斗溅射误伤短居访客。仅表示「有殖民地归属」这一契约，不引入游客的其它任何行为。
 * 用共享标记而非 npc 模块直接引用 {@code TouristEntity}，遵守模块间不直接引用规则。
 */
public interface ColonyVisitor {

    /** 所属殖民地；未归属（占位殖民地）时可为 null，按占位殖民地处理。 */
    UUID getColonyId();
}
