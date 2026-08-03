# 🛣️ Road System & Mode Switching Overview Guide

The road system is the main artery of Wandscape's tourist economy. **Tourists outside town only come in to visit, stay overnight and spend money along paved roads**!

![Road system diagram](wandscape:textures/gui/guide/road_diagram.png =200x100)

---

## 🎮 1. How to Open & Switch Road Edit Modes

### Step 1: Enable road construction mode
1. Press **`V`** in-game to open the Wandscape control panel.
2. Click the **【Road】icon** in the left toolbar to enter road construction projection mode.

### Step 2: How to switch between the 4 modes
- Press **`C`** to toggle between the **cursor-select UI panel** and **crosshair sight interaction**.
- **Mode switching**: with the cursor active, click **`REPLACE`**, **`FILL`**, **`DESTROY/FILL`** or **`SPLINE`** in the left toolbar.
- **Material preset switching**: double-click a bottom card to pick cobblestone, stone brick, dirt path or paving.

### Four Modes Compared

| Mode | Entry behavior | Best for |
| :--- | :--- | :--- |
| **Replace** | Enters two-point placement mode in the world | Straight surface stone-brick road paving and replacement |
| **Fill** | Enters two-corner 3D box fill mode | Filling the box between two corners |
| **Destroy/Fill** | Enters reference block picking mode | Batch terrain flattening and obstacle clearing |
| **Spline** | ROAD panel's embedded spline editor (V panel stays open) | Elegant curves, loops and 3D curved roads |

---

## 📖 2. Core Road System API Parameter Dictionary

| Core parameter / property | Type / range | Detailed behavior & calculation impact |
| :--- | :--- | :--- |
| **`ToolMode`** | Enum (`REPLACE / FILL / DESTROY_FILL / SPLINE`) | Core mode of the road building tool. Mode 1 `REPLACE` replaces surface along straight lines; mode 2 `FILL` fills boxes between two corners; mode 3 `DESTROY_FILL` cleans with a reference height; mode 4 `SPLINE` opens the embedded spline editor. |
| **`RoadPhase`** | Enum (`BAR / PLACING`) | In `BAR` the cursor is free to pick presets/modes; in `PLACING` the cursor locks to the crosshair to click `StartPos`/`EndPos` in the world. Only used by REPLACE, FILL and DESTROY_FILL. |
| **`Reach Distance`** | Float (`64.0 / 128.0 Blocks`) | Max effective range of the crosshair raycast. REPLACE/DESTROY_FILL is 64 blocks; the Spline editor is 128 blocks. |
| **`SplineBuildPacket`** | Network Payload | Triggered by the Spline editor's Build Array Task. Carries full 3D Bezier curve JSON and road block arrays; the server generates roads in batch. |

---

## 📚 3. Detailed Guides for the Three Feature Modes (click to jump)

To help you get each mode up and running easily, we provide independent detailed tutorials and API dictionaries:

- 👉 [【1. Spline Visual Editor】Ultra-Detailed Guide](guide:road_spline_guide)
  *Embedded spline editor. Add anchors, drag 3D axes, tune handles, array generation, template save/load. Ideal for curves and 3D roads.*

- 👉 [【2. Replace Mode】Ultra-Detailed Simple Guide](guide:road_replace_guide)
  *For surface stone/dirt path laying and replacing existing terrain.*

- 👉 [【3. Destroy/Fill Mode】Ultra-Detailed Simple Guide](guide:road_fill_guide)
  *For batch terrain flattening and obstacle clearing after right-clicking to pick the reference height and block type.*

---

## ⌨️ 4. Common Hotkeys & Mouse Buttons Reference

### Replace / Destroy/Fill Modes (two-point interaction in the world)

| Action / Key | Effect |
| :--- | :--- |
| **Right mouse button** | Set start point (or right-click to clear and reselect) |
| **Left mouse button** | Set end point |
| **Enter** | Confirm and submit the construction task |
| **Backspace** | Undo the last set endpoint |
| **ESC** | Exit road edit mode |

### Spline Editor (ROAD panel embedded mode)

| Action / Key | Effect |
| :--- | :--- |
| **Right-click held + mouse** | Rotate view |
| **Right-click held + WASD** | Freecam 3D flight movement (intercepts vanilla character movement) |
| **Right-click held + Space / Shift** | Fly up / down |
| **Right-click held + Ctrl** | Fast flight (2x) |
| **Scroll wheel directly** | Adjust freecam flight speed (no Ctrl needed) |
| **Left click** | Add anchor / select control point / drag 3D axis |
| **Shift + left-click drag** | Temporarily release symmetry lock |
| **Delete / Backspace** | Delete selected control point |
| **ESC** | Exit editor |

---

👉 [Back to main test page](guide:test_guide)
