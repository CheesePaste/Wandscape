# 🏪 Shop

Tourists spend travel money on goods; elements flow into the warehouse based on each good's elemental value and profit rate.

## How It Runs

- A shop's **goods list** is set by its building config; each good carries its own **Comfort / Magic / Wonder** values.
- Tourists stand on the shop's **interact spot** to browse, staying for the building's configured **interaction duration**.
- Tourists spend **travel money**; you collect elements based on each good's elemental value and **profit rate**. Higher profit rate means more earnings per good.
- A good's three values fill the tourist's matching **need bar**: high-Comfort goods feed comfort-loving tourists; high-Magic goods feed magic-loving tourists.

## Operation

**Left-click the shop in overview mode** to open the panel and set each good's **max stock** (0~64). The system auto-restocks from the warehouse; max stock decides how long a good lasts.

> A shop needs three things to function: an **interact spot**, goods with **matching values**, and **road access**.

## Built-in Shops

| Shop | Goods |
|---|---|
| Bread Shop | 6 kinds of food |
| Flower Shop | 6 kinds of flowers |
| Book Shop | 6 kinds of books |

The mod also ships with a Magic Shop. Different shops lean toward different need values; placing them on different streets attracts tourists of different profiles. To configure a new shop: use the shop mode of the [Creative Building Scanner](creative_scanner_guide.md) to set goods and profit rate — it takes effect on export.

---

[Tourist System Guide](tourist_guide.md)  
[📖 Back to the Guide Index](index_guide.md)
