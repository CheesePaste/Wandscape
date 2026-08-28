package com.wsteam.wandscape.compat.jei;

/**
 * 魔法卷轴的 JEI 信息条目（纯逻辑，零 mezz 引用，可单测）。
 *
 * <p>{@code magicId} 用于构建绑定该魔法的 {@code spell_scroll} ItemStack；
 * {@code description} 是 JEI 信息页展示的介绍文本（来自 {@code magic_spells/*.json} 的
 * {@code description}），渲染时经 {@code magic.wandscape.&lt;id&gt;.desc} 语言键本地化，
 * 缺省时回退到原始文本。
 */
public record SpellInfoEntry(String magicId, String description) {
    public SpellInfoEntry {
        description = description == null || description.isBlank() ? null : description;
    }
}