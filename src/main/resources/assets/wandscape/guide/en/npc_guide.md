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

## 🎯 Cast Strategy (Right-click a wizard → Info screen → "Strategy")

Controls a wizard's automatic casting in combat — one "strategy/priority" dial, no scripting:

| Preset | Casting tendency (category order) |
| :--- | :--- |
| Balanced | AOE > Single-target > Support > Defense |
| Offensive | Single-target > AOE > Defense > Support |
| Support | Support (heal first) > Defense > AOE > Single-target |
| Defensive | Defense > Support > AOE > Single-target |

- Click a spell row to toggle it on/off: disabled spells no longer auto-cast, and the list switches to "Custom".
- Presets order spells by category; "Custom" follows the order you enabled (enabled first, disabled after).
- Guard/self-defense combat picks spells by this strategy; altar-only spells (e.g. revive) are excluded (altar-exclusive).

---

👉 [Go to tourist debug guide](guide:tourist_guide)  
👉 [Back to main test page](guide:test_guide)
