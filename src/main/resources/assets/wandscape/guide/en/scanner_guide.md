# 🏗️ Building Scanner API Guide

The Building Scanner (Scanner) is the core developer tool for scanning buildings/roads built in-game and **exporting mod JSON blueprints and road presets in one click**.

![Scanner medieval UI demo](wandscape:textures/gui/guide/scanner_ui.png)

---

## 📖 UI Controls & Modes Reference

The UI uses the standard medieval gilded theme (`MedievalScreen`) with hand-drawn gradient buttons (`drawMinimalBox`). Input boxes have bronze-gold borders and **Focus / Hover dynamic glow**, with native viewport clipping (Scissor Clip) so nothing spills when scrolling.

### 1. Structure Pairing Mode (`BlockMode`)
- **`SAVE` (master save)**: the main scanner; computes the bounding box, shows the config panel and performs JSON export.
- **`CORNER` (auxiliary corner)**: helper block marking the diagonal corners of the 3D bounding box.
- **Auto pairing**: within 64 blocks, `SAVE` and `CORNER` blocks sharing the same `Structure Name` auto-pair to compute the exact 3D bounding box — no manual coordinates needed.

### 2. Export Target Mode (`TargetMode`)
- **`BUILDING` (building mode)**:
  - Shows the full building config (door `Door Offset`, `Tourist Zones`, the three values `Comfort/Magic/Wonder`, `Unlock Level`, `Maintenance Cost`, plus shop/service/node-specific parameters).
  - Click **【Export Building JSON】** to export the blueprint to `.minecraft/wandscape_buildings/<id>.json`.
- **`ROAD` (road mode — simplified)**:
  - Automatically hides all building-specific config; the UI becomes minimal and clean.
  - Keeps only `Road Preset ID` and `Display Name`.
  - Click **【Export & Hot-Register Road JSON】** to export to `.minecraft/wandscape_roads/<id>.json` and **take effect immediately in-game**!

---

## 📋 Fields & Actions Reference Table

| Control / Label | Type | Description |
| :--- | :--- | :--- |
| **`Mode`** | Button | Switches between `SAVE` master mode and `CORNER` corner mode. |
| **`Structure Name`** | Input | Structure name, used for pairing (e.g. `townhall_lv1`). |
| **`Target`** | Button | Switches between `BUILDING` and `ROAD` mode. |
| **`Type` (Category)** | Button | Determines building type (`basic`, `shop`, `service`, `node`, `tavern`, etc.). |
| **`❖ Match Corners`** | Button | Actively triggers pairing with same-named `CORNER` blocks within 64 blocks and recomputes the bounding box. |
| **`X±1 / Y±1 / Z±1`** | Buttons | 6 micro-adjust buttons expanding the 3D bounding box by 1 block along X/Y/Z, no need to move blocks. |
| **`❖ Door Offset`** | Inputs | Sets the entry offset `(X, Y, Z)` for NPC/tourist building interaction. |
| **`Auto-Detect Door`** | Button | **Auto-scans doors inside the 3D bounding box** and fills the door offset; supports multi-door cycling. |
| **`❖ Tourist Zones`** | Rows | Configures 3D interaction regions where tourists stay inside the building (`+ Add` / `× Delete`). |
| **`❖ Placement Metadata`** | Inputs | Sets Building ID (e.g. `wandscape:shop_bakery`), display name and the three value attributes. |
| **`❖ Periodic Maintenance Cost`** | Rows | Sets the elements and amounts needed to keep the building running (e.g. `earth: 1`). |
| **`Scan Area`** | Button | Counts non-air blocks inside the 3D bounding box (**automatically excludes the scanner blocks themselves**). |
| **`Export JSON`** | Button | Serializes and exports the JSON file (**automatically filters out scanner blocks**). |

---

## 🚀 3-Step Workflow

1. **Place & name**: put a `SAVE` scanner at one building corner with a structure name; put a `CORNER` scanner at the diagonal corner with the same name.
2. **Match & configure**: click **【❖ Match Corners】** in the `SAVE` UI to auto-compute the 3D size; configure metadata, maintenance cost, or switch to `ROAD` mode as needed.
3. **One-click export**: click **【Export Building JSON】** or **【Export & Hot-Register Road JSON】** and check the success message in chat.

---

👉 [Back to build overview guide](guide:overview_guide)  
👉 [Back to main test page](guide:test_guide)
