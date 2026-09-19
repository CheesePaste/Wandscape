package com.wsteam.wandscape.content.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.CommandNode;
import com.wsteam.wandscape.content.building.data.BlockOffset;
import com.wsteam.wandscape.content.building.data.BuildingConfig;
import com.wsteam.wandscape.content.building.data.WorkItem;
import com.wsteam.wandscape.content.building.internal.BuildingConfigLoader;
import com.wsteam.wandscape.content.building.internal.BuildingState;
import com.wsteam.wandscape.content.building.internal.EnqueueHelper;
import com.wsteam.wandscape.content.task.ecs.World;
import com.wsteam.wandscape.content.task.engine.dsl.CompiledBlueprint;
import com.wsteam.wandscape.content.task.engine.pool.TaskRequest;
import com.wsteam.wandscape.content.task.event.CustomEvent;
import com.wsteam.wandscape.content.task.op.api.AtomicOp;
import com.wsteam.wandscape.foundation.log.Log;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

/**
 * 调试指令 {@code /wandscape test all}：把全部已加载建筑各生成一座，方块瞬间落地、随即判定为已建成。
 *
 * <p>排布：以玩家水平朝向为「前」、右手方向为横排，按各建筑自身边界盒尺寸排成紧密网格——
 * 行内按宽度紧挨着摆、行深取该行最深的建筑，之间留 {@link #GAP} 格空隙；整体以玩家为基准居中。
 * 每座建筑的落点 Y 取该格中心的地表高度，故各建筑各自贴地。
 *
 * <p>「瞬间完工」不是新机制——就是照做一遍蓝图：{@link EnqueueHelper#buildWorkItem} 把建筑
 * 编译成 {@code build:clear_and_build} 的参数，蓝图注册表编出 {@link AtomicOp} 序列，这里把
 * 序列里的落方块 / 生成装饰实体 / 发事件直接同步执行，跳过仓库要料（调试生成不扣材料）。
 * 序列末尾本就带 {@code build_complete} 事件，由 {@code BuildCompleteListener} 接手置为已建成
 * 并走完归属、贡献、客户端同步，因此不需要另外造一套"标记完成"逻辑。
 *
 * <p>落方块按每 tick {@link #BLOCKS_PER_TICK} 块的预算摊开执行：全部建筑共十余万块，
 * 一次塞进单 tick 会造成明显卡顿（且大建筑所在区块可能因首次生成而极慢）。
 */
public final class SpawnAllBuildingsCommand {

    private SpawnAllBuildingsCommand() {}

    private static final String TAG = "TestSpawnAll";

    /** 网格外框：建筑之间留的空隙（格）。 */
    private static final int GAP = 4;
    /** 网格前沿距玩家的距离（格）——避免首排压在脚下。 */
    private static final int FRONT_OFFSET = 12;
    /** 每行建筑数默认值。 */
    private static final int DEFAULT_PER_ROW = 6;
    /** 每 tick 落方块预算：全部建筑约十万余块，按此摊到约一两秒。 */
    private static final int BLOCKS_PER_TICK = 4000;

    public static CommandNode<CommandSourceStack> node() {
        return Commands.literal("all")
                .executes(ctx -> start(ctx, DEFAULT_PER_ROW))
                .then(Commands.argument("perRow", IntegerArgumentType.integer(1, 16))
                        .executes(ctx -> start(ctx, IntegerArgumentType.getInteger(ctx, "perRow"))))
                .build();
    }

    /** 裸 {@code /wandscape test} 的入口——与 {@code /wandscape test all} 等价。 */
    public static int startDefault(CommandContext<CommandSourceStack> ctx) {
        return start(ctx, DEFAULT_PER_ROW);
    }

    // ── 生成任务（跨 tick 摊开，静态状态在服务端 tick 里推进） ──

    /** 待登记的一座建筑：配置 + 目标格左下角世界坐标 + 占地（排布时已算好）。 */
    private record Pending(BuildingConfig config, String typeId, int cellX, int cellZ, Footprint footprint) {}

    private static final Deque<Pending> PENDING = new ArrayDeque<>();
    /** 当前建筑的落方块序列；执行到末尾的 EmitEventOp 即自动判定完工。 */
    private static final Deque<AtomicOp> OPS = new ArrayDeque<>();
    private static boolean running;
    private static int total;
    private static int built;
    private static int skipped;
    @Nullable
    private static UUID colonyId;
    /** 收尾回显用的命令源（记录起的这座是哪个执行者发起的）。 */
    @Nullable
    private static CommandSourceStack source;

    private static int start(CommandContext<CommandSourceStack> ctx, int perRow) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        if (level != src.getServer().overworld()) {
            src.sendFailure(Component.literal("[魔法小镇] 建筑方块只会落在主世界，请在主世界执行"));
            return 0;
        }
        if (running) {
            src.sendFailure(Component.literal("[魔法小镇] 上一批还在生成（剩 "
                    + (PENDING.size() + 1) + " 座），稍后再试"));
            return 0;
        }
        World world = World.getActive();
        if (world == null || world.blockOps == null || world.entityOps == null
                || world.blueprintRegistry == null) {
            src.sendFailure(Component.literal("[魔法小镇] 引擎未就绪，无法生成建筑"));
            return 0;
        }

