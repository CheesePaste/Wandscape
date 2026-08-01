/**
 * MC 原版粒子 fidelity 表——编辑器预览与游戏端渲染的**共享契约**。
 *
 * 每个 `particle` 风格 id 对应一条记录：贴图帧列表、尺寸公式（quadSize 年龄曲线）、渲染层。
 * 帧列表来自 MC 1.21.1 assets/minecraft/particles/<id>.json；尺寸/渲染层/染色来自反编译粒子类。
 * MC 端 magic/ 包实现 ghost-trail 粒子时复用同一张表：借用 vanilla SpriteSet + 移植本表尺寸公式。
 *
 * 移植口径：
 * - 基础 quadSize = 0.1 × rand(0.5~1.0) × 2（SingleQuadParticle 字段），随机取期望 0.75 → 0.15；
 *   各粒子类再乘自带系数。quadSize 是**半宽**（格），渲染宽 = 2×quadSize。
 * - `sizeOf(age, lifetime)` 移植 `getQuadSize` 年龄曲线；`lifetime` 由调用方传入
 *   （= 元素 trail_ticks），使曲线形状铺满可见生命——编辑器与未来 MC 端一致。
 * - 行为（上升/重力/漂移）**不移植**：ghost-trail 模型撒零速度粒子，静止贴环。
 * - 新增原版粒子的尺寸曲线为合理近似（基础尺寸 + 常用曲线），待 MC 端实现时按源码精修。
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
/** PortalParticle / 扩张类：随龄线性放大。 */
const grow = (q: number) => (age: number, lifetime: number) =>
  q * (age / Math.max(1, lifetime));
const constant = (q: number) => () => q;

/** 便捷构造：默认 constant 尺寸、不染色。 */
const S = (
  id: string,
  mcId: string,
  frames: string[],
  quadSize: number,
  renderType: 'opaque' | 'translucent',
  label: string,
  extra: Partial<Pick<McParticleStyle, 'sizeOf' | 'tintable'>> = {},
): McParticleStyle => ({
  id,
  mcId,
  frames,
  quadSize,
  sizeOf: extra.sizeOf ?? constant(quadSize),
  renderType,
  tintable: extra.tintable ?? false,
  label,
});

