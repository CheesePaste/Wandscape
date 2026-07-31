# Building JSON Specification

**Version:** 1.0
**Date:** 2026-06-20
**Source:** `data/wandscape/buildings/<id>.json`

---

## 1. Schema

```json
{
  "id":                    "<string> (required)",
  "display_name":          "<string> (required)",
  "category":              "<string> (default: \"basic\")",
  "block_id":              "<string> (default: \"\")",
  "pattern":               "[ [x,y,z], ... ] (default: [])",
  "block_mapping":         "{ \"x,y,z\": \"modid:blockid\", ... } (default: {})",
  "comfort":               "<int> (default: 0)",
  "magic":                 "<int> (default: 0)",
  "wonder":                "<int> (default: 0)",
  "maintenance_cost":      "<int> (default: 0)",
  "shutdown_penalty": {
    "output_reduction":    "<double> (default: 0.5)",
    "time_multiplier":     "<double> (default: 2.0)"
  },
  "queue": {
    "capacity":            "<int> (default: 5)",
    "task_types":          ["<string>", ...] (default: ["building"])
  },
  "unlock_requirement": {
    "min_wonder":          "<int> (default: 0)"
  },
  "boundary": {
    "min":                 "[x, y, z] (required if boundary present)",
    "max":                 "[x, y, z] (required if boundary present)"
  }
}
```

All fields except `id` and `display_name` have defaults. A minimal config needs only these two.

---

## 2. Field Reference

### 2.1 `id` (string, required)

Unique identifier for this building type. Used as the map key in `BuildingConfigLoader` and referenced by `blueprintId` ("build:<id>") in the task system.

```json
"id": "town_hall"
```

**Runtime:** Loader key, log output, `DataDrivenSteps` warning messages.

### 2.2 `display_name` (string, required)

Human-readable name. Appears in TaskSequence labels: `"Build 殖民地市政厅 at (0,64,0)"`.

```json
"display_name": "殖民地市政厅"
```

**Runtime:** `DataDrivenSteps` task label, admin panel display (planned).

### 2.3 `category` (string, default: `"basic"`)

Building classification for organizational and UI grouping.

| Value | Meaning |
|-------|---------|
| `government` | Town hall / colony seat. Any building with this category is the colony's townhall. |
| `node` | Element harvesting nodes |
| `shop` / `service` / `storage` / `tavern` | Tourist-facing businesses and infrastructure |
| `crafting_station` / `potion_station` / `workstation` | NPC production buildings |
| `wonder` | High-tier wonder buildings |

**Runtime:** Written to `BuildingDataImpl.category`, exposed via `BuildingData.getCategory()`. `category=government` is functional — it marks the building as the colony's townhall (colony linkage in `ColonyApiImpl`, starter-inventory resolution in `ColonyCommand`/`StressTestCommand`, and client guidance in `WandscapePanelState`).

### 2.4 `block_id` (string, default: `""`)

**Declared-only — not consumed at runtime.** The Minecraft Block registration is done via the NeoForge block registry, not this field. Currently serves as documentation of the mod block ID associated with this building type.

```json
"block_id": "wandscape:town_hall"
```

### 2.5 `pattern` (array of `[x,y,z]`, default: `[]`)

Defines the building's multi-block structure as relative offsets from the anchor block (the block the player placed). Each element is `[x, y, z]` where the anchor is `[0, 0, 0]`.

```json
"pattern": [
  [-1, 0, -1], [-1, 0, 0], [-1, 0, 1],
  [ 0, 0, -1],              [ 0, 0, 1],
  [ 1, 0, -1], [ 1, 0, 0], [ 1, 0, 1],
  [-1, 1, -1], [-1, 1, 1], [ 1, 1, -1], [ 1, 1, 1]
]
```

Single-block buildings use `[[0, 0, 0]]`.

**Runtime (3 consumers):**

| Consumer | What it does |
|----------|-------------|
| `DataDrivenSteps` | Iterates offsets → generates one `TransformOp.place` per offset, using `block_mapping` to resolve the block ID |
| `BlockPlaceHandler` | On building placement, walks pattern to fill any missing blocks |
| `AbstractWandscapeBE.checkStructureIntegrity()` | On block break / explosion, compares each offset's actual block against `block_mapping` expectation |

### 2.6 `block_mapping` (object, default: `{}`)

Maps each pattern offset (as `"x,y,z"` key) to a Minecraft block ID. Only offsets present here are required; missing mappings are skipped silently.

```json
"block_mapping": {
  "-1,0,-1": "minecraft:stone_bricks",
  "0,0,0":   "wandscape:earth_node"
}
```

**Runtime:** Same 3 consumers as `pattern` — each offset is looked up via `BlockOffset.toKey()` to get the expected block.

### 2.7 `comfort` (int, default: `0`)

