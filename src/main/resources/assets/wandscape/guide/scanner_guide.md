# 🏗️ 建筑扫描器（Building Scanner）API 级详细指南

建筑扫描器（Scanner）是创作者用于扫描游戏内建造好的建筑并**一键导出模组结构 JSON 蓝图**的开发工具。

![扫描器结构示意图](wandscape:textures/gui/guide/scanner_diagram.png =200x100)

---

## 📖 UI 控件与字段 API 级别明细字典 (UI Options API Reference)

| 控件标识 / 标签 | 类型 / 范围 | 默认值 | 详细作用机制与计算影响 |
| :--- | :--- | :--- | :--- |
| **`Category`** (类别下拉单) | Enum (`String`) | `basic` | 决定建筑的系统分类。可选值：`basic`, `government`, `node`, `storage`, `workstation`, `crafting_station`, `potion_station`, `tavern`, `shop`, `service`, `decoration`, `wonder`。选择不同类别会自动展开/隐藏专属的字段面板。 |
| **`Min (X, Y, Z)`** | Integer (`-64 ~ 64`) | `0, 0, 0` | 扫描 3D 包围盒的起始相对坐标（以扫描器方块为参照原点）。用于限定扫描的最小方块边界。 |
| **`Max (X, Y, Z)`** | Integer (`-64 ~ 64`) | `10, 10, 10` | 扫描 3D 包围盒的结束相对坐标。与 Min 点组合形成 3D 立方体扫描区域。 |
| **`Door (X, Y, Z)`** | Integer | `0, 0, 0` | 游客与 NPC 进入该建筑交互的门口 entry 偏移坐标。引导 NPC 在正确的坐标点触发动作。 |
| **`Building ID`** (`metaId`) | String | `wandscape:new_bldg` | 蓝图在模组内的唯一注册标识符（格式 `namespace:path`），如 `wandscape:townhall_lv1`。 |
| **`Display Name`** (`metaName`) | String | `新建筑` | 在选建界面与 HUD 面板中向玩家展示的本地化中文/英文名称。 |
| **`Comfort / Magic / Wonder`** | Integer (`0 ~ 100`) | `10, 0, 0` | 评分三值。`Comfort`（舒适度：影响旅馆/商店收益）、`Magic`（魔力值：影响法师恢复）、`Wonder`（奇观值：影响全城声望加成）。 |
| **`Unlock Level`** | Integer (`1 ~ 5`) | `1` | 殖民地升级解锁该蓝图所需的最低市政厅等级。 |
| **`Maintenance Cost`** | Map (`Element -> Int`) | 空 | 建筑维持运转每周期需消耗的元素（`FIRE`, `WATER`, `EARTH`, `AIR`, `ORDER`, `CHAOS`）。若仓库缺元素将导致建筑 `SHUTDOWN` 停运。 |
| **`Shop Goods`** (当 Category=shop) | List (`Item -> Ratings`) | 空 | 配置商店允许上架销售的商品 Item ID，及其额外赋予的 Comfort/Magic/Wonder 评分。 |
| **`Service Element Output`** | Map (`Element -> Int`) | 空 | 当游客在该服务建筑消费时，每次交互向殖民地仓库反哺产出的元素类型与数量。 |
| **`Export JSON`** (导出按钮) | Action Button | — | 点击触发客户端校验并将蓝图数据全量序列化输出至 `.minecraft/wandscape/exports/<metaId>.json`。 |

---

## 🚀 3 步傻瓜式操作流程

### 第一步：放置扫描器方块
1. 在选定的建筑角落放置一个【建筑扫描器方块】。
2. 右键打开扫描器 UI 面板。

### 第二步：框选 3D 边界与参数配置
1. 在 `Min (X, Y, Z)` 与 `Max (X, Y, Z)` 中填入相对坐标。
2. 在 `Category` 下拉菜单中选择建筑类别。
3. 填入 `Building ID` 与 `Display Name`，并设定 `Comfort` 与 `Maintenance Cost`。

### 第三步：一键导出 JSON
1. 点击底部的 **【Export JSON】** 按钮。
2. 文件将自动保存在 `.minecraft/wandscape/exports/<id>.json`。

---

## 🛠️ 常见问题排查（Troubleshooting & FAQ）

### Q1: 导出的蓝图建造时发现扫描器方块也被建出来了？
- **解决**：最新版 Scanner 导包时已**自动过滤扫描器方块本身**。

### Q2: 旋转放置建筑时，楼梯或门朝向错乱？
- **解决**：扫描器已自动补全 4 方向 BlockState 的 `facing` 对齐数据。

---

👉 [跳转至 俯瞰选建与旋转指南](guide:overview_guide)  
👉 [返回主测试页](guide:test_guide)