export const MC_PARTICLE_STYLES: McParticleStyle[] = [
  // ---------- 原版粒子 ----------
  S('flame', 'flame', ['flame'], 0.15, 'opaque', '火焰 flame', { sizeOf: shrink(0.15) }),
  S('small_flame', 'small_flame', ['flame'], 0.11, 'opaque', '小火苗 small_flame', { sizeOf: shrink(0.11) }),
  S(
    'soul',
    'soul',
    ['soul_0', 'soul_1', 'soul_2', 'soul_3', 'soul_4', 'soul_5', 'soul_6', 'soul_7', 'soul_8', 'soul_9', 'soul_10'],
    0.15 * 1.5,
    'opaque',
    '灵魂火焰 soul',
  ),
  S(
    'soul_fire_flame',
    'soul_fire_flame',
    ['soul_fire_flame'],
    0.15,
    'translucent',
    '魂火 soul_fire_flame',
    { sizeOf: shrink(0.15) },
  ),
  S(
    'sculk_soul',
    'sculk_soul',
    ['sculk_soul_0', 'sculk_soul_1', 'sculk_soul_2', 'sculk_soul_3', 'sculk_soul_4', 'sculk_soul_5', 'sculk_soul_6', 'sculk_soul_7', 'sculk_soul_8', 'sculk_soul_9', 'sculk_soul_10'],
    0.15 * 1.2,
    'translucent',
    '幽冥魂 sculk_soul',
  ),
  S('endRod', 'end_rod', ['glitter_7', 'glitter_6', 'glitter_5', 'glitter_4', 'glitter_3', 'glitter_2', 'glitter_1', 'glitter_0'], 0.15 * 0.75, 'translucent', '末地烛 end_rod'),
  S(
    'portal',
    'portal',
    ['generic_0', 'generic_1', 'generic_2', 'generic_3', 'generic_4', 'generic_5', 'generic_6', 'generic_7'],
    0.1 * 0.6,
    'translucent',
    '传送门 portal',
    { sizeOf: grow(0.1 * 0.6) },
  ),
  S(
    'reverse_portal',
    'reverse_portal',
    ['generic_0', 'generic_1', 'generic_2', 'generic_3', 'generic_4', 'generic_5', 'generic_6', 'generic_7'],
    0.1 * 0.6,
    'translucent',
    '逆传送门 reverse_portal',
    { sizeOf: grow(0.1 * 0.6) },
  ),
  S('enchant', 'enchant', ['sga_a', 'sga_b', 'sga_c', 'sga_d', 'sga_e', 'sga_f', 'sga_g', 'sga_h', 'sga_i', 'sga_j', 'sga_k', 'sga_l', 'sga_m', 'sga_n', 'sga_o', 'sga_p', 'sga_q', 'sga_r', 'sga_s', 'sga_t', 'sga_u', 'sga_v', 'sga_w', 'sga_x', 'sga_y', 'sga_z'], 0.15 * 0.75, 'translucent', '附魔文字 enchant'),
  S('ambient_entity_effect', 'ambient_entity_effect', ['effect_7', 'effect_6', 'effect_5', 'effect_4', 'effect_3', 'effect_2', 'effect_1', 'effect_0'], 0.15 * 0.75, 'translucent', '环境实体效果 ambient_entity_effect'),
  S('entity_effect', 'entity_effect', ['effect_7', 'effect_6', 'effect_5', 'effect_4', 'effect_3', 'effect_2', 'effect_1', 'effect_0'], 0.15 * 0.75, 'translucent', '实体效果 entity_effect'),
  S('effect', 'effect', ['effect_7', 'effect_6', 'effect_5', 'effect_4', 'effect_3', 'effect_2', 'effect_1', 'effect_0'], 0.15 * 0.75, 'translucent', '效果 effect'),
  S('instant_effect', 'instant_effect', ['spell_7', 'spell_6', 'spell_5', 'spell_4', 'spell_3', 'spell_2', 'spell_1', 'spell_0'], 0.15 * 0.75, 'translucent', '瞬间效果 instant_effect'),
  S('witch', 'witch', ['spell_7', 'spell_6', 'spell_5', 'spell_4', 'spell_3', 'spell_2', 'spell_1', 'spell_0'], 0.15 * 0.75, 'translucent', '女巫 witch'),
  S('enchanted_hit', 'enchanted_hit', ['enchanted_hit'], 0.15 * 0.75, 'translucent', '附魔命中 enchanted_hit'),
  S('spark', 'electric_spark', ['glow'], 0.15 * 1.5, 'translucent', '电火花 electric_spark'),
  S('crit', 'crit', ['critical_hit'], 0.15 * 0.75, 'opaque', '暴击 crit', { sizeOf: popIn(0.15 * 0.75) }),
  S('damage_indicator', 'damage_indicator', ['damage'], 0.15 * 0.75, 'opaque', '伤害指示 damage_indicator', { sizeOf: popIn(0.15 * 0.75) }),
  S('smoke', 'smoke', ['generic_7', 'generic_6', 'generic_5', 'generic_4', 'generic_3', 'generic_2', 'generic_1', 'generic_0'], 0.15 * 0.75, 'opaque', '烟雾 smoke', { sizeOf: popIn(0.15 * 0.75) }),
  S('large_smoke', 'large_smoke', ['generic_7', 'generic_6', 'generic_5', 'generic_4', 'generic_3', 'generic_2', 'generic_1', 'generic_0'], 0.15 * 0.75 * 2.5, 'opaque', '大烟雾 large_smoke', { sizeOf: popIn(0.15 * 0.75 * 2.5) }),
  S('white_smoke', 'white_smoke', ['generic_7', 'generic_6', 'generic_5', 'generic_4', 'generic_3', 'generic_2', 'generic_1', 'generic_0'], 0.15 * 1.8, 'opaque', '白烟 white_smoke', { sizeOf: popIn(0.15 * 1.8) }),
  S('campfire_cosy_smoke', 'campfire_cosy_smoke', ['big_smoke_0', 'big_smoke_1', 'big_smoke_2', 'big_smoke_3', 'big_smoke_4', 'big_smoke_5', 'big_smoke_6', 'big_smoke_7', 'big_smoke_8', 'big_smoke_9', 'big_smoke_10', 'big_smoke_11'], 0.15 * 1.5, 'opaque', '营火轻烟 campfire_cosy_smoke'),
  S('campfire_signal_smoke', 'campfire_signal_smoke', ['big_smoke_0', 'big_smoke_1', 'big_smoke_2', 'big_smoke_3', 'big_smoke_4', 'big_smoke_5', 'big_smoke_6', 'big_smoke_7', 'big_smoke_8', 'big_smoke_9', 'big_smoke_10', 'big_smoke_11'], 0.15 * 0.8, 'opaque', '营火浓烟 campfire_signal_smoke'),
  S('cloud', 'cloud', ['generic_7', 'generic_6', 'generic_5', 'generic_4', 'generic_3', 'generic_2', 'generic_1', 'generic_0'], 0.15 * 1.875, 'translucent', '云 cloud', { sizeOf: popIn(0.15 * 1.875) }),
  S('poof', 'poof', ['generic_7', 'generic_6', 'generic_5', 'generic_4', 'generic_3', 'generic_2', 'generic_1', 'generic_0'], 0.15 * 2.0, 'opaque', '消散 poof', { sizeOf: popIn(0.15 * 2.0) }),
  S('dragon_breath', 'dragon_breath', ['generic_5', 'generic_6', 'generic_7'], 0.15 * 0.75, 'translucent', '龙息 dragon_breath'),
  S('note', 'note', ['note'], 0.15 * 1.5, 'translucent', '音符 note（可染色）', { sizeOf: popIn(0.15 * 1.5), tintable: true }),
  S('heart', 'heart', ['heart'], 0.15, 'opaque', '爱心 heart'),
  S('angry_villager', 'angry_villager', ['angry'], 0.15, 'opaque', '愤怒村民 angry_villager'),
  S('bubble', 'bubble', ['bubble'], 0.15, 'opaque', '泡泡 bubble'),
  S('bubble_pop', 'bubble_pop', ['bubble_pop_0', 'bubble_pop_1', 'bubble_pop_2', 'bubble_pop_3', 'bubble_pop_4'], 0.15, 'opaque', '泡泡破 bubble_pop', { sizeOf: grow(0.15) }),
  S('splash', 'splash', ['splash_0', 'splash_1', 'splash_2', 'splash_3'], 0.15 * 0.75, 'opaque', '水花 splash'),
  S('cherry_leaves', 'cherry_leaves', ['cherry_0', 'cherry_1', 'cherry_2', 'cherry_3', 'cherry_4', 'cherry_5', 'cherry_6', 'cherry_7', 'cherry_8', 'cherry_9', 'cherry_10', 'cherry_11'], 0.15 * 0.75, 'opaque', '樱花 cherry_leaves（可染色）', { tintable: true }),
  S('lava', 'lava', ['lava'], 0.15, 'opaque', '岩浆 lava'),
  S('firework', 'firework', ['spark_7', 'spark_6', 'spark_5', 'spark_4', 'spark_3', 'spark_2', 'spark_1', 'spark_0'], 0.15 * 0.75, 'translucent', '烟花 firework'),
  S('flash', 'flash', ['flash'], 0.15 * 2.5, 'translucent', '闪光 flash', { sizeOf: shrink(0.15 * 2.5) }),
  S('explosion', 'explosion', ['explosion_0', 'explosion_1', 'explosion_2', 'explosion_3', 'explosion_4', 'explosion_5', 'explosion_6', 'explosion_7', 'explosion_8', 'explosion_9', 'explosion_10', 'explosion_11', 'explosion_12', 'explosion_13', 'explosion_14', 'explosion_15'], 0.15 * 2.0, 'opaque', '爆炸 explosion', { sizeOf: shrink(0.15 * 2.0) }),
  S('gust', 'gust', ['gust_0', 'gust_1', 'gust_2', 'gust_3', 'gust_4', 'gust_5', 'gust_6', 'gust_7', 'gust_8', 'gust_9', 'gust_10', 'gust_11'], 0.15 * 1.5, 'translucent', '阵风 gust'),
  S('small_gust', 'small_gust', ['small_gust_0', 'small_gust_1', 'small_gust_2', 'small_gust_3', 'small_gust_4', 'small_gust_5', 'small_gust_6'], 0.15, 'translucent', '小阵风 small_gust'),
  S('sneeze', 'sneeze', ['generic_7', 'generic_6', 'generic_5', 'generic_4', 'generic_3', 'generic_2', 'generic_1', 'generic_0'], 0.15 * 1.2, 'translucent', '喷嚏 sneeze'),
  S('sonic_boom', 'sonic_boom', ['sonic_boom_0', 'sonic_boom_1', 'sonic_boom_2', 'sonic_boom_3', 'sonic_boom_4', 'sonic_boom_5', 'sonic_boom_6', 'sonic_boom_7', 'sonic_boom_8', 'sonic_boom_9', 'sonic_boom_10', 'sonic_boom_11', 'sonic_boom_12', 'sonic_boom_13', 'sonic_boom_14', 'sonic_boom_15'], 0.15 * 2.5, 'translucent', '音爆 sonic_boom', { sizeOf: grow(0.15 * 2.5) }),
  S('shriek', 'shriek', ['shriek'], 0.15 * 2.5, 'translucent', '尖啸 shriek', { sizeOf: grow(0.15 * 2.5) }),
  S('sweep_attack', 'sweep_attack', ['sweep_0', 'sweep_1', 'sweep_2', 'sweep_3', 'sweep_4', 'sweep_5', 'sweep_6', 'sweep_7'], 0.15 * 1.2, 'translucent', '横扫 sweep_attack'),
  S('sculk_charge', 'sculk_charge', ['sculk_charge_0', 'sculk_charge_1', 'sculk_charge_2', 'sculk_charge_3', 'sculk_charge_4', 'sculk_charge_5', 'sculk_charge_6'], 0.15 * 0.75, 'translucent', '幽匿能量 sculk_charge'),
  S('sculk_charge_pop', 'sculk_charge_pop', ['sculk_charge_pop_0', 'sculk_charge_pop_1', 'sculk_charge_pop_2', 'sculk_charge_pop_3'], 0.15 * 0.75, 'translucent', '幽匿迸发 sculk_charge_pop', { sizeOf: grow(0.15 * 0.75) }),
  S('vibration', 'vibration', ['vibration'], 0.15, 'translucent', '震波 vibration'),
  S('vault_connection', 'vault_connection', ['vault_connection'], 0.15 * 0.75, 'translucent', '宝库连线 vault_connection'),
  S('nautilus', 'nautilus', ['nautilus'], 0.15 * 0.75, 'translucent', '鹦鹉螺 nautilus'),
  S('totem_of_undying', 'totem_of_undying', ['glitter_7', 'glitter_6', 'glitter_5', 'glitter_4', 'glitter_3', 'glitter_2', 'glitter_1', 'glitter_0'], 0.15 * 0.75, 'translucent', '图腾 totem_of_undying'),
  S('ominous_spawning', 'ominous_spawning', ['ominous_spawning'], 0.15 * 0.75, 'translucent', '凶兆生成 ominous_spawning'),
  S('raid_omen', 'raid_omen', ['raid_omen'], 0.15 * 0.75, 'translucent', '袭击之兆 raid_omen'),
  S('trial_omen', 'trial_omen', ['trial_omen'], 0.15 * 0.75, 'translucent', '试炼之兆 trial_omen'),
  S('trial_spawner_detection', 'trial_spawner_detection', ['trial_spawner_detection_0', 'trial_spawner_detection_1', 'trial_spawner_detection_2', 'trial_spawner_detection_3', 'trial_spawner_detection_4'], 0.15 * 0.75, 'translucent', '试炼探测 trial_spawner_detection'),
  S('trial_spawner_detection_ominous', 'trial_spawner_detection_ominous', ['trial_spawner_detection_ominous_0', 'trial_spawner_detection_ominous_1', 'trial_spawner_detection_ominous_2', 'trial_spawner_detection_ominous_3', 'trial_spawner_detection_ominous_4'], 0.15 * 0.75, 'translucent', '凶兆探测 trial_spawner_detection_ominous'),
  S('squid_ink', 'squid_ink', ['generic_7', 'generic_6', 'generic_5', 'generic_4', 'generic_3', 'generic_2', 'generic_1', 'generic_0'], 0.15 * 0.75, 'translucent', '墨汁 squid_ink（可染色）', { sizeOf: grow(0.15 * 0.75), tintable: true }),
  S('glow_squid_ink', 'glow_squid_ink', ['generic_7', 'generic_6', 'generic_5', 'generic_4', 'generic_3', 'generic_2', 'generic_1', 'generic_0'], 0.15 * 0.75, 'translucent', '荧光墨汁 glow_squid_ink（可染色）', { sizeOf: grow(0.15 * 0.75), tintable: true }),
  S('white_ash', 'white_ash', ['generic_0'], 0.15 * 0.75, 'opaque', '白灰 white_ash', { sizeOf: popIn(0.15 * 0.75) }),
  S('ash', 'ash', ['generic_0'], 0.15 * 0.75, 'opaque', '灰烬 ash'),
  S('dolphin', 'dolphin', ['generic_0'], 0.15 * 0.75, 'opaque', '海豚光点 dolphin'),
  S('underwater', 'underwater', ['generic_0'], 0.15 * 0.75, 'opaque', '水下 underwater'),
  S('mycelium', 'mycelium', ['generic_0'], 0.15 * 0.75, 'opaque', '菌丝孢子 mycelium'),
  S('crimson_spore', 'crimson_spore', ['generic_0'], 0.15 * 0.75, 'opaque', '绯红孢子 crimson_spore'),
  S('warped_spore', 'warped_spore', ['generic_0'], 0.15 * 0.75, 'opaque', '诡异孢子 warped_spore'),
  // 模组自定义粒子：复用 MC glow 贴图 + 元素 color 染色（替代旧程序化圆点）。
  S('glow', null, ['glow'], 0.12, 'translucent', '柔光 glow（可染色）', { tintable: true }),
  S('ember', null, ['glow'], 0.08, 'translucent', '余烬 ember（可染色）', { tintable: true }),
];

const byId = new Map(MC_PARTICLE_STYLES.map((s) => [s.id, s]));

export function mcParticleStyle(id?: string): McParticleStyle | undefined {
  return id ? byId.get(id) : undefined;
}

export const PARTICLE_STYLE_IDS = MC_PARTICLE_STYLES.map((s) => s.id);
