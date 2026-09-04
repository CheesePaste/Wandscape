# /wandscape Commands

This page documents every `/wandscape` command. Player-facing commands are grouped by domain; developer/debug commands live under `/wandscape test` (op-2 only).

> Permissions:
> - **No permission**: read-only queries and rescue commands — any player.
> - **op-2 (admin)**: commands that mutate a colony's assets/values.
> - **test (developer)**: low-impact or obscure tuning/testing commands — op-2, invisible to normal players in autocomplete.

## Colony

- `/wandscape colony status` — level/exp/three-values/tourists/mages/elements/works-under-construction (no op)
- `/wandscape colony list` — list all registered colonies (no op)
- `/wandscape colony create <name>` — create a colony ahead and spawn starter mages (op-2)
- `/wandscape colony destroy` — destroy the executor's colony (op-2)
- `/wandscape colony level <n>` — set colony level directly (op-2, resets exp)
- `/wandscape colony exp <n>` — grant experience (op-2, may trigger level-up)
- `/wandscape colony name <name>` — rename the colony (op-2)

## Element

- `/wandscape element view` — view the 7 element balances (no op)
- `/wandscape element add <type> <amount>` — add elements (op-2; earth/wood/water/fire/metal/wind/dark)
- `/wandscape element remove <type> <amount>` — remove elements (op-2)
- `/wandscape element clear` — clear all elements (op-2)

## Warehouse (items only)

- `/wandscape warehouse view` — item count/usage/capacity (no op)
- `/wandscape warehouse add <itemId> <amount>` — add items (op-2)
- `/wandscape warehouse remove <itemId> <amount>` — remove items (op-2)
- `/wandscape warehouse clear` — clear all items (op-2)

## Building

- `/wandscape building list [category]` — list colony buildings (no op; category optional)
- `/wandscape building cancel <buildingId>` — cancel an under-construction building and refund materials (op-2; short id prefix ok)
- `/wandscape building demolish <buildingId>` — demolish a building (op-2)

## Road

- `/wandscape road status` — network edges/build status/total length (no op)
- `/wandscape road cancel <edgeId>` — withdraw an under-construction road edge and refund materials (op-2)

## NPC

- `/wandscape npc list [idle]` — mage roster (level/idle/task/HP/mana/spells; no op)
- Recruiting a mage → `/wandscape tavern recruit` (op-2); training/level-up happens in the Mage Hut UI.

## Tourist

- `/wandscape tourist list` — tourist roster (state/level/three-need bars; no op)
- `/wandscape tourist clear` — clear all tourists in the colony (no op, triggers normal departure; use when stuck/overcrowded)

## Tavern

- `/wandscape tavern list` — pending mage resumes (no op)
- `/wandscape tavern recruit` — recruit one mage (op-2; first free, then 10000 of every element)

## Guard

- `/wandscape guard status` — guard zones/nearest threat/active guard tasks (no op)

## Recovery

- `/wandscape recover status` — task pool/building queue state (no op)
- `/wandscape recover clear` — clear tasks, building queues, reset mages (no op; **last resort before lock-up** — interrupts all running tasks)

## Guide

- `/wandscape guide [page]` — open the guidebook (no op; defaults to this page, or open any page like `warehouse`)

## Developer `test` (op-2 only)

- `/wandscape test log ...` — runtime log levels/filter
- `/wandscape test profile ...` — tick profiling
- `/wandscape test audit_elements` — audit items missing element mappings
- `/wandscape test generate_element_mappings` — regenerate element mapping files
- `/wandscape test fill <type> <spacing> <count>` — register a row of buildings and enqueue construction
- `/wandscape test publish <blueprintId> [key=value ...]` — publish a blueprint task to the pool
- `/wandscape test magic ...` — no-cooldown/no-mana/clear-CD/fill-mana/cast on a player
- `/wandscape test transport ...` — item fly animation/routing benchmark
- `/wandscape test tourist spawn|state|cooldown` — force-spawn tourists/switch state/cooldown toggles
- `/wandscape test tavern spawn_mage|add_resume` — spawn a full mage/inject a resume
- `/wandscape test roadstudio` / `spline` — enter the road studio / spline editor

---

[Back to guide home](index_guide.md)
