# Building Scanner

Turn a building you have finished into a blueprint, and wizards can rebuild it elsewhere from that blueprint. The scanner can be crafted in **survival mode**.

## Crafting Recipe

**Gold ingots** in the four corners, **amethyst shards** on the four sides, and a **crafting table** in the middle.

## Scanned Buildings

The scanner stores buildings under the **custom** category:

- **No tourists come** — no three-stat contribution, not part of the economy.

This kind of building produces no income and draws no tourists; it only copies a building you placed by hand so NPCs can rebuild it.

## Steps

1. **Box it in**: place the scanner and set the **bounding box** manually in the panel so it covers the whole building you want to copy.
2. **Identify**: give the building an **English ID** (something like `player_castle`, which you'll use to pick this blueprint later) and a **display name**; click "Auto-Detect Door" to pick the door's position — that is the entrance NPCs use.
3. **Export**: click "Scan Area" to count the blocks, then click "**Export Building JSON**". Once the chat bar reports success, the building is a blueprint — you can select it in the build panel from now on and have NPCs rebuild it.

To copy a stretch of road: switch to **ROAD mode**, fill in the road preset ID and display name, and export.

## Creative Building Scanner

The feature-packed **Creative Building Scanner** is for creators and map makers (it can configure interact spots, business parameters, and decoration export) and is **unavailable in survival mode**; those settings are of no use for normal play. Map makers can go to the [Creative Building Scanner Guide](creative_scanner_guide.md).

---

[Panel & Overview](overview_guide.md)  
[Back to the Guide Index](index_guide.md)
