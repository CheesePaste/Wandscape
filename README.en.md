# Wandscape

<p align="center"><img src="minecraft_title.png" alt="Wandscape — Magic Town" width="70%"></p>

**English** | [中文](README.md)

A Minecraft NeoForge 1.21.1 mod — **Wandscape**.

> **You're the mayor. Your mages do the rest.**

## Summary

Wandscape is a **magic × town-building × simulation** mod. Found a town with a single block, and wizard NPCs take it from there — they gather elements, lay bricks, brew potions, and guard the walls while you run the place. Then tourists start showing up on your roads: shopping, drinking, checking into your inn, and telling everyone how good (or how bad) your town is.

## Your first ten minutes

1. **Place the Town Hall** — the first building of each type is free, so founding your town costs nothing. Give it a name, and it's official.
2. **Add a warehouse and an element node or two** — the warehouse starts pre-stocked with 2,000 of each element, and node mages begin gathering more on their own.
3. **Watch the mages work** — every building comes with a blueprint. Your NPCs lay the blocks one by one, haul materials, and fix any damage without being asked.

No micromanagement tutorials, no chore lists. Press **H** for the in-game guide and just… go.

## 🏰 The mages do the building

- ~20 buildings in 9 categories — town hall, warehouse, 7 element nodes, 4 shops, tavern, inn, workstation, crafting and brewing stations…
- Blueprint construction: mages build block by block, restock materials, and repair damage automatically.
- Comfort / Magic / Wonder values feed tourist satisfaction, which turns into colony experience — level up to unlock more buildings and recipes.
- Upkeep is settled daily — low on elements? Buildings **suspend gracefully instead of breaking**, and restart automatically when resupplied. No soft-locks, ever.
- A forecast system even spots shortages *before* they happen and queues extra gathering, so you're rarely caught short.

## ⚗️ Seven elements, one economy

- Earth, Wood, Water, Fire, Metal, Wind, Dark — harvested at nodes, decomposed from items at the workstation, synthesized into goods, and spent on everything.
- Nearly every item and block in the game maps to element values — iron ingots are worth 64 Metal, ender pearls 64 Dark. The whole price list is auto-derived from vanilla recipes.
- Crafting and brewing stations turn elements into wands and potions, with recipes unlocking as your town levels up.

## 👥 Tourists with a life of their own

- Tourists arrive on your roads with energy, satisfaction, preferences, and moods.
- They shop, hit the tavern, and check into the inn when night falls — complete with speech bubbles and story-like broadcasts of every arrival, purchase, and departure.
- Shops restock themselves from your warehouse at each building's own profit margin; decorations radiate bonuses to nearby buildings.
- Keep a mage tourist at 100% satisfaction and they'll leave a résumé at the tavern — recruit them to grow your workforce.
- And even when you're miles away, your tourists keep living their little lives — a shadow simulation quietly takes over the moment their chunks unload.

## ✨ Wizards at work

- Your mages are real wizards — they cast in glowing magic circles (rings, arcs, polygons, stars, glyphs) and fire beams of pure light.
- Every cast is a little show: particle-driven animation, no shaders required (though it looks gorgeous under any shader pack).
- Wands are your mages' casting tools — forge them at the crafting station and equip them on your mages: Basic, Adept, Master, each tier boosting their spell power. Wands never break.
- Every mage carries their own stats — spell power, casting speed, work speed, armor and more.

## ⚔️ Even wizards need a militia

- Mages automatically guard your buildings — they spot threats, answer with beams, and hold grudges when hit.
- Raids center on your town hall (vanilla raid mechanics, repurposed). Repel the waves and your town's fame grows.

## 🛠️ Run the town from your armchair

- **V-panel**: fly over your town in a bird's-eye overview, place buildings remotely, and check stats without walking anywhere.
- **Roads**: a road network generates itself as your town grows — or paint your own with the road brush, and use the spline editor for curvy routes. Tourists and mages walk your roads.
- **Building scanner**: box-select any existing structure and export it as a reusable blueprint. It's craftable in survival, so your own hand-built masterpiece can be scanned and rebuilt anywhere.
- **31 vanilla advancements**: build 50 buildings, reach colony level 30, fill an inn to capacity, own a building bigger than 50×50, repel a raid, hire a full roster of 10 mages, host 30 tourists at once, hoard 50k of an element, and more.
- **Daily stats** with 30-day trend summaries, so you always know how the town is doing.

## FAQ

**Do I have to micromanage everything?** No. Mages pick up work on their own — gathering, building, crafting. You decide what to build and where; they handle the rest.

**What happens if I run out of elements?** Buildings suspend and wait instead of breaking. Resupply them and they restart by themselves — the mod is designed so you can never soft-lock yourself.

**Does it change vanilla gameplay?** Almost nothing. No new ores, no rebalanced mechanics — Wandscape is data-driven, adds only a handful of items (a wand, a scanner or two), and most systems are configurable via TOML. It plays nice with modpacks.

**Is running a town a chore?** We designed against that: remote management, no busywork maintenance, everything recoverable. You're the mayor, not the errand boy.

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
3. **Stability first** — every failure path has a fallback; no crashes or soft-locks; auto tiered shutdown on upkeep shortfall
4. **Engine requests, adapters implement** — `core/` has zero MC dependency; MC implementations live in `engine/`
5. **Don't punish players** — remote management panel, mages work on their own, everything recoverable
6. **Performance** — single-entity logistics merging with gold-framed bubble counters to avoid entity-spawn lag

## License

MIT License — see [LICENSE](LICENSE)
