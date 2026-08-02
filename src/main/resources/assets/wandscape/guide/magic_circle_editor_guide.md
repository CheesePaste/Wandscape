# 🔮 魔法阵 Web 编辑器（Magic Circle Editor）API 级详细指南

魔法阵 Web 编辑器位于 `tools/magic-circle-editor/`，是基于 Web (Vite + TypeScript) 的可视化粒子特效设计器。

![魔法阵 Web 编辑器示意图](wandscape:textures/gui/guide/magic_editor_diagram.png =200x100)

---

## 📖 UI 控件与字段 API 级别明细字典 (UI Options API Reference)

| 控件标识 / 属性 | 类型 / 范围 | 默认值 | 详细作用机制与计算影响 |
| :--- | :--- | :--- | :--- |
| **`shape`** (几何形状) | Enum (`String`) | `circle` | 决定魔法阵底面几何形状。可选值：`circle` (圆环), `polygon` (正多边形), `star` (多角星形)。 |
| **`polygon_sides`** (边/角数) | Integer (`3 ~ 12`) | `5` | 当 `shape` 为 `polygon` 或 `star` 时有效，控制多边形边数或星形的顶点角数（如 5 为五角星）。 |
| **`beads`** (周长描边模式) | Boolean (`true/false`) | `true` | **周长均匀描边算法**。当为 `true` 时，沿多边形/星形外廓周长等间距分布粒子亮点，避免在星形尖角处出现过度密集卡顿。 |
| **`density`** (粒子密度) | Float (`0.1 ~ 5.0`) | `1.0` | 控制单位长度上生成的粒子数量。数值越高描边发光越浓密，数值低则呈现疏朗符文感。 |
| **`pulse_interval`** (脉冲周期) | Integer (`0 ~ 200`) | `40` | 魔法阵呼吸脉冲的 Tick 周期（20 Ticks = 1秒）。控制环形扩大与收缩的周期律动节奏。 |
| **`smoothstep`** (平滑插值) | Boolean (`true/false`) | `true` | 开启 Hermite 平滑淡入淡出插值（Smoothstep），使粒子在出现与消逝时过渡更加自然柔和。 |
| **`axis`** (投影轴向) | Enum (`XZ / Y`) | `XZ` | 决定魔法阵在 MC 游戏中的投影平面。`XZ` 为水平平铺于地面（常规法阵），`Y` 为竖直悬浮（传送门/防御护盾）。 |
| **`radius`** (外径半径) | Float (`0.5 ~ 10.0`) | `2.5` | 魔法阵的外廓半径（单位：MC 方块格数）。 |
| **`color_start / color_end`** | RGBA Hex (`#RRGGBBAA`) | `#A020F0FF` | 粒子生命周期起始与终止的颜色渐变，支持 alpha 透明度渐变。 |
| **`curve_alpha`** (透明度曲线) | Bezier 4-Points | `(0,0)->(1,1)` | 4点三次贝塞尔控制曲线，精确调节粒子生命周期中 Alpha 值的变化走势。 |
| **`curve_scale`** (缩放曲线) | Bezier 4-Points | `(0,0)->(1,1)` | 4点三次贝塞尔控制曲线，精确调节粒子生命周期中 Scale 尺寸膨胀/缩小走势。 |
| **`Export Spec`** (导出契约) | Action Button | — | 导出符合 `MagicCircleSpec` Schema 的 JSON 规范文件，可直接装载至模组 `magic_circles/`。 |

---

## 🚀 4 步傻瓜式特效设计流程

### 第一步：选择基础形状 (Shape & Geometry)
在下拉框选择 `circle`、`polygon` 或 `star`，并调整边角数 `polygon_sides`。

### 第二步：配置动画与缓动 (Animation & Smoothstep)
开启 `beads` 模式与 `smoothstep` 平滑插值，设定 `pulse_interval` 呼吸周期。

### 第三步：调节贝塞尔曲线 (Curve Editor)
拖拽贝塞尔曲线节点，定制 `curve_alpha` 与 `curve_scale` 动画变化曲线。

### 第四步：导出 Spec JSON 契约
点击 **Export Spec** 导出 JSON 契约文件并放入模组资源包中。

---

## 🛠️ 常见问题排查（Troubleshooting & FAQ）

### Q1: 导出的魔法阵在 MC 游戏里只有竖立的平面，不能平铺在地面？
- **解决**：在编辑器中将 `axis` 切换为 `XZ`（平铺于地面）。

---

👉 [跳转至 建筑扫描器指南](guide:scanner_guide)  
👉 [返回主测试页](guide:test_guide)
