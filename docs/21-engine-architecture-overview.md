# Magic Colony — Architecture Overview

## Summary

Magic Colony is a colony-simulation engine written in pure Java 21. The core layer has **zero** Minecraft dependencies — all interaction with the game world goes through boundary interfaces. An adaptor layer (Mock or JavaFX) implements those interfaces.

The engine uses an **ECS** (Entity-Component-System) architecture with a **Blueprint → TaskSequence → event-driven orchestration** pipeline. NPCs execute tasks by advancing through a sequence of atomic operations, managed by a global task pool and a priority-based scheduler.

---

## Layer Diagram

```
┌─────────────────────────────────────────────────────────┐
│  Adaptor Layer                                          │
│  MockBoundary (test) / ColonyFxApp (visual)             │
│  Implements: BlockOps, EntityOps, RitualOps,            │
│              ColonyResourceAccess                       │
└──────────────┬──────────────────────────────────────────┘
               │ boundary interfaces (5)
┌──────────────▼──────────────────────────────────────────┐
│  Core Engine                                            │
│                                                         │
│  Engine.bootstrap(EngineConfig) ──► World               │
│                                                         │
│  World.tick(delta):                                     │
│    1. ManaRegenSystem                                   │
│    2. SystemBlueprintSystem          ← V2               │
│    3. TaskSourcePoller                                 │
│    4. SchedulerSystem                                   │
│    5. TaskExecutionSystem                               │
│    6. EventBus.dispatch()              ← tick end        │
│                                                         │
│  ┌────────────┐  ┌──────────────┐  ┌────────────────┐  │
│  │ Components  │  │ GlobalTask   │  │ OpExecutor     │  │
│  │ (7 types)   │  │ Pool         │  │ Registry       │  │
│  └────────────┘  └──────────────┘  └────────────────┘  │
└─────────────────────────────────────────────────────────┘
```

---

## Key Data Flow

### 1. Task Creation
```
TaskSource.poll() or EventDrivenTaskSource.onEvent()
  └─► TaskRequest(blueprintId, params, priority)
      └─► BlueprintRegistry.compile()
          └─► Blueprint.steps.generate(params) → TaskSequence
          └─► CompiledBlueprint(sequence, triggers)
              └─► GlobalTaskPool.addTask() → GlobalTask
```

### 2. Task Execution
```
SchedulerSystem (every 2 ticks)
  └─► score NPCs × tasks → best match
      └─► taskPool.assign(taskId, npcId)
          ├─► subscribe triggers (EventBus)
          └─► copy taskParams to executor

TaskExecutionSystem (every tick, per NPC)
  └─► while (hasWork):
      ├─► pure op (EmitEventOp / IfConditionOp)
      │   └─► execute → self-advance → continue loop
      └─► side-effect op (TransformOp / RitualOp / …)
          └─► mana check → execute → DONE/WATING → break
```

### 3. Resource Waiting & Wake-up
```
ResourceRequestOp → warehouse insufficient
  └─► taskPool.markAwaitingResources(taskId, npcId, needed)
      ├─► state → AWAITING_RESOURCES
      ├─► release NPC
      └─► emit TaskAwaitingResources

ResourceFulfilled event (tick end dispatch)
  └─► GlobalTaskPool.onResourceFulfilled()
      └─► scan AWAITING_RESOURCES tasks
          └─► available >= needed → state → PENDING_ASSIGN
              └─► Scheduler picks up next heartbeat
```

### 4. Event-Driven Chain (V2)
```
Task A executes EmitEventOp("planted", {crop: "wheat"})
  └─► EventBus.dispatch() at tick end
      └─► Trigger handler (subscribed at assign)
          ├─► filter match → ok
          ├─► resolve {{event.resource}} template
          ├─► paramMapping key rename
          ├─► dedup check (skip if duplicate)
          └─► addTask(TaskRequest for blueprint "harvest:wheat")

  Meanwhile: EmitEventOp is last step → completeTask → deferred unsubscribe
  └─► handler still fires because removal is deferred to after dispatch()
```

---

## Component Types (ECS)

| Component | Key Fields | Purpose |
|-----------|------------|---------|
| `Position` | `GridPos pos` | NPC location in world |
| `ManaPool` | `current, max, regenPerTick` | Mana economy |
| `WandCarrier` | `capabilities, bestManaEfficiency, maxRange` | Pre-computed wand stats |
| `TaskExecutor` | `privateQueue, globalTaskId, currentSequence, stepIndex, taskParams` | NPC task execution state |
| `Inventory` | `items, capacity` | NPC 27-slot inventory |
| `ColonyMember` | `colonyId` | Colony membership |
| `ColonyMetadata` | `colonyId, center, radius` | Colony entity data |

