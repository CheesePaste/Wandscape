package com.wsteam.wandscape.engine.service;

import com.wsteam.wandscape.Config;
import com.wsteam.wandscape.shared.network.ParticleBurstPacket;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/**
 * 全模组统一粒子特效门面（docs/particles.md 第 3 节）。
 * 原版粒子（无需颜色）走 {@link #burstAt}/{@link #burstAtFar}/{@link #burstRing}，
 * 染色粒子（烟花/光柱/星光）走 {@link #burstColored}，
 * 庆祝走 {@link #celebrateAt}（单点）/ {@link #celebrateRing}（建筑包围盒一圈）。
 * 所有方法先查 {@link Config#PARTICLE_LEVEL} 全局开关。
 * 粒子是纯瞬时表现，不持久化、不进 ECS / SavedData。
 */
public final class ParticleService {

    /** 烟花爆花的随机配色（金/红/青/紫/绿）。 */
    private static final float[][] FIREWORK_COLORS = {
            { 1.0f, 0.85f, 0.30f }, { 1.0f, 0.30f, 0.20f }, { 0.30f, 0.90f, 1.00f },
            { 0.80f, 0.40f, 1.00f }, { 0.40f, 1.00f, 0.50f }
    };

    private ParticleService() {}

    /** 全局开关：OFF 全关。 */
    private static boolean active() {
        return !"OFF".equalsIgnoreCase(Config.PARTICLE_LEVEL.get());
    }

    /** LOW 减半、HIGH 翻倍、NORMAL 原样；至少 1。 */
    private static int scaled(int count) {
        return switch (Config.PARTICLE_LEVEL.get().toUpperCase()) {
            case "LOW" -> Math.max(1, count / 2);
            case "HIGH" -> count * 2;
            default -> count;
        };
    }

    // ── 建筑包围盒几何 ──

    /** 建筑包围盒 XZ 脚印中心、顶面（maxY）+ rise 上方的点。 */
    public static Vec3 boundsCenterAbove(BoundingBox bounds, double rise) {
        return new Vec3((bounds.minX() + bounds.maxX() + 1) / 2.0,
                bounds.maxY() + rise,
                (bounds.minZ() + bounds.maxZ() + 1) / 2.0);
    }

    /** 包围盒 XZ 脚印的椭圆周采样点（高在顶 + rise），近似"包围盒那一圈"。 */
    private static List<Vec3> perimeterPoints(BoundingBox bounds, int samples, double rise) {
        List<Vec3> pts = new ArrayList<>(samples);
        double cx = (bounds.minX() + bounds.maxX() + 1) / 2.0;
        double cz = (bounds.minZ() + bounds.maxZ() + 1) / 2.0;
        double hw = Math.max(1.0, (bounds.maxX() - bounds.minX() + 1) / 2.0);
        double hd = Math.max(1.0, (bounds.maxZ() - bounds.minZ() + 1) / 2.0);
        double y = bounds.maxY() + rise;
        for (int i = 0; i < samples; i++) {
            double t = (i / (double) samples) * Math.PI * 2;
            pts.add(new Vec3(cx + Math.cos(t) * hw, y, cz + Math.sin(t) * hd));
        }
        return pts;
    }

    // ── 原版粒子广播 ──

    /** 服务端向 32 格内玩家广播原版粒子。 */
    public static void burstAt(ServerLevel level, ParticleOptions type, Vec3 pos,
                               int count, double spread, double speed) {
        if (!active() || level == null || type == null) return;
        level.sendParticles(type, pos.x, pos.y, pos.z, scaled(count), spread, spread, spread, speed);
    }

    /** 方块中心重载。 */
    public static void burstAt(ServerLevel level, ParticleOptions type, BlockPos pos,
                               int count, double spread, double speed) {
        burstAt(level, type, Vec3.atCenterOf(pos), count, spread, speed);
    }

    /** 包围盒重载：XZ 脚印中心、顶面高度。 */
    public static void burstAt(ServerLevel level, ParticleOptions type, BoundingBox bounds,
                               int count, double spread, double speed) {
        burstAt(level, type, boundsCenterAbove(bounds, 0), count, spread, speed);
    }

    /** 服务端大范围广播（512 格，逐玩家 longDistance=true）—— 建成/升级/胜利庆祝用。 */
    public static void burstAtFar(ServerLevel level, ParticleOptions type, Vec3 pos,
                                  int count, double spread, double speed) {
        if (!active() || level == null || type == null) return;
        int c = scaled(count);
        for (ServerPlayer p : level.players()) {
            level.sendParticles(p, type, true, pos.x, pos.y, pos.z, c, spread, spread, spread, speed);
        }
    }

    /** 服务端沿建筑包围盒周长一圈撒原版粒子（拆除/崩塌等）。 */
    public static void burstRing(ServerLevel level, ParticleOptions type, BoundingBox bounds,
                                 int count, double spread, double speed) {
        if (!active() || level == null || type == null) return;
        int samples = Math.max(6, count / 5);
        List<Vec3> pts = perimeterPoints(bounds, samples, 0.0);
        int c = scaled(count);
        int per = Math.max(1, c / pts.size());
        for (Vec3 p : pts) {
            level.sendParticles(type, p.x, p.y, p.z, per, spread, spread, spread, speed);
        }
    }

    // ── 染色粒子 ──

    /** 服务端触发染色粒子：发 ParticleBurstPacket 给追踪玩家，客户端撒运动点粒子。 */
    public static void burstColored(ServerLevel level, Vec3 pos,
                                    float r, float g, float b,
                                    int count, float size, int lifetime, boolean vertical) {
        if (!active() || level == null) return;
        PacketDistributor.sendToPlayersTrackingChunk(level, new ChunkPos(BlockPos.containing(pos)),
                new ParticleBurstPacket(pos, r, g, b, scaled(count), size, lifetime, vertical));
    }

    // ── 庆祝 ──

    /** 单个爆花：彩色爆花（不用原版 EXPLOSION_EMITTER，爆炸闪屏太大）。 */
    private static void burstOne(ServerLevel level, Vec3 base,
                                 double scatterX, double scatterY, double scatterZ) {
        double bx = base.x + (level.random.nextDouble() - 0.5) * scatterX;
        double by = base.y + level.random.nextDouble() * scatterY;
        double bz = base.z + (level.random.nextDouble() - 0.5) * scatterZ;
        float[] col = FIREWORK_COLORS[level.random.nextInt(FIREWORK_COLORS.length)];
        burstColored(level, new Vec3(bx, by, bz), col[0], col[1], col[2], 18, 0.16f, 30, false);
    }

    /** 单点烟花庆祝：小镇升级/袭击胜利等在指定点上方放 bursts 发烟花。 */
    public static void celebrateAt(ServerLevel level, Vec3 pos, int bursts) {
        if (!active() || level == null) return;
        for (int i = 0; i < bursts; i++) {
            burstOne(level, pos.add(0, 1.0, 0), 2.0, 3.0, 2.0);
        }
    }

    /** 建筑包围盒周长一圈烟花庆祝：totalBursts 均布到包围盒椭圆周。建筑建成/创建用。 */
    public static void celebrateRing(ServerLevel level, BoundingBox bounds, int totalBursts) {
        if (!active() || level == null) return;
        int samples = Math.max(4, totalBursts);
        List<Vec3> pts = perimeterPoints(bounds, samples, 1.0);
        for (int i = 0; i < totalBursts; i++) {
            burstOne(level, pts.get(i % pts.size()), 1.5, 2.0, 1.5);
        }
    }
}
