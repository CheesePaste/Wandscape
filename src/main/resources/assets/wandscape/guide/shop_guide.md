# 🏪 商店（Shop）API 级详细指南

商店是赚取游客金币与元素收益的核心建筑！

---

## 📖 UI 控件与字段 API 级别明细字典 (UI Options API Reference)

| 控件标识 / 标签 | 类型 / 范围 | 默认值 | 详细作用机制与计算影响 |
| :--- | :--- | :--- | :--- |
| **`Stock Slider`** (`stockSliders`) | Slider (`0 ~ Max`) | `0` | 配置当前实际上架销售的商品库存量。上架量不能超过最大库存限制。 |
| **`Max Stock Edit`** (`maxStockEdits`) | EditBox (Integer) | `64` | 该商品在此商店可容纳的最大备货上限。 |
| **`Sales Bonus`** (销售加成) | Display Percent | `+0%` | 根据商店的 `Comfort`（舒适度）评分计算的售价溢价比例。舒适度越高，游客付费金币越多。 |
| **`Stay Bonus`** (停留加成) | Display Percent | `+0%` | 影响游客在商店内逗留选购的时间缩放比例。 |
| **`Element Feedback`** (元素反哺) | Display Rate | `0/s` | 当游客购买商品时，通过 `ServiceElementOutput` 反哺回殖民地仓库的魔力元素速率。 |

---

👉 [前往旅馆住宿指南](guide:hotel_guide)  
👉 [返回主测试页](guide:test_guide)
