import type { ArcElement, Element, GlyphElement, PolygonElement, RingElement, StarElement, Vec3 } from './spec';
import { AXIS_GROUND } from './spec';
import { normalize, orthonormalBasis, pointAt, rad, shapeVertices } from './geometry';
import { elementLocalTime, sampleCurve } from './anim';
import { mcParticleStyle, PARTICLE_STYLE_IDS, type McParticleStyle } from './mc-particles';
import { TEXTURE_NAMES } from './mc-textures';

/** 粒子风格的元信息——面板提示 / 下拉列表用，数据本体在 mc-particles.ts。 */
export interface ParticleDef {
  label: string;
  /** true = 原版粒子（color 不生效，贴图本色）；false = 模组自定义可染色。 */
  vanilla: boolean;
  /** true = 贴图可被元素 color 染色。 */
  tintable: boolean;
  /** 首帧贴图名（面板预览用）。 */
  frame: string;
}

export const PARTICLE_IDS = PARTICLE_STYLE_IDS;

export function particleDefFor(id?: string): ParticleDef {
  const s = mcParticleStyle(id);
  if (s) return { label: s.label, vanilla: s.mcId !== null, tintable: s.tintable, frame: s.frames[0] };
  return { label: id ?? 'unknown', vanilla: false, tintable: false, frame: 'glow' };
}

export interface LiveParticle {
  pos: Vec3;
  /** 渲染宽（格）= 2 × quadSize(age)。 */
  size: number;
  /** 帧索引（仿 MC setSpriteFromAge）。 */
  frame: number;
  /** 贴图帧名；'' = 无贴图（渲染层画回退圆点，未知 id 用）。 */
  texture: string;
  /** 最终透明度（曲线 alpha × 拖尾淡出）。 */
  alpha: number;
  /** 染色色值 #RRGGBB；null = 贴图本色。 */
  tint: string | null;
}

/** 元素粒子 id 列表：显式 particles 优先，否则 [particle]，兜底 [glow]。 */
function particleIds(el: Element): string[] {
  const p = el.particles;
  if (Array.isArray(p)) {
    const clean = p.filter((s): s is string => typeof s === 'string' && s.length > 0);
    if (clean.length > 0) return clean;
  }
  return el.particle ? [el.particle] : ['glow'];
}

const MAX_PER_ELEMENT = 1500;
/** 未知粒子 id 的回退视觉尺寸（渲染宽，格）。 */
const FALLBACK_SIZE = 0.28;

/**
 * 符文贴图解析：粒子风格帧 → 合法 sprite → 兜底 enchant 帧。恒返回可用帧名，
 * 绝不落回占位圆点（texture=''）。age/trail 用于仿 MC setSpriteFromAge 推进帧。
 */
function resolveGlyphFrame(
  pstyle: McParticleStyle | undefined,
  glyphSprite: string,
  age: number,
  trail: number,
): { frame: number; texture: string } {
  if (pstyle && pstyle.frames.length > 0) {
    const frame = Math.min(pstyle.frames.length - 1, Math.floor((age / Math.max(1, trail)) * pstyle.frames.length));
    return { frame, texture: pstyle.frames[frame] };
  }
  if (glyphSprite) return { frame: 0, texture: glyphSprite };
  const fallback = mcParticleStyle('enchant');
  const frames = fallback && fallback.frames.length > 0 ? fallback.frames : ['glow'];
  const frame = Math.min(frames.length - 1, Math.floor((age / Math.max(1, trail)) * frames.length));
  return { frame, texture: frames[frame] };
}

/** 确定性伪随机 [0,1)。 */
function hash01(a: number, b: number, c: number): number {
  let h = (Math.imul(a, 374761393) + Math.imul(b, 668265263) + Math.imul(c, 1442695041)) | 0;
  h = Math.imul(h ^ (h >>> 13), 1274126177);
  h ^= h >>> 16;
  return ((h >>> 0) % 1000) / 1000;
}

/**
 * 计算元素在全局归一化时刻 t 的存活粒子（模拟契约粒子模型）：
 * 每 tick 沿弧长撒 density×弧长 个、存活 trail_ticks、带 ±0.2 格抖动。
 * 多粒子 `particles` 按发射位置轮流（第 k 个用 particles[k % n]）。
 * glyph 走独立渲染器 computeGlyphParticles（符文贴图恒可解析，无占位）。
 */
