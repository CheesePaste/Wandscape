# 🏗️ 建筑扫描器（Building Scanner）API 级指南

建筑扫描器（Building Scanner）是创作者用于扫描游戏内建造好的建筑/道路并**一键导出模组 JSON 蓝图与道路预设**的核心开发工具。

![扫描器中世纪 UI 界面演示](wandscape:textures/gui/guide/scanner_ui.png)

---

## 📖 UI 控件与模式明细 (UI Reference)

界面采用标准中世纪金边主题 (`MedievalScreen`) 与手绘渐变按钮 (`drawMinimalBox`)，输入框具有古铜金线边框与 **Focus / Hover 动态发光** 效果，且配置有原生视口裁剪（Scissor Clip），滑动时绝不出界。

### 1. 结构配对模式 (`BlockMode`)
- **`SAVE` (保存主控)**：主扫描器，负责计算包围盒、展示配置面板与执行 JSON 导出。
- **`CORNER` (辅角点)**：用于标记 3D 包围盒对角线顶点的辅助方块。
- **自动配对**：在 64 格范围内，填有相同 `Structure Name` 的 `SAVE` 与 `CORNER` 方块会自动配对算准 3D 包围盒，无需手动录入坐标。

### 2. 导出目标模式 (`TargetMode`)
- **`BUILDING` (建筑模式)**：
  - 展示完整建筑配置（门偏移 `Door Offset`、游览交互区 `Tourist Zones`、三值 `Comfort/Magic/Wonder`、解锁等级 `Unlock Level`、周期维护费 `Maintenance Cost` 以及商店/服务/节点特化参数）。
  - 点击 **【导出建筑 JSON】** 将蓝图导出至 `.minecraft/wandscape_buildings/<id>.json`。
- **`ROAD` (道路模式 - 特化简化)**：
  - 自动隐去所有建筑专属配置，界面极为精简通透。
  - 仅保留 `Road Preset ID` 与 `Display Name`。
  - 点击 **【导出与热注册道路 JSON】** 导出至 `.minecraft/wandscape_roads/<id>.json` 并**在游戏内立即热注册生效**！

---

## 📋 字段与操作明细表

| 控件标识 / 标签 | 类型 | 说明 |
| :--- | :--- | :--- |
| **`Mode`** | Button | 切换 `SAVE` 主保存模式与 `CORNER` 辅角点模式。 |
| **`Structure Name`** | Input | 结构名称，配对依据（例：`townhall_lv1`）。 |
| **`Target`** | Button | 切换 `BUILDING` 建筑模式与 `ROAD` 道路模式。 |
| **`Type` (Category)** | Button | 决定建筑类型（`basic`, `shop`, `service`, `node`, `tavern` 等）。 |
| **`❖ 匹配角点`** | Button | 主动触发 64 格范围内同名 `CORNER` 角点方块配对与包围盒重算。 |
| **`X±1 / Y±1 / Z±1`** | Buttons | 6 个 3D 包围盒微调扩增按钮，可直接向 XYZ 方向扩增 1 格边界，无需重新摆放方块。 |
| **`❖ 门偏移`** | Inputs | 设定 NPC / 游客进入建筑交互的 Entry 偏移坐标 `(X, Y, Z)`。 |
| **`自动检门`** | Button | **自动扫描 3D 包围盒内的门方块**并填入门偏移，支持多门多次点击循环切换。 |
| **`❖ 游览交互区`** | Rows | 配置游客在建筑内停留交互的 3D 边界区域（`+ 添加` / `× 删除`）。 |
| **`❖ 放置元数据`** | Inputs | 设定 Building ID（如 `wandscape:shop_bakery`）、显示名称与三值属性。 |
| **`❖ 周期维护费`** | Rows | 设定建筑维持运转需消耗的元素与数量（如 `earth: 1`）。 |
| **`扫描区域`** | Button | 统计 3D 包围盒内的非空气有效方块数量（**自动剔除所有扫描器方块本身**）。 |
| **`导出 JSON`** | Button | 序列化导出 JSON 文件（**自动过滤扫描器方块本身**）。 |

---

## 🚀 3 步操作流程

1. **摆放与命名**：在建筑顶点放置 `SAVE` 扫描器并填写结构名称；在对角顶点放置 `CORNER` 扫描器并填写相同名称。
2. **匹配与配置**：点击 `SAVE` 界面中的 **【❖ 匹配角点】** 自动计算 3D 尺寸；根据需求配置元数据、维护费或切换为 `ROAD` 模式。
3. **一键导出**：点击 **【导出建筑 JSON】** 或 **【导出与热注册道路 JSON】**，查看聊天栏成功提示。

---

👉 [返回选建指南](guide:overview_guide)  
👉 [返回主测试页](guide:test_guide)
