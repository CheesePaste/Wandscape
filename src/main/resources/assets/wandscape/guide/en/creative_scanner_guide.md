# 🛠️ Creative Building Scanner

This page is for creators and map makers. **The Creative Building Scanner is a creative-mode tool, unavailable in survival mode** — it can export a building together with its **tourist interact spots, decoration entities, and the whole set of business parameters**, producing buildings with "soul". For regular players, the [Building Scanner](guide:scanner_guide) is enough to copy houses.

## Structure Pairing

Place two blocks: **SAVE (main)** and **CORNER (corner point)**, fill in the same structure name, and they auto-pair within 64 blocks to compute the bounding box — no need to enter coordinates manually.

## Fitting the Building Out in the Panel

- **Boundary**: ±1 buttons for X/Y/Z to fine-tune the bounding box size.
- **Door Offset**: auto-detect doors; cycle through multiple doors.
- **Metadata**: building ID, name, creator, the Comfort/Magic/Wonder values, unlock level.
- **Maintenance**: add per-element daily costs one by one.
- **Preset Saving**: save the whole configuration as a preset and load it directly next time.

## Tourist Interact Spots

Want tourists to **actually perform actions** inside the building (instead of standing around doing nothing)? Use the `interact_spot_marker` block to mark a "spot" in the world:

- **Place**: put a marker on the ground and you have an interact spot — a **preview dummy** immediately stands on it, looping through the actions of this spot.
- **Right-click**: cycles through actions — **Browse / Eat / Bathe / View / Pay / Rest / Withdraw**.
- **Sneak right-click**: cycles through facing directions (North/East/South/West); tourists will face this direction when performing actions.
- **Break it**: removes the interact spot.

A few conventions: **the number of interact spots = the maximum number of tourists who can interact here at the same time** (once full, later arrivals queue up); the block occupied by an interact spot is automatically left empty in the blueprint — don't put critical structure blocks underneath it; when the building rotates, interact spot facings rotate along with it.

## Decoration Entities

Entities like item frames and paintings (not blocks) can also be scanned in: on export, their type, facing, and contents are captured, and NPCs restore them exactly when rebuilding. Wall decorations will never be lost again.

## Business Parameters for the Four Tourist Building Types

| Type | What you can configure |
| :--- | :--- |
| **Shop** | Profit rate, interaction duration, **the list of goods on sale** (each good with Comfort/Magic/Wonder values) |
| **Service** | How much tourist energy each use consumes, elements produced, **max occupancy** (>0 makes it a hotel), interaction duration |
| **Relax** | How much tourist energy each use restores, interaction duration |
| **ATM** | How much money each use dispenses, interaction duration |

## Export

When configured, click "**Export Building JSON**" — the export carries the interact spots, decoration entities, and the whole set of business parameters, **takes effect in-game immediately**, and survives `/reload`.

---

[Building Scanner (Copying Buildings in Survival)](guide:scanner_guide)  
[Tourist System Guide](guide:tourist_guide)  
[📖 Back to the Guide Index](guide:index_guide)