export function computeLiveParticles(
  el: Element,
  t: number,
  durTicks: number,
  elIndex: number,
): LiveParticle[] {
  const dur = Math.max(1, durTicks);
  const T = t * dur;
  if (el.type === 'glyph') return computeGlyphParticles(el, t, dur, elIndex);
  // 脉冲门控：interval_ticks 周期 on/off（呼吸节奏）
  if (el.interval_ticks) {
    if (Math.floor(T / el.interval_ticks) % 2 === 1) return [];
  }
  if (el.type === 'polygon' || el.type === 'star') {
    return computeShape(el, t, dur, elIndex);
  }
  if ((el.mode ?? 'beads') === 'beads') {
    return computeBeads(el, t, dur, elIndex);
  }
  // ring/arc continuous：每 tick 沿弧长撒 density×弧长 个拖尾粒子
  const trail = Math.round(el.trail_ticks ?? 10);
  const startTick = Math.max(0, Math.floor(T - trail) + 1);
  const endTick = Math.floor(T);
  if (endTick < startTick) return [];

  const axis = normalize(el.axis ?? AXIS_GROUND);
  const { a, b } = orthonormalBasis(axis);
  const anim = el.anim;
  const easing = anim?.easing;
  const start = el.start ?? 0;
  const ids = particleIds(el);
  const color = el.color ?? null;
  const jitter = 0.2;
  const out: LiveParticle[] = [];

  for (let Tp = startTick; Tp <= endTick; Tp++) {
    const lt = elementLocalTime(start, Tp / dur);
    if (lt === null) continue;
    const alphaEmit = sampleCurve(anim?.alpha, lt, 1, easing);
    if (alphaEmit <= 0.001) continue;
    const radiusScale = Math.max(0, sampleCurve(anim?.scale, lt, 1, easing));
    const animRot = sampleCurve(anim?.rotation, lt, 0, easing);
    const phase =
      (el.rotation_offset_deg ?? 0) +
      ((el.rotate_speed ?? 0) * (Tp - start * dur)) / 20 +
      animRot;

    const radius = el.radius * radiusScale;
    const sweep = el.type === 'arc' ? el.arc_sweep_deg ?? 360 : 360;
    const arcLen = (Math.abs(sweep) / 360) * 2 * Math.PI * radius;
    const N = Math.max(1, Math.round((el.density ?? 1.5) * arcLen));
    const base = el.type === 'arc' ? el.arc_start_deg ?? 0 : 0;
    const angles: number[] = [];
    for (let k = 0; k < N; k++) angles.push(phase + base + (k / N) * sweep);

    const age = T - Tp;
    const ageFade = trail > 0 ? Math.max(0, 1 - age / trail) : 1;
    const yOff = el.y_offset ?? 0;

    for (let k = 0; k < angles.length; k++) {
      const pid = ids[k % ids.length];
      const pstyle = mcParticleStyle(pid);
      let size: number;
      let texture: string;
      let frame = 0;
      let tint: string | null;
      if (pstyle) {
        // quadSize 是半宽，渲染宽 = 2×；lifetime 用拖尾 tick，曲线铺满可见生命
        size = 2 * pstyle.sizeOf(age, trail);
        frame = Math.min(pstyle.frames.length - 1, Math.floor((age / trail) * pstyle.frames.length));
        texture = pstyle.frames[frame];
        tint = pstyle.tintable ? color : null;
      } else {
        size = FALLBACK_SIZE;
        texture = '';
        tint = null;
      }

      const r = rad(angles[k]);
      const jx = (hash01(Tp, elIndex * 131 + k * 7, 11) - 0.5) * 2 * jitter;
      const jy = (hash01(Tp, elIndex * 131 + k * 7, 991) - 0.5) * 2 * jitter;
      const c = Math.cos(r);
      const s = Math.sin(r);
      out.push({
        pos: [
          a[0] * c * radius + b[0] * s * radius + a[0] * jx + b[0] * jy,
          a[1] * c * radius + b[1] * s * radius + a[1] * jx + b[1] * jy + yOff,
          a[2] * c * radius + b[2] * s * radius + a[2] * jx + b[2] * jy,
        ],
        size,
        frame,
        texture,
        alpha: alphaEmit * ageFade,
        tint,
      });
    }
  }

  if (out.length > MAX_PER_ELEMENT) {
    const step = Math.ceil(out.length / MAX_PER_ELEMENT);
    return out.filter((_, i) => i % step === 0);
  }
  return out;
}

/**
 * glyph 符文专用渲染器：count 个符文位均布在圆周，每个符文带彗星拖尾
 * （头部 head_scale×，随 age 线性缩至 tail_scale× 并淡出）。贴图经
 * resolveGlyphFrame 恒可解析（粒子 → sprite → enchant 兜底），绝不占位。
 */
