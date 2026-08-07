# 魔法阵 JSON 格式（MagicCircleSpec）

位置：`data/wandscape/magic_circles/*.json`（由独立项目 [magic-circle-editor](https://github.com/CheesePaste/magic-circle-editor) 导出，MC 端加载同款）

**本文件是 `MagicCircleSpec` 的契约权威**：Web 编辑器与 MC 粒子渲染器都只"画这份几何 spec"，互不搬渲染管线。编辑器仓库 `src/spec.ts` 是本 schema 的 TypeScript 镜像——改 schema 时需同步：本文档 → 编辑器 `src/spec.ts` → 模组侧 `magic/data/MagicCircleSpec.java`。

## 完整 schema

```json
{
  "id": "fire_summon",
  "duration_ticks": 120,
  "height": 0.1,
  "elements": [
    {
      "type": "ring",
      "axis": [0, 1, 0],
      "radius": 4.0,
      "particle": "glow",
      "color": "#00ff88",
      "mode": "beads",
      "density": 1.5,
      "rotation_offset_deg": 0,
      "rotate_speed": 23,
      "y_offset": 0.0,
      "start": 0.0,
      "anim": {
        "scale":    [[0, 0], [0.5, 1], [1, 1.1]],
        "alpha":    [[0, 0], [0.3, 1], [0.85, 1], [1, 0]],
        "rotation": [[0, 0], [1, 90]]
      }
    },
    {
      "type": "arc",
      "axis": [0, 1, 0],
      "radius": 3.0,
      "arc_start_deg": 0,
      "arc_sweep_deg": 240,
      "particle": "ember",
      "color": "#ff8800",
      "mode": "continuous",
      "density": 1.5,
      "trail_ticks": 8,
      "rotate_speed": -17,
      "start": 0.25
    },
    {
      "type": "polygon",
      "axis": [0, 1, 0],
      "radius": 3.2,
      "sides": 6,
      "particle": "glow",
      "color": "#00ccff",
      "mode": "beads",
      "rotate_speed": 8,
      "interval_ticks": 10,
      "start": 0.5,
      "anim": { "easing": "smoothstep", "alpha": [[0, 0], [0.3, 1], [1, 1]] }
    },
    {
      "type": "glyph",
      "axis": [0, 1, 0],
      "radius": 3.5,
      "count": 8,
      "sprite": "rune_fire",
      "scale": 0.3,
      "color": "#ffaa00",
      "rotate_speed": 12,
      "start": 0.3,
      "anim": { "alpha": [[0, 0], [0.2, 1], [1, 1]] }
    }
  ]
}
```

## 字段说明

### 顶层

| 字段 | 类型 | 说明 |
|------|------|------|
| id | string | 唯一标识，snake_case |
| duration_ticks | int | 整个法阵总时长（游戏 tick），下限 1 |
| height | double | 法阵中心离地高度（默认 0.1） |
| elements | Element[] | 元素列表（ring / arc / polygon / star / glyph 五种） |

### Element 通用字段

| 字段 | 类型 | 说明（含 MC 端解析默认值） |
|------|------|------|
| type | string | `ring` / `arc` / `polygon` / `star` / `glyph`，非法值回退 `ring` |
| axis | [x,y,z] | 法阵平面法线，默认 `[0,1,0]`（水平地面）；`[1,0,0]`/`[0,0,1]` 为竖直环（传送门）。全零/缺省回落 `[0,1,0]` |
| radius | double | 基础半径，下限 0 |
| particle | string | 粒子风格 id（见"粒子风格"节）。非 glyph 默认 `glow`，glyph 默认 `enchant` |
| particles | string[] | 可选：粒子 id 列表（轮询使用），显式时优先于 `particle` |
| color | string? | 可选十六进制 `#RRGGBB`；自定义粒子可染色，原版粒子忽略；非法值 → null（渲染为白） |
| rotation_offset_deg | double | 初始相位偏移（度），默认 0 |
| rotate_speed | double | 静态旋转速率（**度/秒**，负=反向），默认 0。合成公式见"旋转与朝向" |
| start | double | 归一化起始时间 [0,1)：元素在总时长哪一刻开始出现（**级联核心**），钳 [0,1] |
| anim | Anim? | 关键帧曲线（见下） |
| y_offset | double | 多层堆叠的纵向偏移，默认 0（渲染端 world Y 偏移） |
| interval_ticks | int | 可选脉冲：`floorDiv(T, interval) % 2 == 1` 停发，on/off 循环（呼吸节奏），<1 视为无 |

### 类型专属

| 类型 | 字段 | 说明 |
|------|------|------|
| ring | mode | 排布模式：`beads`（**默认**，均布亮点） / `continuous`（连续密度拖尾） |
| ring | density | beads 模式亮点数 = `round(density × 弧长)`；continuous 模式每 tick 撒 `density × 弧长` 个粒子。默认 1.5 |
| ring | trail_ticks | continuous 模式：粒子存活 tick = 拖尾长度（默认 10，下限 1） |
| arc | arc_start_deg | 起始角度（度），默认 0 |
| arc | arc_sweep_deg | 扫过角度（度，<360 为部分圆弧），默认 360 |
| arc | mode / density / trail_ticks | 同 ring |
| polygon | sides | 顶点数（≥3，默认 6） |
| polygon | mode / density / trail_ticks | 同 ring（beads 时沿周长按 `density × 周长` 均布描边；continuous 沿各边撒拖尾） |
| star | points | 星芒数（≥2，外顶点数；总顶点 = 2×points），默认 5。首外顶点 180° 起（尖端朝上） |
| star | inner_ratio | 内半径 = radius × inner_ratio（默认 0.4，钳 [0.05,1]） |
| star | mode / density / trail_ticks | 同 polygon |
| glyph | count | 符文个数（默认 1，下限 1） |
| glyph | sprite | 符文贴图 key（当前 v1 渲染用放大点粒子，真符文贴图未实现；默认 "rune"） |
| glyph | scale | 符文渲染尺寸（默认 0.3，下限 0.05） |
| glyph | head_scale | 彗星头倍率（默认 1.35，下限 0.1） |
| glyph | tail_scale | 彗星尾倍率（默认 0.35，下限 0.05） |

> **`beads` 字段（旧 schema）**：编辑器侧的亮点数显式值，**MC 端不解析**——发射器始终按 `round(density × 弧长)` 计算亮点数。示例 spec 中残留的 `"beads": 36` 是冗余字段，实际渲染数量与编辑器预览可能不一致（要精确控制数量请用 `density`）。

### Anim（关键帧曲线）

`Anim` 可含三组曲线，均取归一化时间 [0,1]：

| 字段 | 含义 | 默认 |
|------|------|------|
| scale | 半径倍率 | `[[0,1]]`（恒 1） |
| alpha | 粒子透明度倍率（≤0.001 不发射） | `[[0,1]]` |
| rotation | 窗口内附加旋转角度（**度**），叠在静态旋转之上 | `[]` |
| easing | 关键帧间插值缓动：`linear` / `smoothstep` | `linear` |

keyframe 格式 `[[t, v], ...]`，`t` 归一化 [0,1]，按 `easing` 插值（smoothstep = `f²(3-2f)`）。缺省/空曲线返回 fallback（scale/alpha=1，rotation=0）。

## 动画模型

- 全局 `duration_ticks`；每元素 `start`（归一化）。
- 元素在 `[start, 1]` 窗口内激活；全局时间 `t` 映射到**局部时间** `lt = (t - start) / (1 - start)`，再对 `anim` 曲线采样。`t < start` 不发射。
- **"一层一层展开" = 各元素不同 `start`**（编辑器 Cascade 按钮自动按步长排布）。
- 典型效果映射：
  - 逐渐放大：`scale: [[0,0],[0.5,1],...]`
  - 缩小消散：`scale` 尾段 → 0，`alpha` 尾段 → 0
  - 层进展开：每层不同 `start` + 各自 `scale` 从 0 涨起

## 旋转与朝向

旋转始终在元素自身平面内、绕自身中心进行（无论水平/竖直/倾斜），即角度相位的递增。**全阵同速旋转 = 所有元素设相同 `rotate_speed`。**

### 合成公式

对元素窗口内任意全局 tick `T`，当前旋转角：

```
phase(T) = rotation_offset_deg
         + rotate_speed(度/秒) × (T - start × duration_ticks) / 20
         + anim.rotation(lt)
```

- `T0 = start × duration_ticks` 为元素出现时刻，`lt` 为局部时间。**elapsed 从窗口起点计**——级联元素从自己出现那一刻才开始转。
- `rotate_speed` 负值 = 反向；每元素独立，可做双环对旋。
- 线性 `anim.rotation` 曲线（如 `[[0,0],[1,180]]`）等价于叠加一段恒定附加速率。

### 朝向（axis）

- 默认水平（`[0,1,0]`，地面法阵）。竖直环用 `[1,0,0]`（朝 X）或 `[0,0,1]`（朝 Z）——**传送门/传送阵必用竖直环**。
- 任意倾斜用归一化法线 `[nx,ny,nz]`。两端渲染共用同一定点公式：由法线 `n` 构造正交基 `a = normalize(cross(n, m))`、`b = cross(n, a)`，圆周点 `p = a·cos θ + b·sin θ`（`m` 为任意不与 `n` 平行的单位向量）。
- **施放时 axis 覆盖**：`MagicCircleCastPacket` 携带的 axis（施法朝向）会覆盖 spec 元素 axis——法阵垂直于法杖/视线。地面阵（不传 axis）回落 spec 元素 axis。

## 粒子模型（MC 渲染端如何消费）

- **ring/arc · beads**（**默认**）：`round(density × 弧长)` 个亮点均布在圆周/弧上，随 phase 整体旋转，无随机抖动（有序不糊）。尺寸 = 粒子基础 quadSize（稳定），alpha 来自曲线。
- **ring/arc · continuous**：每 tick 按当前 `radius×scale`、当前 phase、当前 alpha，在圆周/弧上撒 `density × 弧长` 个粒子，带 ±0.2 格随机抖动（避免格子状伪影），存活 `trail_ticks`。
- **polygon/star**：beads = 沿周长按 `density × 周长` 均布（跨角不断、密度一致）；continuous = 沿各边撒拖尾。星形首尖朝上。
- **glyph**：每 tick 在 `count` 个符文位置撒短命粒子（存活 `trail_ticks`），彗星头 `scale × head_scale / 2` → 尾 `scale × tail_scale / 2` 缩放 + 淡出。
- **脉冲**：`interval_ticks` 按 on/off 循环（`floorDiv(T, interval) % 2 == 1` 停发）。
- 同一时刻存活粒子约几百个，MC 可轻松承受。

### 粒子风格（MC 端支持子集）

MC 端 `MagicCircleEmitter.VANILLA` 映射表——**只有以下 13 个原版风格 + 2 个自定义染色点**；其余风格 id（编辑器有 60 种）在 MC 端回退为自定义染色点（glow 尺寸）。

| 风格 id | MC 行为 |
|---------|--------|
| flame / soul / endRod / portal / enchant / enchanted_hit / spark / crit / smoke / large_smoke / cloud / note / white_ash | 原版 `ParticleTypes`，贴图/尺寸/运动全原版，**color 不生效** |
| glow（自定义） | 复用 `minecraft:glow` 贴图 + `color` 染色，quadSize 0.12 |
| ember（自定义） | 同上，quadSize 0.08 |
| 其他 / 未知 | 回退自定义染色点（glow 尺寸），可染色 |

> 编辑器侧有完整 fidelity 表（原版贴图帧/尺寸曲线/渲染层，见编辑器 `src/mc-particles.ts`）——MC 端不逐风格复刻尺寸曲线，只做风格 id 映射 + 自定义染色点。设计时建议只用上表 13+2 种。

## 与仪式/传送的衔接

- 仪式（含传送）通过 `circle_id` 绑定一张魔法阵（如 `self_teleport`）；施法时走 `MagicCircleCastPacket` → `MagicCircleEmitter`，环数/半径/动画全部来自 JSON。
- 传送已迁移为 spec 圆（`self_teleport.json`，引导开始双端生成）；`WandscapeNpcRenderer.spawnRitualCircle` 的 3 环 ENCHANT 迁移仍属**规划中**（见 magic.md）。
- 道路样条线是独立子系统，不在此契约内。

## 示例

### fire_summon.json — 成长 + 级联 + 淡出

```json
{
  "id": "fire_summon",
  "duration_ticks": 120,
  "height": 0.1,
  "elements": [
    {
      "type": "ring",
      "radius": 4.0,
      "particle": "glow",
      "color": "#ff5522",
      "mode": "continuous",
      "density": 1.5,
      "trail_ticks": 10,
      "rotate_speed": 23,
      "start": 0.0,
      "anim": {
        "scale": [[0, 0], [0.5, 1], [1, 1.1]],
        "alpha": [[0, 0], [0.3, 1], [0.85, 1], [1, 0]]
      }
    },
    {
      "type": "ring",
      "radius": 2.6,
      "particle": "ember",
      "color": "#ffaa00",
      "mode": "continuous",
      "density": 1.5,
      "trail_ticks": 8,
      "rotate_speed": -17,
      "start": 0.25,
      "anim": {
        "scale": [[0, 0], [0.5, 1], [1, 1]],
        "alpha": [[0, 0], [0.3, 1], [0.9, 1], [1, 0]]
      }
    },
    {
      "type": "glyph",
      "radius": 3.4,
      "count": 8,
      "sprite": "rune_fire",
      "scale": 0.3,
      "color": "#ff8800",
      "rotate_speed": 12,
      "start": 0.4,
      "anim": { "alpha": [[0, 0], [0.2, 1], [1, 1]] }
    }
  ]
}
```

### arcane_ward.json — 慢旋转 + 双环 + 符文

```json
{
  "id": "arcane_ward",
  "duration_ticks": 200,
  "height": 0.05,
  "elements": [
    {
      "type": "ring",
      "radius": 3.0,
      "particle": "glow",
      "color": "#44ccff",
      "mode": "continuous",
      "density": 2.0,
      "trail_ticks": 12,
      "rotate_speed": 9,
      "start": 0.0,
      "anim": { "alpha": [[0, 0], [0.3, 1], [1, 1]] }
    },
    {
      "type": "arc",
      "radius": 2.2,
      "arc_start_deg": 0,
      "arc_sweep_deg": 270,
      "particle": "endRod",
      "color": "#aaddff",
      "mode": "continuous",
      "density": 1.5,
      "trail_ticks": 10,
      "rotate_speed": 23,
      "start": 0.2,
      "anim": { "alpha": [[0, 0], [0.3, 1], [1, 1]] }
    },
    {
      "type": "glyph",
      "radius": 3.4,
      "count": 6,
      "sprite": "rune_arcane",
      "scale": 0.25,
      "color": "#88ddff",
      "rotate_speed": -6,
      "start": 0.4,
      "anim": { "alpha": [[0, 0], [0.2, 1], [1, 1]] }
    }
  ]
}
```

### ritual_teleport.json — 传送阵（地面环 + 竖直传送环）

```json
{
  "id": "ritual_teleport",
  "duration_ticks": 80,
  "height": 0.05,
  "elements": [
    {
      "type": "ring",
      "axis": [0, 1, 0],
      "radius": 2.0,
      "particle": "glow",
      "color": "#cc66ff",
      "mode": "continuous",
      "density": 1.5,
      "trail_ticks": 10,
      "rotate_speed": 40,
      "start": 0.0,
      "anim": {
        "scale": [[0, 0], [0.3, 1], [1, 0.8]],
        "alpha": [[0, 0], [0.2, 1], [0.8, 1], [1, 0]]
      }
    },
    {
      "type": "ring",
      "axis": [1, 0, 0],
      "radius": 1.6,
      "particle": "endRod",
      "color": "#ddaaff",
      "mode": "continuous",
      "density": 1.5,
      "trail_ticks": 8,
      "rotate_speed": -30,
      "start": 0.15,
      "anim": {
        "scale": [[0, 0], [0.3, 1], [1, 0.9]],
        "alpha": [[0, 0], [0.2, 1], [0.85, 1], [1, 0]]
      }
    },
    {
      "type": "glyph",
      "axis": [0, 1, 0],
      "radius": 2.3,
      "count": 6,
      "sprite": "rune_arcane",
      "scale": 0.25,
      "color": "#cc88ff",
      "rotate_speed": 15,
      "start": 0.25,
      "anim": { "alpha": [[0, 0], [0.15, 1], [0.9, 1], [1, 0]] }
    }
  ]
}
```

## 编辑器（独立外部项目）

魔法阵由独立 Web 编辑器可视化设计：[CheesePaste/magic-circle-editor](https://github.com/CheesePaste/magic-circle-editor)（Vite + TypeScript，打包为单 HTML，离线可用）。

- **构建**：`npm install && npm run build`，产物 `dist/index.html`
- **使用流程**：编辑器导出 `<id>.json` → 放入本模组 `data/wandscape/magic_circles/`
- **契约关系**：编辑器是本文档的消费方（`src/spec.ts` 为镜像）；示例 spec 以本仓库 `example-specs/` 为准
- **schema 变更流程**：先改本文档（权威）→ 同步编辑器 `src/spec.ts` → 同步 MC 端 `MagicCircleSpec.java`
