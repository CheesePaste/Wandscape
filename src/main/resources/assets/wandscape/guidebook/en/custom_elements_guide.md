# Importing Element Values

> Put the file in your datapack under `data/<namespace>/element_mappings/` and `/reload` picks it up. **The file name means nothing here** — what prices anything is the `block`, `item` and `build_cost` written inside it.

The element mappings are the single ledger of what a thing is worth in elements. What the Crafting Station charges to make it, what the workstation yields for taking it apart, what a shop sells it for, what finding one in a chest is worth, and even whether it counts as a material at all — every one of those reads this table.

## Fields

| Key | Meaning |
|---|---|
| block | prices a block, by block registry id |
| item | prices an item, by item registry id |
| build_cost | a map from element to a whole number, keyed by one of the seven elements |
| disabled | a boolean that takes it out of the element economy |

## block and item

One of `block` or `item` is enough; a file with neither means nothing. Write it as a block and the block's item form is priced along with it; write only `item` and a block lookup will not find it.

## Who Reads the Value

`build_cost` is an item's **canonical value**, not what it costs to place. Several things charge against it: a workstation synthesis task spends it; taking an item apart in the workstation yields the value divided by the decompose divisor, which the Settings Center can adjust; a shop sells it with the shop's profit on top; an exploration chest prices its whole loot table through it; and the scanner values a patch of ground with it.

## No Mapping Means Free

Buildings and roads only request **mapped** blocks from the warehouse. A block with no mapping is never requested at all — so a single mistyped item id produces no error whatsoever, it just quietly makes that block free material that builds anyway. This is the easiest place in the table to lose money.

## disabled Has Teeth

`disabled` is not merely "sits out the economy": any building or road containing that block is **refused placement outright**, a deliberate rule that a banned block must never turn into free material. The `disabled/` subfolder in the mod's own data means nothing at all — the scan is recursive and files inside it load like any other. The field is the only switch.

## Overriding the Mod's Own Mappings

To change a price the mod already has, your file must sit in the **`wandscape` namespace under the same file name**, overriding it by datapack priority. A different file name does not work: the registry key is the file name with the namespace stripped, the mod's own copy wins a collision and yours is dropped whole, and even if both survived, a lookup returns whichever comes first in hash order, so which one wins is not defined.

## The Cost of One Bad Key

One bad element key, such as `matel` for `metal`, makes the whole file fail to parse and be discarded, with a warning in the log. That is the only mistake in here that makes a noise.

## Element Seeds

The mod also ships `element_seeds.json`, a hand-written baseline price list of some four hundred entries. It is **only** an input to one developer command: it prices nothing at runtime and no datapack can override it.

## The Generator Command

`/wandscape test generate_element_mappings` takes the seeds as a baseline and works backwards through vanilla recipes to produce a whole batch of mapping files; `--dry-run` shows the plan first and `--force` overwrites hand-written files that already exist.

Note that it writes into a **developer's source tree** — it is for editing the mod's own data on your own machine, and should not be run on a server.
