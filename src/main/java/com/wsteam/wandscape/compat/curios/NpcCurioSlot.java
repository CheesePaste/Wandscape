package com.wsteam.wandscape.compat.curios;

import javax.annotation.Nonnull;

import net.minecraft.core.NonNullList;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import net.neoforged.neoforge.items.SlotItemHandler;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.SlotContext;
import top.theillusivec4.curios.api.type.inventory.IDynamicStackHandler;

/**
 * 法师饰品槽。绑定某个饰品类型的 {@link IItemHandlerModifiable} 栈（服务端为法师实体真实的
 * {@link IDynamicStackHandler}，客户端为接收广播的普通 handler）。
 *
 * <p>语义对齐 Curios 自己的 {@code CurioSlot}：空槽显示槽类型图标；可放置性由 handler 的
 * {@code isItemValid} 裁决（服务端走完整 Curios 校验：谓词 + {@code canEquip} + {@code CurioCanEquipEvent}）；
 * 放置变化触发 {@code onEquipFromUse}（装备音效）。槽上下文以法师为穿戴者。
 */
public class NpcCurioSlot extends SlotItemHandler {

    private final SlotContext slotContext;

    public NpcCurioSlot(String identifier, LivingEntity owner, IItemHandlerModifiable handler,
                        int index, int x, int y, NonNullList<Boolean> renders) {
        super(handler, index, x, y);
        this.slotContext = new SlotContext(identifier, owner, index, false,
                renders.size() > index && renders.get(index));
        CuriosApi.getSlot(identifier, owner.level())
                .ifPresent(slotType -> this.setBackground(InventoryMenu.BLOCK_ATLAS, slotType.getIcon()));
    }

    @Override
    public int getMaxStackSize() {
        return 1;
    }

    @Override
    public void set(@Nonnull ItemStack stack) {
        ItemStack current = this.getItem();
        boolean emptyToEmpty = current.isEmpty() && stack.isEmpty();
        super.set(stack);
        if (!emptyToEmpty && !ItemStack.matches(current, stack) && !stack.isEmpty()) {
            CuriosApi.getCurio(stack).ifPresent(curio -> curio.onEquipFromUse(this.slotContext));
        }
    }
}