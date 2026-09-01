# Magic Circle Web Editor

This page is for creators. Regular players can skip it — the pretty magic circles in-game are drawn with this web tool.

The Magic Circle Editor (`tools/magic-circle-editor/`) is a standalone web page for visually designing particle effects; export JSON, drop it into the mod's `magic_circles/` directory, and it becomes usable in-game.

![Magic circle editor diagram](wandscape:textures/gui/guidebook/magic_editor_diagram.png =200x100)

## What You Can Tune

- **Shape**: ring / regular polygon / multi-pointed star, with a **side count** (5 makes a five-pointed star).
- **Stroke & density**: `beads` distributes particles evenly along the outline, keeping star tips from clumping into a blob; the higher the density, the denser it is.
- **Pulse**: `pulse_interval` controls the breathing rhythm of the circle (20 ticks per second).
- **Projection axis**: `XZ` lays flat on the ground (normal circles), `Y` floats vertically (portals, shields).
- **Radius & color**: the size, and a color gradient from particle birth to death (with alpha support).
- **Curves**: drag Bezier curves to fine-tune fade-in/out and scaling.

## Quick Workflow

Pick a shape → tune the animation (stroke, pulse, smoothness) → drag the curves → click **Export Spec** to export the JSON and drop it into `magic_circles/`. Done.

## Common Questions

**The exported circle stands upright in-game?** Switch the projection axis to `XZ` to lay it flat on the ground; use `Y` only when you want it to float vertically.

---

[Back to the Guide Index](index_guide.md)
