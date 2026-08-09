# 🧳 游客 AI 与调试 API 级详细指南

游客是短居访客，驱动着城池的游客经济！

---

## 📖 UI 控件与字段 API 级别明细字典 (UI Options API Reference)

| 控件标识 / 标签 | 类型 / 范围 | 默认值 | 详细作用机制与计算影响 |
| :--- | :--- | :--- | :--- |
| **`Energy Stat`** | Display (`0 ~ 100`) | `100` | 体力值。体力过低会触发前往旅馆（Hotel）过夜或离城。 |
| **`三条需求条`** (Comfort/Magic/Wonder) | Display (`fill/need`) | — | 游客的三条需求条（舒适/魔法/奇观）。逛对应建筑会填充；三条全满的游客夜晚离场会留经验，法师会留下简历可被招募。 |
| **`Tourist Level`** | Display (`1 ~ 5`) | `1` | 游客富裕等级。等级越高消费力与带入金币越多。 |
| **`State Badge`** | Enum (`String`) | `VISITING` | 当前 AI 状态（`VISITING` 逛店, `EXPLORING` 游览景点, `SLEEPING` 住宿, `WANDERING` 漫步）。 |
| **`Target Building`** | Display String | — | 游客当前寻路前往的目标建筑名称与坐标 (`X, Y, Z`)。 |
| **`Cooldown Ticks`** | Display Int | `0` | 距离下一次 AI 决策与行为判定的剩余延迟 Tick 数。 |

---

👉 [前往奇观/异象指南](guide:anomaly_guide)  
👉 [返回主测试页](guide:test_guide)
