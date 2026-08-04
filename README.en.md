# Wandscape

<p align="center"><img src="minecraft_title.png" alt="Wandscape — Magic Town" width="70%"></p>

**English** | [中文](README.md)

A Minecraft NeoForge 1.21.1 mod — **Wandscape** . Magic-powered town automation & management sim.

Point your magic wand at NPC mages to build structures, gather elements, craft items, and cast magic circles. Once your town gets going, tourists walk in along the roads, and shops, inns, decorations and wonders complete a full management loop.

## Core Gameplay

### Town Automation

- **Wand system** — Basic / Adept / Master wand tiers, unbreakable, dynamically tinted, JSON recipe driven, plus stamina & mana potions
- **NPC Mages** — carry wands and execute tasks, with a personal mana pool and resurrection after death, bridged via ECS components
- **Task system** — 12 blueprints (build / demolish / clear-and-build / terrain / roads / node gather / brew / craft wand / decompose / synthesize…) + a blueprint DSL; a global task pool scores and dispatches by distance/efficiency/stats, auto-triggering gather or craft when resources run low
- **Town building** — JSON-driven buildings (nodes/warehouse/workstation/crafter/brewing/shop/service/tavern/town hall), one-click build from the V-panel, blueprint projection preview; town hall & warehouse are free on first placement
- **Element economy** — 7 elements (earth/wood/water → fire/metal/wind → dark) with increasing rarity, flowing through warehouses via block mapping, gathering, decomposition and crafting
- **Three-value progression** — Comfort/Magic/Wonder determine town tier and unlock buildings & recipes
- **Road system** — MST network auto-generation + editor + wide-surface rendering + spline logistics + pathfinding; both tourists and NPCs move along roads
- **Logistics optimization** — single-entity visual merging for NPC ↔ warehouse ↔ shop transport; large stacks of identical items spawn one entity carrying the count, avoiding lag

### Magic Circles & Laser

- **Data-driven** — circles are defined by `MagicCircleSpec` JSON (ring/arc/polygon/star/glyph elements + animation curves); a web editor exports the same geometry the game renders
- **Particle rendering** — tintable point particles sampled per tick; shader-free, so it displays correctly under any shader pack
- **Casting** — trigger via wand right-click, debug command, or casting on an NPC; the circle renders perpendicular to the cast direction, then fires a tinted laser beam at the target
- **Example** — ships with the `arcane_hexagram` large summoning-hexagram spec

### Management Sim (Tourist Economy)

- **Tourists** — tier/energy/satisfaction/preferences/appearance; they enter town along roads daily and interact, check into inns or leave based on satisfaction
- **Emotions & speech** — tourists and mages show 6 moods (delighted / pleased / satisfied / neutral / disappointed / upset), with speech bubbles and a live satisfaction bar overhead
- **Narratives** — arrivals, visits, check-ins, departures and satisfaction milestones are broadcast as story-like text, bringing the town to life
- **Shops** — restock from warehouses each morning; tourist purchases recycle elements at a fixed margin (default 20%)
- **Service buildings & inns** — tourists spend energy, generating satisfaction & element income; highly-satisfied tourists stay overnight and wake up fully rested
- **Decorations** — area radiation that boosts the three values of nearby functional buildings, capped at 100% of the building itself
- **Wonders** — directly contribute to town values + global modifiers (spell power / prices / rule unlocks)
- **Upkeep & shutdown** — buildings settle upkeep periodically; missing elements trigger tiered shutdown to prevent dead-end saves
- **Tavern recruiting** — 100%-satisfied mage-tourists leave résumés at the tavern for later recruitment

### Management

- **V-panel** — bird's-eye view by default; left sidebar (build/roads/stats/warnings) and a full-width top HUD showing the three values, day count, tourists, NPCs and element reserves
- **Soul projection** — toggle soul projection to preview and place building blueprints
- **Building scanner block** — box-select any structure to generate a JSON config directly
- **Stats system** — daily snapshots + 30-day rolling summary

### Combat & Progression

- **Raids** — reuses the vanilla village raid; approach with Bad Omen to trigger one, repel it for an achievement
- **Achievements** — 15 vanilla advancements covering build scale, colony level, full stock, full house, wonders and raid victories

## Dev Environment

```bash
./gradlew runClient            # start a test client
./gradlew build                # compile
./gradlew test                 # unit tests
./gradlew runGameTestServer    # run GameTest
./gradlew neoForgeIdeSync      # run before first launch / when runClient reports a missing clientRunVmArgs.txt
```

- **Minecraft** — 1.21.1
- **NeoForge** — 21.1.233
- **Mappings** — Parchment 2024.11.17
- **JDK** — 21+

## Project Layout

```
src/main/java/com/wsteam/wandscape/   # modules (core/engine/shared/building/wand/npc/tourist/magic/...)
src/main/resources/data/wandscape/    # JSON configs (buildings/recipes/element mappings/blueprints/roads/magic circles)
architecture/                         # single source of truth for code structure — read before working
docs/                                 # design docs & roadmap
spec/                                 # JSON format specs
magicarchitecture/                    # magic circle design contract (spec/principles/examples)
```

## Design Principles

1. **High compatibility** — no vanilla behavior changes; JSON data-driven; block mapping via tags; `/reload` hot-reload
2. **Atomic design** — modules communicate via `WandscapeApis` + EventBus, no cross-package direct references
3. **Stability first** — every failure path has a fallback; no crashes or soft-locks; auto tiered shutdown on upkeep shortfall
4. **Engine requests, adapters implement** — `core/` has zero MC dependency; MC implementations live in `engine/`
5. **Don't punish players** — remote management panel; NPCs walk short distances / teleport long ones; they can keep working after revival
6. **Performance** — single-entity logistics merging + gold-framed dark bubble counters to avoid entity-spawn lag

## License

MIT License — see [LICENSE](LICENSE)
