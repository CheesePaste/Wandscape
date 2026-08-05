# 🏗️ Building Scanner API Guide

The Building Scanner scans buildings/roads built in-game and **exports mod JSON blueprints and road presets in one click**, so NPCs can rebuild them from blueprints. There are two variants:

- **Creative Building Scanner** (`creative_building_scanner`): full creator tool, all categories and full configuration.
- **Building Scanner** (`building_scanner`): for survival players to copy their own builds; category is locked to **custom** — only size/door/ID/name + export.

![Scanner medieval UI demo](wandscape:textures/gui/guide/scanner_ui.png)

---

## 🛠 Creative Building Scanner

The UI uses the standard medieval gilded theme (`MedievalScreen`) with hand-drawn gradient buttons (`drawMinimalBox`). Input boxes have bronze-gold borders and **Focus / Hover dynamic glow**, with native viewport clipping (Scissor Clip) so nothing spills when scrolling.

### 1. Structure Pairing Mode (`BlockMode`)
- **`SAVE` (master save)**: the main scanner; computes the bounding box, shows the config panel and performs JSON export.
- **`CORNER` (auxiliary corner)**: helper block marking the diagonal corners of the 3D bounding box.
- **Auto pairing**: within 64 blocks, `SAVE` and `CORNER` blocks sharing the same `Structure Name` auto-pair to compute the exact 3D bounding box — no manual coordinates needed.

### 2. Export Target Mode (`TargetMode`)
- **`BUILDING` (building mode)**:
  - Shows the full building config (door `Door Offset`, `Tourist Zones`, the three values `Comfort/Magic/Wonder`, `Unlock Level`, `Maintenance Cost`, plus shop/service/node-specific parameters).
  - `Type (Category)` covers all categories, including **`custom`** — meaning no maintenance cost, no tourist interaction, and zero three-values.
  - Click **【Export Building JSON】** to export the blueprint into the datapack buildings folder and **hot-register it immediately**; it stays valid after `/reload`.
- **`ROAD` (road mode — simplified)**:
  - Automatically hides all building-specific config; the UI becomes minimal and clean.
  - Keeps only `Road Preset ID` and `Display Name`.
  - Click **【Export & Hot-Register Road JSON】** to export and **take effect immediately in-game**!

### 3. Key Actions
- **`Auto-Detect Door`**: auto-scans doors inside the 3D bounding box and fills the door offset; supports multi-door cycling.
- **`Scan Area`**: counts non-air blocks inside the 3D bounding box (**automatically excludes all scanner blocks**).

---

## 🧱 Building Scanner

Designed for survival players to **copy a build they made themselves** so NPCs can rebuild it. The scanner is crafted at a crafting table (gold ingots in the corners, amethyst shards top/bottom/left/right, a crafting table in the middle).

- **Category locked to `custom`** — not changeable.
  - **No maintenance cost**: skipped every daily settlement, never shut down for maintenance.
  - **No tourist interaction**: tourists never visit it.
  - **Three values always 0**: `comfort/magic/wonder` are all 0, no colony contribution.
- **Only exposes**:
  - **Size (boundary)**: manually set the 3D bounding box min/max.
  - **Door Offset**: set the entry coordinate for NPC interaction; auto-detect supported.
  - **ID / Name**: unique building id and display name.
  - **Export**: `Scan Area` counts valid blocks; `Export Building JSON` writes a `custom` blueprint.
- **ROAD mode is preserved** as well for exporting road presets.

### 3-Step Workflow (Survival)

1. **Frame it**: place the survival scanner and manually set the boundary box to cover your build.
2. **Name it**: set a building ID (e.g. `player_castle`) and display name; auto-detect the door offset.
3. **Export**: click **【Export Building JSON】** and confirm the chat message, then have NPCs rebuild it from the blueprint.

---

👉 [Back to build overview guide](guide:overview_guide)  
👉 [Back to main test page](guide:test_guide)
