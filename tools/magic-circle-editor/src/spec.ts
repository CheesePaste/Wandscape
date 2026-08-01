/**
 * MagicCircleSpec JSON 契约的 TypeScript 镜像。
 * 字段与 magicarchitecture/magic-circles.md 一一对应——两端只画同一份几何 spec，勿擅自改字段方向。
 * 若需改 schema，先与维护者确认。
 */

export type Vec3 = [number, number, number];

/** 关键帧 [t, v]，t 为归一化时间 [0,1]，线性插值。 */
export type Keyframe = [number, number];
export type Curve = Keyframe[];

export interface Anim {
  scale?: Curve; // 半径/glyph 尺寸倍率，默认 [[0,1]]
  alpha?: Curve; // 粒子透明度倍率，默认 [[0,1]]
  rotation?: Curve; // 窗口内附加旋转角（度），默认 []
  /** 关键帧间插值缓动，默认 'linear'；'smoothstep' 平滑过渡。 */
  easing?: 'linear' | 'smoothstep';
}

/** 三种元素共有的字段。 */
export interface ElementBase {
  /** 法阵平面法线，默认 [0,1,0]（水平地面）；[1,0,0]/[0,0,1] 为竖直环。 */
  axis?: Vec3;
  /** 基础半径（格）。 */
  radius: number;
  /** 粒子风格 id：glow/ember/flame/endRod...（glyph 用 sprite 代替，可不填）。 */
  particle?: string;
  /** 可选十六进制 #RRGGBB。 */
  color?: string;
  /** 初始相位偏移（度），默认 0。 */
  rotation_offset_deg?: number;
  /** 静态旋转速率（度/秒），负=反向，默认 0。 */
  rotate_speed?: number;
  /** 归一化起始时间 [0,1)（级联核心），默认 0。 */
  start?: number;
  /** 关键帧曲线。 */
  anim?: Anim;
}

/** ring/arc 的排布模式：beads = 固定数量亮点均布成环（有序）；continuous = 连续密度拖尾。 */
export type ElementMode = 'beads' | 'continuous';

export interface RingElement extends ElementBase {
  type: 'ring';
  /** 排布模式，默认 'beads'（有序亮点环）。 */
  mode?: ElementMode;
  /** beads 模式：亮点数量（默认按半径 2πr/1.2 格自动推算）。 */
  beads?: number;
  /** continuous 模式：每格弧长每 tick 粒子数，默认 1.5。 */
  density?: number;
  /** continuous 模式：粒子存活 tick = 拖尾长度，默认 10。 */
  trail_ticks?: number;
  /** 多层堆叠的纵向偏移（格），默认 0。 */
  y_offset?: number;
  /** 脉冲：发射 interval_ticks / 停 interval_ticks 循环，默认无。 */
  interval_ticks?: number;
}

export interface ArcElement extends ElementBase {
  type: 'arc';
  /** 起始角度（度），默认 0。 */
  arc_start_deg?: number;
  /** 扫过角度（度），默认 360。 */
  arc_sweep_deg?: number;
  mode?: ElementMode;
  beads?: number;
  density?: number;
  trail_ticks?: number;
  y_offset?: number;
  /** 脉冲：发射 interval_ticks / 停 interval_ticks 循环，默认无。 */
  interval_ticks?: number;
}

export interface PolygonElement extends ElementBase {
  type: 'polygon';
  /** 顶点数（= beads 模式亮点数），≥3。 */
  sides: number;
  mode?: ElementMode;
  density?: number;
  trail_ticks?: number;
  y_offset?: number;
  interval_ticks?: number;
}

export interface StarElement extends ElementBase {
  type: 'star';
  /** 星芒数（外顶点数），≥2；总顶点 = 2×points。 */
  points: number;
  /** 内半径 = radius × inner_ratio，默认 0.4。 */
  inner_ratio: number;
  mode?: ElementMode;
  density?: number;
  trail_ticks?: number;
  y_offset?: number;
  interval_ticks?: number;
}

export interface GlyphElement extends ElementBase {
  type: 'glyph';
  /** 符文个数。 */
  count: number;
  /** 符文贴图 key。 */
  sprite?: string;
  /** 符文渲染尺寸，默认 0.3。 */
  scale?: number;
}

export type Element = RingElement | ArcElement | PolygonElement | StarElement | GlyphElement;

