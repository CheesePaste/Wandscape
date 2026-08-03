# 🌉 Fill Mode API Guide

Fill mode is used to build elevated bridges, flatten deep pits, or stack 3D block foundations.

---

## 📖 UI Controls & Fields API Reference

| Control / Property | Type / Range | Default | Details |
| :--- | :--- | :--- | :--- |
| **`ToolMode.FILL`** (mode switch) | Enum | `FILL` | Activates the 3D box fill algorithm. Fills a solid volume inside the selected 3D bounding box. |
| **`ToolMode.DESTROY_FILL`** | Enum | `DESTROY_FILL` | Activates erase/fill-removal mode. Captures a reference block (`RefBlockId`) and clears fill blocks in range. |
| **`RoadPreset`** (fill material) | Card Selector | `Stone Bricks` | Picks the target block material for 3D filling (stone bricks, planks, foundation blocks, etc.). |
| **`StartPos`** (bottom corner) | BlockPos (`X, Y, Z`) | `null` | Triggered by **`right-click`** aiming at the base layer. Sets the bottom origin of the 3D fill box. |
| **`EndPos`** (top corner) | BlockPos (`X, Y, Z`) | `null` | Triggered by **`left-click`** aiming at the elevated corner. Drags out a 3D bounding box preview with height and depth. |
| **`RefBlockId`** (reference block) | String | empty | In `DESTROY_FILL` mode, the target block ID to erase, captured by **`right-click`** (e.g. `minecraft:stone_bricks`). |
| **`Enter (submit)`** | Action Key | — | Sends a `FillBoxPacket` (or `DestroyFillPacket`) to the server to batch-generate the 3D bridge/foundation structure. |

---

## 🚀 3-Step Simple Workflow

### Step 1: Enter fill mode
1. Press **`V`** to open the panel and enter the road toolbar.
2. Switch mode to **`FILL`**.
3. Double-click to pick a fill material (e.g. *Stone Bricks*).

### Step 2: Drag out the 3D box
1. Press **`C`** to switch to crosshair interaction.
2. **`Right-click`** the bottom start point (sets the bottom Z/X/Y coordinates).
3. Move the view to the elevated or diagonal corner and **`left-click`** the end position.
4. A solid 3D block bounding box preview appears in the viewport.

### Step 3: Submit generation
1. Press **`Enter`**.
2. The client sends a `FillBoxPacket` to the server, instantly filling the bridge or solid foundation!

---

## 🛠️ Troubleshooting

### Q1: I filled the wrong area and want to delete it?
- **Fix**: switch to **`DESTROY_FILL`** mode in the mode bar, click the wrongly filled block, then press **`Enter`** to clear the fill in one go.

---

👉 [Jump to Replace mode guide](guide:road_replace_guide)  
👉 [Jump to Spline Bezier guide](guide:road_spline_guide)  
👉 [Back to road overview guide](guide:road_guide)
