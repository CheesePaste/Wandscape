# ⚔️ Cast Strategy Guide

The Cast Strategy screen controls how a mage auto-casts in combat: a single "strategy / priority" knob — no scripting. Open it via **right-click a mage → info screen → "Strategy"**.

---

## 🎯 Two-layer model

Strategy has two layers: the **overall preset** decides the category priority order, and **spells within a category** can be enabled/disabled and reordered individually.

### Layer 1: Overall preset (top 4 buttons)

| Preset | Category priority (high → low) |
| :--- | :--- |
| Balanced | AOE > Single-target > Support > Defense |
| Offensive | Single-target > AOE > Defense > Support |
| Support | Support (heal first) > Defense > AOE > Single-target |
| Defensive | Defense > Support > AOE > Single-target |

### Layer 2: Spells within a category

- The second row of 4 category buttons (**Single-target / AOE / Defense / Support**) switches the currently shown category.
- Each category lists the mage's known spells, one per row:
  - **Toggle button** (On/Off): enables / disables the spell. Disabled spells never auto-cast.
  - **↑ / ↓ arrows**: reorder the **enabled spells within the same category** (disabled rows cannot be moved).

---

## ⚙️ Mechanics

- **Auto-cast flow**: concatenate the preset category order with each category's enabled-spell order into a flat priority, then pick the **first castable** spell from top to bottom (must satisfy target rule, conditions, cooldown, and mana).
- **Changes save instantly**: any change (preset / toggle / up / down) is sent to the server immediately — no manual confirmation.
- **Explicit priority**: once you configure a mage's strategy (toggled or reordered), auto-cast uses your explicit priority instead of deriving from a pure preset.
- **Altar spells excluded**: altar-exclusive spells (like Revive) can only be cast at an altar; mages never auto-select them in combat.

---

👉 [Go to Mage NPC guide](guide:npc_guide)  
👉 [Back to main test page](guide:test_guide)
