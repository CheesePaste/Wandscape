package com.wsteam.wandscape.scepter;

/**
 * 玩家权杖种类：决定右键行为与 3D 头部主题色（染色走 ItemColors tintindex 0）。
 */
public enum ScepterKind {

    /** 和平权杖：右键法师切换和平/取消和平。 */
    PEACE("peace_wand", 0xFFF2F2F2),
    /** 跟随权杖：右键法师切换跟随/非跟随。 */
    FOLLOW("follow_wand", 0xFF35C9B8),
    /** 庇护权杖：右键生物标记为盟友（法师不主动攻击/不误伤），再次右键解除。 */
    SHELTER("shelter_wand", 0xFF5FB84F),
    /** 敌对权杖：右键生物让本殖民地 128 格内法师强制仇恨并集火，再次右键解除/转移。 */
    HOSTILE("hostile_wand", 0xFFD04040);

    private final String itemId;
    private final int themeColor;

    ScepterKind(String itemId, int themeColor) {
        this.itemId = itemId;
        this.themeColor = themeColor;
    }

    /** 注册物品 id（如 {@code peace_wand}）。 */
    public String itemId() {
        return itemId;
    }

    /** 头部主题色（ARGB，命名空间 {@code 0xFF}，与法杖 wand_color 同渲染语义）。 */
    public int themeColor() {
        return themeColor;
    }
}