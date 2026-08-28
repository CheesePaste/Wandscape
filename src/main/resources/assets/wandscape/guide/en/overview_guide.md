# Panel & Overview

The panel opened with **V** is used to manage the town: place buildings, lay roads, and check statistics. It opens in **overview mode** by default: the camera rises above your head to survey the whole town.

The panel has two states — understand these and you have the basics:

- **Normal state** (no tab active): the mouse stays in the game layer. Just move the mouse to rotate the camera, and use the **crosshair** at the screen center to target buildings and wizards.
- **Sub-modes** (the Build / Road construction states): the cursor is lifted so you can click the panel UI freely, and you rotate the camera by **holding right-click and dragging**. The Stats and Warning tabs are pure viewing screens and keep the normal controls.

## Most-Used Actions

1. **Normal interaction**: move the mouse to rotate the camera; point the **crosshair** at a building or wizard and press **right-click** to open its panel (name the Town Hall, stock a shop, order node gathering, equip a wizard, set strategy, etc.).
2. **Rotate the camera in a sub-mode**: **hold right-click and drag** — while the cursor is lifted, moving the mouse only moves the pointer, so dragging with right-click is how you turn the view.
3. **Rotate a building's facing**: while placing a building, **left-click** the ghost to rotate its direction.
4. **Quickly switch modes**: press **1 / 2 / 3 / 4** to jump straight to Build / Road / Stats / Warning.

## Key Bindings

| Key | Action |
| :--- | :--- |
| **V** | Open / close the panel |
| **F4** | Hide / show the panel (does not block the F3 debug screen) |
| **1 / 2 / 3 / 4** | Quickly switch to **Build / Road / Stats / Warning** |
| **B** | Show / hide building boundary boxes |
| **H** | Open the help guide |
| **C** | Raise the cursor |
| **Tab** | Fold / expand the tutorial guide (reverts to the vanilla player list once the guide is done) |

## Flying in Overview Mode

Flying works the same as creative mode: **WASD** to pan, **Space / Shift** to ascend/descend, **scroll wheel** to zoom. To inspect a building up close, fly closer; flight speed is fixed and can be adjusted in the mod config under `panel.flySpeed`. In the normal state just move the mouse to rotate the camera; in the Build / Road sub-modes hold right-click and drag instead.

## The Three Sidebar Tabs

- **Build**: the building bar below lists buildable structures by category (All / Government / Storage / Service / Shops / Relax / ATM / Workshop / Node). Flow: **single-click** to select → **double-click** to enter placement → the ghost preview follows your mouse → **left-click** to rotate the facing → press **Submit** on the right panel to open the construction screen → **Submit** to build. If the position needs fine-tuning: press **Lock** (or **Enter**) to pin it, drag the **3D axis gizmo** to move it, then **Submit**. While pinned, **Esc** returns to aiming; **Esc** again exits placement. First-free buildings are marked in the bar.
- **Roads**: the paving toolset — see [Road System Overview](road_guide.md).
- **Stats**: tourist flow, economic income, resident count.

The **warning icon** below the sidebar opens the [Building Status report](anomaly_guide.md), which lists buildings still under construction.

## Raising the Cursor (C)

In the Build and Road sub-modes the cursor is already lifted so you can click the sidebar, building bar, and right panel — rotate the camera by right-drag, no C needed. When you need the cursor lifted so the pointer can move freely between the panel UI and the world, press **C**.

## Folding / Expanding the Tutorial Guide (Tab)

While the tutorial guide card is showing, **Tab** folds / expands it so you can see the panel and the world. Once the guide is done, Tab reverts to its vanilla function (the player list).

## Where to Start

1. Build the **Town Hall** to create your colony.
2. Build a **Warehouse** to store materials.
3. Connect the buildings with the road tools — tourists only walk on paved roads.

See [Getting Started](getting_started_guide.md) for the full route.

---

[Road System Overview](road_guide.md)  
[Back to the Guide Index](index_guide.md)
