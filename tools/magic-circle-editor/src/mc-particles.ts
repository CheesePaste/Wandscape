/**
 * MC 原版粒子 fidelity 表——编辑器预览与游戏端渲染的**共享契约**。
 *
 * 每个 `particle` 风格 id 对应一条记录：贴图帧列表、尺寸公式（quadSize 年龄曲线）、渲染层。
 * 数据来源：MC 1.21.1 反编译源码（客户端粒子类）+ assets/minecraft/particles/<id>.json。
 * MC 端 magic/ 包实现 ghost-trail 粒子时复用同一张表：借用 vanilla SpriteSet + 移植本表尺寸公式。
 *
 * 移植口径：
 * - 基础 quadSize = 0.1 × rand(0.5~1.0) × 2（SingleQuadParticle 字段），随机取期望 0.75 → 0.15；
 *   各粒子类再乘自带系数。quadSize 是**半宽**（格），渲染宽 = 2×quadSize。
 * - `sizeOf(age, lifetime)` 移植 `getQuadSize` 年龄曲线；`lifetime` 由调用方传入
 *   （= 元素 trail_ticks），使曲线形状铺满可见生命——编辑器与未来 MC 端一致。
 * - 行为（上升/重力/漂移）**不移植**：ghost-trail 模型撒零速度粒子，静止贴环。
 */

export interface McParticleStyle {
  /** 编辑器风格 id（spec 的 particle 字段值）。 */
  id: string;
  /** MC ParticleTypes 注册 id；null = 模组自定义粒子（仅编辑器有）。 */
  mcId: string | null;
  /** 贴图帧文件名（顺序 = particles/<id>.json 的 textures 列表，仿 setSpriteFromAge 推进）。 */
  frames: string[];
  /** 基础 quadSize（半宽，格），已含各粒子类自带系数。 */
  quadSize: number;
  /** 年龄尺寸曲线：返回 age 时刻的 quadSize（半宽，格）。 */
  sizeOf(age: number, lifetime: number): number;
  /** 渲染层：opaque = 不混合（alpha 不生效）；translucent = 正常混合。 */
  renderType: 'opaque' | 'translucent';
  /** true = 用元素 color 染色贴图；false = 贴图本色（vanilla 忽略 color）。 */
  tintable: boolean;
  label: string;
}

const clamp01 = (v: number): number => Math.min(1, Math.max(0, v));
/** BaseAshSmokeParticle / CritParticle / NoteParticle：前 1/32 生命弹入，之后恒定。 */
const popIn = (q: number) => (age: number, lifetime: number) =>
  q * clamp01((age / Math.max(1, lifetime)) * 32);
/** FlameParticle：随龄缩小到 0.5。 */
const shrink = (q: number) => (age: number, lifetime: number) => {
  const f = age / Math.max(1, lifetime);
  return q * (1 - f * f * 0.5);
};
/** PortalParticle：随龄线性放大。 */
const grow = (q: number) => (age: number, lifetime: number) =>
  q * (age / Math.max(1, lifetime));
const constant = (q: number) => () => q;

