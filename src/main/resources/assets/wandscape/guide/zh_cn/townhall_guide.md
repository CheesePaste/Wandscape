# 🏛️ 市政厅（Town Hall）API 级详细指南

市政厅是整座魔法小镇的大脑中枢！

---

## 📖 UI 控件与字段 API 级别明细字典 (UI Options API Reference)

| 控件标识 / 标签 | 类型 / 范围 | 默认值 | 详细作用机制与计算影响 |
| :--- | :--- | :--- | :--- |
| **`Colony Name EditBox`** (`nameBox`) | String (`Max 32 chars`) | `My Colony` | 魔法小镇名称文本输入框。修改后按下 Enter 或失去焦点，向服务端发送 `ColonyNameUpdatePacket` 更改全局魔法小镇名称。 |
| **`Colony Level`** (`stat_level`) | Display Badge (`1 ~ 5`) | `1` | 展现当前魔法小镇等级。等级提升可增加全局人口上限与解锁高级建筑蓝图。 |
| **`Experience Bar`** (`stat_exp`) | Progress Bar (`0 ~ 100%`) | `0/1000` | 展现魔法小镇建设与经营经验进度 (`experience / expToNext`)。经验满后触发市政厅升级。 |
| **`Build Plans Button`** (`btn_open_build_plans`) | MedievalButton | — | 点击触发打开蓝图选建 Overlay (`BuildingSelectionOverlay`)，允许玩家挑选并发布建设计划。 |
| **`Reputation Stat`** (`stat_reputation`) | Rating (-100 ~ +100) | `0` | 游客满意度积累的全局声望值。声望越高，每日城门吸引入城的稀有/富裕游客数量越多。 |

---

## 1. 核心职责
- 管理魔法小镇等级与升级经验
- 提供建设蓝图下发与规划
- 决定初始人口上限与游客吸引力

👉 [前往仓库物流指南](guide:warehouse_guide)  
👉 [返回主引导测试页](guide:test_guide)
