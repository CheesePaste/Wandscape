# 🌉 填充模式（Fill Mode）API 级详细指南

填充模式用于建造高架桥梁、平整深坑地形或搭建 3D 方块基座。

---

## 📖 UI 控件与字段 API 级别明细字典 (UI Options API Reference)

| 控件标识 / 属性 | 类型 / 范围 | 默认值 | 详细作用机制与计算影响 |
| :--- | :--- | :--- | :--- |
| **`ToolMode.FILL`** (模式切换) | Enum | `FILL` | 激活 3D 立方体填充算法。在选定的 3D 包围盒内进行实心方块填充。 |
| **`ToolMode.DESTROY_FILL`** | Enum | `DESTROY_FILL` | 激活擦除/填方拆除模式。捕获参照方块 (`RefBlockId`) 并清理范围内的填方方块。 |
| **`RoadPreset`** (填充材质) | Card Selector | `Stone Bricks` | 选定用于 3D 填充的目标方块材质（如石砖、木板、基座方块）。 |
| **`StartPos`** (底面顶点) | BlockPos (`X, Y, Z`) | `null` | **`鼠标右键`** 瞄准基座底层触发。设定 3D 填充立方体的底面起始原点。 |
| **`EndPos`** (顶面对角点) | BlockPos (`X, Y, Z`) | `null` | **`鼠标左键`** 瞄准高处对角点触发。拉出一个带有高度与深度的 3D 包围盒 preview。 |
| **`RefBlockId`** (参照方块) | String | 空 | 在 `DESTROY_FILL` 模式下，**`鼠标右键`** 抓取的待擦除目标方块 ID（如 `minecraft:stone_bricks`）。 |
| **`Enter (回车提交)`** | Action Key | — | 向服务端发送 `FillBoxPacket`（或 `DestroyFillPacket`），批量生成 3D 桥梁/基座结构。 |

---

## 🚀 3 步傻瓜式操作流程

### 第一步：进入填充模式
1. 按下 **`V` 键** 打开面板，进入道路工具栏。
2. 切换模式为 **`FILL`**。
3. 双击选择填充材质（如 *Stone Bricks*）。

### 第二步：拉出 3D 立方体
1. 按 **`C` 键** 切换到准心交互。
2. **`鼠标右键`** 点击底面起始点（设置底部 Z/X/Y 坐标）。
3. 移动视线到高处或对角点，**`鼠标左键`** 点击终点位置。
4. 视口中将拉出一个立体的 3D 方块包围盒 preview。

### 第三步：提交生成
1. 按下 **`Enter` (回车键)**。
2. 客户端向服务端发送 `FillBoxPacket`，瞬间填充形成桥梁或立体基座！

---

## 🛠️ 常见问题排查（Troubleshooting）

### Q1: 填错了想删掉怎么办？
- **解决**：在模式栏切换到 **`DESTROY_FILL`** 模式，点击错填的方块，再次按 **`Enter`** 即可一键清除填方。

---

👉 [跳转至 替换模式指南](guide:road_replace_guide)  
👉 [跳转至 样条线贝塞尔指南](guide:road_spline_guide)  
👉 [返回道路总指南](guide:road_guide)
