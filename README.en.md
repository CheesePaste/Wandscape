# Wandscape

<p align="center"><img src="minecraft_title.png" alt="Wandscape — Magic Town" width="70%"></p>

**English** | [中文](README.md)

A Minecraft NeoForge 1.21.1 mod — **Wandscape**.

> **You're the mayor. Your mages do the rest.**

## Summary

Wandscape is a **magic × town-building × simulation** mod for Minecraft 1.21.1 (NeoForge). Found a town with a single block, and wizard NPCs take it from there — they gather elements, lay bricks, brew potions, and guard the walls while you run the place. Then tourists start showing up on your roads: shopping, drinking, checking into your inn, and telling everyone how good (or how bad) your town is.

## Your first ten minutes

1. **Place the Town Hall** — the first building of each type is free, so founding your town costs nothing. Give it a name, and it's official.
2. **Add a warehouse and an element node or two** — the warehouse starts pre-stocked with 6,000 of each element, and node mages begin gathering more on their own.
3. **Watch the mages work** — every building comes with a blueprint. Your NPCs lay the blocks one by one, haul materials, and fix any damage without being asked.

Craft the **Guide Book** from a few bits of dirt, logs, cobblestone and seeds, then right-click it any time you wonder what to do next — 20+ illustrated topics, bilingual English & Chinese. No micromanagement tutorials, no chore lists. Just… go.

## 🏰 The mages do the building

- **50+ buildings in 13 categories** — town hall, warehouses, 7 element nodes, an altar, tavern, inns and luxury hotels, 18 shops, workshops, crafting and brewing stations, and decorations.
- Blueprint construction: mages build block by block, restock materials, and repair damage automatically.
- A building under construction can be **undone** for a full refund, and a **construction panel** shows exactly which materials are still missing and when work will finish.
- Intact, operating buildings become **safe zones** — no natural mob spawning inside your walls.
- Comfort / Magic / Wonder values feed your colony level, which unlocks new buildings and recipes.
- A forecast system even spots shortages *before* they happen and queues extra gathering, so you're rarely caught short.

## ⚗️ Seven elements, one economy

- Earth, Wood, Water, Fire, Metal, Wind, Dark — harvested at nodes, decomposed from items at the workstation, synthesized into goods, and spent on everything. Seven craftable **element items** auto-deposit into your warehouse.
- Nearly every item and block in the game maps to element values — iron ingots are worth 64 Metal, ender pearls 64 Dark. Yes, the whole price list is auto-derived from vanilla recipes.
- Modpack-friendly: any mapping can be banned or overridden from a simple config.
- Crafting and brewing stations turn elements into wands and potions, with recipes unlocking as your town levels up.

## 👥 Tourists with a life of their own

- Tourists arrive on your roads with **three needs** — Comfort, Magic and Wonder — a personality that leans toward one or another, their own wallet, energy, and mood.
- They shop, hit the tavern, and check into the inn when night falls — complete with speech bubbles and story-like broadcasts of every arrival, purchase, and departure.
- Tourist-facing buildings come in four roles — **shops, services, relaxation spots and ATMs** — each with marked interaction points. Tourists queue politely (per-spot queues, strict first-come-first-served) and sleep in real beds at your inns.
- Shops restock themselves from your warehouse and turn a profit; decorations radiate bonuses to nearby buildings.
- Keep a mage tourist at 100% satisfaction and they'll leave a résumé at the tavern — recruit them to grow your workforce.
- And even when you're miles away, your tourists keep living their little lives — a shadow simulation quietly takes over the moment their chunks unload.

## ✨ Wizards at work

- Your mages are real wizards — they cast in glowing magic circles (rings, arcs, polygons, stars, glyphs) and fire beams of pure light. Spells are data-driven: heal, meteor, petrification, weakness fields and more, each with its own cooldown, mana cost and casting circle.
- Every cast is a little show: particle-driven animation, no shaders required (though it looks gorgeous under any shader pack).
- Forge wands at the crafting station and equip them on your mages — Basic, Adept, Master, each tier boosting their spell power. Wands never break.
- Every mage carries their own stats — spell power, casting speed, work speed, armor and more.
- Fallen mages can be **revived** at the altar — and if your whole colony falls in battle, a final safety spell brings everyone back at the town hall door.

## ⚔️ Even wizards need a militia

