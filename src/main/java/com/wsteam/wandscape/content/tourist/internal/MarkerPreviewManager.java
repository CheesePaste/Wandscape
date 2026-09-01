package com.wsteam.wandscape.content.tourist.internal;
import com.wsteam.wandscape.content.task.ecs.World;

import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.content.building.scanner.InteractSpotMarkerBlock;
import com.wsteam.wandscape.content.tourist.data.Activity;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.content.tourist.entity.TouristEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import javax.annotation.Nullable;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 交互位（interact_spot_marker）预览假人管理（服务器端，始终常态）。
 *
 * <p>每个 marker 方块对应一个预览 TouristEntity（preview 模式）：站桩在该 spot 格，
 * 循环播放该 marker 的动作（含姿态/粒子/朝向），方便创作者查看效果。
 * 无 AI、不参与生成/离开、不持久化（chunk 卸载即消失，重载时重新发现）。
 *
 * <p>生命周期钩子：
 * <ul>
 *   <li>放置（{@link BlockEvent.EntityPlaceEvent}）→ 生成</li>
 *   <li>右键改动作/潜行右键改朝向（marker {@code useWithoutItem} 里 setBlock 后回调）→ 更新</li>
 *   <li>敲掉（{@link BlockEvent.BreakEvent}）→ 移除</li>
 *   <li>chunk 加载（{@link ChunkEvent.Load}）→ 用 palette maybeHas 发现 marker 并生成</li>
 *   <li>周期 reconcile → 兜底：marker 变化/预览丢失时补同步</li>
 * </ul>
 */
public final class MarkerPreviewManager {

    private static final String TAG = "MarkerPreviewManager";
    private static final int RECONCILE_INTERVAL = 100;

    /** marker 方块位置 → 预览实体 UUID。 */
    private final Map<BlockPos, UUID> previews = new ConcurrentHashMap<>();
    private int tickCounter;

    @Nullable
    private static MarkerPreviewManager active;

    private MarkerPreviewManager() {
    }

    public static MarkerPreviewManager register() {
        if (active == null) {
            active = new MarkerPreviewManager();
            NeoForge.EVENT_BUS.register(active);
            Log.info(TAG, "[Preview] MarkerPreviewManager registered");
        }
        return active;
    }

    @Nullable
    public static MarkerPreviewManager getActive() {
        return active;
    }

    // ── Event hooks ──

    @SubscribeEvent
    public void onBlockPlaced(BlockEvent.EntityPlaceEvent event) {
        ServerLevel level = asServerLevel(event.getLevel());
        if (level == null || !isMarker(event.getState())) return;
        spawnPreview(level, event.getPos(), event.getState());
    }

    @SubscribeEvent
    public void onBlockBroken(BlockEvent.BreakEvent event) {
        ServerLevel level = asServerLevel(event.getLevel());
        if (level == null) return;
        removePreview(level, event.getPos());
    }

    @SubscribeEvent
    public void onChunkLoad(ChunkEvent.Load event) {
        ServerLevel level = asServerLevel(event.getLevel());
        if (level == null) return;
        if (event.getChunk() instanceof LevelChunk chunk) {
            discoverInChunk(level, chunk);
        }
    }

    @SubscribeEvent
    public void onChunkUnload(ChunkEvent.Unload event) {
        if (asServerLevel(event.getLevel()) == null) return;
        ChunkPos cp = event.getChunk().getPos();
        previews.keySet().removeIf(pos -> new ChunkPos(pos).equals(cp));
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        if (++tickCounter % RECONCILE_INTERVAL != 0) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        ServerLevel level = server.overworld();
        if (level == null) return;
        reconcile(level);
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        previews.clear();
    }

    /** marker 自身右键改动作/朝向（setBlock 后）回调。 */
    public void onMarkerChanged(ServerLevel level, BlockPos pos, BlockState state) {
        if (isMarker(state)) {
            spawnPreview(level, pos, state); // 幂等：存在则更新
        } else {
            removePreview(level, pos);
        }
    }

    // ── Preview lifecycle ──

