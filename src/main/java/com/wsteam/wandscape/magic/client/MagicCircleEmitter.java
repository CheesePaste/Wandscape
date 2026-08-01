package com.wsteam.wandscape.magic.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.wsteam.wandscape.magic.data.MagicCircleSpec;
import com.wsteam.wandscape.magic.data.MagicCircleSpec.Easing;
import com.wsteam.wandscape.magic.data.MagicCircleSpec.Element;
import com.wsteam.wandscape.magic.data.MagicCircleSpec.ElementType;
import com.wsteam.wandscape.magic.internal.MagicCircleLoader;
import com.wsteam.wandscape.shared.log.Log;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.phys.Vec3;

/**
 * 客户端魔法阵发射器：持有一批 {@link ActiveCircle}，每 tick 采样 spec 动画曲线，
 * 沿当前几何位置撒粒子。元素几何/排布/曲线采样与 Web 编辑器（geometry.ts / particles.ts / anim.ts）
 * 完全一致——两端画同一份 spec。axis 由施放方传入（法杖朝向），覆盖 spec 元素 axis，使法阵垂直于法杖。
 */
public final class MagicCircleEmitter {

    private static final String TAG = "MagicEmitter";

    private static final Map<UUID, ActiveCircle> ACTIVE = new HashMap<>();

    private MagicCircleEmitter() {}

    /** 原版粒子风格 id → ParticleType（color 不生效，贴图本色；不可控尺寸/寿命）。 */
    private static final Map<String, ParticleOptions> VANILLA = Map.ofEntries(
            Map.entry("flame", ParticleTypes.FLAME),
            Map.entry("soul", ParticleTypes.SOUL),
            Map.entry("endRod", ParticleTypes.END_ROD),
            Map.entry("portal", ParticleTypes.PORTAL),
            Map.entry("enchant", ParticleTypes.ENCHANT),
            Map.entry("enchanted_hit", ParticleTypes.ENCHANTED_HIT),
            Map.entry("spark", ParticleTypes.ELECTRIC_SPARK),
            Map.entry("crit", ParticleTypes.CRIT),
            Map.entry("smoke", ParticleTypes.SMOKE),
            Map.entry("large_smoke", ParticleTypes.LARGE_SMOKE),
            Map.entry("cloud", ParticleTypes.CLOUD),
            Map.entry("note", ParticleTypes.NOTE),
            Map.entry("white_ash", ParticleTypes.WHITE_ASH));

    /** 自定义可染色风格的基础 quadSize（半宽）。glow/ember 走魔改点粒子。 */
    private static float styleQuadSize(String styleId) {
        return "ember".equals(styleId) ? 0.08f : 0.12f;
    }

    /** 注册一个施放中的法阵。axis 为法阵平面法线（法杖朝向），覆盖 spec 元素 axis。 */
    public static void add(ClientLevel level, UUID effectId, Vec3 pos, Vec3 axis, String circleId) {
        MagicCircleSpec spec = MagicCircleLoader.getSpec(circleId);
        if (spec == null) {
            Log.warn(TAG, "unknown magic circle id: {}", circleId);
            return;
        }
        ACTIVE.put(effectId, new ActiveCircle(level, spec, pos, axis));
    }

    /** ClientTickEvent.Post：推进所有活跃法阵，到 t≥1 移除。 */
    public static void tick() {
        var mc = Minecraft.getInstance();
        if (mc.level == null) {
            ACTIVE.clear();
            return;
        }
        if (ACTIVE.isEmpty()) return;
        Iterator<ActiveCircle> it = ACTIVE.values().iterator();
        while (it.hasNext()) {
            ActiveCircle c = it.next();
            c.tick();
            if (c.done) it.remove();
        }
    }

    // ── 单个活跃法阵 ──

    private static final class ActiveCircle {
        final ClientLevel level;
        final MagicCircleSpec spec;
        final Vec3 pos;
        final Vec3 axis;
        final long startTick;
        final int durationTicks;
        boolean done;

