package com.wsteam.wandscape.api;

import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * 法师（殖民地 NPC）饰品（Curios）槽位与装备的公开契约。
 *
 * <p>槽类型 = Curios 槽标识字符串（如 {@code ring}/{@code necklace}，实体级默认槽位集由数据包
 * {@code data/curios/curios/entities/wandscape_npc.json} 声明）。本接口方法全部以 {@code UUID}
 * 定位法师，等价于现用 {@code /wandscape curios list/set/add/remove} 命令的能力。
 *
 * <p>实现方在 {@code compat/curios}（仅 Curios 已加载时经 {@code WandscapeApis.setCurioApi} 装配）。
 * 未安装 Curios 时 {@code getCurioApi()} 抛 {@link IllegalStateException}、{@code getCurioApiSilently()}
 * 返回 null——addon 应先判 {@code CuriosCompat.isLoaded()} 或取 Silently 安全面。
 *
 * <p>⚠️ 重设计阶段：全部方法为 {@literal @Unimplemented} 桩（调用抛
 * {@link UnsupportedOperationException}），实现待接入 {@code CuriosCompatImpl}
 * （{@code ICuriosItemHandler} 的 {@code getCurios()}/{@code growSlotType}/{@code shrinkSlotType}、
 * {@code IDynamicStackHandler}），落地后补真。
 */
public interface CurioApi {

    // ── 未实现查询（重设计阶段声明，见 @Unimplemented）──

    /**
     * 法师全部已装备饰品：槽类型 → 该类型各槽的物品列表（空槽为 {@link ItemStack#EMPTY}）。
     *
     * @return 空 map 表示 NPC 不存在或 Curios 未就绪
     */
    @Unimplemented("重设计阶段——待接入 CuriosApi.getCuriosInventory → getCurios() 遍历")
    default Map<String, List<ItemStack>> getCurioContents(UUID npcId) {
        throw new UnsupportedOperationException("CurioApi.getCurioContents not yet implemented");
    }

    /** 法师各槽类型的槽位数量：槽类型 → 槽数。 */
    @Unimplemented("重设计阶段——待接入 handler.getCurios() → 各 ICurioStacksHandler.getSlots()")
    default Map<String, Integer> getSlotCounts(UUID npcId) {
        throw new UnsupportedOperationException("CurioApi.getSlotCounts not yet implemented");
    }

    /** 指定槽类型的槽位数量（未知类型返回 0）。 */
    @Unimplemented("重设计阶段——待接入 handler.getStacksHandler(slot).getSlots()")
    default int getSlotCount(UUID npcId, String slotType) {
        throw new UnsupportedOperationException("CurioApi.getSlotCount not yet implemented");
    }

    /** 法师是否佩戴了指定物品（等价 CuriosCompat.isEquipped）。 */
    @Unimplemented("重设计阶段——待接入 CuriosCompat.isEquipped")
    default boolean isEquipped(UUID npcId, net.minecraft.world.item.Item item) {
        throw new UnsupportedOperationException("CurioApi.isEquipped(Item) not yet implemented");
    }

    /** 法师是否佩戴了满足条件的饰品（等价 CuriosCompat.isEquipped）。 */
    @Unimplemented("重设计阶段——待接入 CuriosCompat.isEquipped(Predicate)")
    default boolean isEquipped(UUID npcId, Predicate<ItemStack> filter) {
        throw new UnsupportedOperationException("CurioApi.isEquipped(Predicate) not yet implemented");
    }

    // ── 未实现增删槽（重设计阶段声明，见 @Unimplemented）──

    /** 为法师的某槽类型增加 {@code amount} 个槽位（持久化在实体，实例级）。 */
    @Unimplemented("重设计阶段——待接入 handler.growSlotType(slotType, amount)")
    default boolean addSlots(UUID npcId, String slotType, int amount) {
        throw new UnsupportedOperationException("CurioApi.addSlots not yet implemented");
    }

    /** 为法师的某槽类型减少 {@code amount} 个槽位（溢出物品由 Curios 处理；缩到 0 为下限）。 */
    @Unimplemented("重设计阶段——待接入 handler.shrinkSlotType(slotType, amount)")
    default boolean removeSlots(UUID npcId, String slotType, int amount) {
        throw new UnsupportedOperationException("CurioApi.removeSlots not yet implemented");
    }

    /** 直接把法师某槽类型设为 {@code amount} 个槽位（>当前 growSlotType、<当前 shrinkSlotType，等价 {@code set} 命令）。 */
    @Unimplemented("重设计阶段——待接入 grow/shinkSlotType 差量")
    default boolean setSlots(UUID npcId, String slotType, int amount) {
        throw new UnsupportedOperationException("CurioApi.setSlots not yet implemented");
    }

    // ── 未实现装备/卸下（重设计阶段声明，见 @Unimplemented）──

    /** 把物品装入法师某槽类型的第一个空槽（经 Curios 校验：谓词/canEquip/事件）。 */
    @Unimplemented("重设计阶段——待接入 IDynamicStackHandler.setStackInSlot")
    default boolean equipCurio(UUID npcId, ItemStack stack, String slotType) {
        throw new UnsupportedOperationException("CurioApi.equipCurio not yet implemented");
    }

    /** 从法师某槽类型的指定槽卸下饰品并返回该物品；槽空/越界/NPC 不存在返回 null。 */
    @Unimplemented("重设计阶段——待接入 IDynamicStackHandler.extractItem")
    default @Nullable ItemStack unequipCurio(UUID npcId, String slotType, int index) {
        throw new UnsupportedOperationException("CurioApi.unequipCurio not yet implemented");
    }
}