function computeGlyphParticles(
  el: GlyphElement,
  t: number,
  dur: number,
  elIndex: number,
): LiveParticle[] {
  const T = t * dur;
  const trail = Math.max(1, Math.round(el.trail_ticks ?? 8));
  const startTick = Math.max(0, Math.floor(T - trail) + 1);
  const endTick = Math.floor(T);
  if (endTick < startTick) return [];

  const axis = normalize(el.axis ?? AXIS_GROUND);
  const { a, b } = orthonormalBasis(axis);
  const anim = el.anim;
  const easing = anim?.easing;
  const start = el.start ?? 0;
  const ids = particleIds(el);
  const color = el.color ?? null;
  const glyphSprite =
    typeof el.sprite === 'string' && TEXTURE_NAMES.has(el.sprite) ? el.sprite : '';
  const scale = el.scale ?? 0.3;
  const head = el.head_scale ?? 1.35;
  const tail = el.tail_scale ?? 0.35;
  const count = Math.max(1, Math.round(el.count));
  const radius = el.radius;
  const jitter = 0.2;
  const out: LiveParticle[] = [];

  for (let Tp = startTick; Tp <= endTick; Tp++) {
    const lt = elementLocalTime(start, Tp / dur);
    if (lt === null) continue;
    const alphaEmit = sampleCurve(anim?.alpha, lt, 1, easing);
    if (alphaEmit <= 0.001) continue;
    const radiusScale = Math.max(0, sampleCurve(anim?.scale, lt, 1, easing));
    const animRot = sampleCurve(anim?.rotation, lt, 0, easing);
    const phase =
      (el.rotation_offset_deg ?? 0) +
      ((el.rotate_speed ?? 0) * (Tp - start * dur)) / 20 +
      animRot;
    const r = radius * radiusScale;

    const age = T - Tp;
    const ageFade = Math.max(0, 1 - age / trail);
    const taper = Math.min(1, age / trail);
    const size = scale * (head - (head - tail) * taper);

    for (let i = 0; i < count; i++) {
      const pid = ids[i % ids.length];
      const pstyle = mcParticleStyle(pid);
      const res = resolveGlyphFrame(pstyle, glyphSprite, age, trail);
      const ang = rad(phase + (i * 360) / count);
      const jx = (hash01(Tp, elIndex * 131 + i * 7, 11) - 0.5) * 2 * jitter;
      const jy = (hash01(Tp, elIndex * 131 + i * 7, 991) - 0.5) * 2 * jitter;
      const c = Math.cos(ang);
      const s = Math.sin(ang);
      out.push({
        pos: [
          a[0] * c * r + b[0] * s * r + a[0] * jx + b[0] * jy,
          a[1] * c * r + b[1] * s * r + a[1] * jx + b[1] * jy,
          a[2] * c * r + b[2] * s * r + a[2] * jx + b[2] * jy,
        ],
        size,
        frame: res.frame,
        texture: res.texture,
        alpha: alphaEmit * ageFade,
        tint: color,
      });
    }
  }

  if (out.length > MAX_PER_ELEMENT) {
    const step = Math.ceil(out.length / MAX_PER_ELEMENT);
    return out.filter((_, i) => i % step === 0);
  }
  return out;
}

/**
 * beads 模式（整条线铺满）：沿环/弧按 density×弧长 均布粒子，全部同时可见
 * （无拖尾淡出、无离散亮点）。多粒子按位置轮流。
 */