        ActiveCircle(ClientLevel level, MagicCircleSpec spec, Vec3 pos, Vec3 axis) {
            this.level = level;
            this.spec = spec;
            this.pos = pos;
            this.axis = axis.lengthSqr() < 1e-6 ? new Vec3(0, 1, 0) : axis.normalize();
            this.startTick = level.getGameTime();
            this.durationTicks = spec.durationTicks;
        }

        void tick() {
            long now = level.getGameTime();
            int elapsed = (int) (now - startTick);
            if (elapsed >= durationTicks) {
                done = true;
                return;
            }
            double t = (double) elapsed / durationTicks;
            for (int i = 0; i < spec.elements.size(); i++) {
                spawnElement(spec.elements.get(i), i, t, elapsed);
            }
        }

        private void spawnElement(Element el, int index, double t, int T) {
            double start = el.start();
            Double lt = MagicCircleSpec.localTime(start, t);
            if (lt == null) return;
            Easing easing = el.anim().easing();
            double alphaEmit = MagicCircleSpec.sampleCurve(el.anim().alpha(), lt, 1, easing);
            if (alphaEmit <= 0.001) return;
            double radiusScale = Math.max(0, MagicCircleSpec.sampleCurve(el.anim().scale(), lt, 1, easing));
            double animRot = MagicCircleSpec.sampleCurve(el.anim().rotation(), lt, 0, easing);
            double phase = el.rotationOffsetDeg() + (el.rotateSpeed() * (T - start * durationTicks)) / 20.0 + animRot;
            // 脉冲门控：interval_ticks 周期 on/off
            if (el.intervalTicks() > 0 && Math.floorDiv(T, el.intervalTicks()) % 2 == 1) return;

            Vec3[] basis = basisOf(axis);
            Vec3 a = basis[0];
            Vec3 b = basis[1];
            double radius = el.radius() * radiusScale;
            float[] tint = tintOf(el);
            List<String> ids = el.particleIds();

            switch (el.type()) {
                case GLYPH -> spawnGlyph(el, a, b, radius, phase, alphaEmit, tint, ids);
                case POLYGON, STAR -> spawnShape(el, a, b, radius, phase, alphaEmit, tint, ids);
                case RING, ARC -> {
                    if (el.continuous()) {
                        spawnContinuous(el, a, b, radius, phase, alphaEmit, tint, ids);
                    } else {
                        spawnBeads(el, a, b, radius, phase, alphaEmit, tint, ids);
                    }
                }
            }
        }
    }

    // ── 各元素类型撒粒子 ──

    /** ring/arc · beads：沿弧长按 density×弧长 均布，全部同时可见，无拖尾淡出。 */
    private static void spawnBeads(Element el, Vec3 a, Vec3 b, double radius, double phase,
                                   double alphaEmit, float[] tint, List<String> ids) {
        double sweep = el.type() == ElementType.ARC ? el.arcSweepDeg() : 360;
        double base = el.type() == ElementType.ARC ? el.arcStartDeg() : 0;
        double arcLen = (Math.abs(sweep) / 360) * 2 * Math.PI * radius;
        if (arcLen <= 0) return;
        int n = Math.max(2, (int) Math.round(el.density() * arcLen));
        for (int i = 0; i < n; i++) {
            double angle = phase + base + (i / (double) n) * sweep;
            Vec3 p = pointOnCircle(a, b, radius, angle, el.yOffset());
            String style = ids.get(i % ids.size());
            emit(el, style, p, tint, styleQuadSize(style), styleQuadSize(style), (float) alphaEmit, false, 3);
        }
    }

