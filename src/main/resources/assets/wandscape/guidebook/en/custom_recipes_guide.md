# Importing Crafting Recipes

> Put the file in your datapack under `data/<namespace>/craft_recipes/` and reopen the Crafting Station after `/reload`. **The file name is the recipe id**; the `id` field inside is read by nothing, so renaming the file renames the recipe.

Every row in the Crafting Station's wand and gadget list, and every row in the Magic Workshop's scroll list, is backed by a `craft_recipes` file. These recipes do not use the vanilla `pattern`/`key` shape: they state only how many elements something costs and what it produces, and a mage does the work in town.

## Where the Files Go

The ordinary datapack path, `data/<namespace>/craft_recipes/<id>.json`, takes effect on `/reload`.

To override a recipe the mod ships, your file **must sit in the `wandscape` namespace under the same file name**: the registry key is the file name with the namespace stripped away, and when two namespaces collide the mod's own copy wins while the other is dropped in silence.

## Fields Every Recipe Shares

| Key | Meaning |
|---|---|
| type | `wand`, `spell`, `misc` or `potion`; absent means `wand` |
| output | the product, which must carry at least `item` |
| cost | a map from element to a whole number |
| unlock_requirement | only `min_colony_level` is read, default 1 |
| craft_station | labels the station on the JEI page only |
| display_name | **read by no code at all** |

## What a Typo Costs

Get `type` wrong by a single letter and the file is **dropped in silence** — no error, no warning. An element key that does not exist makes the whole file fail to parse, which at least leaves a warning in the log.

## The Name on the Screen

A recipe row shows neither `display_name` nor the file name, but the language key `craft_recipe.wandscape.<recipe id>`, falling back to **the product item's own name**. A wand recipe with no language key therefore shows up as plain "Wand" next to every other un-named wand.

## Where Language Files Live

Language files live on the `assets` side, so **a pure datapack cannot supply one**. To choose the name yourself the pack has to ship `assets/<namespace>/lang/en_us.json` alongside its data and fill in that key.

## Wands

`output.item` must be `wandscape:wand`, `wand_color` takes a `#RRGGBB` string, and the modifiers go in the `attributes` array, each entry carrying three keys: `type`, `operation` and `amount`.

## Writing the Modifiers

- `type` is one of nine: `max_hp`, `move_speed`, `spell_power`, `work_speed`, `spell_speed`, `armor_value`, `max_mana`, `health_regen`, `mana_regen`.
- `operation` is either `addition`, which adds the amount outright, or `multiply_base`, which adds a percentage of the base value. `amount` is therefore sometimes `40.0` and sometimes `0.2`, and negative numbers are legal.

## What a Wand Is

A wand has no tier or class field: what a wand *is* is exactly this set of modifiers plus a colour.

## Two Things That Bite

The modifiers **apply to a mage holding the wand and to nobody else** — a player holding one gets nothing. And a misspelled enum value inside `attributes` costs you the preset variant in the creative inventory while **the recipe itself still crafts**, so the result is a wand that exists, crafts fine, and carries no modifiers at all.

## Scrolls

Set `type` to `spell`, use `wandscape:spell_scroll` for `output.item`, and point `output.magic_id` at the magic to bind. The default `craft_station` is `magic_station`.

## What magic_id Defaults To

Omit `output.magic_id` and it **defaults to the recipe's own file name** rather than to any magic id. A recipe called `scroll_heal.json` that leaves it out binds to a magic named `scroll_heal`, which very likely does not exist. Nothing validates it at load time, and the price of the typo is a scroll that does nothing when used and reports unknown data.

## Gadgets

Set `type` to `misc` and put any item id in `output.item`. One item comes out per craft and the player picks the quantity in the screen. This type has no `count` or `nbt` field to write.

A `potion` type also exists, supporting a product with NBT and extra ingredients; the mod itself does not use it anywhere.

## Costs Get Multiplied

What you write into `cost` is a **unit price**. The amount actually paid is multiplied by the server's element craft-cost multiplier (1.0 by default, adjustable in the Settings Center) and rounded up, and a player raising the output quantity scales the cost linearly with it. Keep that layer in mind when tuning: a thousand in the JSON is not a thousand out of the player's warehouse.