    private void spawnPreview(ServerLevel level, BlockPos pos, BlockState state) {
        Activity action = InteractSpotMarkerBlock.spotActionOrBrowse(state);
        Direction facing = state.getValue(InteractSpotMarkerBlock.FACING);

        UUID existing = previews.get(pos);
        if (existing != null) {
            if (level.getEntity(existing) instanceof TouristEntity p && p.isAlive()) {
                p.setCurrentActivity(action);
                applyFacing(p, facing);
                p.setCustomName(previewName(action));
                return;
            }
            previews.remove(pos); // 预览丢失 → 重建
        }

        TouristEntity preview = new TouristEntity(Wandscape.TOURIST.get(), level);
        preview.setPreview(true);
        preview.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        preview.setCurrentActivity(action);
        applyFacing(preview, facing);
        preview.setCustomName(previewName(action));
        preview.setInvulnerable(true);
        level.addFreshEntity(preview);
        previews.put(pos, preview.getUUID());
        Log.info(TAG, "[Preview] spawned {} at {}", action, pos.toShortString());
    }

    private void removePreview(ServerLevel level, BlockPos pos) {
        UUID id = previews.remove(pos);
        if (id == null) return;
        if (level.getEntity(id) instanceof TouristEntity p && p.isAlive()) {
            p.discard();
        }
    }

    private void discoverInChunk(ServerLevel level, LevelChunk chunk) {
        Block marker = Wandscape.INTERACT_SPOT_MARKER.get();
        int baseX = chunk.getPos().getMinBlockX();
        int baseZ = chunk.getPos().getMinBlockZ();
        LevelChunkSection[] sections = chunk.getSections();
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            if (section.hasOnlyAir()) continue;
            if (!section.maybeHas(s -> s.is(marker))) continue;
            int startY = chunk.getMinBuildHeight() + i * 16;
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    for (int y = 0; y < 16; y++) {
                        BlockState st = section.getBlockState(x, y, z);
                        if (st.is(marker)) {
                            BlockPos pos = new BlockPos(baseX + x, startY + y, baseZ + z);
                            if (!previews.containsKey(pos)) {
                                spawnPreview(level, pos, st);
                            }
                        }
                    }
                }
            }
        }
    }

    /** 周期兜底：marker 没了→移除；预览丢了→重建；动作/朝向变了→更新。 */
    private void reconcile(ServerLevel level) {
        Iterator<Map.Entry<BlockPos, UUID>> it = previews.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, UUID> entry = it.next();
            BlockPos pos = entry.getKey();
            if (!level.isLoaded(pos)) {
                it.remove(); // chunk 卸载——重载时 discovery 重建
                continue;
            }
            BlockState st = level.getBlockState(pos);
            if (!isMarker(st)) {
                it.remove(); // marker 已被移除
                continue;
            }
            UUID id = entry.getValue();
            if (!(level.getEntity(id) instanceof TouristEntity p) || !p.isAlive()) {
                it.remove();
                spawnPreview(level, pos, st); // 重建（重新入 map）
            } else {
                // 动作/朝向变化兜底（正常路径已由 marker 回调处理）
                Activity action = InteractSpotMarkerBlock.spotActionOrBrowse(st);
                Direction facing = st.getValue(InteractSpotMarkerBlock.FACING);
                if (p.getCurrentActivity() != action) p.setCurrentActivity(action);
                if (p.getYRot() != facing.toYRot()) applyFacing(p, facing);
            }
        }
    }

    // ── Helpers ──

    private static void applyFacing(TouristEntity p, Direction facing) {
        float yaw = facing.toYRot();
        p.setFrozenYaw(yaw);
        p.setYRot(yaw);
        p.setYHeadRot(yaw);
        p.yBodyRot = yaw;
    }

    private static Component previewName(Activity action) {
        return Component.translatable("preview.wandscape.name",
                Component.translatable("activity.wandscape." + action.name().toLowerCase()));
    }

    private static boolean isMarker(BlockState state) {
        return state.is(Wandscape.INTERACT_SPOT_MARKER.get());
    }

    @Nullable
    private static ServerLevel asServerLevel(LevelAccessor level) {
        return level instanceof ServerLevel sl ? sl : null;
    }
}