function computeBeads(
  el: RingElement | ArcElement,
  t: number,
  durTicks: number,
  elIndex: number,
): LiveParticle[] {
  const dur = Math.max(1, durTicks);
  const T = t * dur;
  const start = el.start ?? 0;
  const lt = elementLocalTime(start, t);
  if (lt === null) return [];
  const anim = el.anim;
  const easing = anim?.easing;
  const alphaEmit = sampleCurve(anim?.alpha, lt, 1, easing);
  if (alphaEmit <= 0.001) return [];
  const radiusScale = Math.max(0, sampleCurve(anim?.scale, lt, 1, easing));
  const radius = el.radius * radiusScale;
  const animRot = sampleCurve(anim?.rotation, lt, 0, easing);
  const phase =
    (el.rotation_offset_deg ?? 0) +
    ((el.rotate_speed ?? 0) * (T - start * dur)) / 20 +
    animRot;
  const axis = normalize(el.axis ?? AXIS_GROUND);
  const { a, b } = orthonormalBasis(axis);
  const ids = particleIds(el);
  const color = el.color ?? null;
  const sweep = el.type === 'arc' ? (el.arc_sweep_deg ?? 360) : 360;
  const base = el.type === 'arc' ? (el.arc_start_deg ?? 0) : 0;
  const arcLen = (Math.abs(sweep) / 360) * 2 * Math.PI * radius;
  if (arcLen <= 0) return [];
  const yOff = el.y_offset ?? 0;
  // 密度 = 每格弧长粒子数（默认 1.5，同 continuous），整条线铺满、无拖尾淡出
  const N = Math.max(2, Math.round((el.density ?? 1.5) * arcLen));
  const out: LiveParticle[] = [];
  for (let i = 0; i < N; i++) {
    const angle = phase + base + (i / N) * sweep;
    const pid = ids[i % ids.length];
    const pstyle = mcParticleStyle(pid);
    let size: number;
    let texture: string;
    let frame = 0;
    let tint: string | null;
    if (pstyle) {
      size = 2 * pstyle.quadSize;
      frame = pstyle.frames.length ? Math.floor((T / 20) * 2) % pstyle.frames.length : 0;
      texture = pstyle.frames[frame] ?? '';
      tint = pstyle.tintable ? color : null;
    } else {
      size = FALLBACK_SIZE;
      texture = '';
      tint = null;
    }
    const r = rad(angle);
    const c = Math.cos(r);
    const s = Math.sin(r);
    out.push({
      pos: [
        a[0] * c * radius + b[0] * s * radius,
        a[1] * c * radius + b[1] * s * radius + yOff,
        a[2] * c * radius + b[2] * s * radius,
      ],
      size,
      frame,
      texture,
      alpha: alphaEmit,
      tint,
    });
  }
  if (out.length > MAX_PER_ELEMENT) {
    const step = Math.ceil(out.length / MAX_PER_ELEMENT);
    return out.filter((_, i) => i % step === 0);
  }
  return out;
}

/**
 * polygon/star：beads = 沿周长均布粒子（整条线铺满，无离散亮点）；continuous = 沿各边
 * 每 tick 撒 density×边长 个拖尾粒子。多粒子按位置/发射序号轮流。
 */