    /** ring/arc · continuous：每 tick 沿弧长撒 density×弧长 个拖尾粒子（±0.2 抖动），寿命=trail。 */
    private static void spawnContinuous(Element el, Vec3 a, Vec3 b, double radius, double phase,
                                        double alphaEmit, float[] tint, List<String> ids) {
        double sweep = el.type() == ElementType.ARC ? el.arcSweepDeg() : 360;
        double base = el.type() == ElementType.ARC ? el.arcStartDeg() : 0;
        double arcLen = (Math.abs(sweep) / 360) * 2 * Math.PI * radius;
        if (arcLen <= 0) return;
        int n = Math.max(1, (int) Math.round(el.density() * arcLen));
        int lifetime = el.trailTicks();
        for (int k = 0; k < n; k++) {
            double angle = phase + base + (k / (double) n) * sweep;
            Vec3 p = jitter(pointOnCircle(a, b, radius, angle, el.yOffset()), a, b);
            String style = ids.get(k % ids.size());
            emit(el, style, p, tint, styleQuadSize(style), styleQuadSize(style), (float) alphaEmit, true, lifetime);
        }
    }

    /** polygon/star：beads = 沿周长均布；continuous = 沿各边撒拖尾。 */
    private static void spawnShape(Element el, Vec3 a, Vec3 b, double radius, double phase,
                                   double alphaEmit, float[] tint, List<String> ids) {
        List<double[]> verts = shapeVertices(el);
        int n = verts.size();
        Vec3[] wv = new Vec3[n];
        for (int i = 0; i < n; i++) {
            wv[i] = pointOnCircle(a, b, radius * verts.get(i)[1], phase + verts.get(i)[0], el.yOffset());
        }

        if (!el.continuous()) {
            double perimeter = 0;
            double[] segs = new double[n];
            for (int e = 0; e < n; e++) {
                segs[e] = wv[e].distanceTo(wv[(e + 1) % n]);
                perimeter += segs[e];
            }
            if (perimeter <= 0) return;
            int total = Math.max(2, (int) Math.round(el.density() * perimeter));
            for (int i = 0; i < total; i++) {
                double s = ((i + 0.5) / total) * perimeter;
                int e = 0;
                double acc = 0;
                while (e < n && acc + segs[e] < s) {
                    acc += segs[e];
                    e++;
                }
                Vec3 p0 = wv[e];
                Vec3 p1 = wv[(e + 1) % n];
                double u = segs[e] > 0 ? (s - acc) / segs[e] : 0;
                Vec3 p = p0.add(p1.subtract(p0).scale(u));
                String style = ids.get(i % ids.size());
                emit(el, style, p, tint, styleQuadSize(style), styleQuadSize(style), (float) alphaEmit, false, 3);
            }
            return;
        }

        int lifetime = el.trailTicks();
        int seq = 0;
        for (int e = 0; e < n; e++) {
            Vec3 p0 = wv[e];
            Vec3 p1 = wv[(e + 1) % n];
            Vec3 d = p1.subtract(p0);
            double edgeLen = d.length();
            int ne = Math.max(1, (int) Math.round(el.density() * edgeLen));
            Vec3 dir = d.normalize();
            for (int m = 0; m < ne; m++) {
                double u = (m + 0.5) / ne;
                Vec3 p = jitter(p0.add(dir.scale(edgeLen * u)), a, b);
                String style = ids.get(seq % ids.size());
                emit(el, style, p, tint, styleQuadSize(style), styleQuadSize(style), (float) alphaEmit, true, lifetime);
                seq++;
            }
        }
    }

    /** glyph：count 个符文位，彗星头(head_scale×scale)→尾(tail_scale×scale)缩放 + 淡出。 */
    private static void spawnGlyph(Element el, Vec3 a, Vec3 b, double radius, double phase,
                                   double alphaEmit, float[] tint, List<String> ids) {
        int count = el.count();
        int lifetime = el.trailTicks();
        float head = (float) (el.glyphScale() * el.headScale() / 2);
        float tail = (float) (el.glyphScale() * el.tailScale() / 2);
        for (int i = 0; i < count; i++) {
            double angle = phase + (i * 360.0) / count;
            Vec3 p = jitter(pointOnCircle(a, b, radius, angle, 0), a, b);
            String style = ids.get(i % ids.size());
            emit(el, style, p, tint, head, tail, (float) alphaEmit, true, lifetime);
        }
    }

