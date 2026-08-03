# ➰ Spline Road Studio Detailed Guide

The Spline Road Studio is Wandscape's most powerful road building tool. Through the embedded edit panel on the right, you can add, edit and drag Bezier curve control points in real time in the 3D world — crafting elegant curves, loops and complex multi-level roads, with seamless integration with the block selected in the overview (V) panel and dynamic fine-tuning!

---

## 🚪 1. How to Enter & Exit the Editor

### Entering
1. Press **`V`** to open the Wandscape control panel.
2. Click the **Road** icon on the left to enter the road tool.
3. Click the **`Spline`** button in the left toolbar.
4. Keep the V panel in ROAD mode; a road studio edit panel appears on the right side of the screen (the ROAD bottom bar can still switch block presets).

### Exiting
- Press **`ESC`** to exit the editor (when not holding right-click).
- Or click the panel's **`Close Editor`** button.

---

## 🎮 2. Camera Control (Camera Flight / Freecam Mode)

After entering the editor, the player character is in freecam state; WASD input is intercepted (the in-game player entity does not move). Fly around freely:

| Action | Function |
| :--- | :--- |
| **Right-click held + move mouse** | Rotate view (Yaw / Pitch) |
| **Right-click held + WASD** | Freecam 3D flight (flies along view direction, character stays put) |
| **Right-click held + Space** | Fly straight up |
| **Right-click held + Shift** | Fly straight down |
| **Right-click held + Ctrl** | Fast flight (2x speed) |
| **Scroll wheel directly** | Adjust freecam flight speed (0.02 ~ 5.0, no Ctrl needed, real-time actionbar hint) |
| **G key** | Toggle 2D top-down view |
| **H key** | Open this guide at any time |

Releasing right-click frees the mouse cursor to operate the right panel or click control points in the world.

---

## 🖱️ 3. In-World Interaction (Pick, Add, Drag)

### 3.1 Add control points (Click-to-Add mode)
1. In the panel's **`🛣️ Curve Edit`** tab, select **`➕ Click to Add Point`**.
2. Release right-click (cursor free), and aim at a block surface in the world.
3. **Left-click** → creates a new spline anchor on the top face of that block.
4. Add 2+ anchors and the editor auto-generates a Bezier curve preview.

### 3.2 Select control points
- In add or edit mode, **left-click** an existing anchor sphere to select it.
- Once selected, an **RGB 3D axis Gizmo** appears around it (red = X, green = Y, blue = Z).
- In **`🎯 Select & Drag`** mode you can also select tangent handle spheres (front/back handle).

### 3.3 Axis Gizmo Drag
With a control point selected:
1. Hover over an axis arrow (it auto-highlights).
2. **Left-click and drag** → translate the selected anchor or tangent handle along that axis.
3. Supports 6 directions: X+, X-, Y+, Y-, Z+, Z-.
4. Hold **`Shift`** while dragging → temporarily release symmetry lock (moves only the current handle, not the mirrored one).

### 3.4 Delete control points
- Select a control point and press **`Delete`** or **`Backspace`**.
- Or click the **`🗑️ Delete Node`** button in the panel's node property inspector.

---

## 🖥️ 4. The Three Panel Tabs Explained

### 4.1 Tab One: 🛣️ Curve Edit (Curve Nodes)

#### Edit mode switching
- **`➕ Click to Add Point`**: left-click block surfaces in sequence to create new anchors.
- **`🎯 Select & Drag`**: pick anchors/handles and shape the curve via the 3D axis Gizmo.

#### Curve geometry & translation
- **Closed loop road (connect head/tail)**: when checked, head and tail connect to form a seamless loop.
- **Translate All (X, Y, Z)**: enter X/Y/Z deltas and click **`Translate All`** to move every point of the curve together.

