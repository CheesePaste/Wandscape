package com.wsteam.wandscape.foundation.util;

/**
 * 方块 id 字符串的纯字符串处理（不依赖 MC，可被纯逻辑层引用）。
 */
public final class BlockIds {

    private BlockIds() {}

    /**
     * 去掉方块状态后缀：{@code "minecraft:oak_stairs[facing=east]"} → {@code "minecraft:oak_stairs"}。
     *
     * <p>元素映射、建材统计都按**裸方块 id** 记账，所以逐块循环里必须先剥掉 {@code [...]}。
     * 这里手写截断而不是 {@code replaceAll("\\[.*?\\]", "")}：后者每次调用都要重新编译一次
     * 正则，而这段代码被放在逐方块循环里（一栋大建筑 58 万次），实测单次虽只占 0.27%，
     * 但它是白给的开销。方块状态串由 {@code BlockState#toString} 产出，形如
     * {@code id[prop=val,...]}，只有一个方括号组且其后无内容，所以截断到第一个 {@code [} 即等价。
     */
    public static String stripBlockState(String blockId) {
        int open = blockId.indexOf('[');
        return (open < 0 ? blockId : blockId.substring(0, open)).trim();
    }
}
