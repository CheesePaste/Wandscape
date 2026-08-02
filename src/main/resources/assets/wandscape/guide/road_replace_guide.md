# 🧱 替换模式（Replace Mode）API 级详细指南

替换模式用于将地表原有的草方块、泥土或沙石**直接替换为精美的道路铺面**。

---

## 📖 UI 控件与字段 API 级别明细字典 (UI Options API Reference)

| 控件标识 / 属性 | 类型 / 范围 | 默认值 | 详细作用机制与计算影响 |
| :--- | :--- | :--- | :--- |
| **`ToolMode.REPLACE`** (模式切换) | Enum | `REPLACE` | 激活地表平铺替换算法。将框选范围内的地表顶层方块替换为选定的道路材质。 |
| **`RoadPreset`** (材质预设) | Card Selector | `Cobblestone` | 从道路材质库中选择替换目标方块（如 *Cobblestone Road*, *Stone Brick Road*, *Dirt Path*）。双击卡片高亮选中。 |
| **`RoadPhase`** (交互相态) | Enum (`BAR / PLACING`) | `BAR` | `BAR` (光标在 UI 上滑动选择预设与模式)；`PLACING` (按下 `C` 键释放光标，准心在世界中定点)。 |
| **`StartPos`** (起点坐标) | BlockPos (`X, Y, Z`) | `null` | **`鼠标右键`** 瞄准地面触发。设置替换矩形区域的第一个基准顶点，在视口中高亮渲染红色边界框。 |
| **`EndPos`** (终点坐标) | BlockPos (`X, Y, Z`) | `null` | **`鼠标左键`** 瞄准地面触发。与 `StartPos` 组合拉出平铺矩形区域，在视口中高亮渲染绿色包围框。 |
| **`GhostPos`** (幽灵预览) | BlockPos | `null` | 准心 64 格射线裁剪（Reach Clip）检测到的视线所指悬停坐标，渲染白色半透明幽灵方块 preview。 |
| **`Enter (回车提交)`** | Action Key | — | 校验 `StartPos` 与 `EndPos` 后，向服务端发送 `RoadPlacePacket` 包，批量替换区域内的地表方块。 |
| **`Backspace (撤销端点)`** | Action Key | — | 优先撤销 `EndPos`；若已清空则撤销 `StartPos`。 |

---

## 🚀 3 步傻瓜式操作流程

### 第一步：进入替换模式
1. 按下 **`V` 键** 打开面板，点击 **Road 道路图标**（或按 `R` 键）。
2. 在底部模式栏选择 **`REPLACE`** 模式。
3. 双击底部的材质卡片（如 *Cobblestone Road*）。

### 第二步：框选地表范围
1. 按 **`C` 键** 隐藏面板，使用视线准心瞄准地面。
2. **`鼠标右键`** 点击起点方块：聊天栏显示 `[Road] Start point set`，地面出现红色高亮起始点。
3. 将准心移动到对角位置，**`鼠标左键`** 点击终点方块：地面将拉出一个绿色框选矩形区域。

### 第三步：提交建造
1. 检查绿色框选区域是否满意。如果不满意，再次 **`鼠标右键`** 可全清重新选取。
2. 确认无误后，按下 **`Enter` (回车键)**！
3. 地表方块瞬间替换为选定的道路材质！

---

## 🛠️ 常见问题排查（Troubleshooting）

### Q1: 按左键/右键没有反应？
- **解决**：按下 **`C` 键** 释放光标回到准心模式。

### Q2: 选错了起点怎么清除？
- **解决**：按 **`Backspace` (退格键)** 可以撤销前一个点；或者直接 **`鼠标右键`** 清空全部选择。

---

👉 [跳转至 填充模式指南](guide:road_fill_guide)  
👉 [跳转至 样条线贝塞尔指南](guide:road_spline_guide)  
👉 [返回道路总指南](guide:road_guide)
