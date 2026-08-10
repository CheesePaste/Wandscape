package com.wsteam.wandscape.tourist.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;

import java.util.OptionalDouble;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import com.wsteam.wandscape.building.data.BuildingConfig;
import com.wsteam.wandscape.building.internal.BuildingConfigLoader;
import com.wsteam.wandscape.shared.network.BuildingAreaSyncPacket;
import com.wsteam.wandscape.tourist.entity.TouristEntity;

import java.util.List;

/**
 * Debug renderer that visualizes tourist navigation targets.
 *
 * <p>Press F6 to toggle. X-ray (no depth test) rendering:
 * <ul>
 *   <li>Cyan line → entry point (macro outdoor nav target)</li>
 *   <li>Blue cross at entry point</li>
 *   <li>Magenta line → interact point (micro indoor nav)</li>
 *   <li>Yellow cross at interact point</li>
 *   <li>旅店床位 debug（临时，后面删）：旅店 bbox 内每张床画黄色十字（原版 OCCUPIED 的床画红色）+ 白色 bbox 线框</li>
 * </ul>
 */
public final class TouristDebugRenderer {

    private static boolean enabled = false;
    private static boolean registered = false;

    private static final float LINE_Y_OFFSET = 1.6f;  // tourist eye height
    private static final float CROSS_SIZE = 0.4f;
    private static final int SCAN_RADIUS = 80;

    private TouristDebugRenderer() {}

