# Spline Editor

Replace mode can only pull rectangles; drawing arcs requires **splines**: click anchors in the world to draw curves, used for building curves, loops, and interchanges.

## Entering & Exiting

Press **V** → the **Roads** icon → click **Spline**; an edit panel appears on the right side of the screen. **ESC** or the panel's "Close" exits.

## Camera Controls

After entering the editor, **WASD no longer moves the character, but controls the camera**. Flying works the same as overview mode: **WASD/Space/Shift** to fly at any time, **right-click drag** to rotate the view, **scroll wheel** to zoom. Flight speed is fixed and can be adjusted in the mod config under `panel.flySpeed`.

Two editor-only keys: **G** switches to 2D top-down view, **H** opens this guide.

With the cursor free, click the panel or control points in the world; right-click drag rotates the view.

## Drawing a Curve

1. In the panel's "**Click to Add Point**" mode, **left-click** a block surface in the world — that adds an anchor. Add two or more, and the curve preview appears.
2. **Left-click** an anchor to select it; an **RGB 3D axis** appears around it (red X / green Y / blue Z). **Hold and drag an axis** to move the anchor along it. The **tangent handles** at both ends control the arc.
3. Want a symmetric bend on both sides? Keep "Symmetric Tangent Handle Lock" on; hold **Shift** while dragging to temporarily move only one side.
4. Delete a point: select it and press **Delete** / **Backspace**, or use the panel's "Delete Node".

## Generating a Road

The "**Array Generation**" tab turns the curve into a road:

- **Material** follows the preset selected in the V panel's bottom bar; switching materials updates the preview in real time.
- **Width** (1~15 blocks), **base layer thickness** (1~3 layers), **stone shoulder edging** (check to line both sides with stone bricks).
- A smaller **sample step** makes it smoother; keep the **3D preview** on to see the effect.
- Once confirmed, click "**Submit Construction Task**" and the wizards will execute the paving.

## Saving Templates

The "Templates & Tools" tab lets you save the current curve as a template under a name, stored in `<game dir>/config/wandscape/splines/`. Loading it next time places it with your current position as the origin.

## Common Questions

**Broken blocks at sharp corners?** The control handles are pulled too long and the curvature is too extreme. Shorten the handles (2~4 blocks), or reduce the sample step (e.g. to 1.0).

**Dragging one axis moves the other side too?** That is the "Symmetric Tangent Handle Lock". Hold Shift while dragging to release it temporarily, or uncheck it in the panel.

---

[Replace Mode (Surface Paving)](road_replace_guide.md)  
[Road System Overview](road_guide.md)  
[Back to the Guide Index](index_guide.md)
