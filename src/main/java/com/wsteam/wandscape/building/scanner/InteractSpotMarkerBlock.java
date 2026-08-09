package com.wsteam.wandscape.building.scanner;

import com.wsteam.wandscape.shared.data.Activity;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;

/**
 * interact_spot_marker 方块：标记一个游客交互位（spot）。
 *
 * <p>交互位唯一真源 = world 里的 marker（用户拍板）：放置=标记一个交互位，
 * 右键循环动作（Activity.SPOT_ACTIONS，含 WITHDRAW），潜行右键=移除。
 * action 存 blockstate 属性（无需 BlockEntity/NBT，随方块自动持久化）。
 *
 * <p>占格语义（用户拍板：创作者自行留空）：marker 是实体方块占据 spot 格，
 * 导出时跳过不进 pattern——创作者须把该格当作游客站位留空，不要压在必需的结构方块上。
 */
public class InteractSpotMarkerBlock extends Block {

    public static final EnumProperty<Activity> ACTION = EnumProperty.create("action", Activity.class);

    public InteractSpotMarkerBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(ACTION, Activity.BROWSE));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(ACTION);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hitResult) {
        if (!level.isClientSide) {
            if (player.isShiftKeyDown()) {
                // 潜行右键 = 移除该 marker
                level.destroyBlock(pos, true);
            } else {
                // 右键循环动作
                Activity next = nextAction(state.getValue(ACTION));
                level.setBlock(pos, state.setValue(ACTION, next), 3);
                player.displayClientMessage(
                        Component.translatable("marker.wandscape.spot_action", actionLabel(next)), true);
            }
        }
        return InteractionResult.SUCCESS;
    }

    private static Activity nextAction(Activity cur) {
        Activity[] arr = Activity.SPOT_ACTIONS;
        for (int i = 0; i < arr.length; i++) {
            if (arr[i] == cur) return arr[(i + 1) % arr.length];
        }
        return arr[0];
    }

    private static String actionLabel(Activity a) {
        return Component.translatable("activity.wandscape." + a.name().toLowerCase()).getString();
    }

    /** 供导出读取：只认 7 个交互位动作，非法值回退 BROWSE。 */
    public static Activity spotActionOrBrowse(BlockState state) {
        Activity a = state.getValue(ACTION);
        for (Activity s : Activity.SPOT_ACTIONS) {
            if (s == a) return a;
        }
        return Activity.BROWSE;
    }
}
