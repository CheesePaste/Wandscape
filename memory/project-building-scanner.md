---
name: Building Scanner block replaces ImGui editor
description: Building Scanner block (structure-block-like) with 5 modes replaces the bulky ImGui building editor. interaction_radius removed.
type: project
---

Building Scanner block (building_scanner) replaces the old ImGui building editor.

**Why:** The ImGui editor was bulky and didn't allow closing the GUI to walk around and see markers. The new block works like vanilla Structure Block — place it, configure in modes, close GUI to see wireframes, re-open to continue editing.

**What was done:**
- `InteractionRadius.java` fully deleted, JSON files cleaned up
- Building Scanner block with modes: BOUNDARY (orange wireframe), DOOR (red marker), INTERACT (green wireframes), META (GUI fields), EXPORT (scan + export)
- Vanilla Screen GUI (no ImGui), wireframe renderer via RenderLevelStageEvent
- C2S sync packet for GUI changes, full NBT persistence in BE
- Registered: block + BlockEntityType + packet in Wandscape.java, renderer in WandscapeClient.java
- Old ImGui editor client registrations removed (BuildingEditorController, BuildingEditorRenderer, etc.)

**Files under `building/scanner/` :**
- `ScannerMode.java` — mode enum
- `BuildingScannerBlockEntity.java` — full NBT BE
- `BuildingScannerBlock.java` — block with FACING
- `client/BuildingScannerScreen.java` — vanilla GUI screen
- `client/BuildingScannerRenderer.java` — wireframe rendering
- `network/BuildingScannerSyncPacket.java` — C2S sync

**How to apply:** When adding new features to the scanner, modify the screen for GUI changes and the renderer for visual changes. The BE handles persistence. Sync via `syncToServer()` in the screen.
