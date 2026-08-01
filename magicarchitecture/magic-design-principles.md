# 魔法阵设计原则（从 UsefulMagic 提炼）

本文从第三方模组 **UsefulMagic**（GPL-3.0，参考样例见 `usefulmagic-examples/`）的魔法阵实现中提炼出一套可迁移到 Wandscape `MagicCircleSpec` 的设计原则。目的是让后续设计魔法阵时有章可循，避免"堆元素但不好看"。

> 适用对象：`data/wandscape/magic_circles/*.json` 的编写者 / Web 编辑器使用者。schema 见 [magic-circles.md](magic-circles.md)。

## 核心心法

**一座好看的魔法阵 = 5~8 个"简单层"的叠加，每层只做一件简单的事。**

UsefulMagic 的所有阵法（Small/Mid/LargeFormationStyle）都是 `getCurrentFrames()` 返回若干个独立层（`StyleData`），每层独立半径、转速、旋转方向、缩放动画。复杂度来自**层数**和**层间关系**，不来自单层内部。

设计顺序建议（从贵到便宜，贵的先定）：

1. **分层** — 先想这座阵有哪几层（外环 / 内环 / 几何骨架 / 符文 / 中间点缀）。
2. **定半径** — 各层半径有主次（如 4.0 / 2.6 / 3.4），避免全部等距（会显得平）。
3. **定转速与方向** — 每层独立 `rotate_speed`，**相邻层异向**。
4. **定入场/出场动画** — scale 从 0 缓动涨起、尾段缩小淡出。
5. **选粒子与色系** — 1 种主力粒子 + 同一色系，≤2 色。

## 12 条原则

### P1. 相邻层异向旋转，是最便宜的"活气"
UsefulMagic 到处是 `rotateAsAxis(PI/128)` 与 `rotateAsAxis(-PI/128)` 交替。异向旋转制造视差，读者一眼觉得"它是活的"。

- Wandscape 对应：相邻元素 `rotate_speed` 取相反符号（如 `23` / `-17`）。
- 让最外层快转、核心符文层慢转，层级感更强。

### P2. 虚实交替出节奏 — 实环 + 亮点环 + 几何形
每个法阵都应有三种"质感"的层交替出现：
- **实环**（`continuous`）— 拖尾密度，做基底。
- **亮点环**（`beads`）— 均布有序亮点，做骨架（UsefulMagic 的 `addDottedCircle`）。
- **几何形**（正多边形/星形）— 做"棱角感"，纯圆会糊。

### P3. 相位错开 = 双环不打架
两圈同半径亮点环，第二圈用 `rotation_offset_deg` 偏移半周期，亮点就交错排列（UsefulMagic：两个 `addDottedCircle` 相差 `PI/16`）。

- 适用：双层菱形 / 双层三角 / 双层亮点环。

### P4. 正多边形环给法阵"骨架感"
UsefulMagic 几乎每座阵都有 `addPolygonInCircle`（3/4/5/6/12 边）。三角（能量）、四角（稳定）、六角（传送/六芒）、八角（星芒）是经典视觉语言。

- **Wandscape 当前缺口**：spec 只有 ring/arc/glyph，没有正多边形。建议新增 `polygon` 元素（`sides` + `rotation_offset_deg` 控制顶角朝向），或先临时用 `beads` 环 + 每 N 个加粗模拟。
- 双层多边形错 45° 叠加 → 八角星（UsefulMagic 的 `addPolygonInCircle(...).rotateAsAxis(0.25*PI)` + 原多边形）。

### P5. 顶点挂载符文，别手写每个符文
UsefulMagic 用 `addPolygonInCircleVertices(12, r)` 取顶点，再在**每个顶点**上 `addBuilder(vertex, 子环/子形)`。glyph 环形布置本质上就是这个。

- Wandscape `glyph.count` 已是均布顶点。**缺口**：若要做"顶点处小环/星芒"，需要 `on_vertices` 挂载能力或显式相位列表（`phases: [...]` 指定符文落点），否则只能均布。