export interface MagicCircleSpec {
  /** 唯一标识，小写 snake_case。 */
  id: string;
  /** 整个法阵总时长（游戏 tick）。 */
  duration_ticks: number;
  /** 法阵中心离地高度（格），默认 0.1。 */
  height?: number;
  elements: Element[];
}

export const AXIS_GROUND: Vec3 = [0, 1, 0];

const clamp01 = (v: number): number => Math.min(1, Math.max(0, v));
const num = (v: unknown, fallback: number): number =>
  typeof v === 'number' && Number.isFinite(v) ? v : fallback;
/** beads 默认数量：按周长 1.2 格间距推算，钳到 [4, 48]。显式 beads（>0）优先。 */
const defaultBeadCount = (radius: number, explicit = 0): number =>
  explicit >= 2 ? Math.round(explicit) : Math.min(48, Math.max(4, Math.round((2 * Math.PI * Math.max(0.1, radius)) / 1.2)));

/** polygon/star 新建默认：自转 + 整个窗口持续扩大 + 淡入，像 ring 示范一样"活"起来。 */
const SHAPE_DEFAULT_ANIM: Anim = {
  scale: [[0, 0], [1, 1.4]],
  alpha: [[0, 0], [0.3, 1], [1, 1]],
};
const SHAPE_DEFAULT_ROTATE = 15;

function normalizeAnim(a: Anim): Anim {
  return {
    scale: Array.isArray(a.scale) ? a.scale : [[0, 1]],
    alpha: Array.isArray(a.alpha) ? a.alpha : [[0, 1]],
    rotation: Array.isArray(a.rotation) ? a.rotation : [],
    easing: a.easing === 'smoothstep' ? 'smoothstep' : 'linear',
  };
}

function isVec3(v: unknown): v is Vec3 {
  return (
    Array.isArray(v) &&
    v.length === 3 &&
    v.every((n) => typeof n === 'number' && Number.isFinite(n))
  );
}

/** 默认初始 spec——一个可见的示范：环 + 弧 + 符文，级联展开。 */
export function createDefaultSpec(): MagicCircleSpec {
  return {
    id: 'new_circle',
    duration_ticks: 120,
    height: 0.1,
    elements: [
      {
        type: 'ring',
        radius: 4.0,
        particle: 'glow',
        color: '#44ccff',
        mode: 'beads',
        beads: 20,
        rotate_speed: 20,
        start: 0,
        anim: {
          scale: [[0, 0], [0.5, 1], [1, 1]],
          alpha: [[0, 0], [0.3, 1], [1, 1]],
        },
      },
      {
        type: 'arc',
        radius: 3.0,
        arc_start_deg: 0,
        arc_sweep_deg: 240,
        particle: 'endRod',
        color: '#ffaa00',
        mode: 'beads',
        beads: 14,
        rotate_speed: -14,
        start: 0.2,
      },
      {
        type: 'glyph',
        radius: 3.5,
        count: 8,
        sprite: 'rune_arcane',
        scale: 0.3,
        color: '#ffdd66',
        rotate_speed: 12,
        start: 0.35,
      },
    ],
  };
}

