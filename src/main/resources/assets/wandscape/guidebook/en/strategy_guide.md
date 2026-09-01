# Cast Strategy

Which spells a wizard casts in combat is decided by the strategy. With no strategy set, the wizard follows the default preset.

How to open: **aim your crosshair at a wizard and right-click → info screen → the "Strategy" button**.

## Two Layers: Preset + Fine-Tuning

**Layer 1 · Overall preset** (the top 4 buttons) decides the casting priority order:

| Preset | Casting order (high → low) |
| :--- | :--- |
| **Balanced** | AoE → Single-target → Support → Defense |
| **Offensive** | Single-target → AoE → Defense → Support |
| **Support** | Support (heal first) → Defense → AoE → Single-target |
| **Defensive** | Defense → Support → AoE → Single-target |

**Layer 2 · Fine-tuning within a category**: the second row of category buttons (**Single-target / AoE / Defense / Support**) switches to a category and shows the wizard's **known spells**:

- **On/Off**: enable or disable; disabled spells are never cast.
- **↑ / ↓**: reorder the enabled spells within the same category.

## Mechanics

- When auto-casting, scan from high to low by "preset order + in-category order", and pick the **first castable one**: valid target, cooldown passed, mana sufficient.
- **Changes save instantly** — no confirmation needed.
- Once you have configured a wizard (toggled or reordered), your configuration is used; wizards you never configured follow the preset.
- Altar spells (like Revive) are not in auto-casting — see the [Altar](altar_guide.md).

---

[Wizard NPC Guide](npc_guide.md)  
[Altar (Ritual Spells like Revive)](altar_guide.md)  
[Back to the Guide Index](index_guide.md)
