# ➰ 样条线贝塞尔曲线模式（Spline Mode）API 级详细指南

样条线模式用于打造自然优雅弯曲的山路、环形弯道与非规则 3D 曲线道路！

---

## 📖 UI 控件与字段 API 级别明细字典 (UI Options API Reference)

| 控件标识 / 属性 | 类型 / 范围 | 默认值 | 详细作用机制与计算影响 |
| :--- | :--- | :--- | :--- |
| **`ToolMode.SPLINE`** (模式切换) | Enum | `SPLINE` | 激活 3D 三次贝塞尔样条线（Cubic Bezier Spline `SplineModel`）算法。根据控制点集拟合连续平滑曲线。 |
| **`SplinePoint`** (控制节点) | List (`SplineVec3`) | 空 | **`鼠标右键`** 瞄准地形触发。在 3D 空间中添加一个样条线控制节点，自动计算两侧切线方向。 |
| **`handlePrev / handleNext`** | SplineVec3 Vector | `(-2,0,0)` / `(+2,0,0)` | **控制句柄拉伸与切线**。在 UI 模式下按住 **`鼠标左键`** 拖拽。句柄长度（推荐 2~4 格）决定曲率弧度，方向决定切线切入角。 |
| **`closed`** (闭环环路) | Boolean (`true/false`) | `false` | 决定样条线是否首尾相连封闭形成环形弯道/环形广场。 |
| **`CurveSampling`** (插值采样) | Algorithm (`Distance`) | `1.0 Block` | 沿贝塞尔曲线做 3D 密度均分采样（CurveSampling）。在采样点周围自动投影填充道路方块。 |
| **`SplineBuildPacket`** (提交发包) | Packet | — | 按下 **`Enter`** 触发。将全量 `SplineModel` 序列化为 JSON 发送给服务端，在世界中批量生成平滑曲线道路。 |

---

## 🚀 4 步傻瓜式操作流程

### 第一步：开启 3D 样条线模式
1. 按下 **`V` 键** 打开面板，选择 **Road 道路工具**。
2. 将模式切换为 **`SPLINE`**（样条线）。
3. 挑选道路曲线材质预设。

### 第二步：添加控制节点（Spline Points）
1. 按 **`C` 键** 切换到准心视线模式。
2. 沿弯道走向，依次按下 **`鼠标右键`** 放置样条线控制节点。
3. 每次放置节点后，`SplineModel` 会自动生成紫金色的曲线预览线段。

### 第三步：调节弧度与控制句柄（Control Handles）
1. 按 **`C` 键** 唤出鼠标光标。
2. 悬停在曲线节点上，按住 **`鼠标左键`** 拖拽前后控制句柄（`handlePrev` / `handleNext`）。
3. 调整句柄的拉伸长度与角度，实时观察 3D 曲线平滑度的变化。

### 第四步：提交曲线生成道路
1. 满意后按下 **`Enter` (回车键)**！
2. 系统发送 `SplineBuildPacket` 到服务端，沿平滑曲线做 3D 采样铺设平滑弯道地质！

---

## 🛠️ 常见问题排查（Troubleshooting）

### Q1: 曲线弯角处方块有断裂或空隙？
- **原因**：控制句柄拉得过长，导致弯角曲率过大。
- **解决**：缩短控制句柄的距离（推荐 2~4 格），算法会自动增加插值采样密度（CurveSampling）。

---

👉 [跳转至 替换模式指南](guide:road_replace_guide)  
👉 [跳转至 填充模式指南](guide:road_fill_guide)  
👉 [返回道路总指南](guide:road_guide)
