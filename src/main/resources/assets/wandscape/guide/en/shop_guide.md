# 🏪 Shop API Guide

Shops are the core buildings for earning tourist gold and elemental income!

---

## 📖 UI Controls & Fields API Reference

| Control / Label | Type / Range | Default | Details |
| :--- | :--- | :--- | :--- |
| **`Stock Slider`** (`stockSliders`) | Slider (`0 ~ Max`) | `0` | Sets the current stock quantity actually on sale. Cannot exceed the max stock cap. |
| **`Max Stock Edit`** (`maxStockEdits`) | EditBox (Integer) | `64` | Maximum stock this product can hold at this shop. |
| **`Sales Bonus`** | Display Percent | `+0%` | Price markup ratio computed from the shop's `Comfort` rating. Higher comfort means tourists pay more gold. |
| **`Stay Bonus`** | Display Percent | `+0%` | Scales how long tourists linger to shop inside the store. |
| **`Element Feedback`** | Display Rate | `0/s` | Rate at which magic elements flow back to the town warehouse via `ServiceElementOutput` when tourists buy goods. |

---

👉 [Go to Hotel lodging guide](guide:hotel_guide)  
👉 [Back to main test page](guide:test_guide)
