---
name: minecraft-source
description: |
  Read Minecraft source code from a Fabric or NeoForge mod project. Extracts decompiled, human-readable Java source directly from the project's generated sources jar. CRITICAL: When writing Minecraft mod code, NEVER guess or hallucinate Minecraft/NeoForge APIs — ALWAYS read the actual source first. Before using any Minecraft class, method, field, or checking if a method exists, what parameters it takes, or what it returns, invoke this skill to read the real source. This is mandatory for mod development, not optional. Use this skill whenever the user asks to read or view Minecraft source code, wants to understand a Minecraft class, method, or API, references a Minecraft class name, or whenever you are about to write mod code that interacts with Minecraft internals (e.g., "what is ActionResult", "show me ItemStack", "read net.minecraft.util.ActionResult", "how does X work in MC"). Triggers on: mc source, Minecraft code, read Minecraft class, show Minecraft source, what is <ClassName> in Minecraft, decompile Minecraft, Yarn mappings, Mojang mappings, mod development, neoforge, fabric mod.
compatibility: Requires a Fabric (Loom) or NeoForge (MDG) mod project with sources already generated. Works on Windows (Git Bash), macOS, and Linux.
---

# Minecraft Source Reader

Read Minecraft source code from a Fabric Loom or NeoForge MDG project's generated sources jar. Both contain fully decompiled Java with readable names — no `cbq`/`func_1234_a` gibberish.

**Golden rule of MC mod development: never guess the API.** Before writing any code that touches a Minecraft or NeoForge class, read its actual source. Method signatures, field names, class hierarchies, side effects — all of these differ between mappings, versions, and platforms. A hallucinated method call compiles fine in your head and explodes at runtime. Read the source.

## Auto-detection

The main script detects your project type by checking the filesystem:

- **NeoForge** — `build/moddev/artifacts/neoforge-*-sources.jar` exists
- **Fabric** — `.gradle/loom-cache/minecraftMaven/` exists

When you invoke the skill, the correct backend script is selected automatically.

If you need to force a specific backend:

```
bash .claude/skills/minecraft-source/scripts/decompile-fabric.sh   <class-name>
bash .claude/skills/minecraft-source/scripts/decompile-neoforge.sh <class-name>
```

## Usage

Run the main script, passing a Minecraft class name in any common format:

```
bash .claude/skills/minecraft-source/scripts/decompile.sh <class-name>
```

### Supported input formats

| Format | Example |
|---|---|
| Dots (package) | `net.minecraft.world.item.ItemStack` |
| Slashes (path) | `net/minecraft/world/item/ItemStack` |
| Short name | `ItemStack` |
| Inner class with `$` | `ItemStack$Builder` |
| Inner class with `.` | `ItemStack.Builder` |
| NeoForge class | `net.neoforged.neoforge.event.server.ServerStartingEvent` |

Inner classes are extracted from their parent file (they share the same `.java` file).

## How it works

1. Walks up from CWD to find the mod project root (`gradle.properties`)
2. Detects Fabric vs NeoForge and locates the correct sources jar(s)
3. **NeoForge**: searches a hierarchy of source jars:
   - `build/moddev/artifacts/neoforge-*-sources.jar` — vanilla MC (Mojang)
   - `build/moddev/artifacts/neoforge-*-merged.jar` — MC + NeoForge patches
   - `~/.gradle/caches/.../net.neoforged/**/*-sources.jar` — NeoForge library sources (event bus, registries, capabilities, coremods, etc.)
4. Converts the class name to a file path (`net/minecraft/world/item/ItemStack.java`)
5. Extracts and prints the file with `unzip -p`

No Gradle execution, no network calls. All source jars are already in the project or Gradle cache.

## Fabric backend

- **Jar**: `.gradle/loom-cache/minecraftMaven/.../...-sources.jar`
- **Mappings**: Yarn (human-readable names from Fabric community)
- **Prerequisite**: `./gradlew genSources` or `./gradlew build`

## NeoForge backend

- **Primary jars** (`build/moddev/artifacts/`):
  - `neoforge-{version}-sources.jar` — vanilla MC (Mojang official names)
  - `neoforge-{version}-merged.jar` — vanilla + NeoForge patches merged
- **Gradle cache jars** (`~/.gradle/caches/.../net.neoforged/`):
  - `neoforge-{version}-sources.jar` — NeoForge SDK (registries, capabilities, events)
  - `bus-{version}-sources.jar` — NeoForge Event Bus (Event, SubscribeEvent, IEventBus)
  - `coremods-{version}-sources.jar` — coremod infrastructure
  - Other NeoForge sub-library sources
- **Resolution**: `net.neoforged.*` classes search Gradle cache jars first (best source quality), then MDG merged jar, then MDG sources jar. Vanilla MC classes prefer the MDG sources jar.
- **Mappings**: Mojang official
- **Prerequisite**: MDG places jars during project setup — no extra step needed for primary jars. Gradle cache jars are populated when dependencies are resolved (`./gradlew build`).

## What you get

Decompiled Java with:
- Correct package declarations
- Readable class, method, and field names (Yarn or Mojang depending on platform)
- Original Javadoc comments
- Complete class structure (inner classes, enums, records)

## Prerequisites

Fabric: `./gradlew genSources` (one-time setup, cached permanently).
NeoForge: `./gradlew build` or IDE import (MDG sets up artifacts automatically).