Colony comfort contribution when this building type is first built.

**Runtime:** `BlockPlaceHandler` → `BuildingDataImpl.getComfort()` → `BuildingApiImpl.computeColonyStat(colonyId, BuildingConfig::comfort)`. Aggregated once per building type per colony.

### 2.8 `magic` (int, default: `0`)

Colony magic contribution. Same aggregation rule as comfort.

**Runtime:** Same pipeline as comfort, via `BuildingConfig::magic`.

### 2.9 `wonder` (int, default: `0`)

Colony wonder contribution. Same aggregation rule as comfort.

**Runtime:** Same pipeline as comfort, via `BuildingConfig::wonder`.

### 2.10 `maintenance_cost` (int, default: `0`)

Periodic element cost (wood element) per maintenance cycle to keep the building operational.

**Runtime:** `BlockPlaceHandler` → `BuildingDataImpl.getMaintenanceCost()`. The deduction logic (per 20-min cycle, from colony warehouse) is **planned but not yet implemented**.

### 2.11 `shutdown_penalty` (object, default: see below)

Penalties applied when the building is shut down (manual or auto from maintenance debt).

| Sub-field | Type | Default | Description |
|-----------|------|---------|-------------|
| `output_reduction` | double | `0.5` | Output multiplier (0.5 = 50% output) |
| `time_multiplier` | double | `2.0` | Task time multiplier (2.0 = 2× slower) |

Default: `{ "output_reduction": 0.5, "time_multiplier": 2.0 }`

**Status: Parsed, stored, defaults applied — not consumed by any shutdown logic yet.** The BE has `isShutdown` state and `isOperational()` check, but the penalty values are not applied to task execution.

### 2.12 `queue` (object, default: see below)

Building's internal task queue configuration.

| Sub-field | Type | Default | Description |
|-----------|------|---------|-------------|
| `capacity` | int | `5` | Max queued tasks |
| `task_types` | list | `["building"]` | Allowed task types for this building |

**Runtime:** `capacity` is written to `BuildingDataImpl.getQueueCapacity()`. `task_types` is **parsed but no filtering/enforcement logic exists yet**.

### 2.13 `unlock_requirement` (object, default: `{ "min_wonder": 0 }`)

Condition to unlock this building type for construction.

| Sub-field | Type | Default | Description |
|-----------|------|---------|-------------|
| `min_wonder` | int | `0` | Minimum colony wonder required |

**Status: Parsed, stored — unlocking check is not yet implemented.**

### 2.14 `boundary` (object, default: absent/null)

Axis-aligned bounding box (AABB) defining the building's occupied area in the world. Described by two opposite corner points: the minimum corner and the maximum corner. All coordinates are **relative to the anchor block** (same coordinate system as `pattern`).

```json
"boundary": {
  "min": [-1, 0, -1],
  "max": [ 1, 2,  1]
}
```

| Sub-field | Type | Required | Description |
|-----------|------|----------|-------------|
| `min` | `[int,int,int]` | yes (if boundary present) | Corner with the smallest x,y,z values |
| `max` | `[int,int,int]` | yes (if boundary present) | Corner with the largest x,y,z values |

**Usage (planned):**
- NPC pathfinding boundary — NPCs assigned to this building stay within this zone
- Building overlap detection — prevent placing a new building that would intersect an existing boundary
- Territory visualization — render a wireframe box in debug/admin mode
- Repair scan scope — limit `checkStructureIntegrity` to blocks within the boundary (narrower than scanning every pattern offset individually, useful for large buildings)

**Constraints:**
- `min.x <= max.x`, `min.y <= max.y`, `min.z <= max.z`
- The boundary must fully enclose all `pattern` offsets. An overlap check can be done at load time (pattern offset outside boundary → warning).
- If `boundary` is omitted, the AABB is implicitly the bounding box of the `pattern` offsets (computed at runtime).

**Runtime:** Not yet consumed. Will live in `BuildingConfig` as a `BoundaryBox` record, stored alongside `BuildingData`, queried by `BuildingApi`.

---

## 3. Implementation Status Summary

### ✅ Implemented & active in runtime

| Field | Consumers |
|-------|-----------|
| `id` | `BuildingConfigLoader`, `DataDrivenSteps` |
| `display_name` | `DataDrivenSteps` task labels |
| `category` | `BuildingDataImpl` → `BuildingData.getCategory()` |
| `pattern` | `DataDrivenSteps` (blueprint ops), `BlockPlaceHandler` (placement), `AbstractWandscapeBE` (integrity check) |
| `block_mapping` | Same 3 consumers as `pattern` |
| `comfort` | `BuildingDataImpl` → `BuildingApiImpl` colony stat aggregation |
| `magic` | Same as comfort |
| `wonder` | Same as comfort |
| `maintenance_cost` | `BuildingDataImpl.getMaintenanceCost()` (value exposed; deduction logic pending) |
| `queue.capacity` | `BuildingDataImpl.getQueueCapacity()` (value exposed; enforcement pending) |

