package com.wsteam.wandscape.compass;

/**
 * 魔法指南针的档位。决定解锁等级、是否在 tooltip 显示市政厅坐标、是否右键传送。
 */
public enum CompassTier {

    /** 合成站 1 级解锁：仅指向市政厅（圆盘蓝）。 */
    BASIC("magic_compass", 1, 0x4A90D9, false, false),
    /** 合成站 10 级解锁：额外在 tooltip 显示市政厅坐标（圆盘金）。 */
    ADVANCED("advanced_magic_compass", 10, 0xD4AF37, true, false),
    /** 合成站 20 级解锁：额外右键传送（圆盘紫）。 */
    ULTIMATE("ultimate_magic_compass", 20, 0x9B30FF, true, true);

    private final String itemId;
    private final int minColonyLevel;
    private final int themeColor;
    private final boolean showsCoords;
    private final boolean canTeleport;

    CompassTier(String itemId, int minColonyLevel, int themeColor, boolean showsCoords, boolean canTeleport) {
        this.itemId = itemId;
        this.minColonyLevel = minColonyLevel;
        this.themeColor = themeColor;
        this.showsCoords = showsCoords;
        this.canTeleport = canTeleport;
    }

    /** 本档位物品注册名（如 {@code magic_compass}）。 */
    public String itemId() {
        return itemId;
    }

    /** 配方解锁所需殖民地等级。 */
    public int minColonyLevel() {
        return minColonyLevel;
    }

    /** 物品染色（层 0），用于区分三档占位贴图。0xFFFFFF 即不染色。 */
    public int themeColor() {
        return themeColor;
    }

    /** 是否在 tooltip 显示市政厅坐标。 */
    public boolean showsCoords() {
        return showsCoords;
    }

    /** 是否右键传送到市政厅。 */
    public boolean canTeleport() {
        return canTeleport;
    }
}
