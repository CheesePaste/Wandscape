# 📦 Warehouse & Material Logistics API Guide

The Warehouse handles the whole town's automated resource storage and logistics distribution!

---

## 📖 UI Controls & Fields API Reference

| Control / Label | Type / Range | Default | Details |
| :--- | :--- | :--- | :--- |
| **`Tab Switcher`** (`activeTab`) | TabBar (`0/1`) | `0` | Switches panel mode. `Tab 0: Overview` (global supplies and six-element stock); `Tab 1: Exchange` (player inventory ↔ warehouse transfer). |
| **`Search Input`** (`searchInput`) | String | empty | Material search filter. Supports case-insensitive fuzzy matching by item name. |
| **`Element Panel`** (`elementPanel`) | Display Grid | — | Shows total stock of the six native magic elements (`FIRE`, `WATER`, `EARTH`, `AIR`, `ORDER`, `CHAOS`) in the current town. |
| **`Supply Gap Tab`** (`supply_gap_tab`) | Alert Tab | — | **Material shortage panel**. When crafting or workstation production is stuck on missing materials, the missing materials are highlighted here. |
| **`Quantity Slider`** (`qtySlider`) | Slider (`1 ~ 64`) | `1` | Transfer quantity slider. Controls how many items are deposited or withdrawn per operation. |
| **`Deposit / Withdraw Buttons`** | MedievalButton | — | Deposit and Withdraw buttons, triggering data transfer between warehouse and player inventory. |

---

👉 [Back to Town Hall guide](guide:townhall_guide)  
👉 [Back to main test page](guide:test_guide)
