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

## Third-party spells need the right gear

**Iron's Spells scrolls** can only be given to a wizard who wears a **spell book** in their curio slot. The number of **Iron spell scrolls** you can place = the spell book's **spell slots** (larger books hold more). Without a spell book equipped, Iron spell scrolls cannot be placed into the strategy bar, and any already placed ones stop being cast — they are kept and resume once a book is equipped.

**Goety focuses** require the wizard to hold a **Goety wand in their main hand** (the staff slot), and only **one focus** fits in the whole strategy bar at a time. Without a wand, focuses can't be placed and any placed ones stop being cast until a wand is equipped.

Native Wandscape scrolls are not affected.

---

[Wizard NPC Guide](npc_guide.md)  
[Altar (Ritual Spells like Revive)](altar_guide.md)  
[Back to the Guide Index](index_guide.md)
