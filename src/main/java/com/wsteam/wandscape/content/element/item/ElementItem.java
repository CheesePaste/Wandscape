package com.wsteam.wandscape.content.element.item;

import com.wsteam.wandscape.engine.service.SoundService;
import com.wsteam.wandscape.engine.sound.WandscapeSounds;
import com.wsteam.wandscape.api.ColonyApi;
import com.wsteam.wandscape.api.WarehouseApi;
import com.wsteam.wandscape.content.element.data.ElementType;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.api.WandscapeApis;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.UUID;

/**
 * 元素代币：一种元素的物品形态（供 JEI/配方展示）。
 *
 * <p>进入玩家背包后立即转化为所在小镇仓库的对应元素，物品本身消失。
 * 不在任何小镇范围内时保留物品，等玩家进入小镇后再转化。
 */
public class ElementItem extends Item {

    private static final String TAG = "ElementItem";

    private final ElementType elementType;

    public ElementItem(Properties properties, ElementType elementType) {
        super(properties);
        this.elementType = elementType;
    }

    public ElementType elementType() {
        return elementType;
    }

    @Override
    public String getDescriptionId() {
        return "element.wandscape." + elementType.getId();
    }

    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slotId, boolean isSelected) {
        if (level.isClientSide) return;
        if (!(entity instanceof ServerPlayer player)) return;
        convertToWarehouse(stack, player);
    }

    private void convertToWarehouse(ItemStack stack, ServerPlayer player) {
        ColonyApi colonyApi = WandscapeApis.getColonyApiSilently();
        WarehouseApi warehouseApi = WandscapeApis.getWarehouseApiSilently();
        if (colonyApi == null || warehouseApi == null) return;
        UUID colonyId = colonyApi.getColonyId(player.blockPosition());
        if (colonyId == null) return; // 不在小镇范围，保留物品等待进入小镇
        int count = stack.getCount();
        if (count <= 0) return;
        warehouseApi.addElement(colonyId, elementType, count);
        stack.shrink(count);
        SoundService.playAt(player.serverLevel(), player.blockPosition(),
                WandscapeSounds.WAREHOUSE, SoundSource.PLAYERS, 0.6f, 1.0f);
        Log.info(TAG, "{} x{} -> warehouse {}", elementType.getId(), count,
                colonyId.toString().substring(0, 8));
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context,
                                List<Component> tooltipComponents, TooltipFlag tooltipFlag) {
        tooltipComponents.add(Component.translatable("item.wandscape.element.tooltip"));
    }
}
