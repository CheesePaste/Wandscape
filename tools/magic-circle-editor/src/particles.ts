import type { Element, Vec3 } from './spec';
import { AXIS_GROUND } from './spec';
import { normalize, orthonormalBasis, rad } from './geometry';
import { elementLocalTime, sampleCurve } from './anim';
import { mcParticleStyle, PARTICLE_STYLE_IDS } from './mc-particles';

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
  /** 贴图帧名；'' = 无贴图（渲染层画回退圆点，glyph/未知 id 用）。 */
  texture: string;
  /** 最终透明度（曲线 alpha × 拖尾淡出）。 */
  alpha: number;
  /** 染色色值 #RRGGBB；null = 贴图本色。 */
  tint: string | null;
}

const GLYPH_TRAIL = 8;
const MAX_PER_ELEMENT = 1500;
/** 未知粒子 id 的回退视觉尺寸（渲染宽，格）。 */
const FALLBACK_SIZE = 0.28;

/** 确定性伪随机 [0,1)。 */
function hash01(a: number, b: number, c: number): number {
  let h = (Math.imul(a, 374761393) + Math.imul(b, 668265263) + Math.imul(c, 1442695041)) | 0;
  h = Math.imul(h ^ (h >>> 13), 1274126177);
  h ^= h >>> 16;
  return ((h >>> 0) % 1000) / 1000;
}

/**
 * 计算元素在全局归一化时刻 t 的存活粒子（模拟契约粒子模型）：
 * beads 模式 = 固定亮点均布成环（有序）；continuous = 每 tick 撒 density×弧长 个、
 * 存活 trail_ticks、带 ±0.2 格抖动。vanilla 粒子用真实贴图 + 移植尺寸曲线；
 * glyph 仍是回退圆点（符文贴图后续）。
 */
