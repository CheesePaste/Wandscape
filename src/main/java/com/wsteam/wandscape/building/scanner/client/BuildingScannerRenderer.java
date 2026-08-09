package com.wsteam.wandscape.building.scanner.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.wsteam.wandscape.building.data.BlockOffset;
import com.wsteam.wandscape.building.scanner.BuildingScannerBlockEntity;
import com.wsteam.wandscape.shared.data.Activity;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;

/**
 * Block Entity Renderer for the Building Scanner block.
 * Draws orange boundary box, per-action colored interact-spot dots (scanned from
 * world {@code interact_spot_marker} blocks, cached at low frequency), and red door marker.
 */
public class BuildingScannerRenderer implements BlockEntityRenderer<BuildingScannerBlockEntity> {

    // Boundary: orange
    private static final int BDY_R = 255, BDY_G = 150, BDY_B = 50, BDY_A = 200;
    private static final int BDY_FACE_R = 255, BDY_FACE_G = 150, BDY_FACE_B = 50, BDY_FACE_A = 50;

    // Door marker: red
    private static final int DOOR_R = 255, DOOR_G = 50, DOOR_B = 50, DOOR_A = 200;

    // Interact spot dot colors by action
    private static final Map<Activity, int[]> ACTION_COLORS = new HashMap<>();
    static {
        ACTION_COLORS.put(Activity.BROWSE, new int[]{0, 255, 255});       // 青
        ACTION_COLORS.put(Activity.EAT, new int[]{255, 165, 0});          // 橙
        ACTION_COLORS.put(Activity.BATHE, new int[]{30, 144, 255});       // 蓝
        ACTION_COLORS.put(Activity.VIEW, new int[]{180, 60, 255});        // 紫
        ACTION_COLORS.put(Activity.PAY, new int[]{255, 215, 0});          // 金币黄
        ACTION_COLORS.put(Activity.REST, new int[]{255, 120, 170});       // 粉
        ACTION_COLORS.put(Activity.WITHDRAW, new int[]{255, 255, 0});     // 黄
    }

    /** 低频扫 world marker，避免每帧扫整个 boundary。 */
    private static final int SPOT_SCAN_INTERVAL_TICKS = 40;
    private final Map<BuildingScannerBlockEntity, SpotCache> spotCaches = new HashMap<>();

    private record SpotCache(List<BlockPos> positions, List<Activity> actions, long lastScan) {}

    public BuildingScannerRenderer(BlockEntityRendererProvider.Context ctx) {}

    @Override
    public void render(BuildingScannerBlockEntity be, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        // CORNER mode scanners do not render bounding box in world
        if (be.getBlockMode() == BuildingScannerBlockEntity.BlockMode.CORNER) {
            return;
        }

        // 1. Boundary box (orange)
        BlockOffset bMin = be.getBoundaryMin();
        BlockOffset bMax = be.getBoundaryMax();
        if (bMin != null && bMax != null) {
            renderBox(bufferSource, poseStack.last(),
                    bMin.x(), bMin.y(), bMin.z(),
                    bMax.x() + 1, bMax.y() + 1, bMax.z() + 1,
                    BDY_R, BDY_G, BDY_B, BDY_A,
                    BDY_FACE_R, BDY_FACE_G, BDY_FACE_B, BDY_FACE_A);
        }

        // 2. Interact spots (colored dots by action) — local offsets from block origin
        SpotCache spots = refreshSpots(be);
        BlockPos bePos = be.getBlockPos();
        for (int i = 0; i < spots.positions().size(); i++) {
            BlockPos lp = spots.positions().get(i).subtract(bePos);
            int[] c = ACTION_COLORS.getOrDefault(spots.actions().get(i), ACTION_COLORS.get(Activity.BROWSE));
            renderBox(bufferSource, poseStack.last(),
                    lp.getX() + 0.25f, lp.getY() + 0.25f, lp.getZ() + 0.25f,
                    lp.getX() + 0.75f, lp.getY() + 0.75f, lp.getZ() + 0.75f,
                    c[0], c[1], c[2], 220,
                    c[0], c[1], c[2], 60);
        }

        // 3. Door marker (red)
        BlockOffset door = be.getDoorOffset();
        if (door != null) {
            renderBox(bufferSource, poseStack.last(),
                    door.x(), door.y(), door.z(),
                    door.x() + 1, door.y() + 1, door.z() + 1,
                    DOOR_R, DOOR_G, DOOR_B, DOOR_A,
                    DOOR_R, DOOR_G, DOOR_B, DOOR_A);
        }
    }

