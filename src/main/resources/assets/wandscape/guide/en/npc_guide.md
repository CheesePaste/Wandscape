# 🧙‍♂️ Wizard NPC Panel API Guide

Wizards (NPCs) are the core worker entities of the town's automation!

---

## 📖 UI Controls & Fields API Reference

| Control / Label | Type / Range | Default | Details |
| :--- | :--- | :--- | :--- |
| **`Wand Equipment Slot`** | ItemSlot | empty / default wand | **Wand equipment slot**. Placing a custom wand boosts the wizard's spell power and cast range. |
| **`Health Stat`** (`stat_hp`) | Display (`Cur/Max`) | `20/20` | Current health and max health. |
| **`Mana Stat`** (`stat_mana`) | Display (`Cur/Max`) | `100/100` | Current mana and max mana. Executing atomic tasks consumes mana. |
| **`Mana Regen`** (`stat_regen`) | Int (`pts/s`) | `5` | Natural mana regeneration rate. |
| **`Spell Power`** (`stat_spell`) | Int | `10` | Spell power. Affects cast speed and shortens craft countdowns. |
| **`Cast Range`** (`stat_range`) | Int (`blocks`) | `16` | Maximum effective cast distance (in blocks). |
| **`Mana Cost Multiplier`** | Float (`0.5 ~ 2.0`) | `1.0` | Cast mana cost coefficient. Lower is more mana-efficient. |

---

👉 [Go to tourist debug guide](guide:tourist_guide)  
👉 [Back to main test page](guide:test_guide)
