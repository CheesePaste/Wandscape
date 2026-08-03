# 🧱 Replace Mode API Guide

Replace mode directly converts existing grass, dirt or sand on the surface into fine road paving.

---

## 📖 UI Controls & Fields API Reference

| Control / Property | Type / Range | Default | Details |
| :--- | :--- | :--- | :--- |
| **`ToolMode.REPLACE`** (mode switch) | Enum | `REPLACE` | Activates the surface-paving replacement algorithm. Replaces the top surface blocks inside the selected box with the chosen road material. |
| **`RoadPreset`** (material preset) | Card Selector | `Cobblestone` | Picks the replacement target block from the road material library (e.g. *Cobblestone Road*, *Stone Brick Road*, *Dirt Path*). Double-click to select. |
| **`RoadPhase`** (interaction phase) | Enum (`BAR / PLACING`) | `BAR` | `BAR` (cursor free to pick presets and modes); `PLACING` (press `C` to release cursor, crosshair aims in the world). |
| **`StartPos`** (start point) | BlockPos (`X, Y, Z`) | `null` | Triggered by **`right-click`** aiming at the ground. Sets the first base corner of the replacement rectangle, highlighted as a red bounding box. |
| **`EndPos`** (end point) | BlockPos (`X, Y, Z`) | `null` | Triggered by **`left-click`** aiming at the ground. Together with `StartPos` drags out the paving rectangle, highlighted as a green bounding box. |
| **`GhostPos`** (ghost preview) | BlockPos | `null` | The hovered coordinate detected by the 64-block reach clip, rendered as a white translucent ghost block preview. |
| **`Enter (submit)`** | Action Key | — | After validating `StartPos` and `EndPos`, sends a `RoadPlacePacket` to batch-replace surface blocks in the area. |
| **`Backspace (undo point)`** | Action Key | — | Undoes `EndPos` first; if cleared, undoes `StartPos`. |

---

## 🚀 3-Step Simple Workflow

### Step 1: Enter replace mode
1. Press **`V`** to open the panel and click the **Road icon** (or press `R`).
2. Select **`REPLACE`** mode in the bottom mode bar.
3. Double-click a material card (e.g. *Cobblestone Road*).

### Step 2: Select the surface area
1. Press **`C`** to hide the panel and aim at the ground with the crosshair.
2. **`Right-click`** the start block: the chat shows `[Road] Start point set` and a red highlight marks the start.
3. Move the crosshair to the diagonal corner and **`left-click`** the end block: a green rectangle selection is dragged out.

### Step 3: Submit construction
1. Check the green selection. Not satisfied? **`Right-click`** again to clear and reselect.
2. When ready, press **`Enter`**!
3. The surface blocks are instantly replaced with the chosen road material!

---

## 🛠️ Troubleshooting

### Q1: Left/right click not responding?
- **Fix**: press **`C`** to release the cursor back to crosshair mode.

### Q2: Picked the wrong start point — how to clear it?
- **Fix**: press **`Backspace`** to undo the previous point; or **`right-click`** to clear the whole selection.

---

👉 [Jump to Fill mode guide](guide:road_fill_guide)  
👉 [Jump to Spline Bezier guide](guide:road_spline_guide)  
👉 [Back to road overview guide](guide:road_guide)
