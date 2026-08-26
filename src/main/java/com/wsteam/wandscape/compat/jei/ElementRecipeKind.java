package com.wsteam.wandscape.compat.jei;

/** JEI 展示的元素配方类型：合成或分解。 */
public enum ElementRecipeKind {
    /** 元素合成（元素 → 物品）。 */
    SYNTHESIZE,
    /** 元素分解（物品 → 元素，产出 = 价值 ÷ ELEMENT_DECOMPOSE_DIVISOR）。 */
    DECOMPOSE
}
