# 数据格式 — 魔法阵

位置：`src/main/resources/data/wandscape/magic_circles/<id>.json`

解析：`magic/internal/MagicCircleLoader`（类目 `magic_circles`）→ `magic/data/MagicCircleSpec`。数据契约由 Web 编辑器导出，MC 端粒子消费。

## 顶层结构

```json
{
  "id": "arcane_hexagram",
  "duration_ticks": 200,       // 默认 120
  "height": 0.1,               // 法阵离地高度
  "elements": [ ... ]          // 元素数组
}
```

## elements[]（Element 枚举：RING/ARC/POLYGON/STAR/GLYPH）

公共字段（record）：

| 字段 | 说明 |
|---|---|
| `type` | ring/arc/polygon/star/glyph |
| `radius` | 半径 |
| `particle` / `particles` | 粒子 id（glow/ember/原版风格 id/自定义） |
| `color` | hex 颜色 |
| `rotation_offset_deg` / `rotate_speed` | 旋转偏移/速度 |
| `start` | 起始时间 |
| `anim` | 动画：`scale` / `alpha` 关键帧 `[[t,v],...]` + `easing`(linear/smoothstep) |
| `mode` | beads（珠链）/ continuous（连续） |
| `density` | 密度 |
| `trail_ticks` | 拖尾 |
| `y_offset` | Y 偏移 |
| `interval_ticks` | 脉冲间隔 |
| `arc_start` / `arc_sweep_deg` | 仅 ARC |
| `sides` | 仅 POLYGON |
| `points` / `inner_ratio` | 仅 STAR |
| `count` / `sprite` / `glyph_scale` / `head_scale` / `tail_scale` | 仅 GLYPH |

各类型示例（arcane_hexagram.json）：

```json
{ "type": "ring", "radius": 4, "particle": "glow", "color": "...",
  "mode": "continuous", "density": 1, "trail_ticks": 20,
  "rotate_speed": 0.05, "start": 0, "anim": {...} },
{ "type": "star", "radius": 3, "points": 6, "inner_ratio": 0.5,
  "particle": "...", "color": "...", "mode": "beads", "density": 0.5,
  "rotate_speed": 0.03, "start": 20, "anim": {...} },
{ "type": "glyph", "radius": 2, "count": 6, "sprite": "rune_arcane",
  "scale": 0.5, "particle": "...", "color": "...",
  "rotation_offset_deg": 0, "rotate_speed": 0.02, "start": 40, "anim": {...} },
{ "type": "polygon", "radius": 3, "sides": 6, "particle": "...",
  "color": "...", "mode": "continuous", "rotation_offset_deg": 0,
  "rotate_speed": 0.04, "start": 0, "anim": {...} }
```

## 渲染行为

- 发射器按 `density × 弧长` 计算数量（JSON 中 `beads` 字段**不被解析**，是冗余）。
- 原版风格 id → vanilla ParticleType 映射（颜色不生效）；glow/ember/未知 → 自定义染色点粒子。
- GLYPH 默认 mode=beads、trail=8、scale 0.3/head 1.35/tail 0.35。
