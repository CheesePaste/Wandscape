package com.wsteam.wandscape.content.items.magic.wand.item;

import java.util.Locale;

/**
 * 玩家手持法杖时的右键模式：决定右键落点执行哪条指挥命令。
 *
 * <p>模式由 {@link WandItem} 存在物品自定义数据里（键 {@link WandItem#MODE_KEY}），shift+右键
 * 循环切换。四种模式按落点分两族——{@link #SHELTER}/{@link #HOSTILE} 打生物（含本镇法师），
 * {@link #GATHER}/{@link #IDENTIFY} 打方块；这条分界线是交互分流的唯一依据，客户端与服务端
 * 都读它，两端因此不会各接一半（见 {@link WandItem#useOn}）。
 *
 * <p>模式各带一个主题色（{@link #themeColor()}）：玩家切过模式之后，法杖头上的宝石就染成该色，
 * 不点开物品提示也能一眼看出当前是哪一种（见 {@link WandItem#headColorArgb}）。
 *
 * <p>权杖时代的和平/跟随是「按法师开关」，没有并入法杖，本枚举也不含它们。
 */
public enum WandMode {

    /** 庇护：右键生物加入本殖民地盟友名单（再右键解除）。 */
    SHELTER(0xFF5FB84F),
    /** 敌对：右键生物设为本殖民地强制仇恨目标（再右键解除，换目标即转移）。 */
    HOSTILE(0xFFD04040),
    /** 集合：右键方块，附近本镇法师走过去（一次性，走到即止）。 */
    GATHER(0xFF4A90D9),
    /** 鉴定：右键方块，解锁该方块对应的合成配方。 */
    IDENTIFY(0xFFE0B341);

    private final int themeColor;

    WandMode(int themeColor) {
        this.themeColor = themeColor;
    }

    /**
     * 法杖头（模型里的 gem）主题色，ARGB、命名空间 {@code 0xFF}。
     *
     * <p>庇护/敌对取的是原庇护、敌对权杖的同一组颜色，含义跟着物品搬过来不另起一套；
     * 集合/鉴定是法杖新增的，各配一个可直接区分色相（蓝/琥珀）。四种色相绿/红/蓝/黄互不混淆，
     * 缩到 16×16 的物品图标上也分得开。
     */
    public int themeColor() {
        return themeColor;
    }

    /**
     * 无记录/非法值时的出厂模式。
     *
     * <p>取 {@link #IDENTIFY} 是因为它对原版交互的侵占最小：对生物一律放行（喂牛/驯狼/交易照常），
     * 对方块也只接管「原版本来就不响应右键」的那些（箱子/熔炉/门仍开自己的界面），
     * 唯一后果是解锁一条配方。换成庇护做默认会让玩家喂牛时莫名其妙多出一个盟友。
     */
    public static final WandMode DEFAULT = IDENTIFY;

    /** 本模式是否以生物为落点（决定是否接管生物右键）。 */
    public boolean affectsCreatures() {
        return this == SHELTER || this == HOSTILE;
    }

    /** 本模式是否以方块为落点（决定是否接管方块右键）。 */
    public boolean affectsBlocks() {
        return this == GATHER || this == IDENTIFY;
    }

    /** 模式名的 lang 键（{@code mode.wandscape.wand.<name>}）。 */
    public String langKey() {
        return "mode.wandscape.wand." + name().toLowerCase(Locale.ROOT);
    }
}
