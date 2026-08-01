# 魔法阵 JSON 格式（MagicCircleSpec）

位置：`data/wandscape/magic_circles/*.json`（由独立 Web 编辑器导出，MC 端加载同款）

Web 编辑器与 MC 粒子渲染器的**唯一契约**：两端都只"画这份几何 spec"，互不搬渲染管线。

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
      "beads": 20,
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
| duration_ticks | int | 整个法阵总时长（游戏 tick） |
| height | double | 法阵中心离地高度（默认 0.1） |
| elements | Element[] | 元素列表（ring / arc / glyph 三种） |

### Element 通用字段

| 字段 | 类型 | 说明 |
|------|------|------|
| type | string | `ring` / `arc` / `glyph` |
| axis | [x,y,z] | 法阵平面法线，默认 `[0,1,0]`（水平地面）；`[1,0,0]`/`[0,0,1]` 为竖直环（传送门）。编辑器提供朝向预设 |
| radius | double | 基础半径（编辑器里拖出来的值） |
| particle | string | 粒子风格 id：`glow`/`ember`/`flame`/`endRod`…（映射到 MC 粒子/自定义粒子） |
| color | string? | 可选十六进制 `#RRGGBB`；自定义粒子可染色，vanilla 粒子忽略 |
| rotation_offset_deg | double | 初始相位偏移（度），同半径双环靠它错开，默认 0 |
| rotate_speed | double | 静态旋转速率（**度/秒**，负=反向），默认 0。合成公式见"旋转与朝向" |
| start | double | 归一化起始时间 [0,1)：元素在总时长哪一刻开始出现（**级联核心**），默认 0 |
| anim | Anim? | 关键帧曲线（见下） |

### 类型专属

| 类型 | 字段 | 说明 |
|------|------|------|
| ring | mode | 排布模式：`beads`（**默认**，固定亮点均布成环，有序） / `continuous`（连续密度拖尾） |
| ring | beads | beads 模式亮点数（默认按周长 1.2 格/点推算，钳 [4,48]） |
| ring | density | continuous 模式：每格弧长每 tick 粒子数（默认 1.5） |
| ring | trail_ticks | continuous 模式：粒子存活 tick = 拖尾长度（默认 10） |
| ring | y_offset | 多层堆叠的纵向偏移（默认 0） |
| arc | arc_start_deg | 起始角度（度） |
| arc | arc_sweep_deg | 扫过角度（度，<360 为部分圆弧） |
| arc | mode / beads / density / trail_ticks / y_offset | 同 ring |
| glyph | count | 符文个数 |
| glyph | sprite | 符文贴图 key（后续与 MC 共享同一批资源） |
| glyph | scale | 符文渲染尺寸（默认 0.3） |

### Anim（关键帧曲线）

`Anim` 可含三组曲线，均取归一化时间 [0,1]：

| 字段 | 含义 | 默认 |
|------|------|------|
| scale | 半径/glyph 尺寸倍率 | `[[0,1]]`（恒 1） |
| alpha | 粒子透明度倍率（≤0 不发射） | `[[0,1]]` |
| rotation | 窗口内附加旋转角度（**度**），叠在静态旋转之上（合成公式见"旋转与朝向"） | `[]` |

keyframe 格式 `[[t, v], ...]`，`t` 归一化 [0,1]，线性插值（可选 smoothstep 缓动）。

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
angle(T) = rotation_offset_deg
         + rotate_speed(度/秒) × (T - start × duration_ticks) / 20
         + anim.rotation(lt)
