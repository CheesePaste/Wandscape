# 🏗️ 建筑扫描器（Building Scanner）API 级详细指南

建筑扫描器（Scanner）是创作者用于扫描游戏内建造好的建筑并**一键导出模组结构 JSON 蓝图**的开发工具。

![扫描器结构示意图](wandscape:textures/gui/guide/scanner_diagram.png =200x100)

---

## 📖 UI 控件与字段 API 级别明细字典 (UI Options API Reference)

| 控件标识 / 标签 | 类型 / 范围 | 默认值 | 详细作用机制与计算影响 |
| :--- | :--- | :--- | :--- |
| **`Mode`** | Enum (`SAVE / CORNER`) | `SAVE` | 结构方块工作模式。`SAVE` 为主保存扫描器，`CORNER` 为对角线辅角点方块。 |
| **`Structure Name`** | String | 空 | 结构名称。在 64 格范围内，填有相同 Structure Name 的 `SAVE` 与 `CORNER` 方块会自动配对算出 3D 边界包围盒。 |
| **`Target`** (导出目标) | Enum (`BUILDING / ROAD`) | `BUILDING` | 选择导出类型。`BUILDING` 导出建筑蓝图（含三值与维护费），`ROAD` 导出道路预设并自动热注册。 |
| **`Category`** (类别下拉单) | Enum (`String`) | `basic` | 决定建筑的系统分类（`basic`, `shop`, `service`, `workstation`, `tavern` 等）。 |
| **`Door (X, Y, Z)`** | Integer | `0, 0, 0` | 游客与 NPC 进入该建筑交互的门口 entry 偏移坐标。 |
| **`Building ID`** (`metaId`) | String | `wandscape:new_bldg` | 蓝图在模组内的唯一注册标识符（如 `wandscape:townhall_lv1`）。 |
| **`Display Name`** (`metaName`) | String | `新建筑` | 在选建界面与 HUD 面板中展示的名称。 |
| **`Detect Corners`** | Action Button | — | 主动触发 64 格范围内同名 `CORNER` 角点方块的自动匹配与包围盒重算。 |
| **`Export JSON`** (导出按钮) | Action Button | — | 触发扫描并将蓝图全量序列化输出至 `.minecraft/wandscape_buildings/<id>.json`（自动过滤扫描器方块本身）。 |

---

## 🚀 3 步傻瓜式操作流程

### 第一步：摆放 SAVE 与 CORNER 扫描器方块
1. 在建筑的一角放置【扫描器方块】，在界面中将 Mode 设为 **`CORNER`**，填入结构名称（如 `house1`）。
2. 在建筑的对角线顶点放置另一个【扫描器方块】，将 Mode 设为 **`SAVE`**，填入相同的结构名称（`house1`）。

### 第二步：自动计算包围盒与参数配置
1. 点击 SAVE 扫描器界面中的 **【Detect Corners】** 按钮，系统自动配对计算出 3D 包围盒并实时渲染边框。
2. 配置 `Category` 类别、`Building ID`、`Display Name` 与 `Maintenance Cost` 等经营参数。

### 第三步：一键导出 JSON
1. 点击底部的 **【Export Building JSON】** （或 `ROAD` 模式下的 **【Export Road JSON】**）按钮。
2. 建筑导出至 `.minecraft/wandscape_buildings/<id>.json`；道路导出至 `.minecraft/wandscape_roads/<id>.json` 并自动热注册。

---

## 🛠️ 常见问题排查（Troubleshooting & FAQ）

### Q1: 导出的蓝图建造时发现扫描器方块也被建出来了？
- **解决**：最新版 Scanner 导包时已**自动过滤扫描器方块本身**。

### Q2: 旋转放置建筑时，楼梯或门朝向错乱？
- **解决**：扫描器已自动补全 4 方向 BlockState 的 `facing` 对齐数据。

---

👉 [跳转至 俯瞰选建与旋转指南](guide:overview_guide)  
👉 [返回主测试页](guide:test_guide)