---

## AtomicOp Hierarchy

```
sealed interface AtomicOp
  ├── TransformOp         (side-effect) — place/break/convert blocks
  ├── BlockInteractOp     (side-effect) — toggle/activate/open GUI
  ├── EntityInteractOp    (side-effect) — apply effect to entity
  ├── RitualOp            (side-effect) — channeled ritual (polling)
  ├── ResourceRequestOp   (side-effect) — pull from warehouse (inline)
  ├── EmitEventOp         (pure)        — queue custom event       ← V2
  └── IfConditionOp       (pure)        — runtime step skip        ← V2
```

---

## Event Types

| Event | Purpose | V1/V2 |
|-------|---------|-------|
| `CustomEvent` | All blueprint-emitted events (by name) | V2 |
| `ResourceLow` | Resource below threshold | V1 |
| `TaskAwaitingResources` | Task needs resources | V1 |
| `TaskCompleted` | Task finished successfully | V1 |
| `ResourceFulfilled` | Resources replenished (wake-up trigger) | V1 |
| `MobNearby` | Hostile mobs detected | V1 |

---

## Boundary Interfaces (Adaptor Contract)

| Interface | Methods | Purpose |
|-----------|---------|---------|
| `BlockOps` | `setBlock, getBlock, isAir, toggle, activate, openGui` | World block manipulation |
| `EntityOps` | `applyEffect, getPosition` | External entity interaction |
| `RitualOps` | `beginRitual, pollRitual` | Channeled ritual lifecycle |
| `ColonyResourceAccess` | `hasEnough, reserve, commit, release, available` | Warehouse resource management |
| `EventBus` | `emit, subscribe, unsubscribe` | Tick-delayed event dispatch |

---

## V2 Key Additions (vs V1)

| Feature | V1 | V2 |
|---------|----|---- |
| Blueprint | `@FunctionalInterface` | `record(id, steps, triggers)` |
| Compile result | `TaskSequence` | `CompiledBlueprint(sequence, triggers)` |
| TaskRequest.location | `GridPos` field | Removed; `x/y/z` in `params` map |
| Event emission | Not available | `EmitEventOp` with `{{variable}}` templates |
| Conditional steps | Not available | `IfConditionOp` + `ConditionEvaluator` registry |
| Downstream tasks | Hardcoded in `EventDrivenTaskSource` | `TriggerDeclaration` from blueprints |
| Tick execution | One op per tick | Pure ops batch continuously; side-effect ops 1:1 |
| Event unsubscribe | Not available | Deferred removal (after dispatch) |
| System-level triggers | Not available | `SystemBlueprintRegistry` + permanent subscriptions |
| Idempotency | Time-cooldown + in-flight sum | Double-layer: EventBus merge + `dedupKey` scan |

---

## System Execution Order

Systems execute in registration order. Order matters:

1. **ManaRegenSystem** — Recover mana before NPCs act (so they have energy)
2. **SystemBlueprintSystem** — Drive system blueprint steps (V2)
3. **TaskSourcePoller** — Generate new tasks (WarehouseSource, etc.)
4. **SchedulerSystem** — Assign tasks to idle NPCs
5. **TaskExecutionSystem** — Execute one tick of work per NPC
6. **EventBus.dispatch()** — Deliver all queued events (tick end, not a System)

---

## Source File Count

| Package | Files | Purpose |
|---------|-------|---------|
| `types/` | 10 | Value types (GridPos, ResourceId, etc.) |
| `ecs/` | 4 | ECS framework |
| `component/` | 7 | ECS components |
| `boundary/` | 5 | Adaptor interfaces |
| `op/` | 6 | AtomicOp + executors + conditions |
| `event/` | 7 | Domain events + bus |
| `task/` | 12 | Task system |
| `system/` | 10 | Systems + task sources |
| `core/` | 4 | Engine, Config, Log, TemplateResolver |
| `demo/` | 1 | Mock boundary |
| `fxadapter/` | 1 | JavaFX visual adapter |
| **Total** | **67** | Core + adaptor |

---

## Testing

Three test suites, 32 tests total:

| Suite | Tests | Focus |
|-------|-------|-------|
| `ResourceWaitingFulfillTest` | 4 | Resource shortage → wait → fulfill → resume |
| `EventDrivenTaskSourceTest` | 5 | Hardcoded event → task mappings |
| `BlueprintEventSystemTest` | 23 | V2: EmitEventOp, IfConditionOp, triggers, batch, dedup, lifecycle |
