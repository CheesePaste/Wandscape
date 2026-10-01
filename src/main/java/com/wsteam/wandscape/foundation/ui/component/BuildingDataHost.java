package com.wsteam.wandscape.foundation.ui.component;

import com.wsteam.wandscape.content.building.projection.network.BuildingDebugResponsePacket;

/**
 * 能接收服务端建筑调试数据的屏。
 *
 * <p>{@code BuildingDebugResponsePacket} 的客户端分发口按本接口投递，这样
 * {@link MedievalScreen}（{@code Screen} 支）与 {@link MedievalContainerScreen}
 * （{@link net.minecraft.client.gui.screens.inventory.AbstractContainerScreen} 支）
 * 两种血统的建筑屏都能拿到状态徽标与舒适/魔力/奇迹读数，而不必在分发口堆 instanceof。
 */
public interface BuildingDataHost {

    /** 服务端建筑数据回填；实现方按自己的 buildingId/buildingPos 判归属后采用。 */
    void setBuildingData(BuildingDebugResponsePacket data);
}
