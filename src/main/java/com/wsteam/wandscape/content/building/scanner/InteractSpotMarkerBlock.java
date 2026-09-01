package com.wsteam.wandscape.content.building.scanner;

import com.wsteam.wandscape.content.tourist.internal.MarkerPreviewManager;
import com.wsteam.wandscape.content.tourist.data.Activity;
import com.wsteam.wandscape.foundation.ui.I18n;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * interact_spot_marker 方块：标记一个游客交互位（spot）。
 *
 * <p>交互位唯一真源 = world 里的 marker（用户拍板）：放置=标记一个交互位，
 * 右键循环动作、潜行右键循环朝向、移除=敲掉方块。
 * action/facing 都存 blockstate 属性（无需 BlockEntity/NBT，随方块自动持久化）。
 *
 * <p>朝向（facing）= 游客在该位做动作时面朝的方向：放置时取玩家面朝方向，潜行右键循环 N→E→S→W。
 *
 * <p>marker 无碰撞（预览假人可站在同一格做动作）；视觉为贴地薄板，占位语义不变——
 * 创作者仍须把该格当作游客站位留空（导出时 marker 跳过不进 pattern），不要压在必需的结构方块上。
 */
public class InteractSpotMarkerBlock extends Block {

    public static final EnumProperty<Activity> ACTION = EnumProperty.create("action", Activity.class);
    public static final DirectionProperty FACING = DirectionProperty.create("facing", Direction.Plane.HORIZONTAL);

    public InteractSpotMarkerBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState()
                .setValue(ACTION, Activity.BROWSE)
                .setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(ACTION, FACING);
    }

    /** 无碰撞：实体（含预览假人）可站进同一格；选型仍用默认整格（可敲掉移除）。 */
    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return Shapes.empty();
    }

    @Override
    @javax.annotation.Nullable
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        // 放置时 facing = 玩家面朝方向（游客动作将面向玩家看向的方向）；action 默认 BROWSE。
        Player player = ctx.getPlayer();
        Direction facing = player != null ? player.getDirection() : Direction.NORTH;
        return defaultBlockState().setValue(FACING, facing);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hitResult) {
        if (!level.isClientSide) {
            BlockState next;
            String msgKey;
            Object[] msgArgs;
            if (player.isShiftKeyDown()) {
                // 潜行右键循环朝向
                Direction facing = state.getValue(FACING).getClockWise();
                next = state.setValue(FACING, facing);
                msgKey = "marker.wandscape.spot_facing";
                msgArgs = new Object[]{facingLabel(facing)};
            } else {
                // 右键循环动作
                Activity action = nextAction(state.getValue(ACTION));
                next = state.setValue(ACTION, action);
                msgKey = "marker.wandscape.spot_action";
                msgArgs = new Object[]{actionLabel(action)};
            }
            level.setBlock(pos, next, 3);
            player.displayClientMessage(Component.translatable(msgKey, msgArgs), true);
            // 通知预览假人即时更新（服务端）
            var preview = MarkerPreviewManager.getActive();
            if (preview != null) {
                preview.onMarkerChanged((net.minecraft.server.level.ServerLevel) level, pos, next);
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

    private static Component actionLabel(Activity a) {
        return Component.translatable("activity.wandscape." + a.name().toLowerCase());
    }

    private static Component facingLabel(Direction d) {
        String fallback = switch (d) {
            case NORTH -> "北";
            case EAST -> "东";
            case SOUTH -> "南";
            default -> "西";
        };
        return I18n.name("direction.wandscape." + d.getName(), fallback);
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