export function computeLiveParticles(
  el: Element,
  t: number,
  durTicks: number,
  elIndex: number,
): LiveParticle[] {
  if (el.type !== 'glyph' && (el.mode ?? 'beads') === 'beads') {
    return computeBeads(el, t, durTicks, elIndex);
  }
  const dur = Math.max(1, durTicks);
  const trail = el.type === 'glyph' ? GLYPH_TRAIL : Math.round(el.trail_ticks ?? 10);
  const T = t * dur;
  const startTick = Math.max(0, Math.floor(T - trail) + 1);
  const endTick = Math.floor(T);
  if (endTick < startTick) return [];

  const axis = normalize(el.axis ?? AXIS_GROUND);
  const { a, b } = orthonormalBasis(axis);
  const anim = el.anim;
  const start = el.start ?? 0;
  const style = el.type === 'glyph' ? undefined : mcParticleStyle(el.particle);
  const color = el.color ?? null;
  const tint = el.type === 'glyph' ? color : style?.tintable ? color : null;
  const jitter = 0.2;
  const out: LiveParticle[] = [];

  for (let Tp = startTick; Tp <= endTick; Tp++) {
    const lt = elementLocalTime(start, Tp / dur);
    if (lt === null) continue;
    const alphaEmit = sampleCurve(anim?.alpha, lt, 1);
    if (alphaEmit <= 0.001) continue;
    const radiusScale = Math.max(0, sampleCurve(anim?.scale, lt, 1));
    const animRot = sampleCurve(anim?.rotation, lt, 0);
    const phase =
      (el.rotation_offset_deg ?? 0) +
      ((el.rotate_speed ?? 0) * (Tp - start * dur)) / 20 +
      animRot;

    let angles: number[];
    let radius: number;
    if (el.type === 'glyph') {
      radius = el.radius * radiusScale;
      const count = Math.max(1, Math.round(el.count));
      angles = [];
      for (let i = 0; i < count; i++) angles.push(phase + (i * 360) / count);
    } else {
      radius = el.radius * radiusScale;
      const sweep = el.type === 'arc' ? el.arc_sweep_deg ?? 360 : 360;
      const arcLen = (Math.abs(sweep) / 360) * 2 * Math.PI * radius;
      const N = Math.max(1, Math.round((el.density ?? 1.5) * arcLen));
      const base = el.type === 'arc' ? el.arc_start_deg ?? 0 : 0;
      angles = [];
      for (let k = 0; k < N; k++) angles.push(phase + base + (k / N) * sweep);
    }

    const age = T - Tp;
    const ageFade = trail > 0 ? Math.max(0, 1 - age / trail) : 1;
    const yOff = el.type === 'glyph' ? 0 : el.y_offset ?? 0;

    // 尺寸 / 贴图 / 染色（每 tick 算一次，避免逐粒子重复）
    let size: number;
    let texture: string;
    let frame = 0;
    if (el.type === 'glyph') {
      size = el.scale ?? 0.3;
      texture = '';
    } else if (style) {
      // quadSize 是半宽，渲染宽 = 2×；lifetime 用拖尾 tick，曲线铺满可见生命
      size = 2 * style.sizeOf(age, trail);
      const f = style.frames;
      frame = Math.min(f.length - 1, Math.floor((age / trail) * f.length));
      texture = f[frame];
    } else {
      size = FALLBACK_SIZE;
      texture = '';
    }

    for (let k = 0; k < angles.length; k++) {
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
 * beads 有序模式：固定 `beads` 个持久亮点均布在圆周/弧上，随 rotate_speed 整体旋转，
 * 无随机抖动（有序不糊）。亮点亮度带一圈慢速行进波（shimmer），帧随全局时间慢速推进。
 * 尺寸用粒子基础 quadSize（稳定），不用 continuous 的年龄曲线。
 */
function computeBeads(el: Element, t: number, durTicks: number, elIndex: number): LiveParticle[] {
  if (el.type === 'glyph') return [];
  const dur = Math.max(1, durTicks);
  const T = t * dur;
  const start = el.start ?? 0;
  const lt = elementLocalTime(start, t);
  if (lt === null) return [];
  const anim = el.anim;
  const alphaEmit = sampleCurve(anim?.alpha, lt, 1);
  if (alphaEmit <= 0.001) return [];
  const radiusScale = Math.max(0, sampleCurve(anim?.scale, lt, 1));
  const radius = el.radius * radiusScale;
  const animRot = sampleCurve(anim?.rotation, lt, 0);
  const phase =
    (el.rotation_offset_deg ?? 0) +
    ((el.rotate_speed ?? 0) * (T - start * dur)) / 20 +
    animRot;
  const axis = normalize(el.axis ?? AXIS_GROUND);
  const { a, b } = orthonormalBasis(axis);
  const style = mcParticleStyle(el.particle);
  const tint = style?.tintable ? (el.color ?? null) : null;
  const frames = style ? style.frames : [];
  const count = Math.max(2, Math.round(el.beads ?? 16));
  const sweep = el.type === 'arc' ? (el.arc_sweep_deg ?? 360) : 360;
  const base = el.type === 'arc' ? (el.arc_start_deg ?? 0) : 0;
  const size = style ? 2 * style.quadSize : FALLBACK_SIZE;
  const yOff = el.y_offset ?? 0;
  const frame = frames.length ? Math.floor((T / 20) * 2) % frames.length : 0;
  const texture = style ? frames[frame] : '';
  const out: LiveParticle[] = [];
  for (let i = 0; i < count; i++) {
    const angle = phase + base + (i / count) * sweep;
    const r = rad(angle);
    const c = Math.cos(r);
    const s = Math.sin(r);
    // 行进亮度波：沿环一圈，周期约 3 秒
    const shimmer = 0.82 + 0.18 * Math.sin((i / count) * Math.PI * 2 + T * 0.11);
    out.push({
      pos: [
        a[0] * c * radius + b[0] * s * radius,
        a[1] * c * radius + b[1] * s * radius + yOff,
        a[2] * c * radius + b[2] * s * radius,
      ],
      size,
      frame,
      texture,
      alpha: alphaEmit * shimmer,
      tint,
    });
  }
  return out;
}