```

- `T0 = start × duration_ticks` 为元素出现时刻，`lt` 为局部时间。**elapsed 从窗口起点计**——级联元素从自己出现那一刻才开始转。
- `rotate_speed` 负值 = 反向；每元素独立，可做双环对旋。
- 线性 `anim.rotation` 曲线（如 `[[0,0],[1,180]]`）等价于叠加一段恒定附加速率。

### 朝向（axis）

- 默认水平（`[0,1,0]`，地面法阵）。竖直环用 `[1,0,0]`（朝 X）或 `[0,0,1]`（朝 Z）——**传送门/传送阵必用竖直环**。
- 任意倾斜用归一化法线 `[nx,ny,nz]`。两端渲染共用同一定点公式：由法线 `n` 构造正交基 `a = normalize(cross(n, m))`、`b = cross(n, a)`，圆周点 `p = a·cos θ + b·sin θ`（`m` 为任意不与 `n` 平行的单位向量）。
- 编辑器提供朝向预设：地面 / 竖直-X / 竖直-Z / 自定义。

## 粒子模型（MC 渲染端如何消费）

- **ring/arc · beads**（**默认**）：固定 `beads` 个亮点均布在圆周/弧上，随 `rotate_speed` 整体旋转，**无随机抖动**（有序不糊）。尺寸 = 粒子基础 quadSize×2（稳定，不用年龄曲线），亮度带一圈慢速行进波（shimmer）。alpha 来自曲线，级联展开由 `start` 控制。
- **ring/arc · continuous**：每 tick 按当前 `radius×scale`、当前旋转角、当前 `alpha`，在圆周/弧上撒 `density × 弧长` 个粒子，带 ±0.2 格随机抖动（避免格子状伪影），存活 `trail_ticks`。适合火焰/雾等需要密度的风格。
- **glyph**：每 tick 在 `count` 个符文位置撒短命符文粒子（存活 `trail_ticks`），尺寸 = glyph `scale`，alpha 来自曲线。
- 同一时刻存活粒子约几百个，MC 可轻松承受。

### 粒子 fidelity（vanilla 贴图 + 尺寸）

`particle` 风格分两类：

- **原版粒子**：完全复现 MC 原生贴图与尺寸（quadSize 年龄曲线），`color` 字段**不生效**（贴图本色）。贴图来自 `assets/minecraft/textures/particle/*.png`，帧列表来自 `assets/minecraft/particles/<id>.json`。
- **模组自定义粒子**（`glow`/`ember`）：复用 MC `glow.png` 贴图 + 元素 `color` 染色。

尺寸口径：基础 quadSize = `0.1 × rand(0.5~1.0) × 2`（`SingleQuadParticle`，随机取期望 0.15），各粒子类再乘自带系数并叠年龄曲线。**quadSize 是半宽**，渲染宽 = `2 × quadSize`。`sizeOf(age, lifetime)` 移植各粒子类的 `getQuadSize`；`lifetime` 用元素 `trail_ticks`，曲线铺满可见生命。贴图帧随 age 推进（仿 `setSpriteFromAge`），如灵魂 swirl、enchant 字母翻页。

**行为不原版**：ghost-trail 模型撒零速度粒子、静止贴环——火焰/灵魂不上升、暴击不坠落。贴图和尺寸 100% 原版，运动为模组自控。

风格 id ↔ MC ParticleTypes 映射（编辑器 `mc-particles.ts` 为唯一实现，MC 端复用同一张表）：

| 风格 id | MC id | 贴图帧 | quadSize（半宽） | 尺寸曲线 | 渲染层 |
|---------|-------|--------|-----------------|---------|--------|
| flame | flame | flame | 0.15 | 随龄缩小 ×(1−f²×0.5) | opaque |
| soul | soul | soul_0..10 | 0.225 | 恒定 | opaque |
| endRod | end_rod | glitter_7..0 | 0.1125 | 恒定 | translucent |
| portal | portal | generic_0..7 | 0.06 | 随龄放大 ×f | translucent |
| enchant | enchant | sga_a..z | 0.1125 | 恒定 | translucent |
| enchanted_hit | enchanted_hit | enchanted_hit | 0.1125 | 恒定 | translucent |
| spark | electric_spark | glow | 0.225 | 恒定 | translucent |
| crit | crit | critical_hit | 0.1125 | 前 1/32 弹入 | opaque |
| smoke | smoke | generic_7..0 | 0.1125 | 前 1/32 弹入 | opaque |
| large_smoke | large_smoke | generic_7..0 | 0.28125 | 前 1/32 弹入 | opaque |
| cloud | cloud | generic_7..0 | 0.28125 | 前 1/32 弹入 | translucent |
| note | note | note | 0.225 | 前 1/32 弹入 | translucent（可染色） |
| white_ash | white_ash | generic_0 | 0.1125 | 前 1/32 弹入 | opaque |
| glow | —（自定义） | glow | 0.12 | 恒定 | translucent（可染色） |
| ember | —（自定义） | glow | 0.08 | 恒定 | translucent（可染色） |

## 与仪式/传送的衔接

- 仪式（含传送）通过 `circle_id` 绑定一张魔法阵（如 `ritual_teleport`）；施法时走 `MagicCircleCastPacket` → `MagicCircleEmitter`，环数/半径/动画全部来自 JSON。
- 现有硬编码视觉（`WandscapeRitualOps.self_teleport` 的随机 PORTAL 爆点、`WandscapeNpcRenderer.spawnRitualCircle` 的 3 环 ENCHANT）上线后迁移为 spec 圆，不再散落魔法阵代码。
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