### P6. 入场用缓动放大，不是线性
UsefulMagic 用贝塞尔缩放（`CompositionBezierScaleHelper`：10 tick，0.01→1.0），先快后慢；入场附弹性旋转（`SkillRangeDisplay`：`outElastic` 180°→0° 入位）。

- Wandscape 对应：`scale` 关键帧用缓动曲线（如 `[[0,0],[0.5,1.1],[1,1]]`），别 `[[0,0],[1,1]]` 线性涨。
- **缺口**：keyframe 目前只有线性插值，建议加可选 `ease` 字段（`outBack` / `outElastic` / `inOutCubic` / `linear`）。

### P7. 出场 = 逐层错开缩小消散
UsefulMagic `reverseScaleOrRemove` 逐层 `doScaleReversed()` 缩小，配合 DISABLE 状态。层多时不要同帧一起消失——错开 `start` 让消散也有层次。

- Wandscape 对应：`alpha` 尾段 → 0、`scale` 尾段 → 0，各元素 `start` 错开。

### P8. 状态驱动转速 — 待机/工作两种节奏
UsefulMagic 有 `FormationStatus.IDLE/WORKING`：IDLE 慢转（PI/256），WORKING 快转（PI/128）。切换转速是"它被激活了"最便宜的信号。

- **Wandscape 缺口**：spec 是单次播放。若做领域/结界等持续阵，需 `repeat`/`loop` 能力或 `phase`（循环待机 + 施法加速）。传送/仪式一次性施放无需此能力。

### P9. 粒子风格克制 — 一种主力 + 同色系
UsefulMagic 几乎所有阵法粒子都用 `ControlableEndRodEffect`（end rod），靠染色 + `size` 区分。极端统一反而高级。

- Wandscape：一个法阵尽量 1 种主力粒子（同色系），跨色系 ≤2 色。色系决定法阵"属性感"：火=橙红、雷=青蓝、传送=紫、奥术=蓝青。

### P10. 半径由"阵规模"定，元素写相对值
UsefulMagic 把结构（`FormationScale`：小/中/大水晶布局）与表现（particle style 消费半径）分离。

- **Wandscape 缺口**：radius 直接写在元素上。若未来做多级阵，顶层给 `scale` 乘子，元素写相对半径即可复用同一份 spec。

### P11. 表现力优先级：旋转 > 缩放 > 透明度 > 颜色
从样例观察，做"好看"的成本排序：异向旋转（最便宜）→ 缩放动画（入场/消散）→ alpha 开合 → 颜色（最后动）。设计时先把前两项做对，alpha 只做开合，颜色保持统一。

### P12. 整张贴图路线是"高精度静态"补充
UsefulMagic 有三张完整法阵 PNG（`magic_*.png`）直接 billboard 渲染。优点是精细便宜，缺点是无法参数化动画。Wandscape 走粒子路线（JSON 可调、可动画）是对的——贴图路线只作为特殊静态场景的补充，不入主契约。

## 检查清单（写完一个 JSON 后自查）

- [ ] 层数 5~8，每层只做一件事
- [ ] 至少 1 条实环 + 1 圈亮点环 + 1 个几何/符文层
- [ ] 相邻层转速异向，外层快内层慢
- [ ] 入场 scale 用缓动曲线，出场 alpha/scale 尾段归零
- [ ] 各层 `start` 错开（级联展开）
- [ ] 粒子 ≤2 种，色系统一
- [ ] 高度（`y_offset`）不全挤在 0

## 后续 spec 缺口清单（按价值排序）

| 缺口 | 来源原则 | 说明 |
|------|---------|------|
| `polygon` 元素 | P4 | 正多边形环，`sides` 边数 + 旋转偏移 |
| keyframe `ease` 字段 | P6/P10 | 缓动函数，替代纯线性插值 |
| glyph 相位列表 / `on_vertices` | P5 | 顶点挂载子形、符文指定落点 |
| `repeat`/`loop` 持续模式 | P8 | 领域/结界持续运转 |
| 顶层 `scale` 乘子 | P10 | 一套 spec 多级阵复用 |

这些是**设计能力**的扩展，不是当前必须实现的功能；每新增一个再更新本契约与 Web 编辑器。