export const MC_PARTICLE_STYLES: McParticleStyle[] = [
  {
    id: 'flame',
    mcId: 'flame',
    frames: ['flame'],
    quadSize: 0.15,
    sizeOf: shrink(0.15),
    renderType: 'opaque',
    tintable: false,
    label: '火焰 flame',
  },
  {
    id: 'soul',
    mcId: 'soul',
    frames: ['soul_0', 'soul_1', 'soul_2', 'soul_3', 'soul_4', 'soul_5', 'soul_6', 'soul_7', 'soul_8', 'soul_9', 'soul_10'],
    quadSize: 0.15 * 1.5,
    sizeOf: constant(0.15 * 1.5),
    renderType: 'opaque',
    tintable: false,
    label: '灵魂火焰 soul',
  },
  {
    id: 'endRod',
    mcId: 'end_rod',
    frames: ['glitter_7', 'glitter_6', 'glitter_5', 'glitter_4', 'glitter_3', 'glitter_2', 'glitter_1', 'glitter_0'],
    quadSize: 0.15 * 0.75,
    sizeOf: constant(0.15 * 0.75),
    renderType: 'translucent',
    tintable: false,
    label: '末地烛 end_rod',
  },
  {
    id: 'portal',
    mcId: 'portal',
    frames: ['generic_0', 'generic_1', 'generic_2', 'generic_3', 'generic_4', 'generic_5', 'generic_6', 'generic_7'],
    quadSize: 0.1 * 0.6,
    sizeOf: grow(0.1 * 0.6),
    renderType: 'translucent',
    tintable: false,
    label: '传送门 portal',
  },
  {
    id: 'enchant',
    mcId: 'enchant',
    frames: ['sga_a', 'sga_b', 'sga_c', 'sga_d', 'sga_e', 'sga_f', 'sga_g', 'sga_h', 'sga_i', 'sga_j', 'sga_k', 'sga_l', 'sga_m', 'sga_n', 'sga_o', 'sga_p', 'sga_q', 'sga_r', 'sga_s', 'sga_t', 'sga_u', 'sga_v', 'sga_w', 'sga_x', 'sga_y', 'sga_z'],
    quadSize: 0.15 * 0.75,
    sizeOf: constant(0.15 * 0.75),
    renderType: 'translucent',
    tintable: false,
    label: '附魔文字 enchant',
  },
  {
    id: 'enchanted_hit',
    mcId: 'enchanted_hit',
    frames: ['enchanted_hit'],
    quadSize: 0.15 * 0.75,
    sizeOf: constant(0.15 * 0.75),
    renderType: 'translucent',
    tintable: false,
    label: '附魔命中 enchanted_hit',
  },
  {
    id: 'spark',
    mcId: 'electric_spark',
    frames: ['glow'],
    quadSize: 0.15 * 1.5,
    sizeOf: constant(0.15 * 1.5),
    renderType: 'translucent',
    tintable: false,
    label: '电火花 electric_spark',
  },
  {
    id: 'crit',
    mcId: 'crit',
    frames: ['critical_hit'],
    quadSize: 0.15 * 0.75,
    sizeOf: popIn(0.15 * 0.75),
    renderType: 'opaque',
    tintable: false,
    label: '暴击 crit',
  },
  {
    id: 'smoke',
    mcId: 'smoke',
    frames: ['generic_7', 'generic_6', 'generic_5', 'generic_4', 'generic_3', 'generic_2', 'generic_1', 'generic_0'],
    quadSize: 0.15 * 0.75,
    sizeOf: popIn(0.15 * 0.75),
    renderType: 'opaque',
    tintable: false,
    label: '烟雾 smoke',
  },
  {
    id: 'large_smoke',
    mcId: 'large_smoke',
    frames: ['generic_7', 'generic_6', 'generic_5', 'generic_4', 'generic_3', 'generic_2', 'generic_1', 'generic_0'],
    quadSize: 0.15 * 0.75 * 2.5,
    sizeOf: popIn(0.15 * 0.75 * 2.5),
    renderType: 'opaque',
    tintable: false,
    label: '大烟雾 large_smoke',
  },
  {
    id: 'cloud',
    mcId: 'cloud',
    frames: ['generic_7', 'generic_6', 'generic_5', 'generic_4', 'generic_3', 'generic_2', 'generic_1', 'generic_0'],
    quadSize: 0.15 * 1.875,
    sizeOf: popIn(0.15 * 1.875),
    renderType: 'translucent',
    tintable: false,
    label: '云 cloud',
  },
  {
    id: 'note',
    mcId: 'note',
    frames: ['note'],
    quadSize: 0.15 * 1.5,
    sizeOf: popIn(0.15 * 1.5),
    renderType: 'translucent',
    tintable: true,
    label: '音符 note（可染色）',
  },
  {
    id: 'white_ash',
    mcId: 'white_ash',
    frames: ['generic_0'],
    quadSize: 0.15 * 0.75,
    sizeOf: popIn(0.15 * 0.75),
    renderType: 'opaque',
    tintable: false,
    label: '白灰 white_ash',
  },
  // 模组自定义粒子：复用 MC glow 贴图 + 元素 color 染色（替代旧程序化圆点）。
  {
    id: 'glow',
    mcId: null,
    frames: ['glow'],
    quadSize: 0.12,
    sizeOf: constant(0.12),
    renderType: 'translucent',
    tintable: true,
    label: '柔光 glow（可染色）',
  },
  {
    id: 'ember',
    mcId: null,
    frames: ['glow'],
    quadSize: 0.08,
    sizeOf: constant(0.08),
    renderType: 'translucent',
    tintable: true,
    label: '余烬 ember（可染色）',
  },
];

const byId = new Map(MC_PARTICLE_STYLES.map((s) => [s.id, s]));

export function mcParticleStyle(id?: string): McParticleStyle | undefined {
  return id ? byId.get(id) : undefined;
}

export const PARTICLE_STYLE_IDS = MC_PARTICLE_STYLES.map((s) => s.id);
