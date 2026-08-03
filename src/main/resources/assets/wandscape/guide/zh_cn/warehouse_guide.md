# 📦 仓库与物资调配 API 级详细指南

仓库负责全城的自动化资源存储与物流分配！

---

## 📖 UI 控件与字段 API 级别明细字典 (UI Options API Reference)

| 控件标识 / 标签 | 类型 / 范围 | 默认值 | 详细作用机制与计算影响 |
| :--- | :--- | :--- | :--- |
| **`Tab Switcher`** (`activeTab`) | TabBar (`0/1`) | `0` | 切换面板模式。`Tab 0: Overview` (全局物资与六元素存量)；`Tab 1: Exchange` (玩家背包与仓库互存)。 |
| **`Search Input`** (`searchInput`) | String | 空 | 物资搜索过滤框。支持输入物品中文/英文名称不区分大小写进行模糊匹配。 |
| **`Element Panel`** (`elementPanel`) | Display Grid | — | 展现当前魔法小镇储备的六大原生魔力元素（`FIRE`, `WATER`, `EARTH`, `AIR`, `ORDER`, `CHAOS`）的总数量。 |
| **`Supply Gap Tab`** (`supply_gap_tab`) | Alert Tab | — | **物资缺口面板**。当合成台或工作站生产因缺少材料卡住时，缺少的材料在此高亮展示。 |
| **`Quantity Slider`** (`qtySlider`) | Slider (`1 ~ 64`) | `1` | 转移数量调节拉条。控制每次存入或取出物品的堆叠数量。 |
| **`Deposit / Withdraw Buttons`** | MedievalButton | — | 存入（Deposit）与取出（Withdraw）按钮。触发仓库与玩家背包之间的数据交互。 |

---

👉 [返回市政厅指南](guide:townhall_guide)  
👉 [返回主引导测试页](guide:test_guide)
