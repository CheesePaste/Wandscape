package com.wsteam.wandscape.content.magic.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * MagicCircleSpec JSON 契约的纯数据镜像，与 Web 编辑器 {@code src/spec.ts} 一一对应。
 * 两端只画同一份几何 spec，互不搬渲染管线。解析时套用编辑器 {@code normalizeSpec} 的默认值，
 * 保证 MC 端渲染与编辑器预览一致。
 *
 * <p>字段与 {@code architecture/magic/magic-circles.md} 一致；元素用单一 {@link Element} record +
 * {@link ElementType} 判别（编辑器就是单一 union，比 sealed 层级更贴合 fromJson/发射器）。
 */
public final class MagicCircleSpec {

    public final String id;
    public final int durationTicks;
    public final double height;
    public final List<Element> elements;

    public MagicCircleSpec(String id, int durationTicks, double height, List<Element> elements) {
        this.id = id;
        this.durationTicks = durationTicks;
        this.height = height;
        this.elements = elements;
    }

    public static MagicCircleSpec fromJson(String id, JsonElement json) {
        JsonObject obj = json.getAsJsonObject();
        String specId = getString(obj, "id", id);
        int durationTicks = Math.max(1, (int) Math.round(getDouble(obj, "duration_ticks", 120)));
        double height = getDouble(obj, "height", 0.1);
        List<Element> elements = new ArrayList<>();
        if (obj.has("elements")) {
            JsonArray arr = obj.getAsJsonArray("elements");
            for (JsonElement el : arr) {
                Element parsed = Element.fromJson(el.getAsJsonObject());
                if (parsed != null) elements.add(parsed);
            }
        }
        return new MagicCircleSpec(specId, durationTicks, height, elements);
    }

    // ── 动画模型 ──

    public enum Easing { LINEAR, SMOOTHSTEP }

    public record Keyframe(double t, double v) {}

    public record Anim(List<Keyframe> scale, List<Keyframe> alpha, List<Keyframe> rotation, Easing easing) {
        public static final Anim DEFAULT = new Anim(
                List.of(new Keyframe(0, 1)),
                List.of(new Keyframe(0, 1)),
                List.of(),
                Easing.LINEAR);

        public static Anim fromJson(JsonElement json) {
            if (json == null || !json.isJsonObject()) return DEFAULT;
            JsonObject a = json.getAsJsonObject();
            Easing easing = "smoothstep".equals(getString(a, "easing", "linear"))
                    ? Easing.SMOOTHSTEP : Easing.LINEAR;
            return new Anim(
                    parseCurve(a.get("scale"), List.of(new Keyframe(0, 1))),
                    parseCurve(a.get("alpha"), List.of(new Keyframe(0, 1))),
                    parseCurve(a.get("rotation"), List.of()),
                    easing);
        }
    }

    /** 关键帧 [[t,v],...] 解析；空/缺失返回 {@code fallback}。 */
    static List<Keyframe> parseCurve(@Nullable JsonElement el, List<Keyframe> fallback) {
        if (el == null || !el.isJsonArray()) return fallback;
        JsonArray arr = el.getAsJsonArray();
        List<Keyframe> out = new ArrayList<>();
        for (JsonElement kf : arr) {
            if (!kf.isJsonArray() || kf.getAsJsonArray().size() < 2) continue;
            JsonArray pair = kf.getAsJsonArray();
            try {
                out.add(new Keyframe(pair.get(0).getAsDouble(), pair.get(1).getAsDouble()));
            } catch (Exception ignored) {
            }
        }
        return out.isEmpty() ? fallback : out;
    }

    /** 关键帧曲线采样：归一化 t [0,1]，空曲线返回 fallback（scale/alpha 默认 1，rotation 默认 0）。 */
    public static double sampleCurve(List<Keyframe> curve, double t, double fallback, Easing easing) {
        if (curve == null || curve.isEmpty()) return fallback;
        if (curve.size() == 1) return curve.get(0).v();
        double tc = clamp(t, 0.0, 1.0);
        Keyframe first = curve.get(0);
        if (tc <= first.t()) return first.v();
        Keyframe last = curve.get(curve.size() - 1);
        if (tc >= last.t()) return last.v();
        for (int i = 0; i < curve.size() - 1; i++) {
            Keyframe k0 = curve.get(i);
            Keyframe k1 = curve.get(i + 1);
            if (tc >= k0.t() && tc <= k1.t()) {
                double span = k1.t() - k0.t();
                double f = span == 0 ? 0 : (tc - k0.t()) / span;
                if (easing == Easing.SMOOTHSTEP) f = f * f * (3 - 2 * f);
                return k0.v() + (k1.v() - k0.v()) * f;
            }
        }
        return last.v();
    }

    /** 元素局部时间：全局 t < start → null（未激活）；否则 (t-start)/(1-start) 钳到 [0,1]。 */
    @Nullable
    public static Double localTime(double start, double t) {
        if (t < start) return null;
        double denom = 1 - start;
        if (denom <= 0) return 0.0;
        return clamp((t - start) / denom, 0.0, 1.0);
    }

    // ── 元素 ──

    public enum ElementType { RING, ARC, POLYGON, STAR, GLYPH }