    public static void register() {
        if (registered) return;
        registered = true;
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                RenderLevelStageEvent.class, TouristDebugRenderer::onRenderLevelStage);
    }

    public static void toggle() {
        enabled = !enabled;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (!enabled) return;
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRIPWIRE_BLOCKS) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        List<? extends TouristEntity> tourists = mc.level.getEntitiesOfClass(
                TouristEntity.class,
                new AABB(mc.player.blockPosition()).inflate(SCAN_RADIUS));

        Vec3 camPos = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource buf = mc.renderBuffers().bufferSource();

        poseStack.pushPose();
        poseStack.translate(-camPos.x, -camPos.y, -camPos.z);

        VertexConsumer lineBuf = buf.getBuffer(DebugRenderType.DEBUG_LINES);
        PoseStack.Pose pose = poseStack.last();

        for (TouristEntity t : tourists) {
            BlockPos pos = t.blockPosition();
            BlockPos commute = t.getDebugCommuteTarget();
            BlockPos entryPt = t.getDebugEntryPoint();
            BlockPos interactPt = t.getDebugInteractPoint();
            boolean indoor = t.isDebugIndoorPhase();

            // Fallback: entry point not yet computed → use raw commute target
            if (entryPt == null) entryPt = commute;
            // Fallback: interact not yet computed → use commute target
            if (interactPt == null) interactPt = commute;

            // Skip tourists with no target data at all (idle/wandering/hotel)
            if (entryPt == null && interactPt == null) continue;

            float sx = pos.getX() + 0.5f;
            float sy = pos.getY() + LINE_Y_OFFSET;
            float sz = pos.getZ() + 0.5f;

            if (indoor && interactPt != null) {
                // ── Indoor micro-nav ──
                // Magenta line: tourist → interact point
                line(pose, lineBuf, sx, sy, sz,
                        interactPt.getX() + 0.5f, interactPt.getY() + 0.1f, interactPt.getZ() + 0.5f,
                        255, 64, 255, 220);
                // Yellow cross at interact point
                cross(pose, lineBuf, interactPt, 255, 220, 50, 220);
                // Blue cross at entry point (exit target)
                if (entryPt != null) {
                    cross(pose, lineBuf, entryPt, 64, 140, 255, 180);
                }
            } else if (entryPt != null) {
                // ── Outdoor macro-nav ──
                // Cyan line: tourist → entry point
                line(pose, lineBuf, sx, sy, sz,
                        entryPt.getX() + 0.5f, entryPt.getY() + 0.1f, entryPt.getZ() + 0.5f,
                        64, 220, 255, 220);
                // Blue cross at entry point
                cross(pose, lineBuf, entryPt, 64, 140, 255, 220);
                // Show interact point if resolved (dimmed orange)
                if (interactPt != null && !interactPt.equals(entryPt)) {
                    cross(pose, lineBuf, interactPt, 255, 180, 100, 140);
                }
            }
        }

        drawHotelBeds(mc, pose, lineBuf);

        buf.endBatch(DebugRenderType.DEBUG_LINES);
        poseStack.popPose();
    }

    /**
     * 临时 debug（后面删）：显示附近旅店 bbox 内每张床的位置。
     * 黄色 = 可用的床，红色 = 原版 OCCUPIED 的床；白线框 = 旅店 bbox（findBed 扫描的就是这个范围）。
     * 需要先开过一次 V 面板（BuildingAreaSyncPacket 缓存才有建筑数据）。
     */
    private static void drawHotelBeds(Minecraft mc, PoseStack.Pose pose, VertexConsumer vc) {
        var buildings = BuildingAreaSyncPacket.getCached();
        if (buildings.isEmpty()) return;

        BlockPos playerPos = mc.player.blockPosition();
        int scanSq = SCAN_RADIUS * SCAN_RADIUS;
        for (BuildingAreaSyncPacket.BuildingEntry entry : buildings) {
            if (!entry.hasBoundary()) continue;
            BlockPos anchor = entry.anchor();
            if (anchor.distSqr(playerPos) > scanSq) continue;

            BuildingConfig config = BuildingConfigLoader.getInstance().get(entry.buildingTypeId());
            if (config == null || config.service() == null || config.service().maxOccupancy() <= 0) continue;

            int x0 = anchor.getX() + entry.bMinX();
            int y0 = anchor.getY() + entry.bMinY();
            int z0 = anchor.getZ() + entry.bMinZ();
            int x1 = anchor.getX() + entry.bMaxX();
            int y1 = anchor.getY() + entry.bMaxY();
            int z1 = anchor.getZ() + entry.bMaxZ();

            // bbox 线框（白）
            boxEdges(vc, pose, x0, y0, z0, x1 + 1, y1 + 1, z1 + 1, 255, 255, 255, 120);

            // 每张床一个十字
            for (int x = x0; x <= x1; x++) {
                for (int y = y0; y <= y1; y++) {
                    for (int z = z0; z <= z1; z++) {
                        BlockState bs = mc.level.getBlockState(new BlockPos(x, y, z));
                        if (!(bs.getBlock() instanceof BedBlock)) continue;
                        boolean occupied = bs.getValue(BedBlock.OCCUPIED);
                        cross(pose, vc, new BlockPos(x, y, z),
                                occupied ? 255 : 255, occupied ? 60 : 220, occupied ? 60 : 50, 220);
                    }
                }
            }
        }
    }

    // ── Drawing primitives ──

    private static void line(PoseStack.Pose pose, VertexConsumer vc,
                              float x1, float y1, float z1,
                              float x2, float y2, float z2,
                              int r, int g, int b, int a) {
        vc.addVertex(pose, x1, y1, z1).setColor(r, g, b, a);
        vc.addVertex(pose, x2, y2, z2).setColor(r, g, b, a);
    }

    private static void cross(PoseStack.Pose pose, VertexConsumer vc,
                               BlockPos pos, int r, int g, int b, int a) {
        float cx = pos.getX() + 0.5f;
        float cy = pos.getY() + 0.08f;
        float cz = pos.getZ() + 0.5f;
        float s = CROSS_SIZE;

        // X line
        vc.addVertex(pose, cx - s, cy, cz).setColor(r, g, b, a);
        vc.addVertex(pose, cx + s, cy, cz).setColor(r, g, b, a);
        // Z line
        vc.addVertex(pose, cx, cy, cz - s).setColor(r, g, b, a);
        vc.addVertex(pose, cx, cy, cz + s).setColor(r, g, b, a);
        // Y spike
        vc.addVertex(pose, cx, cy - 0.1f, cz).setColor(r, g, b, a);
        vc.addVertex(pose, cx, cy + 0.1f, cz).setColor(r, g, b, a);
    }

    private static void boxEdges(VertexConsumer vc, PoseStack.Pose pose,
                                 float x0, float y0, float z0, float x1, float y1, float z1,
                                 int r, int g, int b, int a) {
        line(pose, vc, x0, y0, z0, x1, y0, z0, r, g, b, a);
        line(pose, vc, x1, y0, z0, x1, y0, z1, r, g, b, a);
        line(pose, vc, x1, y0, z1, x0, y0, z1, r, g, b, a);
        line(pose, vc, x0, y0, z1, x0, y0, z0, r, g, b, a);
        line(pose, vc, x0, y1, z0, x1, y1, z0, r, g, b, a);
        line(pose, vc, x1, y1, z0, x1, y1, z1, r, g, b, a);
        line(pose, vc, x1, y1, z1, x0, y1, z1, r, g, b, a);
        line(pose, vc, x0, y1, z1, x0, y1, z0, r, g, b, a);
        line(pose, vc, x0, y0, z0, x0, y1, z0, r, g, b, a);
        line(pose, vc, x1, y0, z0, x1, y1, z0, r, g, b, a);
        line(pose, vc, x1, y0, z1, x1, y1, z1, r, g, b, a);
        line(pose, vc, x0, y0, z1, x0, y1, z1, r, g, b, a);
    }

    // ═══════════════════════════════════════════════════════════════
    // X-Ray debug render type
    // ═══════════════════════════════════════════════════════════════

    private static final class DebugRenderType extends RenderType {
        private DebugRenderType(String name, VertexFormat format, VertexFormat.Mode mode,
                                int bufSize, boolean affectsCrumbling, boolean sortOnUpload,
                                Runnable setup, Runnable clear) {
            super(name, format, mode, bufSize, affectsCrumbling, sortOnUpload, setup, clear);
        }

        static final RenderType DEBUG_LINES = create(
                "tourist_debug",
                DefaultVertexFormat.POSITION_COLOR,
                VertexFormat.Mode.DEBUG_LINES,
                512,
                false,
                false,
                CompositeState.builder()
                        .setShaderState(POSITION_COLOR_SHADER)
                        .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                        .setDepthTestState(NO_DEPTH_TEST)
                        .setCullState(NO_CULL)
                        .setLineState(new RenderStateShard.LineStateShard(OptionalDouble.of(2.0)))
                        .setLayeringState(VIEW_OFFSET_Z_LAYERING)
                        .createCompositeState(false)
        );
    }
}