        List<BuildingConfig> configs = BuildingConfigLoader.getInstance().getAll().values().stream()
                .filter(c -> !c.deprecated())
                .sorted(Comparator.comparing(BuildingConfig::category).thenComparing(BuildingConfig::id))
                .toList();
        if (configs.isEmpty()) {
            src.sendFailure(Component.literal("[魔法小镇] 没有已加载的建筑数据"));
            return 0;
        }

        ServerPlayer player = src.getPlayer();
        Direction forward = player != null ? player.getDirection() : Direction.SOUTH;
        Direction right = forward.getClockWise();
        int totalWidth = plan(configs, perRow, BlockPos.containing(src.getPosition()), forward, right);

        colonyId = CommandUtil.resolveColony(src);
        source = src;
        total = PENDING.size();
        built = 0;
        skipped = 0;
        running = true;

        src.sendSuccess(() -> Component.literal("[魔法小镇] 开始生成 " + total + " 座建筑（每种一座，"
                + perRow + " 座/行，网格宽 " + totalWidth + " 格，朝向 " + forward.getName()
                + "），方块逐 tick 落地"), false);
        if (colonyId == null) {
            src.sendSuccess(() -> Component.literal("[魔法小镇] 当前没有可归属的小镇：建筑仍会生成，"
                    + "但依赖小镇的界面（仓库、游客、评级）不会把它们算进去"), false);
        }
        Log.info(TAG, "[Test] spawn-all started: {} buildings, perRow={}, forward={}, colony={}",
                total, perRow, forward, colonyId == null ? "none" : colonyId.toString().substring(0, 8));
        return Command.SINGLE_SUCCESS;
    }

    /**
     * 排完网格并把每座建筑压进 {@link #PENDING}。
     *
     * <p>每行从左往右按各建筑自身宽度紧挨着摆（行内不预留列宽），行深取该行最深的建筑——
     * 于是「一座 52 格宽的大店」只把它自己那行撑宽，不会像按列取最大宽那样让全表跟着变宽。
     *
     * @return 网格总宽度（格），仅用于回显
     */
    private static int plan(List<BuildingConfig> configs, int perRow, BlockPos playerPos,
                            Direction forward, Direction right) {
        int n = configs.size();
        int rows = (n + perRow - 1) / perRow;

        int[] cellLx = new int[n];
        int[] rowDepth = new int[rows];
        Footprint[] fps = new Footprint[n];
        int totalWidth = 0;
        for (int r = 0; r < rows; r++) {
            int cursor = 0;
            for (int c = 0; c < perRow && r * perRow + c < n; c++) {
                int i = r * perRow + c;
                Footprint fp = Footprint.of(configs.get(i));
                fps[i] = fp;
                cellLx[i] = cursor;
                cursor += fp.width() + GAP;
                rowDepth[r] = Math.max(rowDepth[r], fp.depth());
            }
            totalWidth = Math.max(totalWidth, cursor - GAP);
        }
        int[] rowZ = new int[rows];
        for (int r = 1; r < rows; r++) rowZ[r] = rowZ[r - 1] + rowDepth[r - 1] + GAP;

        // 网格以玩家为中心：前沿离玩家 FRONT_OFFSET 格，再沿右手方向左移半个总宽
        int halfWidth = -totalWidth / 2;
        BlockPos base = playerPos.offset(
                forward.getStepX() * FRONT_OFFSET + right.getStepX() * halfWidth, 0,
                forward.getStepZ() * FRONT_OFFSET + right.getStepZ() * halfWidth);

        for (int i = 0; i < n; i++) {
            int lx = cellLx[i];
            int lz = rowZ[i / perRow];
            int cellX = base.getX() + right.getStepX() * lx + forward.getStepX() * lz;
            int cellZ = base.getZ() + right.getStepZ() * lx + forward.getStepZ() * lz;
            PENDING.add(new Pending(configs.get(i), configs.get(i).id(), cellX, cellZ, fps[i]));
        }
        return totalWidth;
    }

    /** 建筑水平占地 + 边界盒左下角偏移（用于把边界盒角对齐到格子角）。 */
    private record Footprint(int width, int depth, int minX, int minZ, int minY) {
        static Footprint of(BuildingConfig config) {
            BuildingConfig.BoundaryBox b = config.boundary();
            if (b != null) {
                return new Footprint(b.max().x() - b.min().x() + 1, b.max().z() - b.min().z() + 1,
                        b.min().x(), b.min().z(), b.min().y());
            }
            // 无边界盒的旧数据：退回图案自身范围，至少 1x1
            int minX = 0, maxX = 0, minZ = 0, maxZ = 0;
            for (BlockOffset off : config.pattern()) {
                minX = Math.min(minX, off.x());
                maxX = Math.max(maxX, off.x());
                minZ = Math.min(minZ, off.z());
                maxZ = Math.max(maxZ, off.z());
            }
            return new Footprint(maxX - minX + 1, maxZ - minZ + 1, minX, minZ, 0);
        }
    }

    // ── 每 tick 推进（由 Wandscape#onServerTick 调用） ──

    /** 按预算落方块；一批跑完即收尾。队列空时是空操作。 */
    public static void tick() {
        if (!running) return;

        World world = World.getActive();
        if (world == null || world.blockOps == null) {
            // 引擎已重启/关服——丢弃这批，避免把方块落到下一个存档
            Log.warn(TAG, "[Test] spawn-all aborted: engine unavailable ({} left)", PENDING.size());
            clear();
            return;
        }

        int budget = BLOCKS_PER_TICK;
        while (budget > 0) {
            if (OPS.isEmpty() && !submitNext(world)) {
                finish();
                return;
            }
            AtomicOp op = OPS.poll();
            if (op == null) break;
            applyOp(world, op);
            budget--;
        }
    }

    /** 登记下一座建筑并编译出它的 op 序列；队列已空返回 false。 */
    private static boolean submitNext(World world) {
        Pending p = PENDING.poll();
        while (p != null) {
            BuildingConfig config = p.config();
            Footprint fp = p.footprint();
            ServerLevel level = serverLevel();
            if (level == null) {
                clear();
                return false;
            }
            int groundY = groundHeight(level, p.cellX() + fp.width() / 2, p.cellZ() + fp.depth() / 2);
            BlockPos anchor = new BlockPos(p.cellX() - fp.minX(), groundY - fp.minY(), p.cellZ() - fp.minZ());

            BuildingState state = EnqueueHelper.registerIfAbsent(anchor, config, p.typeId(), 0, colonyId);
            if (state == null) {
                skipped++;
                Log.warn(TAG, "[Test] {} @ {} skipped — position occupied or overlapping", p.typeId(), anchor);
                p = PENDING.poll();
                continue;
            }

            try {
                WorkItem work = EnqueueHelper.buildWorkItem(config, anchor, p.typeId(), 10,
                        null, state.getBuildingId(), 0, /* skipMaterials */ true, /* clearBox */ false);
                TaskRequest request = new TaskRequest(work.blueprintId(), work.params(),
                        work.priority(), colonyId);
                CompiledBlueprint compiled = world.blueprintRegistry.compile(request, world);
                OPS.addAll(compiled.sequence().steps());
                built++;
                Log.info(TAG, "[Test] {} @ {} registered, {} ops queued",
                        p.typeId(), anchor, OPS.size());
            } catch (RuntimeException e) {
                // 蓝图缺失/参数不全是数据问题：跳过并留痕，不中断整批
                skipped++;
                Log.warn(TAG, "[Test] {} @ {} failed to compile: {}", p.typeId(), anchor, e.toString());
            }
            return true;
        }
        return false;
    }

    private static void applyOp(World world, AtomicOp op) {
        if (op instanceof AtomicOp.TransformOp t) {
            world.blockOps.setBlock(t.target(), t.to());
            world.blockOps.setBlockEntityData(t.target(), t.blockNbtBase64());
        } else if (op instanceof AtomicOp.SpawnDecorationOp d) {
            world.entityOps.spawnDecoration(d.target(), d.entityType(), d.facing(), d.nbtBase64());
        } else if (op instanceof AtomicOp.EmitEventOp e) {
            // 蓝图自带的 build_complete——BuildCompleteListener 据此置为已建成
            world.eventBus.emit(new CustomEvent(e.eventName(), e.templateParams()));
        }
        // ResourceRequestOp：调试生成不向仓库要料，跳过
    }

    private static void finish() {
        CommandSourceStack src = source;
        if (src != null) {
            int done = built;
            int lost = skipped;
            src.sendSuccess(() -> Component.literal("[魔法小镇] 建筑生成完毕：成功 " + done
                    + " 座" + (lost > 0 ? "，跳过 " + lost + " 座（位置被占/数据异常，见日志）" : "")
                    + "；用 /wandscape building list 查看"), false);
        }
        Log.info(TAG, "[Test] spawn-all finished: {} built, {} skipped, {} total", built, skipped, total);
        clear();
    }

    private static void clear() {
        PENDING.clear();
        OPS.clear();
        running = false;
        source = null;
    }

    @Nullable
    private static ServerLevel serverLevel() {
        var server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        return server != null ? server.overworld() : null;
    }

    /**
     * 该格中心的地表高度（首个空气方块的 y）。
     *
     * <p>{@code Level#getHeight} 对未加载的区块会直接返回 {@code minBuildHeight}——网格铺到玩家视距外
     * 时会把整排建筑甩到世界底部，所以先显式把区块载入出来再问高度。
     */
    private static int groundHeight(ServerLevel level, int x, int z) {
        level.getChunk(x >> 4, z >> 4);
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
    }
}