    // ── 粒子发射 ──

    /** 原版风格 → addParticle（本色/自控尺寸）；自定义（glow/ember/未知）→ 魔改点粒子（可染色、可控尺寸）。 */
    private static void emit(Element el, String styleId, Vec3 p, float[] tint,
                             float startSize, float endSize, float alpha, boolean fadeOut, int lifetime) {
        ParticleOptions vanilla = VANILLA.get(styleId);
        if (vanilla != null) {
            if (level() != null) level().addParticle(vanilla, p.x, p.y, p.z, 0, 0, 0);
            return;
        }
        MagicCircleDotParticle.spawn(level(), p.x, p.y, p.z,
                tint[0], tint[1], tint[2], startSize, endSize, alpha, fadeOut, lifetime);
    }

    private static ClientLevel level() {
        return Minecraft.getInstance().level;
    }

    // ── 几何（镜像 geometry.ts）──

    /** 由法线 n 构造正交基 a = normalize(cross(n,m))，b = cross(n,a)。 */
    private static Vec3[] basisOf(Vec3 n) {
        Vec3 nn = n.normalize();
        Vec3 m = Math.abs(nn.dot(new Vec3(1, 0, 0))) > 0.99 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0);
        Vec3 a = nn.cross(m).normalize();
        Vec3 b = nn.cross(a);
        return new Vec3[]{a, b};
    }

    /** 平面内角度 deg 处的圆周点（yOffset 加在世界 Y 上，同编辑器 pointAt）。 */
    private static Vec3 pointOnCircle(Vec3 a, Vec3 b, double radius, double deg, double yOffset) {
        double t = Math.toRadians(deg);
        double c = Math.cos(t);
        double s = Math.sin(t);
        return new Vec3(a.x * c * radius + b.x * s * radius,
                a.y * c * radius + b.y * s * radius + yOffset,
                a.z * c * radius + b.z * s * radius);
    }

    /** polygon/star 顶点表（极角+半径比例），star 首外顶点 180° 起。 */
    private static List<double[]> shapeVertices(Element el) {
        List<double[]> verts = new ArrayList<>();
        if (el.type() == ElementType.POLYGON) {
            int n = el.sides();
            for (int i = 0; i < n; i++) verts.add(new double[]{i * 360.0 / n, 1.0});
        } else {
            int p = el.points();
            double inner = el.innerRatio();
            for (int i = 0; i < 2 * p; i++) {
                verts.add(new double[]{180.0 + (i * 180.0) / p, i % 2 == 0 ? 1.0 : inner});
            }
        }
        return verts;
    }

    /** 平面内 ±0.2 抖动。 */
    private static Vec3 jitter(Vec3 p, Vec3 a, Vec3 b) {
        double jx = (level().random.nextDouble() * 2 - 1) * 0.2;
        double jy = (level().random.nextDouble() * 2 - 1) * 0.2;
        return p.add(a.scale(jx)).add(b.scale(jy));
    }

    /** "#RRGGBB" → (r,g,b)；null/非法 → 白。 */
    private static float[] tintOf(Element el) {
        String hex = el.color();
        if (hex == null || hex.length() != 7 || hex.charAt(0) != '#') return new float[]{1, 1, 1};
        try {
            int v = Integer.parseInt(hex.substring(1), 16);
            return new float[]{((v >> 16) & 0xFF) / 255f, ((v >> 8) & 0xFF) / 255f, (v & 0xFF) / 255f};
        } catch (NumberFormatException e) {
            return new float[]{1, 1, 1};
        }
    }
}