function computeShape(
  el: PolygonElement | StarElement,
  t: number,
  dur: number,
  elIndex: number,
): LiveParticle[] {
  const T = t * dur;
  const start = el.start ?? 0;
  const lt = elementLocalTime(start, t);
  if (lt === null) return [];
  const anim = el.anim;
  const easing = anim?.easing;
  const alphaEmit = sampleCurve(anim?.alpha, lt, 1, easing);
  if (alphaEmit <= 0.001) return [];
  const axis = normalize(el.axis ?? AXIS_GROUND);
  const { a, b } = orthonormalBasis(axis);
  const ids = particleIds(el);
  const color = el.color ?? null;
  const yOff = el.y_offset ?? 0;
  const verts = shapeVertices(el);

  // beads：沿周长均布粒子（整条线铺满），间距 = 参考粒子宽（密度 ≥1 无间隔），全部同时可见
  if ((el.mode ?? 'beads') === 'beads') {
    const radiusScale = Math.max(0, sampleCurve(anim?.scale, lt, 1, easing));
    const radius = el.radius * radiusScale;
    const animRot = sampleCurve(anim?.rotation, lt, 0, easing);
    const phase =
      (el.rotation_offset_deg ?? 0) +
      ((el.rotate_speed ?? 0) * (T - start * dur)) / 20 +
      animRot;
    const n = verts.length;
    const wv = verts.map((v) => pointAt(axis, radius * v.radiusRatio, phase + v.deg, yOff));
    const segs: number[] = [];
    let perimeter = 0;
    for (let e = 0; e < n; e++) {
      const p0 = wv[e];
      const p1 = wv[(e + 1) % n];
      const L = Math.hypot(p1[0] - p0[0], p1[1] - p0[1], p1[2] - p0[2]);
      segs.push(L);
      perimeter += L;
    }
    if (perimeter <= 0) return [];
    const total = Math.max(2, Math.round((el.density ?? 1.5) * perimeter));
    const out: LiveParticle[] = [];
    for (let i = 0; i < total; i++) {
      const s = ((i + 0.5) / total) * perimeter;
      let acc = 0;
      let e = 0;
      while (e < n && acc + segs[e] < s) {
        acc += segs[e];
        e++;
      }
      const p0 = wv[e];
      const p1 = wv[(e + 1) % n];
      const u = segs[e] > 0 ? (s - acc) / segs[e] : 0;
      const pid = ids[i % ids.length];
      const pstyle = mcParticleStyle(pid);
      let size: number;
      let texture: string;
      let frame = 0;
      let tint: string | null;
      if (pstyle) {
        size = 2 * pstyle.quadSize;
        frame = pstyle.frames.length ? Math.floor((T / 20) * 2) % pstyle.frames.length : 0;
        texture = pstyle.frames[frame] ?? '';
        tint = pstyle.tintable ? color : null;
      } else {
        size = FALLBACK_SIZE;
        texture = '';
        tint = null;
      }
      out.push({
        pos: [
          p0[0] + (p1[0] - p0[0]) * u,
          p0[1] + (p1[1] - p0[1]) * u,
          p0[2] + (p1[2] - p0[2]) * u,
        ],
        size,
        frame,
        texture,
        alpha: alphaEmit,
        tint,
      });
    }
    if (out.length > MAX_PER_ELEMENT) {
      const step = Math.ceil(out.length / MAX_PER_ELEMENT);
      return out.filter((_, i) => i % step === 0);
    }
    return out;
  }

  const trail = Math.round(el.trail_ticks ?? 10);
  const jitter = 0.2;
  const out: LiveParticle[] = [];
  const startTick = Math.max(0, Math.floor(T - trail) + 1);
  const endTick = Math.floor(T);
  let seq = 0; // 全局发射序号：多粒子轮流
  if (endTick >= startTick) {
    for (let Tp = startTick; Tp <= endTick; Tp++) {
      const ltp = elementLocalTime(start, Tp / dur);
      if (ltp === null) continue;
      const aEmit = sampleCurve(anim?.alpha, ltp, 1, easing);
      if (aEmit <= 0.001) continue;
      // 每 tick 用当刻的缩放/相位重建顶点（旋转拖尾）
      const rs = Math.max(0, sampleCurve(anim?.scale, ltp, 1, easing));
      const rp = el.radius * rs;
      const ar = sampleCurve(anim?.rotation, ltp, 0, easing);
      const pp =
        (el.rotation_offset_deg ?? 0) +
        ((el.rotate_speed ?? 0) * (Tp - start * dur)) / 20 +
        ar;
      const wv = verts.map((v) => pointAt(axis, rp * v.radiusRatio, pp + v.deg, yOff));
      const age = T - Tp;
      const ageFade = trail > 0 ? Math.max(0, 1 - age / trail) : 1;
      for (let e = 0; e < wv.length; e++) {
        const p0 = wv[e];
        const p1 = wv[(e + 1) % wv.length];
        const dx = p1[0] - p0[0];
        const dy = p1[1] - p0[1];
        const dz = p1[2] - p0[2];
        const edgeLen = Math.hypot(dx, dy, dz);
        const N = Math.max(1, Math.round((el.density ?? 1.5) * edgeLen));
        for (let m = 0; m < N; m++, seq++) {
          const u = (m + 0.5) / N;
          const pid = ids[seq % ids.length];
          const pstyle = mcParticleStyle(pid);
          let size: number;
          let texture: string;
          let frame = 0;
          let tint: string | null;
          if (pstyle) {
            size = 2 * pstyle.sizeOf(age, trail);
            frame = Math.min(pstyle.frames.length - 1, Math.floor((age / trail) * pstyle.frames.length));
            texture = pstyle.frames[frame];
            tint = pstyle.tintable ? color : null;
          } else {
            size = FALLBACK_SIZE;
            texture = '';
            tint = null;
          }
          const jx = (hash01(Tp, elIndex * 131 + e * 7 + m, 11) - 0.5) * 2 * jitter;
          const jy = (hash01(Tp, elIndex * 131 + e * 7 + m, 991) - 0.5) * 2 * jitter;
          out.push({
            pos: [
              p0[0] + dx * u + a[0] * jx + b[0] * jy,
              p0[1] + dy * u + a[1] * jx + b[1] * jy,
              p0[2] + dz * u + a[2] * jx + b[2] * jy,
            ],
            size,
            frame,
            texture,
            alpha: aEmit * ageFade,
            tint,
          });
        }
      }
    }
  }
  if (out.length > MAX_PER_ELEMENT) {
    const step = Math.ceil(out.length / MAX_PER_ELEMENT);
    return out.filter((_, i) => i % step === 0);
  }
  return out;
}