#### Control point list & node property inspector
- **Control point list**: scroll to view all anchor coordinates and symmetry state (`[Sym]` / `[Free]`); click an item to select it quickly.
- **Handle target**: switch between `Main anchor`, `Front handle`, `Back handle`.
- **Precise 3D coordinates**: enter exact float values to fine-tune positions.
- **Symmetric tangent handle lock**: when checked, front/back handles mirror each other for smooth curves.
- **📷 Focus view**: one-click to pan the freecam near the selected control point.
- **🗑️ Delete node**: delete the currently selected control node.

---

### 4.2 Tab Two: 🧊 Array Studio

#### Template source & V panel integration (core feature)
Two template generation modes:
1. **V panel block preset (recommended)**:
   - **Strongly linked** to the road preset selected in the overview (V) panel (e.g. `Dirt Path`, `Paving`, `Grass Block`, `Water`, `Cobblestone`, `Gravel`, `Oak Planks`). Switching blocks in the V panel preset bar live-syncs the dynamic template on the right panel!
   - **Dynamic road spec generator**:
     - **Road Width**: freely slide to set the lateral paving width (`1 ~ 15` blocks).
     - **Base Depth**: freely slide to set the number of base layers laid downward (`1 ~ 3` layers).
     - **Side Border**: when checked, the outermost left/right edges auto-pave a stone-brick guard border.
2. **JSON preset file**:
   - Reads a fixed-structure JSON blueprint template saved on disk.

#### Real-time 3D preview & adjustment
- **Enable 3D array preview**: previews the layout in the world as a 3D bounding box in real time.
- **Sample step (blocks)**: the interval between samples along the Bezier curve. Smaller = smoother and denser.
- **3D array pose rotation fine-tuning**: `Roll`, `Pitch`, `Yaw` sliders and a `0° Reset` button to adjust the cross-section tilt.
- **🧊 Submit road construction task**: packs the generated road blocks and sends them to the server for the town's wizard NPCs to build automatically!

---

### 4.3 Tab Three: 💾 Templates & Tools

#### Template file management
- **Enter template name**: type a name in the text box (e.g. `main_road`).
- **Save JSON template**: exports all spline points as JSON to `config/wandscape/splines/`.
- **Load JSON template**: loads the spline with the current player position as the placement origin.

#### View & quick tools
- **Toggle 2D top-down view (G)**: switch to vertical top-down view.
- **Open guide (H)**: quick popup of this document.
- **Clear canvas**: remove all control nodes and reset the canvas.
- **Close Studio**: exit the road studio.

---

## ⌨️ 5. Hotkey Reference

| Key | Function |
| :--- | :--- |
| **Right-click held + mouse** | Rotate view |
| **Right-click held + WASD** | Freecam 3D flight (intercepts character movement) |
| **Right-click held + Space / Shift** | Fly up / down |
| **Right-click held + Ctrl** | Fast flight (2x) |
| **Scroll wheel directly** | Adjust freecam flight speed (no Ctrl needed) |
| **Left click** | Add anchor (add mode) / select anchor / drag Gizmo axis |
| **Shift + left-click drag handle** | Temporarily release symmetry lock |
| **Delete / Backspace** | Delete selected control point |
| **G** | Toggle 2D top-down view |
| **H** | Open guide document |
| **ESC** | Exit editor |

---

## 🛠️ 6. Common Questions

### Q: Broken blocks or gaps at curve corners?
The handles are pulled too long, making the curvature too extreme. Shorten the handle distance (2~4 blocks), or reduce the sample step (to 1.0) for a denser sample.

### Q: Dragging an axis also moves the other side's handle?
That's the "symmetric tangent handle lock". Hold Shift while dragging to temporarily release it, or uncheck `Symmetric tangent handle lock` in the panel.

### Q: Where are template files stored?
`<game dir>/config/wandscape/splines/*.json`

---

👉 [Jump to Replace mode guide](guide:road_replace_guide)  
👉 [Jump to Fill mode guide](guide:road_fill_guide)  
👉 [Back to road overview guide](guide:road_guide)
