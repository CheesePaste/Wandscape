package com.wsteam.wandscape.shared.data;

import net.minecraft.util.StringRepresentable;

/**
 * 游客活动状态 / 交互位动作种类。
 *
 * <p>放 {@code shared/data}（不是 tourist/internal），因为 {@code building/data/BuildingConfig.InteractSpot}
 * 要引用它，避免跨模块直接引用。
 *
 * <p>{@code TouristState} 保持移动状态标签，禁止扩展为状态机；活动状态一律走本枚举。
 * 实现 {@link StringRepresentable} 以便作为 blockstate 属性值（interact_spot_marker 的 action）。
 */
public enum Activity implements StringRepresentable {
    /** 赶路中（AI 移动状态，非交互位动作）。 */
    TRAVEL,
    /** 排队等待（spot 全满，AI 状态，非交互位动作）。 */
    QUEUE,
    /** 浏览/参观（交互位动作）。 */
    BROWSE,
    /** 用餐（交互位动作）。 */
    EAT,
    /** 泡澡（交互位动作）。 */
    BATHE,
    /** 看展/观景（交互位动作）。 */
    VIEW,
    /** 冥想（交互位动作）。 */
    MEDITATE,
    /** 睡觉（旅店夜晚，非交互位动作）。 */
    SLEEP,
    /** 歇脚/休息（交互位动作）。 */
    REST,
    /** 取现（atm 交互位动作）。 */
    WITHDRAW;

    /** 可设置在 {@code interact_spot_marker} 交互位上的动作子集。 */
    public static final Activity[] SPOT_ACTIONS = {
            BROWSE, EAT, BATHE, VIEW, MEDITATE, REST, WITHDRAW
    };

    /** JSON 字符串 = 枚举名小写；非法值回退 BROWSE。 */
    public static Activity fromJsonString(String s) {
        if (s == null || s.isBlank()) return BROWSE;
        try {
            return Activity.valueOf(s.toUpperCase());
        } catch (IllegalArgumentException e) {
            return BROWSE;
        }
    }

    /** 序列化为小写 JSON 字符串。 */
    public String toJsonString() {
        return name().toLowerCase();
    }

    @Override
    public String getSerializedName() {
        return name().toLowerCase();
    }
}