/** 深拷贝并补齐所有可选字段的默认值，使渲染/面板可放心使用完整字段。 */
export function normalizeSpec(input: MagicCircleSpec): MagicCircleSpec {
  const elements: Element[] = (input.elements ?? []).map((e) => {
    const base: ElementBase = {
      axis: isVec3(e.axis) && !(e.axis[0] === 0 && e.axis[1] === 0 && e.axis[2] === 0)
        ? ([...e.axis] as Vec3)
        : AXIS_GROUND,
      radius: num(e.radius, 1),
      particle: typeof e.particle === 'string' && e.particle ? e.particle : 'glow',
      color: typeof e.color === 'string' ? e.color : undefined,
      rotation_offset_deg: num(e.rotation_offset_deg, 0),
      rotate_speed: num(e.rotate_speed, 0),
      start: clamp01(num(e.start, 0)),
      anim: e.anim && typeof e.anim === 'object' ? normalizeAnim(e.anim) : undefined,
    };

    if (e.type === 'glyph') {
      return {
        ...base,
        type: 'glyph',
        count: Math.max(1, Math.round(num(e.count, 1))),
        sprite: typeof e.sprite === 'string' ? e.sprite : 'rune',
        scale: num(e.scale, 0.3),
      } as GlyphElement;
    }

    const mode = e.mode === 'continuous' ? 'continuous' : 'beads';
    const interval = num(e.interval_ticks, 0);
    const ringLike = {
      mode,
      density: num(e.density, 1.5),
      trail_ticks: Math.max(1, Math.round(num(e.trail_ticks, 10))),
      y_offset: num(e.y_offset, 0),
      ...(interval >= 1 ? { interval_ticks: Math.round(interval) } : {}),
    };

    if (e.type === 'arc') {
      return {
        ...base,
        ...ringLike,
        beads: defaultBeadCount(num(e.radius, 1), num(e.beads, 0)),
        type: 'arc',
        arc_start_deg: num(e.arc_start_deg, 0),
        arc_sweep_deg: num(e.arc_sweep_deg, 360),
      } as ArcElement;
    }

    if (e.type === 'polygon') {
      return {
        ...base,
        ...ringLike,
        type: 'polygon',
        sides: Math.max(3, Math.round(num(e.sides, 6))),
      } as PolygonElement;
    }

    if (e.type === 'star') {
      const ratio = num(e.inner_ratio, 0.4);
      return {
        ...base,
        ...ringLike,
        type: 'star',
        points: Math.max(2, Math.round(num(e.points, 5))),
        inner_ratio: Math.min(1, Math.max(0.05, ratio)),
      } as StarElement;
    }

    return { ...base, ...ringLike, beads: defaultBeadCount(num(e.radius, 1), num(e.beads, 0)), type: 'ring' } as RingElement;
  });

  return {
    id: typeof input.id === 'string' && input.id ? input.id : 'untitled',
    duration_ticks: Math.max(1, Math.round(num(input.duration_ticks, 120))),
    height: num(input.height, 0.1),
    elements,
  };
}

function validAxis(v: unknown): boolean {
  return isVec3(v) && !(v[0] === 0 && v[1] === 0 && v[2] === 0);
}

function validCurve(curve: Curve, where: string, errs: string[]): void {
  if (!Array.isArray(curve)) {
    errs.push(`${where} 必须是关键帧数组`);
    return;
  }
  curve.forEach((kf, i) => {
    if (
      !Array.isArray(kf) ||
      kf.length !== 2 ||
      typeof kf[0] !== 'number' ||
      typeof kf[1] !== 'number' ||
      kf[0] < 0 ||
      kf[0] > 1
    ) {
      errs.push(`${where}[${i}] 必须为 [t, v]，t 在 [0,1] 区间`);
    }
  });
}

