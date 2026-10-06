package com.wsteam.wandscape.content.magic.worldresponse;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 「移山填海」用的**液面替身**：外表与原版液体一模一样，但有完整支撑。
 *
 * <p>为什么需要它：MC 里没有「只对某个实体生效的碰撞」（{@code canStandOnFluid} 是实体方法，
 * 原版玩家换不掉），所以「让水对你变成实心」只能真放一个方块。而放普通方块（比如屏障）会露出马脚——
 * 那一格水会凭空消失。于是这里放一个**渲染成水的实心方块**：
 * <ul>
 *   <li>模型用 {@code minecraft:block/water_still} 贴图 + {@code render_type: translucent}，
 *       并用生物群系水色染色（见 {@code WandscapeClient#onBlockColors}，与液体渲染器同一套取色）；</li>
 *   <li>高度照抄原版液面的 <b>8/9 格</b>（{@code FlowingFluid.getHeight}：上方没有同种液体时取
 *       {@code getOwnHeight()}）——模型与碰撞都是这个高度，所以替身与紧邻的真液面**齐平**，
 *       不会在该是水面的地方露出一条台阶边；</li>
 *   <li>不可破坏、不掉落、不遮光不窒息、没有脚步声、活塞推不动（属性在注册处统一给）。</li>
 * </ul>
 *
 * <p>它只是**临时**存在：由 {@link TerraformEffect} 在人踩上液面前放下，走开/停止时换回真液体。
 * 内部机制方块，不注册物品、不进创造栏。
 */
public class SolidFluidBlock extends Block {

    /** 原版液面高度：8/9 格。模型里的 {@code to[1]} 必须与它一致，否则模型与碰撞会错位。 */
    private static final VoxelShape SHAPE = Block.box(0.0, 0.0, 0.0, 16.0, 14.2222, 16.0);

    private final Fluid fluid;

    public SolidFluidBlock(Fluid fluid, BlockBehaviour.Properties properties) {
        super(properties);
        this.fluid = fluid;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    /**
     * 替身与替身之间、替身与原版同种液体之间不画内侧面。
     *
     * <p>替身是半透明的：两层共面的半透明面互相重绘会显出一条深色的缝（原版 {@code LiquidBlock}
     * 对同种液体就是这么处理的）。相邻是空气或石头时照常画，所以水体该有的"水墙"还在。
     */
    @Override
    protected boolean skipRendering(BlockState state, BlockState adjacent, Direction side) {
        return adjacent.is(this) || adjacent.getFluidState().getType().isSame(this.fluid);
    }
}
