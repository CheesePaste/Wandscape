package com.wsteam.wandscape.magic.data;

import javax.annotation.Nullable;

/**
 * 一个已装备法术及其所在的**策略组**（{@code EquippedMagicComponent} 的桶名：
 * single_target / aoe / defense / support 之一）。纯数据，零 MC 依赖。
 *
 * <p>策略组由玩家在策略页放置决定（非法术自身的 {@link MagicDef#category()}）——
 * CastBrain 的敌数门控（单体组 ≤3 / 群攻组 ≥3）与预设排序都按 {@code group} 判，
 * 使门控与玩家可见、可操作的分组一致。SPECIAL/ALTAR 等不进装备桶的法术 {@code group} 为 null。
 *
 * @param def   法术定义
 * @param group 所在策略组名；未进装备桶（系统固有/祭坛）为 null
 */
public record SpellRef(MagicDef def, @Nullable String group) {

    public SpellRef {
        if (def == null) throw new NullPointerException("def cannot be null");
    }
}