### ⚠️ Defined in spec, not yet in code

| Field | Status |
|-------|--------|
| `boundary` | **New — spec only.** Not yet added to `BuildingConfig` or `BuildingData`. No parser, no runtime consumer. |

### ⚠️ Parsed, stored, not consumed

| Field | Reason |
|-------|--------|
| `block_id` | MC block registration is separate; field is declarative |
| `shutdown_penalty.output_reduction` | Shutdown penalty logic pending |
| `shutdown_penalty.time_multiplier` | Shutdown penalty logic pending |
| `queue.task_types` | Task type filtering pending |
| `unlock_requirement.min_wonder` | Unlock check logic pending |

---

## 4. Data Flow

```
JSON File (data/wandscape/buildings/<id>.json)
  │
  ├─[parse]─► BuildingConfigLoader (Gson → BuildingConfig record)
  │              │
  │              ├─[lookup]─► BuildingConfigLoader.get(id)
  │              │
  │              ├─[placement]─► BlockPlaceHandler
  │              │                  ├── walk pattern + block_mapping → fill missing blocks
  │              │                  └── BuildingDataImpl(id, category, comfort, magic, wonder,
  │              │                      maintenanceCost, queueCapacity) → BuildingApi.register()
  │              │
  │              ├─[blueprint]─► DataDrivenSteps.fromConfig(config)
  │              │                  └── for each offset in pattern:
  │              │                      blockId = block_mapping[offset.toKey()]
  │              │                      → TransformOp.place(anchor+offset, blockId)
  │              │                      → TaskSequence(label="Build {display_name} at {anchor}")
  │              │
  │              └─[integrity]─► AbstractWandscapeBE.checkStructureIntegrity(config)
  │                                 └── for each offset: compare actual vs expected block
  │                                     → isStructureIntact = true/false
  │
  └─[runtime]─► BuildingApiImpl.computeColonyStat(colonyId, BuildingConfig::comfort/magic/wonder)
                   └── Sum once per building type, per colony
```

---

## 5. Existing Buildings

### 5.1 Town Hall (`townhall1.json`)

- **Category:** government
- **Pattern:** 12 blocks (8 floor + 4 pillars)
- **Blocks:** stone_bricks floor, oak_log pillars
- **Stats:** comfort=5, magic=3, wonder=2, maintenance=4
- **Queue:** capacity=5, types=["building"]
- **Unlock:** min_wonder=0
- **Boundary:** `min=[-1,0,-1]`, `max=[1,1,1]` — snug box around the 3×1×3 footprint

### 5.2 Earth Node (`earth_node.json`)

- **Category:** node
- **Pattern:** single block `[[0,0,0]]`
- **Blocks:** `wandscape:earth_node`
- **Stats:** comfort=1, magic=2, wonder=0, maintenance=2
- **Queue:** capacity=10, types=["gathering"]
- **Unlock:** min_wonder=0
- **Boundary:** `min=[0,0,0]`, `max=[0,0,0]` — degenerate box (single-block building)

### 5.3 Forest Node (`forest_node.json`)

- **Category:** node
- **Pattern:** single block `[[0,0,0]]`
- **Blocks:** `wandscape:forest_node`
- **Stats:** comfort=1, magic=2, wonder=0, maintenance=2
- **Queue:** capacity=10, types=["gathering"]
- **Unlock:** min_wonder=0
- **Boundary:** `min=[-1,-1,-1]`, `max=[1,2,1]` — 3×3×3 zone for NPC gathering activities

---

## 6. Java Type Mapping

| JSON | Java |
|------|------|
| `{...}` root | `BuildingConfig` record |
| `[x,y,z]` | `BlockOffset` record (`x`, `y`, `z` ints) |
| `"x,y,z"` → `"modid:block"` | `Map<String, String>` (key via `BlockOffset.toKey()`) |
| `shutdown_penalty` | `BuildingConfig.ShutdownPenalty` record |
| `queue` | `BuildingConfig.QueueDef` record |
| `unlock_requirement` | `BuildingConfig.UnlockRequirement` record |
| `boundary` | `BuildingConfig.BoundaryBox` record (planned) — `min: BlockOffset`, `max: BlockOffset` |

## 7. Adding a New Building

1. Create `data/wandscape/buildings/<id>.json` with the schema above.
2. Register the corresponding MC Block (if new) in the block registry.
3. Place the JSON in the `buildings/` data directory — it will be auto-loaded by `WandscapeDataLoader` → `BuildingConfigLoader` on mod init or `/reload`.
4. No Java code changes needed for basic buildings. Complex buildings (with custom BE logic) extend `AbstractWandscapeBE`.