    /**
     * 单个元素（编辑器 union 镜像）。全部字段已归一化（套默认值），渲染端可放心使用。
     */
    public record Element(
            ElementType type,
            double[] axis,
            double radius,
            String particle,
            List<String> particles,
            @Nullable String color,
            double rotationOffsetDeg,
            double rotateSpeed,
            double start,
            Anim anim,
            String mode,
            double density,
            int trailTicks,
            double yOffset,
            int intervalTicks,
            double arcStartDeg,
            double arcSweepDeg,
            int sides,
            int points,
            double innerRatio,
            int count,
            String sprite,
            double glyphScale,
            double headScale,
            double tailScale
    ) {
        /** 生效的粒子 id 列表：显式 particles 优先，否则 [particle]。 */
        public List<String> particleIds() {
            return particles != null && !particles.isEmpty() ? particles : List.of(particle);
        }

        public boolean continuous() {
            return "continuous".equals(mode);
        }

        public static Element fromJson(JsonObject e) {
            String typeStr = getString(e, "type", "ring");
            ElementType type;
            try {
                type = ElementType.valueOf(typeStr.toUpperCase());
            } catch (IllegalArgumentException ex) {
                type = ElementType.RING;
            }

            boolean glyph = type == ElementType.GLYPH;
            double[] axis = parseAxis(e.get("axis"));
            double radius = Math.max(0, getDouble(e, "radius", 1));
            String particle = getString(e, "particle", glyph ? "enchant" : "glow");
            List<String> particles = parseStringArray(e.get("particles"));
            String color = validHexColor(getString(e, "color", null));
            double rotationOffsetDeg = getDouble(e, "rotation_offset_deg", 0);
            double rotateSpeed = getDouble(e, "rotate_speed", 0);
            double start = clamp(getDouble(e, "start", 0), 0.0, 1.0);
            Anim anim = Anim.fromJson(e.get("anim"));

            if (glyph) {
                return new Element(type, axis, radius, particle, particles, color,
                        rotationOffsetDeg, rotateSpeed, start, anim,
                        "beads", 1.5, Math.max(1, (int) Math.round(getDouble(e, "trail_ticks", 8))),
                        0, 0,
                        0, 360, 3, 2, 0.4,
                        Math.max(1, (int) Math.round(getDouble(e, "count", 1))),
                        getString(e, "sprite", "rune"),
                        Math.max(0.05, getDouble(e, "scale", 0.3)),
                        Math.max(0.1, getDouble(e, "head_scale", 1.35)),
                        Math.max(0.05, getDouble(e, "tail_scale", 0.35)));
            }

            String mode = "continuous".equals(getString(e, "mode", "beads")) ? "continuous" : "beads";
            double density = getDouble(e, "density", 1.5);
            int trailTicks = Math.max(1, (int) Math.round(getDouble(e, "trail_ticks", 10)));
            double yOffset = getDouble(e, "y_offset", 0);
            int intervalTicks = (int) Math.round(getDouble(e, "interval_ticks", 0));
            if (intervalTicks < 1) intervalTicks = 0;

            double arcStartDeg = getDouble(e, "arc_start_deg", 0);
            double arcSweepDeg = getDouble(e, "arc_sweep_deg", 360);
            int sides = Math.max(3, (int) Math.round(getDouble(e, "sides", 6)));
            int points = Math.max(2, (int) Math.round(getDouble(e, "points", 5)));
            double innerRatio = clamp(getDouble(e, "inner_ratio", 0.4), 0.05, 1.0);

            return new Element(type, axis, radius, particle, particles, color,
                    rotationOffsetDeg, rotateSpeed, start, anim,
                    mode, density, trailTicks, yOffset, intervalTicks,
                    arcStartDeg, arcSweepDeg, sides, points, innerRatio,
                    1, "rune", 0.3, 1.35, 0.35);
        }
    }

    // ── 小工具 ──

    static double clamp(double v, double min, double max) {
        return Math.min(max, Math.max(min, v));
    }

    @Nullable
    static double[] parseAxis(@Nullable JsonElement el) {
        if (el != null && el.isJsonArray() && el.getAsJsonArray().size() == 3) {
            JsonArray arr = el.getAsJsonArray();
            try {
                double x = arr.get(0).getAsDouble();
                double y = arr.get(1).getAsDouble();
                double z = arr.get(2).getAsDouble();
                if (x != 0 || y != 0 || z != 0) return new double[]{x, y, z};
            } catch (Exception ignored) {
            }
        }
        return new double[]{0, 1, 0};
    }

    @Nullable
    static List<String> parseStringArray(@Nullable JsonElement el) {
        if (el == null || !el.isJsonArray()) return null;
        JsonArray arr = el.getAsJsonArray();
        List<String> out = new ArrayList<>();
        for (JsonElement s : arr) {
            if (s.isJsonPrimitive() && s.getAsString().length() > 0) {
                out.add(s.getAsString());
            }
        }
        return out.isEmpty() ? null : out;
    }

    /** 校验并返回合法 hex 颜色；否则 null。 */
    @Nullable
    static String validHexColor(@Nullable String hex) {
        if (hex != null && hex.length() == 7 && hex.charAt(0) == '#') {
            for (int i = 1; i < 7; i++) {
                char c = hex.charAt(i);
                boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
                if (!ok) return null;
            }
            return hex;
        }
        return null;
    }

    static String getString(JsonObject o, String key, String def) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : def;
    }

    static double getDouble(JsonObject o, String key, double def) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsDouble() : def;
    }
}
