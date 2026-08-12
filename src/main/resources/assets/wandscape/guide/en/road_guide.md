# 🛣️ Road System

**Tourists only walk on paved roads.** Without roads there are no tourists — a shop can open but no one will enter.

![Road system diagram](wandscape:textures/gui/guide/road_diagram.png =200x100)

## Entering Road Mode

Press **V** to open the panel → the **Roads** icon on the left. Pick a material in the bottom bar, and a tool in the mode bar. The cursor is always free — use it directly on the UI and in the world.

## The Four Modes

| Mode | Usage | When to use it |
| :--- | :--- | :--- |
| **Replace REPLACE** | Hold left-click and drag a box, framing a rectangle | Everyday paving: turning grass and dirt into road surface |
| **Fill FILL** | Hold left-click and drag, pulling out a 3D box | Bridges, filling deep pits, laying foundations |
| **Destroy DESTROY** | Left-click to grab a reference block, then drag a box | Removing wrongly placed blocks, flattening terrain |
| **Spline SPLINE** | Opens the editor, click anchors in the world to draw curves | Curves, loops, interchanges |

## Materials

Double-click a card in the bottom bar to pick: **Dirt Path / Road (Stone) / Grass / Water / Cobblestone / Gravel / Oak Planks**.

## Common Controls (Replace / Destroy Modes)

| Control | Action |
| :--- | :--- |
| **Hold left-click + drag** | Frame a selection: press to set the start, drag to expand, release to finish |
| **Enter** | Submit, publish the paving task |
| **Backspace** | Undo the last framing |
| **ESC** | Exit road construction |

After submitting, wizard NPCs pave automatically according to the task (if materials are short, wait for NPCs to automatically synthesize the required building blocks); when enough buildings are placed, the town also plans roads automatically to connect them. For fine curves and interchanges, use the [Spline Editor](road_spline_guide.md).

## Per-Mode Tutorials

- [Replace Mode (Surface Paving)](road_replace_guide.md)
- [Fill & Destroy](road_fill_guide.md)
- [Spline Editor (Curves & Interchanges)](road_spline_guide.md)

---

[Panel & Overview](overview_guide.md)  
[📖 Back to the Guide Index](index_guide.md)