/** 返回中文错误消息列表；空数组 = 契约合法。 */
export function validateSpec(spec: MagicCircleSpec): string[] {
  const errs: string[] = [];
  if (!spec || typeof spec !== 'object') return ['spec 不是对象'];

  if (typeof spec.id !== 'string' || !/^[a-z][a-z0-9_]*$/.test(spec.id)) {
    errs.push('id 必须为小写 snake_case（如 fire_summon）');
  }
  if (!Number.isInteger(spec.duration_ticks) || spec.duration_ticks < 1) {
    errs.push('duration_ticks 必须为正整数');
  }
  if (typeof spec.height === 'number' && spec.height < 0) {
    errs.push('height 不能为负');
  }
  if (!Array.isArray(spec.elements)) {
    errs.push('elements 必须是数组');
    return errs;
  }
  if (spec.elements.length === 0) {
    errs.push('elements 至少需要一个元素');
  }

  spec.elements.forEach((el, i) => {
    const where = `elements[${i}]`;
    if (!el || typeof el !== 'object') {
      errs.push(`${where} 不是对象`);
      return;
    }
    if (!['ring', 'arc', 'polygon', 'star', 'glyph'].includes(el.type)) {
      errs.push(`${where}: 未知 type「${String(el.type)}」`);
    }
    if (typeof el.radius !== 'number' || !Number.isFinite(el.radius) || el.radius < 0) {
      errs.push(`${where}: radius 必须为非负数字`);
    }
    if (el.type !== 'glyph' && (typeof el.particle !== 'string' || !el.particle)) {
      errs.push(`${where}: particle 必须为非空字符串`);
    }
    if (
      el.type !== 'glyph' &&
      el.mode !== undefined &&
      el.mode !== 'beads' &&
      el.mode !== 'continuous'
    ) {
      errs.push(`${where}: mode 必须为 beads 或 continuous`);
    }
    if (el.type !== 'glyph' && el.beads !== undefined && (!Number.isInteger(el.beads) || el.beads < 2)) {
      errs.push(`${where}: beads 必须为 ≥2 的整数`);
    }
    if (
      el.type !== 'glyph' &&
      el.interval_ticks !== undefined &&
      (!Number.isInteger(el.interval_ticks) || el.interval_ticks < 1)
    ) {
      errs.push(`${where}: interval_ticks 必须为 ≥1 的整数`);
    }
    if (el.axis !== undefined && !validAxis(el.axis)) {
      errs.push(`${where}: axis 必须为 3 个数字的非零向量`);
    }
    if (el.color !== undefined && !/^#[0-9a-fA-F]{6}$/.test(el.color)) {
      errs.push(`${where}: color 必须为 #RRGGBB 格式`);
    }
    const start = el.start ?? 0;
    if (start < 0 || start >= 1) {
      errs.push(`${where}: start 必须在 [0,1) 区间`);
    }
    if (el.anim) {
      if (el.anim.scale) validCurve(el.anim.scale, `${where}.anim.scale`, errs);
      if (el.anim.alpha) validCurve(el.anim.alpha, `${where}.anim.alpha`, errs);
      if (el.anim.rotation) validCurve(el.anim.rotation, `${where}.anim.rotation`, errs);
      if (el.anim.easing !== undefined && el.anim.easing !== 'linear' && el.anim.easing !== 'smoothstep') {
        errs.push(`${where}.anim.easing 必须为 linear 或 smoothstep`);
      }
    }
    if (el.type === 'arc') {
      const sweep = el.arc_sweep_deg ?? 360;
      if (Math.abs(sweep) > 360) errs.push(`${where}: arc_sweep_deg 绝对值不能超过 360`);
    }
    if (el.type === 'polygon' && (!Number.isInteger(el.sides) || el.sides < 3)) {
      errs.push(`${where}: polygon sides 必须为 ≥3 的整数`);
    }
    if (el.type === 'star') {
      if (!Number.isInteger(el.points) || el.points < 2) {
        errs.push(`${where}: star points 必须为 ≥2 的整数`);
      }
      if (el.inner_ratio <= 0 || el.inner_ratio > 1) {
        errs.push(`${where}: star inner_ratio 必须在 (0,1] 区间`);
      }
    }
    if (el.type === 'glyph') {
      if (!Number.isInteger(el.count) || el.count < 1) {
        errs.push(`${where}: glyph count 必须为正整数`);
      }
    }
  });

  return errs;
}

// ---------------------------------------------------------------------------
// 不可变变更助手（编辑器写回用）
// ---------------------------------------------------------------------------

/** 替换顶层字段（elements 引用不变）。 */
export function withSpec(spec: MagicCircleSpec, patch: Partial<MagicCircleSpec>): MagicCircleSpec {
  return { ...spec, ...patch, elements: spec.elements };
}

/** 替换第 index 个元素的字段。 */
export function withElement(
  spec: MagicCircleSpec,
  index: number,
  patch: Partial<Element>,
): MagicCircleSpec {
  const elements = spec.elements.map((e, i) => (i === index ? ({ ...e, ...patch } as Element) : e));
  return { ...spec, elements };
}

/** 写回某元素的某条动画曲线（scale/alpha/rotation）。 */
export function withCurve(
  spec: MagicCircleSpec,
  index: number,
  field: keyof NonNullable<Element['anim']>,
  curve: Curve,
): MagicCircleSpec {
  const el = spec.elements[index];
  const anim = { ...(el.anim ?? {}), [field]: curve };
  return withElement(spec, index, { anim });
}