    private SpotCache refreshSpots(BuildingScannerBlockEntity be) {
        var level = be.getLevel();
        if (level == null) {
            return new SpotCache(List.of(), List.of(), 0);
        }
        long now = level.getGameTime();
        SpotCache cached = spotCaches.get(be);
        if (cached != null && now - cached.lastScan() < SPOT_SCAN_INTERVAL_TICKS) {
            return cached;
        }
        BlockPos wMin = be.getWorldMin();
        BlockPos wMax = be.getWorldMax();
        List<BlockPos> positions = new ArrayList<>();
        List<Activity> actions = new ArrayList<>();
        if (wMin != null && wMax != null) {
            for (int x = wMin.getX(); x <= wMax.getX(); x++) {
                for (int y = wMin.getY(); y <= wMax.getY(); y++) {
                    for (int z = wMin.getZ(); z <= wMax.getZ(); z++) {
                        BlockPos p = new BlockPos(x, y, z);
                        if (level.getBlockState(p).is(com.wsteam.wandscape.Wandscape.INTERACT_SPOT_MARKER.get())) {
                            positions.add(p.immutable());
                            actions.add(com.wsteam.wandscape.building.scanner.InteractSpotMarkerBlock
                                    .spotActionOrBrowse(level.getBlockState(p)));
                        }
                    }
                }
            }
        }
        SpotCache fresh = new SpotCache(positions, actions, now);
        spotCaches.put(be, fresh);
        return fresh;
    }

    private static void renderBox(MultiBufferSource buf, PoseStack.Pose pose,
                                   float x0, float y0, float z0,
                                   float x1, float y1, float z1,
                                   int lineR, int lineG, int lineB, int lineA,
                                   int faceR, int faceG, int faceB, int faceA) {
        // Semi-transparent faces
        VertexConsumer fvc = buf.getBuffer(RenderType.debugQuads());
        quad(fvc, pose, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, faceR, faceG, faceB, faceA);
        quad(fvc, pose, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0, faceR, faceG, faceB, faceA);
        quad(fvc, pose, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0, faceR, faceG, faceB, faceA);
        quad(fvc, pose, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1, faceR, faceG, faceB, faceA);
        quad(fvc, pose, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, faceR, faceG, faceB, faceA);
        quad(fvc, pose, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, faceR, faceG, faceB, faceA);

        // Edges
        VertexConsumer lvc = buf.getBuffer(RenderType.lines());
        boxEdges(lvc, pose, x0, y0, z0, x1, y1, z1, lineR, lineG, lineB, lineA);
    }

    private static void boxEdges(VertexConsumer vc, PoseStack.Pose pose,
                                  float x0, float y0, float z0, float x1, float y1, float z1,
                                  int r, int g, int b, int a) {
        line(vc, pose, x0, y0, z0, x1, y0, z0, r, g, b, a);
        line(vc, pose, x1, y0, z0, x1, y0, z1, r, g, b, a);
        line(vc, pose, x1, y0, z1, x0, y0, z1, r, g, b, a);
        line(vc, pose, x0, y0, z1, x0, y0, z0, r, g, b, a);
        line(vc, pose, x0, y1, z0, x1, y1, z0, r, g, b, a);
        line(vc, pose, x1, y1, z0, x1, y1, z1, r, g, b, a);
        line(vc, pose, x1, y1, z1, x0, y1, z1, r, g, b, a);
        line(vc, pose, x0, y1, z1, x0, y1, z0, r, g, b, a);
        line(vc, pose, x0, y0, z0, x0, y1, z0, r, g, b, a);
        line(vc, pose, x1, y0, z0, x1, y1, z0, r, g, b, a);
        line(vc, pose, x1, y0, z1, x1, y1, z1, r, g, b, a);
        line(vc, pose, x0, y0, z1, x0, y1, z1, r, g, b, a);
    }

    private static void line(VertexConsumer vc, PoseStack.Pose pose,
                              float x1, float y1, float z1, float x2, float y2, float z2,
                              int r, int g, int b, int a) {
        vc.addVertex(pose, x1, y1, z1).setColor(r, g, b, a).setNormal(pose, 0, 1, 0);
        vc.addVertex(pose, x2, y2, z2).setColor(r, g, b, a).setNormal(pose, 0, 1, 0);
    }

    private static void quad(VertexConsumer vc, PoseStack.Pose pose,
                              float x1, float y1, float z1, float x2, float y2, float z2,
                              float x3, float y3, float z3, float x4, float y4, float z4,
                              int r, int g, int b, int a) {
        vc.addVertex(pose, x1, y1, z1).setColor(r, g, b, a);
        vc.addVertex(pose, x2, y2, z2).setColor(r, g, b, a);
        vc.addVertex(pose, x3, y3, z3).setColor(r, g, b, a);
        vc.addVertex(pose, x4, y4, z4).setColor(r, g, b, a);
    }
}
