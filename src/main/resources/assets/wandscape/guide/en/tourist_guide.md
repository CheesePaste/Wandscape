# 🧳 Tourist AI & Debug API Guide

Tourists are short-stay visitors who drive the town's tourism economy!

---

## 📖 UI Controls & Fields API Reference

| Control / Label | Type / Range | Default | Details |
| :--- | :--- | :--- | :--- |
| **`Energy Stat`** | Display (`0 ~ 100`) | `100` | Stamina. If it gets too low, the tourist heads to the Hotel to sleep or leaves town. |
| **`Satisfaction Stat`** | Display (`0 ~ 100%`) | `50%` | Satisfaction. At 100% the tourist leaves a resume at the Tavern and can be recruited as a wizard. |
| **`Tourist Level`** | Display (`1 ~ 5`) | `1` | Tourist wealth level. Higher level means more spending power and gold brought in. |
| **`State Badge`** | Enum (`String`) | `VISITING` | Current AI state (`VISITING` shopping, `EXPLORING` sightseeing, `SLEEPING` lodging, `WANDERING` strolling). |
| **`Target Building`** | Display String | — | Name and coordinates (`X, Y, Z`) of the building the tourist is currently pathfinding to. |
| **`Cooldown Ticks`** | Display Int | `0` | Remaining tick delay until the next AI decision and behavior evaluation. |

---

👉 [Go to Wonder/Anomaly guide](guide:anomaly_guide)  
👉 [Back to main test page](guide:test_guide)
