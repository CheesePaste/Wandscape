# ⛏️ 资源节点（Node）API 级详细指南

资源节点是魔法小镇自动化采集的核心场所！

---

## 📖 UI 控件与字段 API 级别明细字典 (UI Options API Reference)

| 控件标识 / 标签 | 类型 / 范围 | 默认值 | 详细作用机制与计算影响 |
| :--- | :--- | :--- | :--- |
| **`Element Cycle Button`** | CycleButton | `FIRE` | 在 `FIRE`, `WATER`, `EARTH`, `AIR`, `ORDER`, `CHAOS` 6 种元素间轮换选择当前节点绑定的产出元素类型。 |
| **`Harvest Slider`** (`slider`) | Slider (`1 ~ 10`) | `1` | 设定单次采集任务的目标数量。数额越大，法师 NPC 引导吟唱时间越长，但效率更高。 |
| **`Toggle Collect Button`** | MedievalButton | — | 发布 / 取消采集任务按钮。点击向任务池下发或撤回 `NodeHarvestTask`。 |
| **`Task Queue Panel`** | TaskQueuePanel | — | 展示当前正前来该节点执行采集的法师 NPC 姓名及其吟唱进度。 |

---

👉 [前往市政厅指南](guide:townhall_guide)  
👉 [返回主测试页](guide:test_guide)
