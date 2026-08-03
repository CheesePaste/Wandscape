# ⛏️ Resource Node API Guide

Resource nodes are the heart of the town's automated gathering!

---

## 📖 UI Controls & Fields API Reference

| Control / Label | Type / Range | Default | Details |
| :--- | :--- | :--- | :--- |
| **`Element Cycle Button`** | CycleButton | `FIRE` | Cycles the element type this node produces among `FIRE`, `WATER`, `EARTH`, `AIR`, `ORDER`, `CHAOS`. |
| **`Harvest Slider`** (`slider`) | Slider (`1 ~ 10`) | `1` | Sets the target amount for a single gather task. Larger amounts mean longer channeling by the wizard NPC, but better efficiency. |
| **`Toggle Collect Button`** | MedievalButton | — | Publishes / cancels the gather task. Clicking pushes or withdraws a `NodeHarvestTask` from the task pool. |
| **`Task Queue Panel`** | TaskQueuePanel | — | Shows wizard NPCs currently heading to this node to gather, with their channeling progress. |

---

👉 [Go to Town Hall guide](guide:townhall_guide)  
👉 [Back to main test page](guide:test_guide)
