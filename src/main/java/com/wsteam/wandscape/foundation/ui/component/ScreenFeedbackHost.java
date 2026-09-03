package com.wsteam.wandscape.foundation.ui.component;

import net.minecraft.network.chat.Component;

/**
 * 能就地绘制瞬态反馈 toast 的自定义屏幕。
 *
 * <p>{@link MedievalScreen} 与 {@link com.wsteam.wandscape.content.warehouse.client.WarehouseScreen}
 * 都实现本接口，{@code ScreenFeedbackPacket} 的客户端路由据此把消息画到 UI 层而不是世界
 * （否则容器类屏幕会挡住 action bar，玩家看不到「仓库容量不足」这类错误反馈）。
 *
 * <p>实现方负责在自身渲染里调用自己的 toast 绘制；本接口只提供统一入料点。
 */
public interface ScreenFeedbackHost {

    /** 在屏幕 UI 层显示一条瞬态反馈（色值建议用 0xAARRGGBB，见 {@link com.wsteam.wandscape.foundation.ui.theme.MedievalColors}）。 */
    void showFeedback(Component message, int color);
}