- Mages automatically guard your buildings — they spot threats, dodge incoming projectiles, answer with beams and spells, and hold grudges when hit.
- Switch each mage between **guard, follow and peace** modes from their panel.
- Raids center on your town hall (yes, vanilla raid mechanics, repurposed). Repel the waves and fireworks celebrate your town's fame.

## 🛠️ Run the town from your armchair

- **V-panel**: fly over your town in a bird's-eye overview, place buildings remotely, and check stats without walking anywhere. Press **F4** to hide every overlay and get a clear view.
- **Roads**: a road network generates itself as your town grows — or paint your own with the road brush, and use the spline editor for curvy routes.
- **Building scanner**: box-select any existing structure and export it as a reusable blueprint — with multi-door and decorative-entity support. It's craftable in survival, so your own hand-built masterpiece can be scanned and rebuilt anywhere.
- **30 advancements**: build 50 buildings, reach colony level 30, fill an inn to capacity, own a building bigger than 50×50, repel a raid.
- **Daily stats** with 30-day trend summaries, so you always know how the town is doing.

## FAQ

**Do I have to micromanage everything?** No. Mages pick up work on their own — gathering, building, crafting. You decide what to build and where; they handle the rest.

**What happens if I run out of elements?** Building and crafting wait for supplies instead of failing, and continue automatically once resupplied — the mod is designed so you can never soft-lock yourself.

**Does it change vanilla gameplay?** Almost nothing. No new ores, no rebalanced mechanics — Wandscape is data-driven, adds only a handful of items (wands, two scanners, a guide book, seven element items), and most systems are configurable via TOML. It plays nice with modpacks.

**Is running a town a chore?** We designed against that: remote management, no busywork chores, everything recoverable. You're the mayor, not the errand boy.

**Can I contribute?** It's MIT-licensed and open source — building, code, docs, and suggestions are all welcome.

## Dev Environment

```bash
./gradlew runClient            # start a test client
./gradlew build                # compile
./gradlew test                 # unit tests
./gradlew runGameTestServer    # run GameTest
./gradlew neoForgeIdeSync      # run before first launch / when runClient reports a missing clientRunVmArgs.txt
```

- **Minecraft** — 1.21.1
- **NeoForge** — 21.1.233 (min 21.1.1)
- **Mappings** — Parchment 2024.11.17
- **JDK** — 21+

### AI-assisted dev tool (optional but recommended)

If you develop with Claude Code, install **codebase-memory-mcp**: it indexes the codebase into a semantic knowledge graph so structural queries (call chains, impact analysis, architecture, dead code) are far faster and more token-efficient than grepping. It also backs the "code discovery" workflow in `CLAUDE.md` (`search_graph` → `trace_path` → `get_code_snippet`).

Open source: <https://github.com/DeusData/codebase-memory-mcp> (MIT, single static binary, zero deps). After install it auto-detects Claude Code and writes the MCP config:

```powershell
# Windows (PowerShell)
Invoke-WebRequest -Uri https://raw.githubusercontent.com/DeusData/codebase-memory-mcp/main/install.ps1 -OutFile install.ps1
.\install.ps1
```

```bash
# macOS / Linux
curl -fsSL https://raw.githubusercontent.com/DeusData/codebase-memory-mcp/main/install.sh | bash
```

Restart Claude Code after installing; on first use, index the repo first: `index_repository '{"repo_path":"."}'`

## Project Layout

```
src/main/java/com/wsteam/wandscape/   # modules (core/engine/shared/building/wand/npc/tourist/magic/...)
src/main/resources/data/wandscape/    # JSON configs (buildings/recipes/element mappings/blueprints/magic circles/narratives)
docs/                                 # module docs & data formats (source of truth, written against the real code)
architecture/                         # legacy structure snapshot (partly outdated — trust docs/)
architecture/magic/                   # magic circle contract (spec/principles/examples)
```

## Design Principles

1. **High compatibility** — no vanilla behavior changes; JSON data-driven; block mapping via tags; `/reload` hot-reload
2. **Atomic design** — modules communicate via `WandscapeApis` + EventBus, no cross-package direct references
3. **Stability first** — every failure path has a fallback; no crashes or soft-locks; building damage protection
4. **Engine requests, adapters implement** — `core/` has zero MC dependency; MC implementations live in `engine/`
5. **Don't punish players** — remote management panel, mages work on their own, everything recoverable
6. **Performance** — single-entity logistics merging with gold-framed bubble counters to avoid entity-spawn lag

## License

MIT License — see [LICENSE](LICENSE)
