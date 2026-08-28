package com.wsteam.wandscape.compat.curios.client;

import net.minecraft.client.gui.components.ImageButton;
import top.theillusivec4.curios.client.gui.CuriosButton;

/**
 * 法师饰品按钮：复用 Curios 自己的按钮纹样（{@code curios:button}/{@code curios:button_highlighted}，
 * 与玩家背包 3D 缩略图左上角的饰品图标按钮同款同源），点击打开法师饰品栏。
 *
 * <p>不加入 NpcScreen 的 renderable widget 列表（其渲染早于模型底，会被盖住）；由 NpcScreen 在
 * 模型之后手动渲染，并手动处理点击与 tooltip。
 */
public class NpcCuriosButton extends ImageButton {

    public NpcCuriosButton(int x, int y, Runnable onClick) {
        super(x, y, 10, 10, CuriosButton.BIG, btn -> onClick.run());
    }
}