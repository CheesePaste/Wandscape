package com.wsteam.wandscape.content.warehouse;

import com.wsteam.wandscape.shared.log.Log;
import com.wsteam.wandscape.shared.registry.WandscapeApis;
import com.wsteam.wandscape.content.warehouse.network.WarehouseDataPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * 仓库终端：右键打开玩家自己殖民地的仓库面板（元素 + 物品存取，概览/兑换两页）。
 *
 * <p>便携式——不绑定某栋建筑：{@code buildingPos} 取玩家当前坐标（菜单 64 格内有效，
 * 与仓库在 colonyId 层的存/取逻辑无关）。无殖民地玩家禁用并提示。
 *
 * <p><b>本期不做：</b>Curios「手饰」槽位穿戴 + 穿戴时按键开面板（见 {@code docs/gaps.md}）。
 */
public class WarehouseTerminalItem extends Item {

    private static final String TAG = "WarehouseTerminalItem";

    public WarehouseTerminalItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (!level.isClientSide && player instanceof ServerPlayer sp) {
            openWarehouse(sp);
            return InteractionResultHolder.consume(player.getItemInHand(hand));
        }
        return InteractionResultHolder.success(player.getItemInHand(hand));
    }

    /** 打开玩家自己殖民地的仓库菜单；无殖民地给出提示并返回 false。 */
    public static boolean openWarehouse(ServerPlayer player) {
        UUID colonyId = ownColony(player);
        if (colonyId == null) {
            player.displayClientMessage(
                    Component.translatable("message.wandscape.warehouse_terminal.no_colony"), true);
            return false;
        }
        BlockPos pos = player.blockPosition();
        player.openMenu(new SimpleMenuProvider(
                (id, inv, p) -> new WarehouseMenu(id, inv, colonyId, pos),
                Component.translatable("gui.wandscape.warehouse.title")));
        pushInitialData(player, colonyId, pos);
        return true;
    }

    /** 检查玩家是否穿戴或持有了仓库终端（支持 Curios 饰品槽与背包）。 */
    public static boolean isTerminalEquipped(Player player) {
        if (player == null) return false;
        Item terminal = com.wsteam.wandscape.Wandscape.WAREHOUSE_TERMINAL.get();
        if (com.wsteam.wandscape.compat.curios.CuriosCompat.isLoaded()
                && com.wsteam.wandscape.compat.curios.CuriosCompat.isEquipped(player, terminal)) {
            return true;
        }
        if (player.getMainHandItem().is(terminal) || player.getOffhandItem().is(terminal)) {
            return true;
        }
        for (ItemStack armor : player.getArmorSlots()) {
            if (armor.is(terminal)) {
                return true;
            }
        }
        return player.getInventory().contains(new ItemStack(terminal));
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context,
                                java.util.List<Component> tooltipComponents, net.minecraft.world.item.TooltipFlag tooltipFlag) {
        tooltipComponents.add(Component.translatable("item.wandscape.warehouse_terminal.tooltip"));
        tooltipComponents.add(Component.translatable("item.wandscape.warehouse_terminal.tooltip_curios"));
    }

    /** 打开后推一帧仓库数据（物品 + 元素快照）供客户端渲染；与 {@code BuildingInteractHandler.openWarehouseMenu} 对齐。 */
    private static void pushInitialData(ServerPlayer player, UUID colonyId, BlockPos pos) {
        ColonyItemBank bank = ColonyItemBank.get(player.serverLevel());
        if (bank == null) return;
        PacketDistributor.sendToPlayer(player,
                WarehouseDataPacket.from(pos, colonyId,
                        bank.getSnapshot(colonyId), bank.getElementSnapshot(colonyId)));
    }

    /** 玩家创建殖民地的 UUID；无殖民地（含 API 未就绪）返回 null。 */
    @Nullable
    private static UUID ownColony(ServerPlayer player) {
        try {
            var api = WandscapeApis.getColonyApiSilently();
            return api != null ? api.getColonyByFounder(player.getUUID()) : null;
        } catch (RuntimeException e) {
            Log.warn(TAG, "Failed to resolve own colony for {}: {}", player.getUUID(), e.toString());
            return null;
        }
    }
}
