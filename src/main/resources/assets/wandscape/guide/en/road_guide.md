# 🛣️ Road System

First, a harsh fact: **tourists only walk on paved roads**. How far your roads reach is how far your town's economy can grow — that is not a metaphor, it is a hard rule. So this page is worth reading carefully.

![Road system diagram](wandscape:textures/gui/guide/road_diagram.png =200x100)

## Entering Road Mode

Press **V** to open the panel → the **Roads** icon on the left. Pick a material in the bottom bar, and a tool in the mode bar. **C** switches between the "panel cursor" and the "crosshair sight": use the cursor for the UI, use the crosshair to pick points in the world.

## The Four Modes

| Mode | Usage | When to use it |
| :--- | :--- | :--- |
| **Replace REPLACE** | Right-click to set the start, left-click to set the end, framing a rectangle | Everyday paving: turning grass and dirt into road surface |
| **Fill FILL** | Pick two diagonal corners, pull out a 3D box | Bridges, filling deep pits, laying foundations |
| **Destroy DESTROY** | Right-click to grab a reference block, then frame an area | Removing wrongly placed blocks, flattening terrain |
| **Spline SPLINE** | Opens the editor, click anchors in the world to draw curves | Curves, loops, interchanges |

## Materials

Double-click a card in the bottom bar to pick: **Dirt Path / Road (Stone) / Grass / Water / Cobblestone / Gravel / Oak Planks**.

## Common Controls (Replace / Destroy Modes)

| Control | Action |
| :--- | :--- |
| **Right-click** | Set the start point (press again to clear and reselect) |
| **Left-click** | Set the end point |
| **Enter** | Submit, publish the paving task |
| **Backspace** | Undo the last endpoint |
| **ESC** | Exit road construction |

After submitting, wizard NPCs pave automatically according to the task; when you have enough buildings, the town also plans roads automatically to connect them. For fine curves and interchanges, use the [Spline Editor](road_spline_guide.md).

## Per-Mode Tutorials

- [Replace Mode (Surface Paving)](road_replace_guide.md)
- [Fill & Destroy](road_fill_guide.md)
- [Spline Editor (Curves & Interchanges)](road_spline_guide.md)

---

[Panel & Overview](overview_guide.md)  
[📖 Back to the Guide Index](index_guide.md)
