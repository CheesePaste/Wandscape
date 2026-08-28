package com.wsteam.wandscape.compat.curios;

import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.shared.ui.vanilla.VanillaPlayerInventory;

import net.minecraft.core.NonNullList;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import net.neoforged.neoforge.items.ItemStackHandler;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.type.ISlotType;
import top.theillusivec4.curios.api.type.capability.ICuriosItemHandler;
import top.theillusivec4.curios.api.type.inventory.ICurioStacksHandler;
import top.theillusivec4.curios.api.type.inventory.IDynamicStackHandler;

/**
 * 法师饰品容器菜单：法师全部饰品槽（按槽类型自然序排列，8 列网格）+ 原版玩家背包槽。槽位是真实
 * vanilla 槽——拾取/放置/Shift/拖拽全部生效，服务端写 {@link IDynamicStackHandler} 走 Curios 完整结算
 * （槽校验、装备/卸下事件、属性、同步、NBT 持久化）。
 *
 * <p>客户端工厂（{@code MenuType}）无实体上下文：槽位布局从 Curios 同步到客户端的实体槽位表
 * （{@code CuriosApi.getEntitySlots(wandscape:wandscape_npc)}，含服务端运行时镜像）构建——槽类型次序与
 * 数量和服务端一致，槽内容经容器广播填充。特殊情况下客户端缺失法师条目时回退到玩家槽位集
 * （与服务端镜像语义一致），保证与服务端槽位对齐。
 *
 * <p>服务端工厂持有法师实体与 {@link ICuriosItemHandler}，槽位绑法师真实饰品栈。
 */
public class NpcCuriosMenu extends AbstractContainerMenu {

    public static final int PANEL_W = 176;
    public static final int SLOT = 18;
    public static final int COLUMNS = 8;
    public static final int CURIO_X = 8;
    public static final int CURIO_Y = 40;
    /** 饰品区与玩家背包之间的间距。 */
    public static final int PLAYER_GAP = 12;

    @Nullable
    private final WandscapeNpc npc;
    private final int curioSlotCount;

    /** Client-side factory (MenuType): contents arrive via sync. */
    public NpcCuriosMenu(int containerId, Inventory playerInventory) {
        this(containerId, playerInventory, null, null);
    }

    /** Server-side factory (MenuProvider). */
    public NpcCuriosMenu(int containerId, Inventory playerInventory,
                         @Nullable ICuriosItemHandler handler, @Nullable WandscapeNpc npc) {
        super(CuriosCompat.NPC_CURIOS_MENU.get(), containerId);
        this.npc = npc;
        int count = 0;
        if (handler != null) {
            LivingEntity wearer = handler.getWearer();
            // getCurios() 为插入序（槽类型按自然序排序后的顺序）——与服务端构建一致
            for (ICurioStacksHandler stacks : handler.getCurios().values()) {
                IDynamicStackHandler stackHandler = stacks.getStacks();
                NonNullList<Boolean> renders = stacks.getRenders();
                for (int i = 0; i < stackHandler.getSlots(); i++) {
                    addSlot(new NpcCurioSlot(stacks.getIdentifier(), wearer, stackHandler, i,
                            CURIO_X + (count % COLUMNS) * SLOT, CURIO_Y + (count / COLUMNS) * SLOT,
                            renders));
                    count++;
                }
            }
        } else {
            // 客户端：从同步的实体槽位表构建（默认含服务端镜像的玩家槽位集；缺法师条目时回退玩家集）
            Map<String, ISlotType> slotMap = CuriosApi.getEntitySlots(
                    Wandscape.WANDSCAPE_NPC.get(), playerInventory.player.level());
            if (slotMap.isEmpty()) {
                slotMap = CuriosApi.getEntitySlots(
                        EntityType.PLAYER, playerInventory.player.level());
            }
            LivingEntity localPlayer = playerInventory.player;
            List<ISlotType> ordered = slotMap.values().stream().sorted().toList();
            for (ISlotType slotType : ordered) {
                int size = slotType.getSize();
                IItemHandlerModifiable clientHandler = new ItemStackHandler(size);
                NonNullList<Boolean> renders = NonNullList.withSize(Math.max(size, 0), true);
                for (int i = 0; i < size; i++) {
                    addSlot(new NpcCurioSlot(slotType.getIdentifier(), localPlayer, clientHandler, i,
                            CURIO_X + (count % COLUMNS) * SLOT, CURIO_Y + (count / COLUMNS) * SLOT,
                            renders));
                    count++;
                }
            }
        }
        this.curioSlotCount = count;

        VanillaPlayerInventory.addTo(this::addSlot, playerInventory,
                playerInvTop(curioSlotCount), hotbarTop(curioSlotCount));
    }

    public int getCurioSlotCount() {
        return curioSlotCount;
    }

    public int getEntityId() {
        return npc != null ? npc.getId() : -1;
    }

    // ── 布局辅助（菜单与服务端屏幕共用） ──

    public static int curioRows(int slotCount) {
        return (slotCount + COLUMNS - 1) / COLUMNS;
    }

    public static int playerInvTop(int slotCount) {
        return CURIO_Y + curioRows(slotCount) * SLOT + PLAYER_GAP;
    }

    public static int hotbarTop(int slotCount) {
        return playerInvTop(slotCount) + 3 * SLOT + 6;
    }

    public static int panelHeight(int slotCount) {
        return hotbarTop(slotCount) + SLOT + 9;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = this.slots.get(index);
        if (slot == null || !slot.hasItem()) return ItemStack.EMPTY;
        ItemStack result = slot.getItem().copy();
        if (index >= curioSlotCount) {
            // 玩家背包 → 饰品槽（NpcCurioSlot.mayPlace 走 Curios 校验）
            if (!moveItemStackTo(slot.getItem(), 0, curioSlotCount, false)) {
                return ItemStack.EMPTY;
            }
        } else {
            // 饰品槽 → 玩家背包
            if (!moveItemStackTo(slot.getItem(), curioSlotCount, this.slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        }
        return result;
    }

    @Override
    public boolean stillValid(Player player) {
        if (npc == null) return true;
        // 与 NpcMenu.stillValid 一致：只看法师存活，不要求玩家靠近（法师小屋远程管理站语义）
        return !npc.isRemoved() && npc.isAlive();
    }
}