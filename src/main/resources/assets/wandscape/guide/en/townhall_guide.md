# 🏛️ Town Hall API Guide

The Town Hall is the brain of the whole town!

---

## 📖 UI Controls & Fields API Reference

| Control / Label | Type / Range | Default | Details |
| :--- | :--- | :--- | :--- |
| **`Colony Name EditBox`** (`nameBox`) | String (`Max 32 chars`) | `My Colony` | Town name input. After editing, pressing Enter or losing focus sends a `ColonyNameUpdatePacket` to change the global town name. |
| **`Colony Level`** (`stat_level`) | Display Badge (`1 ~ 5`) | `1` | Shows the current town level. Leveling up raises the global population cap and unlocks advanced building blueprints. |
| **`Experience Bar`** (`stat_exp`) | Progress Bar (`0 ~ 100%`) | `0/1000` | Shows town building and management experience progress (`experience / expToNext`). Filling it triggers a Town Hall upgrade. |
| **`Build Plans Button`** (`btn_open_build_plans`) | MedievalButton | — | Opens the blueprint selection overlay (`BuildingSelectionOverlay`), letting the player pick and publish construction plans. |
| **`Reputation Stat`** (`stat_reputation`) | Rating (-100 ~ +100) | `0` | Global reputation accumulated from tourist satisfaction. Higher reputation attracts more rare/wealthy tourists to the gate each morning. |

---

## 1. Core Responsibilities
- Manage town level and upgrade experience
- Provide blueprint publishing and planning
- Determine initial population cap and tourist attraction

👉 [Go to Warehouse logistics guide](guide:warehouse_guide)  
👉 [Back to main test page](guide:test_guide)
