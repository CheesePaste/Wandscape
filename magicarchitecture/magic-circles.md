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
      "radius": 4.0,
      "particle": "glow",
      "color": "#00ff88",
      "density": 1.5,
      "trail_ticks": 10,
      "rotate_speed": 0.4,
      "y_offset": 0.0,
      "start": 0.0,
      "anim": {
        "scale":    [[0, 0], [0.5, 1], [1, 1.1]],
        "alpha":    [[0, 0], [0.3, 1], [0.85, 1], [1, 0]],
        "rotation": [[0, 0], [1, 1]]
      }
    },
    {
      "type": "arc",
      "radius": 3.0,
      "arc_start_deg": 0,
      "arc_sweep_deg": 240,
      "particle": "ember",
      "color": "#ff8800",
      "density": 1.5,
      "trail_ticks": 8,
      "rotate_speed": -0.3,
      "start": 0.25
    },
    {
      "type": "glyph",
      "radius": 3.5,
      "count": 8,
      "sprite": "rune_fire",
      "scale": 0.3,
      "color": "#ffaa00",
      "rotate_speed": 0.2,
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
| radius | double | 基础半径（编辑器里拖出来的值） |
| particle | string | 粒子风格 id：`glow`/`ember`/`flame`/`endRod`…（映射到 MC 粒子/自定义粒子） |
| color | string? | 可选十六进制 `#RRGGBB`；自定义粒子可染色，vanilla 粒子忽略 |
| rotate_speed | double | 静态旋转速度（弧度/秒，负=反向），默认 0 |
| start | double | 归一化起始时间 [0,1)：元素在总时长哪一刻开始出现（**级联核心**），默认 0 |
| anim | Anim? | 关键帧曲线（见下） |

### 类型专属

| 类型 | 字段 | 说明 |
|------|------|------|
| ring | density | 每格弧长每 tick 粒子数（默认 1.5） |
| ring | trail_ticks | 粒子存活 tick = 拖尾长度（默认 10） |
| ring | y_offset | 多层堆叠的纵向偏移（默认 0） |
| arc | arc_start_deg | 起始角度（度） |
| arc | arc_sweep_deg | 扫过角度（度，<360 为部分圆弧） |
| arc | density / trail_ticks / y_offset | 同 ring |
| glyph | count | 符文个数 |
| glyph | sprite | 符文贴图 key（后续与 MC 共享同一批资源） |
| glyph | scale | 符文渲染尺寸（默认 0.3） |

### Anim（关键帧曲线）

`Anim` 可含三组曲线，均取归一化时间 [0,1]：

| 字段 | 含义 | 默认 |
|------|------|------|
| scale | 半径/glyph 尺寸倍率 | `[[0,1]]`（恒 1） |
| alpha | 粒子透明度倍率（≤0 不发射） | `[[0,1]]` |
| rotation | 窗口内附加旋转（弧度），叠在 `rotate_speed×elapsed` 之上 | `[]` |

keyframe 格式 `[[t, v], ...]`，`t` 归一化 [0,1]，线性插值（可选 smoothstep 缓动）。

## 动画模型

- 全局 `duration_ticks`；每元素 `start`（归一化）。
- 元素在 `[start, 1]` 窗口内激活；全局时间 `t` 映射到**局部时间** `lt = (t - start) / (1 - start)`，再对 `anim` 曲线采样。`t < start` 不发射。
- **"一层一层展开" = 各元素不同 `start`**（编辑器 Cascade 按钮自动按步长排布）。
- 典型效果映射：
  - 逐渐放大：`scale: [[0,0],[0.5,1],...]`
  - 缩小消散：`scale` 尾段 → 0，`alpha` 尾段 → 0
  - 层进展开：每层不同 `start` + 各自 `scale` 从 0 涨起

## 粒子模型（MC 渲染端如何消费）

- **ring/arc**：每 tick 按当前 `radius×scale`、当前旋转角、当前 `alpha`，在圆周/弧上撒 `density × 弧长` 个粒子，带 ±0.2 格随机抖动（避免格子状伪影），静止或轻微外漂，存活 `trail_ticks`，颜色取 `color`。
- **glyph**：每 tick 在 `count` 个符文位置撒短命符文粒子（存活 `trail_ticks`），尺寸 = glyph `scale`，alpha 来自曲线。
- 同一时刻存活粒子约几百个，MC 可轻松承受。

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
      "density": 1.5,
      "trail_ticks": 10,
      "rotate_speed": 0.4,
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
      "density": 1.5,
      "trail_ticks": 8,
      "rotate_speed": -0.3,
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
      "rotate_speed": 0.2,
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
      "density": 2.0,
      "trail_ticks": 12,
      "rotate_speed": 0.15,
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
      "density": 1.5,
      "trail_ticks": 10,
      "rotate_speed": 0.4,
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
      "rotate_speed": -0.1,
      "start": 0.4,
      "anim": { "alpha": [[0, 0], [0.2, 1], [1, 1]] }
    }
  ]
}
```
