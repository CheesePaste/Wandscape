package com.wsteam.wandscape.integration.jei;

import java.util.List;
import java.util.Map;

import com.wsteam.wandscape.shared.data.ElementType;

/**
 * JEI 展示用的元素配方模型（纯逻辑，零 mezz/MC 运行时引用，可单测）。
 *
 * <p>{@code itemId} 存字符串而非 ItemStack，JEI 层再解析成物品，保持此模型与 JEI 解耦。
 *
 * <ul>
 *   <li>SYNTHESIZE：{@code elements} = 合成/酿造所需元素成本；{@code value} 无用。</li>
 *   <li>DECOMPOSE：{@code elements} = 完整元素价值（整数，槽位显示用），
 *       {@code value} = 物品总价值；精确分数产出 = value ÷ 除数 由 JEI 层在 tooltip 展示。</li>
 * </ul>
 *
 * <p>{@code stationKey} 标注配方发生的设施：{@code workstation}（元素合成/分解，工作站）、
 * {@code crafting_station}（合成站）、{@code potion_station}（酿造站）。
 * {@code extraInputs} 为额外的非元素原料（如药剂的玻璃瓶）。
 */
public record ElementRecipe(
    String id,
    ElementRecipeKind kind,
    String stationKey,
    String itemId,
    Map<ElementType, Long> elements,
    List<String> extraInputs,
    long value
) {
    public ElementRecipe {
        elements = Map.copyOf(elements);
        extraInputs = List.copyOf(extraInputs);
    }
}
