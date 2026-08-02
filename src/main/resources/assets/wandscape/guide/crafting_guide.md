# 🔨 合成台（Crafting Station）API 级详细指南

合成台用于将基础元素与原材料加工为中级建材！

---

## 📖 UI 控件与字段 API 级别明细字典 (UI Options API Reference)

| 控件标识 / 标签 | 类型 / 范围 | 默认值 | 详细作用机制与计算影响 |
| :--- | :--- | :--- | :--- |
| **`Recipe List`** (`recipeList`) | ScrollableList | — | 可选合成配方列表，显示所需元素原料与产出物品。 |
| **`Quantity Slider`** (`slider`) | Slider (`1 ~ 64`) | `1` | 单次挂载生产的数量。 |
| **`Submit Button`** | MedievalButton | — | 将配方生产任务压入后台任务队列 `TaskQueuePanel`。 |
| **`Task Queue Panel`** | TaskQueuePanel | — | 展现队列中排队与正在合成的任务，显示法师 6 秒合成倒计时。 |

---

👉 [前往工作站指南](guide:workstation_guide)  
👉 [返回主测试页](guide:test_guide)
