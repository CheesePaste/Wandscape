# Importing Loot Chest Values

> You edit `exploration_regions` in a datapack and `/reload` picks it up at once. Before you start, look at the files the server writes for itself — whatever you mean to override is probably already in there.

When a player opens or breaks a naturally generated chest out in the world, the town is paid a sum of experience and elements. That payout is not stored on the chest; it is worked out from the **loot table**: the mod reads the whole table's weights, works out the expected yield of one opening, then prices every item through the element mappings and adds it up.

## Where the Files Go

Region configs load through the ordinary datapack path, `data/<namespace>/exploration_regions/<id>.json`.

## The Self-Written Tier

There is a second tier the server writes for itself, under `<world>/wandscape/generated_regions/`: every time the server starts, the mod walks all loot tables, prices the chest tables that no region declares, and writes the result as a file in the same format.

Between the two tiers the **declared one wins** — as soon as a region you wrote matches, the generated file of the same name steps aside on its own, and you never have to delete it.

## Which Chests It Claims

The top level holds just two keys. `name` is the display name, shown in the HUD notice when a player opens a chest; `loot_table_patterns` is a list of regexes matched against the loot table's full id, such as `minecraft:chests/simple_dungeon`.

## How a Match Is Chosen

Matching is won **on specificity**: of every region that matches, the one whose patterns carry the most literal characters wins, ties broken by region id. You can therefore write one `.*` region to catch everything, and it will only take effect where no more specific region fits — the result does not depend on the order files load in.

You do not need anchors: a pattern is accepted on a full match or on a substring match.

## What It Pays

The `reward` block says where the value comes from. `mode` picks the source of the element value vector:

- `derived` (the default) reads the loot table's weights and works out the expectation, with nothing written down.
- `fixed` uses only the numbers in `value` and never touches the loot table.
- `additive` adds `value` on top of the sampled expectation.

## The value Field

`value` maps an element to an amount, keyed by one of the seven elements. **If you write a `value` without moving `mode` to `fixed` or `additive`, the value is logged as a warning and ignored** — "written but doing nothing" is the expensive kind of quiet, so this one gets loud.

## The Three Parameters

| Key | Effect |
|---|---|
| exp_ratio | experience = the vector's total ÷ this ratio, default 5.0, and never below a floor of 15 |
| variance | how far the roll spreads, default 0.25 for plus or minus 25% |
| loot_share | the fraction of the element total that keeps the loot table's proportions; the rest is spread over the seven elements, default 0.5 |

Experience follows the chest's value alone — there is no per-region multiplier such as a danger factor: whatever a chest is worth in elements, that is what it pays in experience.

## How the Estimate Is Priced

Pricing through the element mappings measures what it costs to build the things in the table. That is an honest reflection of a chest's worth, but it sits below how rewarding finding one should feel, so **the estimated vector is doubled before it enters the formulas above**.

A number you wrote into `value` is used exactly as written — whoever writes a number means that number.

## Nothing Priced, Nothing Paid

When no item in the loot table has an element mapping — another mod's chests are the usual case — the vector is empty and the payout is zero: no experience, no elements, no particles, no HUD notice, just one line in the log.

That is deliberate: a small consolation payout only makes "no data" look like "a cheap chest" and hides the real problem.

## When It Settles

The payout settles when a player right-clicks a naturally generated container or breaks it, and a given position only ever counts once. What it keys off is the container being **still unopened, with a loot table still attached** — vanilla clears the table the first time a chest is opened, and a chest a player placed never had one, so there is nothing to farm.

A double chest counts once for the whole chest, and container entities such as chest minecarts work the same way.

## Global Multipliers

The Settings Center's Town tab carries two dials, an exploration chest experience multiplier and an element multiplier, each settable from 0 to 10 times. They multiply at the very last step, so they cover both the estimate and a hand-written `value` alike — that is an administrator's global switch, not an region setting, and it is not the way to tune one region.
