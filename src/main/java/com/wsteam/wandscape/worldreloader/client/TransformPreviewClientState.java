package com.wsteam.wandscape.worldreloader.client;

import com.wsteam.wandscape.worldreloader.network.TransformPreviewCancelPacket;
import com.wsteam.wandscape.worldreloader.network.TransformPreviewPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Client-side state tracker for active terrain transformation preview.
 * Interpolates ghost opacity from subtle/transparent to solid over duration.
 */
public final class TransformPreviewClientState {

    private static BlockPos center = null;
    private static int radius = 0;
    private static long startTimeMs = 0;
    private static long durationMs = 0;
    private static final List<TransformPreviewPacket.PreviewBlock> blocks = new ArrayList<>();
    private static AABB bounds = null;

    private TransformPreviewClientState() {}

    public static void init() {
        TransformPreviewPacket.setClientHandler(TransformPreviewClientState::onPreviewReceived);
        TransformPreviewCancelPacket.setClientHandler(pkt -> clear());
    }

    public static void onPreviewReceived(TransformPreviewPacket packet) {
        center = packet.center();
        radius = packet.radius();
        startTimeMs = System.currentTimeMillis();
        durationMs = packet.durationTicks() * 50L;
        blocks.clear();
        blocks.addAll(packet.blocks());

        int minX = center.getX() - radius;
        int maxX = center.getX() + radius + 1;
        int minZ = center.getZ() - radius;
        int maxZ = center.getZ() + radius + 1;
        bounds = new AABB(minX, center.getY() - 64, minZ, maxX, center.getY() + 128, maxZ);
    }

    public static void clear() {
        center = null;
        radius = 0;
        blocks.clear();
        bounds = null;
    }

    public static boolean isActive() {
        if (center == null || blocks.isEmpty()) return false;
        long elapsed = System.currentTimeMillis() - startTimeMs;
        return elapsed >= 0 && elapsed <= durationMs + 200L;
    }

    public static float getAlpha() {
        if (!isActive()) return 0f;
        long elapsed = System.currentTimeMillis() - startTimeMs;
        float progress = Math.min(1.0f, elapsed / (float) Math.max(1L, durationMs));
        // Smoothly ramp up opacity from 0.12f to 0.85f (fading in and solidifying)
        return 0.12f + 0.73f * (progress * progress);
    }

    public static float getProgress() {
        if (!isActive()) return 0f;
        long elapsed = System.currentTimeMillis() - startTimeMs;
        return Math.min(1.0f, elapsed / (float) Math.max(1L, durationMs));
    }

    @Nullable
    public static BlockPos getCenter() {
        return center;
    }

    public static int getRadius() {
        return radius;
    }

    public static List<TransformPreviewPacket.PreviewBlock> getBlocks() {
        return blocks;
    }

    @Nullable
    public static AABB getBounds() {
        return bounds;
    }

    public static void clientTick(Minecraft mc) {
        if (!isActive() || mc.level == null || center == null) return;

        Level level = mc.level;
        float progress = getProgress();
        int particleCount = (int) (1 + progress * 5);

        // Spawn magic / portal particles along random angles of the perimeter and center
        for (int i = 0; i < particleCount; i++) {
            double angle = level.random.nextDouble() * 2 * Math.PI;
            double r = level.random.nextDouble() * radius;
            double px = center.getX() + 0.5 + Math.cos(angle) * r;
            double pz = center.getZ() + 0.5 + Math.sin(angle) * r;
            double py = center.getY() + level.random.nextDouble() * 4.0;

            level.addParticle(ParticleTypes.ENCHANT, px, py, pz,
                    (level.random.nextDouble() - 0.5) * 0.2,
                    level.random.nextDouble() * 0.5 + 0.2,
                    (level.random.nextDouble() - 0.5) * 0.2);
        }
    }
}
