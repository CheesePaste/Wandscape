package com.wsteam.wandscape.compat.jei;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import com.wsteam.wandscape.magic.data.MagicDef;

/**
 * 从 {@code magic_spells/*.json} 收集 JEI 魔法卷轴信息（纯逻辑，零 mezz 引用，可单测）。
 *
 * <p>JEI 对每个已绑定魔法的卷轴注册一条信息页（recipe usage 侧），介绍的保留字段
 * {@code description}。ALTAR 祭坛专属魔法（revive）无卷轴物品形态，不带描述或不满足条件的魔法一律跳过。
 */
public final class SpellInfoCollector {

    private SpellInfoCollector() {}

    /**
     * 按传入魔法定义集合的顺序生成卷轴信息条目；跳过 ALTAR（祭坛专属，无卷轴）与
     * 无 {@code description}（或为空）的魔法。空输入返回空列表。
     */
    public static List<SpellInfoEntry> fromDefs(Collection<MagicDef> defs) {
        List<SpellInfoEntry> result = new ArrayList<>();
        for (MagicDef def : defs) {
            if (def == null || def.category() == MagicDef.Category.ALTAR) continue;
            String description = def.description();
            if (description == null || description.isBlank()) continue;
            result.add(new SpellInfoEntry(def.id(), description));
        }
        return result;
    }
}