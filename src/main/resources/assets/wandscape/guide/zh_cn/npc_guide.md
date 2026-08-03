# 🧙‍♂️ 法师 NPC 面板 API 级详细指南

法师（Npc）是殖民地自动化的核心劳工实体！

---

## 📖 UI 控件与字段 API 级别明细字典 (UI Options API Reference)

| 控件标识 / 标签 | 类型 / 范围 | 默认值 | 详细作用机制与计算影响 |
| :--- | :--- | :--- | :--- |
| **`Wand Equipment Slot`** | ItemSlot | 空/默认法杖 | **法杖装备槽**。点击放入自定义法杖可提升法师法术强度与施法距离。 |
| **`Health Stat`** (`stat_hp`) | Display (`Cur/Max`) | `20/20` | 当前生命值与最大生命上限。 |
| **`Mana Stat`** (`stat_mana`) | Display (`Cur/Max`) | `100/100` | 当前魔法值存量与最大魔法上限。法师执行原子任务会消耗魔法。 |
| **`Mana Regen`** (`stat_regen`) | Int (`pts/s`) | `5` | 魔法自然恢复速率。 |
| **`Spell Power`** (`stat_spell`) | Int | `10` | 法术强度。影响施法速度与合成倒计时缩短比例。 |
| **`Cast Range`** (`stat_range`) | Int (`blocks`) | `16` | 施法最大有效距离（格数）。 |
| **`Mana Cost Multiplier`** | Float (`0.5 ~ 2.0`) | `1.0` | 施法消耗系数。系数越低消耗越省魔。 |

---

👉 [前往游客调试指南](guide:tourist_guide)  
👉 [返回主测试页](guide:test_guide)
