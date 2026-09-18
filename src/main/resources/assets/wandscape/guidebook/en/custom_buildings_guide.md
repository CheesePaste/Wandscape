# Importing Custom Buildings

> Read the [Building Scanner](building_scanner_guide.md) first: what the scanner exports *is* the file this page describes. Hand-writing only pays off when you want to generate a batch, or to touch up what a scan produced.

Put a building file in your datapack and after `/reload` it shows up in the build panel and in the Wand's list. **The file name only decides which package it belongs to; the `id` written inside is the building's real identity.**

## Where the Files Go

The path is `data/<namespace>/buildings/<id>.json`, and the namespace is yours to choose — it need not be `wandscape`. Only an override of a building the mod ships has to sit in the `wandscape` namespace under the same file name.

The **first subdirectory level under `buildings/` is the building package**: `data/xxx/buildings/medieval/inn.json` belongs to the `medieval` package, and files sitting directly under `buildings/` belong to `default`. See [Importing Building Packs](custom_packs_guide.md).

## The Smallest Working File

| Key | Meaning |
|---|---|
| id | identity; the file name is not used |
| category | class, deciding its build-panel tab |
| pattern | block offsets, three numbers each |
| palette | the block state list |
| block_indices | as long as `pattern`, each an index into `palette` |
| blueprint | construction blueprint; without it the panel never lists it |

## The Blueprint

`blueprint` is the one people miss, and missing it looks like this: the log reports a clean load, and the building is nowhere in the panel. Copy its contents verbatim out of a scanner export. It holds two fields, `id` for which construction logic to use and `bind` for which blueprint parameter takes which building field.

A `bind` value carries a leading marker, the way a variable reference is written in a scripting language. Composing that binding yourself buys nothing — copy the scanner's.

## The Three Geometry Fields

`pattern`, `palette` and `block_indices` together are the building: the offsets, a table of block states, and an index pointing each offset at one entry of that table. Their lengths and index ranges are checked at load time, and a mismatch throws the whole file away with one warning.

## Bounding Box and Block Data

`boundary` is the bounding box, two corners under `min` and `max`, and a missing corner fails the parse. It is **not** checked against `pattern` in any way: the pattern may reach outside the box, while the space inside the box that the pattern does not cover is what "clear the box" sweeps at build time.

`block_nbt` carries block entity data and is stripped from a survival scanner's export. The `block_mapping` field of older files is now an outright error — rewrite it as palette plus block_indices.

## Doors

`door_offsets` is the list of candidate door blocks tourists come in through, and you may list several. Leave it out and tourists come in along the edge of the bounding box — a house that floats in the air or sits buried underground has no entrance they can find. The singular `door_offset` is the old key, still readable, no longer worth using.

## Interact Spots

`interact_spots` is where tourists stand, each entry carrying three keys: `pos` relative to the anchor, `action` for what they do, and `facing` for which way they look. `action` is one of `browse`, `eat`, `bathe`, `view`, `pay`, `read`, `take`, `rest`, `withdraw`; `facing` is one of `north`, `east`, `south`, `west`. Getting either wrong **raises nothing** — it is quietly rewritten to `browse` and `south`.

## How Many Spots

A building may hold several interact spots, and how many tourists it serves at once is exactly how many spots it has, so **a building with no interact spot at all is one tourists never touch** — it builds fine and still counts towards the town, they simply never come.

## Class

`category` is a free string and is not validated, but it decides which tab of the build panel the building lands in. Town halls, warehouses, workstations, crafting stations, magic workshops, taverns and altars carry further meaning in code, and mistaking the class costs the building that identity.

## Parameter Groups

Four parameter groups concern tourists — `shop`, `service`, `relax` and `atm` — and harvesting nodes use `node_config`. **Those groups and `category` do not follow each other**: what makes a building a tourist target is having one of those four groups written, while `category` only affects filing and sorting. Write a `shop` group and set `category` to something else, and tourists still come and shop.

## Values and the Gate

`comfort`, `magic` and `wonder` are the three values, deciding which need the building satisfies. `unlock_requirement` is the town-level gate: **omit it entirely and there is no gate at all (level 1)**, whereas an empty object `{}` means level 0 — the two are not the same, so do not write `{}` out of habit.

## What Happens When It Is Wrong

- Missing `id`: the entry is skipped, with one warning line.
- A syntax or geometry check fails: the whole entry is dropped with one warning, and every other building loads as usual.
- A mistyped block id: nothing is raised at load time, the block is skipped when the build reaches that cell, and the house comes out with a hole.
- `blueprint` missing or its id wrong: the first leaves the building out of the panel, the second throws while the build task is being expanded. Neither makes a sound at load time.

## Making a Change Take Effect

One `/reload` is enough: it re-reads on the server and re-syncs clients in the same pass, with no restart and no relog. The one thing that will not refresh is a **build panel that is already open** — leave and re-enter the build page to see the new building.

`/wandscape building list` prints the buildings the game currently knows about, and `/wandscape fill <building id> <spacing> <count>` lays out a row of them to check — with a mistyped id, that command echoes every known id back at you.
