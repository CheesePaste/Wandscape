# Warehouse

Stores all the town's elements and materials. Building and crafting deduct from here, and tourists' spending income returns here. **NPCs can only use materials stored in the warehouse.**

## Two Tabs

**Overview**

| Item | Description |
|---|---|
| Element panel | Reserve of the town's **7 elements** (Earth / Wood / Water / Fire / Metal / Wind / Dark) |
| Item list | Searchable material list with fuzzy search by name |
| Supply gaps | When the Crafting Station or Workstation is stuck, the missing materials are highlighted here |

**Exchange**

A chest-style slot grid: one item per slot with the count in the bottom-right corner.

| Action | Effect |
|---|---|
| Left-click a warehouse item | Take the whole stack into the cursor |
| Right-click a warehouse item | Take half into the cursor |
| Shift + left-click a warehouse item | Quick-withdraw into the inventory |
| Click a warehouse slot with a carried stack | Deposit the cursor stack |
| Right-click (with a carried stack) | Deposit 1 item |
| Shift + left-click an inventory item | Quick-deposit into the warehouse |
| Click the **×** delete slot (bottom right) with a carried stack | Delete the carried items (left-click: whole stack, right-click: one) |

The **×** at the bottom right is a delete slot (like the Creative Mode destroy X): pick up whatever you want gone — from your own inventory or just taken out of the warehouse — then click it to delete. Use the deposit actions above when you mean to store something. Only items held on the cursor are deleted; the read-only warehouse slots themselves are never affected.

The inventory area behaves exactly like a vanilla container: hotbar keys 1-9, Q drop, drag-split and inventory-sorting mods all work.

## Warehouse Capacity

Warehouse capacity scales with your **warehouse buildings**: **each warehouse adds 50000** (a colony with no dedicated warehouse yet counts as one), so the more you build, the more you can store. **Every item counts 1** — unstackable wands / scrolls count 1 too; elements live in their own ledger and do **not** count.

| State | Behavior |
|---|---|
| Space available | Deposits and crafting run normally |
| Warehouse full | Players cannot deposit; "consume materials to craft items" tasks at the Workstation / Crafting Station / Magic Station are marked **"Storage full"** and wait in the queue — they resume automatically once space frees, no need to re-submit |
| Exempt | Shop restocking (including auto-synthesis for restock), item decomposition, demolition salvage and task refunds are never blocked by a full warehouse, so the colony economy never stalls |

The space readout — current **used/limit** — is shown under the elements on the Overview tab and to the right of the "Warehouse" title on the Exchange tab (turns red when full). To free space, withdraw items on the Exchange tab or **decompose** them into elements at the [Workstation](workstation_guide.md).

## When No Warehouse Building Exists

When the colony has no warehouse building, the [Town Hall](townhall_guide.md) panel shows a Warehouse Access button that temporarily acts as a warehouse for deposits/withdrawals; NPCs also pick up materials from the Town Hall position. Once a warehouse is built, this reverts to normal.

## Startup Funds

When you place your first building, the warehouse automatically receives **6000 of each element** as startup funds.

## Element Consumption

Building and crafting both consume elements. When short, gather from [Element Nodes](node_guide.md) or replenish by decomposing at the [Workstation](workstation_guide.md).

## Tips

> When NPCs take materials, items are shown **flying** from the warehouse to the wizard's hands; this is normal, not a glitch.

---

[Town Hall](townhall_guide.md)  
[Element Node](node_guide.md)  
[Getting Started: From Empty Land to a Tourist Town](getting_started_guide.md)  
[Back to the Guide Index](index_guide.md)
