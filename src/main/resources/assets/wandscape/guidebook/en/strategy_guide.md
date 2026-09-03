# Cast Strategy

What a wizard casts in combat is decided by two things: which **magic scrolls sit in the strategy bar**, and the **overall preset** you pick.

How to open: **aim your crosshair at a wizard and right-click → info screen → the "Strategy" button**; the Strategy button in the Mage Hut's Promotion & Commands card opens the same screen.

## A wizard's spells come from scrolls

A wizard's combat spells come entirely from the scrolls loaded into the 12 strategy slots — wizards do not know a whole spellbook by default. To let a wizard cast something, load its scroll into the bar:

- Wandscape's own **magic scrolls** are synthesized from elements at the [Magic Workshop](magic_station_guide.md), each bound to one spell (e.g. Beam, Meteor).
- The 3 starting wizards that come with a new colony have Beam + Meteor loaded by default; **wizards hired from the tavern start with no combat magic** — craft them scrolls first if you want them to fight.
- With Iron's Spells or Goety installed, their scrolls / focuses can also go into the bar — see below.

## Two settings: load scrolls + pick a preset

**Scroll slots (middle)**: 12 slots arranged in four category rows — **Single-target / AoE / Defense / Support**, three per row. Drag a scroll from your inventory into a slot to equip it (take it out to get the scroll back; **Shift** moves it quickly):

- The row you drop a scroll into decides which category it counts as for the casting order; spells are de-duplicated and each row holds at most 3.
- Slot order within a row (top → bottom) = casting priority inside that category; higher up casts first.

**Overall preset (4 buttons at the top)**: decides the priority between the four categories:

| Preset | Casting order (high → low) |
| :--- | :--- |
| **Balanced** | AoE → Single-target → Support → Defense |
| **Offensive** | Single-target → AoE → Defense → Support |
| **Support** | Support (heal first) → Defense → AoE → Single-target |
| **Defensive** | Defense → Support → AoE → Single-target |

The "**Special**" column on the right is read-only: inherent spells like Teleport and Heal are listed there — used by default by every wizard and cannot be changed; they only fire in specific situations (self-heal when in danger, teleport on pathing failure).

## Mechanics

- When auto-casting, the wizard scans from high to low by "preset category order → in-row slot order" and picks the **first castable** one: valid target, cooldown passed, mana sufficient.
- **Changes save instantly** — no confirmation; each wizard's slots are configured individually.
- Altar spells (like Revive) are neither auto-cast nor equippable — see the [Altar](altar_guide.md).
- Magic that is not loaded into the strategy bar is never cast.

## Third-party spells need the right gear

**Iron's Spells scrolls** can only be equipped on a wizard wearing a **spell book** in their curio slot. The number of scrolls you can load = the **spell book's capacity** (the more spells a book can hold, the higher its tier). Without a book, scrolls can't be placed, and any already placed ones are **disabled but kept** — they resume once a book is equipped.

**Goety focuses** require the wizard to hold a **Goety wand in their main hand** (the staff slot), and only **one focus** fits in the whole bar at a time. Without a wand, focuses can't be placed, and any placed ones are disabled until a wand is equipped.

Give the wizard its spell book through the [Wizard Panel](npc_guide.md)'s trinket entry and its Goety wand through the wand slot. Native Wandscape scrolls are not affected by these gates.

> When third-party magic is loaded, the strategy screen shows a status line at the bottom (e.g. "Iron 2/5", "Focus 1/1"); missing gear turns it into a warning ("No spell book: Iron off", "No wand: focus off"). Holding a gated scroll over a slot shows the blocking reason in red.

---

[Wizard NPC Guide](npc_guide.md)  
[Altar (Ritual Spells like Revive)](altar_guide.md)  
[Back to the Guide Index](index_guide.md)
