# 🔨 Crafting Station API Guide

The Crafting Station refines basic elements and raw materials into mid-tier building materials!

---

## 📖 UI Controls & Fields API Reference

| Control / Label | Type / Range | Default | Details |
| :--- | :--- | :--- | :--- |
| **`Recipe List`** (`recipeList`) | ScrollableList | — | List of selectable crafting recipes, showing required element ingredients and output items. |
| **`Quantity Slider`** (`slider`) | Slider (`1 ~ 64`) | `1` | Quantity produced per queued craft. |
| **`Submit Button`** | MedievalButton | — | Pushes the recipe production task into the background task queue `TaskQueuePanel`. |
| **`Task Queue Panel`** | TaskQueuePanel | — | Shows queued and in-progress crafting tasks, displaying the wizard's 6-second craft countdown. |

---

👉 [Go to Workstation guide](guide:workstation_guide)  
👉 [Back to main test page](guide:test_guide)
