package com.wsteam.wandscape.compat.curios.client;

import net.minecraft.client.gui.components.ImageButton;
import net.minecraft.client.gui.components.WidgetSprites;
import net.minecraft.resources.ResourceLocation;

/**
 * 法师饰品按钮：复用 Curios 自己的按钮纹样（{@code curios:button}/{@code curios:button_highlighted}，
 * 与玩家背包 3D 缩略图左上角的饰品图标按钮同款同源），点击打开法师饰品栏。
 *
 * <p>不加入 NpcScreen 的 renderable widget 列表（其渲染早于模型底，会被盖住）；由 NpcScreen 在
 * 模型之后手动渲染，并手动处理点击与 tooltip。
 *
 * <p>按钮纹样以硬编码 {@link ResourceLocation} 直接取用而非引用 {@code CuriosButton.BIG}——使本类
 * 不依赖 Curios 客户端类，未安装 Curios 时也不会因类装载抛 {@code NoClassDefFoundError}。
 * 该按钮仅当 {@code CuriosCompat.isLoaded()} 为真时才会被实例化，此时 Curios 纹样必然存在。
 */
public class NpcCuriosButton extends ImageButton {

    private static final WidgetSprites SPRITES = new WidgetSprites(
            ResourceLocation.fromNamespaceAndPath("curios", "button"),
            ResourceLocation.fromNamespaceAndPath("curios", "button_highlighted"));

    public NpcCuriosButton(int x, int y, Runnable onClick) {
        super(x, y, 10, 10, SPRITES, btn -> onClick.run());
    }
}
