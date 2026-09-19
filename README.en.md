# Wandscape

<p align="center"><img src="minecraft_title.png" alt="Wandscape — Magic Town" width="70%"></p>

**English** | [中文](README.md)

A Minecraft NeoForge 1.21.1 mod — **Wandscape**. **Must be installed on both the server and the client.**

> **You're the mayor. Your mages do the rest.**

## Summary

**Wandscape** is a large-scale mod that blends magic, town building, and simulation management. Players take on the role of a mayor, dispatching mages to construct buildings and fend off enemies — building a thriving magical town from empty ground.

## Gameplay Lines

**Simulation Management:** Host tourists, earn profits, and boost popularity.

**Adventure & Exploration:** Craft wands, arrange spells, and venture out to explore.

## Key Features

- **Extensive Building System:** 10 major categories, 50+ buildings, with support for importing custom buildings.
- **Seven-Element Economy System:** Lowers the difficulty of construction and management.
- **Unified Management Panel:** Building, path-laying, and interaction all in one place, with a built-in flight mode.
- **Offline Earnings:** As long as the server stays on, you earn even while AFK.
- **Lightweight Experience:** Rare building materials can be synthesized, mages can be revived, and it's beginner-friendly.
- **Combat Arrangement:** Eight types of magic, a large number of combat items, and strategy arrangement that can be precise down to the individual unit.

## Dev Environment

```bash
./gradlew runClient            # start a test client (run neoForgeIdeSync first / if it errors)
./gradlew build                # compile (no unit tests maintained — build is the full check)
./gradlew neoForgeIdeSync      # run before first launch / when runClient reports a missing clientRunVmArgs.txt
```

- **Minecraft** — 1.21.1
- **NeoForge** — 21.1.233 (min 21.1.1)
- **Mappings** — Parchment 2024.11.17
- **JDK** — 21+

## Project Layout

```
src/main/java/com/wsteam/wandscape/   # source: content/<13 domains>, foundation/, api/, compat/, impl/
src/main/resources/data/wandscape/    # JSON configs (buildings/recipes/element mappings/blueprints/magic/narratives/tags)
src/main/resources/assets/wandscape/  # assets (lang en_us/zh_cn, models, textures, sounds)
docs/                                 # developer docs & data formats (navigation: docs/README.md; code is source of truth)
```

## Design Principles

1. **High compatibility** — no vanilla behavior changes, purely additive content & mechanics; JSON data-driven; block mapping via tags; `/reload` hot-reload
2. **Aggregate by feature, call directly** — one domain per package; cross-domain code calls business classes directly, no bridge layers, no event-bus overuse
3. **Stability first** — every failure path has a fallback; no crashes or soft-locks; key infrastructure (town hall / warehouse / workstation) is protected when only one remains
4. **Pure logic stays MC-free** — core algorithms, blueprint parsing and task scoring never import Minecraft classes, keeping them portable
5. **Don't punish players** — remote management panel, mages work on their own, everything recoverable
6. **Performance** — single-entity logistics merging with bubble counters to avoid entity-spawn lag

## License

MIT License — see [LICENSE](LICENSE)
