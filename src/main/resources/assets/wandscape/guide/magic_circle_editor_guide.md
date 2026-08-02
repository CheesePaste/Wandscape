# 🔮 魔法阵 Web 编辑器（Magic Circle Editor）傻瓜式指南

魔法阵 Web 编辑器位于 `tools/magic-circle-editor/`，是基于 Web (Vite + TypeScript) 的可视化粒子特效设计器。

![魔法阵 Web 编辑器示意图](wandscape:textures/gui/guide/magic_editor_diagram.png =200x100)

---

## 1. 🚀 4 步傻瓜式特效设计流程

### 第一步：选择基础形状 (Shape & Geometry)
- **`circle`**：标准圆环描边。
- **`polygon`**：多边形描边（可调边数 3~12）。
- **`star`**：多角星形描边（使用 `beads` 模式沿周长均匀描边，角上不会堆积密度，保持尖朝上）。

### 第二步：配置动画与缓动 (Animation & Smoothstep)
- 设置 **`pulse_interval`** 脉冲频率。
- 设置自转角度与持续扩大/缩小速率。
- 勾选 `smoothstep` 使得粒子出现与消失更加柔和。

### 第三步：调节贝塞尔曲线 (Curve Editor)
- 打开贝塞尔曲线编辑器，拖拽控制点调节粒子生命周期内 **Alpha 透明度** 与 **Scale 缩放比例** 渐变。

### 第四步：导出 Spec JSON 契约
- 点击 **Export Spec** 按钮，导出 `MagicCircleSpec` JSON 文本。
- 将其装载入模组 `assets/wandscape/magic_circles/` 文件夹中，由 MC 客户端粒子发射器（`MagicCircleEmitter`）实时消费！

---

## 🛠️ 常见问题排查（Troubleshooting & FAQ）

### Q1: 导出的魔法阵在 MC 游戏里只有竖立的平面，不能平铺在地面？
- **原因**：`axis` 朝向配置为 `Y` 轴竖直方向。
- **解决**：在 Spec JSON 中将 `axis` 设为 `XZ`（平铺于地面），或者设为 `Y`（竖直传送门/法术防护环）。

### Q2: 粒子渲染出来糊成一团？
- **解决**：开启 `beads` (亮点描边模式)，将 `density`（密度）适当调低，并开启 MC 纹理 Fidelity 忠实预览。

---

👉 [跳转至 建筑扫描器指南](guide:scanner_guide)  
👉 [返回主测试页](guide:test_guide)