/** 追加一个指定类型的元素（start 递增避免全部同时出现）。 */
export function addElement(
  spec: MagicCircleSpec,
  type: 'ring' | 'arc' | 'polygon' | 'star' | 'glyph',
): MagicCircleSpec {
  const start = Math.min(0.9, spec.elements.length * 0.1);
  const base = {
    axis: AXIS_GROUND as Vec3,
    radius: type === 'glyph' ? 2.8 : 3.0,
    particle: 'glow',
    color: type === 'glyph' ? '#ffdd66' : type === 'arc' ? '#ff8800' : '#44ccff',
    rotation_offset_deg: 0,
    rotate_speed: 0,
    start,
  };
  let el: Element;
  if (type === 'ring') {
    el = { ...base, type: 'ring', mode: 'beads', beads: 16, density: 1.5, trail_ticks: 10, y_offset: 0 };
  } else if (type === 'arc') {
    el = { ...base, type: 'arc', mode: 'beads', beads: 12, arc_start_deg: 0, arc_sweep_deg: 240, density: 1.5, trail_ticks: 8, y_offset: 0 };
  } else if (type === 'polygon') {
    el = { ...base, type: 'polygon', mode: 'beads', sides: 6, density: 2.5, trail_ticks: 10, y_offset: 0, rotate_speed: SHAPE_DEFAULT_ROTATE, anim: SHAPE_DEFAULT_ANIM };
  } else if (type === 'star') {
    el = { ...base, type: 'star', mode: 'beads', points: 5, inner_ratio: 0.4, density: 2.5, trail_ticks: 10, y_offset: 0, rotate_speed: SHAPE_DEFAULT_ROTATE, anim: SHAPE_DEFAULT_ANIM };
  } else {
    el = { ...base, type: 'glyph', count: 8, sprite: 'rune', scale: 0.3 };
  }
  return { ...spec, elements: [...spec.elements, el] };
}

export function removeElement(spec: MagicCircleSpec, index: number): MagicCircleSpec {
  return { ...spec, elements: spec.elements.filter((_, i) => i !== index) };
}

/** 上下移一个元素（与相邻元素交换）。 */
export function moveElement(spec: MagicCircleSpec, index: number, dir: -1 | 1): MagicCircleSpec {
  const j = index + dir;
  if (j < 0 || j >= spec.elements.length) return spec;
  const elements = [...spec.elements];
  [elements[index], elements[j]] = [elements[j], elements[index]];
  return { ...spec, elements };
}

/** 换元素类型：保留公共字段，按新类型填入默认专属字段。 */
export function setElementType(
  spec: MagicCircleSpec,
  index: number,
  type: 'ring' | 'arc' | 'polygon' | 'star' | 'glyph',
): MagicCircleSpec {
  const el = spec.elements[index];
  const wasRingLike = el.type === 'ring' || el.type === 'arc' || el.type === 'polygon' || el.type === 'star';
  const prevMode = wasRingLike ? el.mode : undefined;
  const prevBeads = el.type === 'ring' || el.type === 'arc' ? el.beads : undefined;
  const common: Record<string, unknown> = { ...el };
  for (const k of [
    'density',
    'trail_ticks',
    'y_offset',
    'arc_start_deg',
    'arc_sweep_deg',
    'count',
    'sprite',
    'scale',
    'mode',
    'beads',
    'sides',
    'points',
    'inner_ratio',
    'interval_ticks',
  ]) {
    delete common[k];
  }
  delete common.type;
  let next: Element;
  if (type === 'ring') {
    next = { ...common, type: 'ring', mode: prevMode ?? 'beads', beads: prevBeads ?? 16, density: 1.5, trail_ticks: 10, y_offset: 0 } as Element;
  } else if (type === 'arc') {
    next = { ...common, type: 'arc', mode: prevMode ?? 'beads', beads: prevBeads ?? 12, arc_start_deg: 0, arc_sweep_deg: 240, density: 1.5, trail_ticks: 8, y_offset: 0 } as Element;
  } else if (type === 'polygon') {
    next = { ...common, type: 'polygon', mode: prevMode ?? 'beads', sides: 6, density: 2.5, trail_ticks: 10, y_offset: 0, rotate_speed: 15, anim: common.anim ?? SHAPE_DEFAULT_ANIM } as Element;
  } else if (type === 'star') {
    next = { ...common, type: 'star', mode: prevMode ?? 'beads', points: 5, inner_ratio: 0.4, density: 2.5, trail_ticks: 10, y_offset: 0, rotate_speed: 15, anim: common.anim ?? SHAPE_DEFAULT_ANIM } as Element;
  } else {
    next = { ...common, type: 'glyph', count: 8, sprite: 'rune', scale: 0.3 } as Element;
  }
  return withElement(spec, index, next);
}
