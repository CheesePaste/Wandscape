package com.wsteam.wandscape.compass;

import com.wsteam.wandscape.compass.client.CompassTargetClientCache;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * 魔法指南针（玩家侧物品）：指针经 {@code angle} item property 始终指向自己殖民地的市政厅。
 *
 * <p>三档差异见 {@link CompassTier}：高级/终极在 tooltip 显示市政厅坐标，终极右键传送到市政厅。
 * 本物品不持有任何数据——市政厅坐标由服务端权威({@link CompassService})解析并经
 * {@code CompassTargetPacket} 同步到客户端缓存。指针朝向为 vanilla 指南针帧模型 + 32 帧染色圆盘。
 */
public class MagicCompassItem extends Item {

    private final CompassTier tier;

    public MagicCompassItem(Properties properties, CompassTier tier) {
        super(properties);
        this.tier = tier;
    }

    /** 本指南针档位。 */
    public CompassTier tier() {
        return tier;
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context,
                                List<Component> tooltipComponents, TooltipFlag tooltipFlag) {
        tooltipComponents.add(Component.translatable("item.wandscape.compass.tooltip.points_to_town_hall"));
        if (tier.showsCoords()) {
            GlobalPos target = CompassTargetClientCache.get();
            if (target != null) {
                BlockPos pos = target.pos();
                tooltipComponents.add(Component.translatable("item.wandscape.compass.tooltip.coords",
                        pos.getX(), pos.getY(), pos.getZ()));
            }
        }
        if (tier.canTeleport()) {
            tooltipComponents.add(Component.translatable("item.wandscape.compass.tooltip.right_click_tp"));
        }
    }

    // ── 终极：右键空气/方块都传送到市政厅；非终极：右键触发一次目标重同步（刚建好市政厅后即时校准）──

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (!level.isClientSide && player instanceof ServerPlayer sp) {
            if (tier.canTeleport()) {
                CompassService.teleportToTownHall(sp);
            } else {
                CompassService.syncFor(sp);
            }
            return InteractionResultHolder.consume(player.getItemInHand(hand));
        }
        return InteractionResultHolder.success(player.getItemInHand(hand));
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        if (!level.isClientSide && context.getPlayer() instanceof ServerPlayer sp && tier.canTeleport()) {
            CompassService.teleportToTownHall(sp);
            return InteractionResult.CONSUME;
        }
        return super.useOn(context);
    }

    /** 服务端节流（每 100 tick）重同步市政厅，覆盖中途新建/重建市政厅的情形。 */
    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int itemSlot, boolean isSelected) {
        if (entity instanceof ServerPlayer player && level.getGameTime() % 100 == 0) {
            CompassService.syncFor(player);
        }
    }
}
