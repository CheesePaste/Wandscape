# 🏗️ Building Scanner

Finished a house and want NPCs to build another one like it? Use the Building Scanner to turn your building into a blueprint — afterwards, wizards can rebuild it exactly the same. This scanner can be crafted in **survival mode**; it is your own copy tool.

## Crafting Recipe

**Gold ingots** in the four corners, **amethyst shards** on the four sides, and a **crafting table** in the middle.

## What Its Scans Look Like

The scanner stores buildings under the **custom** category, with two traits:

- **No maintenance cost** — it can never shut down for failing to pay.
- **No tourists come** — no three-stat contribution, not part of the economy.

In short, it doesn't earn money and doesn't attract people; it purely lets you copy a house you built by hand and have NPCs rebuild it elsewhere.

## Three Steps

1. **Box it in**: place the scanner and set the **bounding box** manually in the panel so it covers the whole building you want to copy.
2. **Identify**: fill in the building's **ID** (a unique identifier, e.g. `player_castle`) and **display name**; click "Auto-Detect Door" to pick the door's position — that is the entrance NPCs use.
3. **Export**: click "Scan Area" to count the blocks, then click "**Export Building JSON**". Once the chat bar reports success, the building is a blueprint — you can select it in the build panel from now on and have NPCs rebuild it.

Want to copy a stretch of road? Switch to **ROAD mode**, fill in the road preset ID and display name, and export.

## What About the Creative Building Scanner?

The feature-packed **Creative Building Scanner** is for creators and map makers (it can configure interact spots, business parameters, and decoration export) — **unavailable in survival mode**, and those settings are of no use for normal play anyway. Map makers can go to the [Creative Building Scanner Guide](guide:creative_scanner_guide); players have seen all they need here.

---

[Panel & Overview](guide:overview_guide)  
[📖 Back to the Guide Index](guide:index_guide)
