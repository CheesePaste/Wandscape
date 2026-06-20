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
│  CoreBootstrap.bootstrap(EngineConfig) ──► World               │
│                                                         │
│  World.tick(delta) [gated by hasPendingAsyncOps()]:          │
│    0. Wandscape.onServerTick checks gate                     │
│    1. ManaRegenSystem                                   │
│    2. SystemBlueprintSystem          ← V2               │
│    3. TaskSourcePoller                                 │
│    4. SchedulerSystem                                   │
│    5. TaskExecutionSystem                               │
│    6. NavigationSystem               ← V2.6             │
│    7. EventBus.dispatch()              ← tick end        │
│                                                         │
│  ┌────────────┐  ┌──────────────┐  ┌────────────────┐  │
│  │ Components  │  │ GlobalTask   │  │ OpExecutor     │  │
│  │ (8 types)   │  │ Pool         │  │ Registry       │  │
│  └────────────┘  └──────────────┘  └────────────────┘  │
└─────────────────────────────────────────────────────────┘
```

---

## Key Data Flow

### 1. Task Creation
```
TaskSource.poll() or EventDrivenTaskSource.onEvent()
  └─► TaskRequest(blueprintId, params{JsonElement}, priority)
      └─► BlueprintRegistry.compile()
          ├─ DSL Blueprint: BlueprintInterpreter.interpret(definition, params)
          │     └─► evaluate ExprNodes → expand for_each/if/call → TaskSequence
          └─ Legacy Lambda: BlueprintSteps.generate(params) → TaskSequence
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
| `NavigationState` | `mode(IDLE/PATHFINDING/TELEPORT_WAITING), target, future, startTick, stuckChecks, repathCount` | NPC movement state — single source of truth, driven by NavigationSystem |

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
| `RitualOps` | `beginRitual` → `CompletableFuture<Void>` | Ritual lifecycle (sync/async) |
| `ColonyResourceAccess` | `hasEnough, reserve, commit, release, available` | Warehouse resource management |
| `MovementOps` | `navigateTo → CompletableFuture, cancelNavigation` | NPC movement (WandscapeMovementOps writes NavigationState, NavigationSystem drives it) |
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
5. **TaskExecutionSystem** — Execute one tick of work per NPC (writes NavigationState for movement)
6. **NavigationSystem** — Drive all NPC movement (reads NavigationState, runs same tick)
7. **EventBus.dispatch()** — Deliver all queued events (tick end, not a System)

---

## Event-Driven Tick Gating (V2.5)

### Rationale

The engine tick is a **logic frame**, not a physics frame. Some operations (e.g., MoveOp pathfinding, RitualOp channeling) span multiple Minecraft ticks. The engine must wait for all async operations to complete before advancing to the next logic tick, analogous to `Promise.all().then(nextTick)` in JavaScript.

Java's `CompletableFuture` provides the exact semantics we need:
- `future.complete()` / `future.completeExceptionally()` — resolve/reject
- `future.whenComplete((v, ex) -> ...)` — auto-cleanup callbacks
- `future.orTimeout(30, TimeUnit.SECONDS)` — built-in timeout, NPCs never stuck forever

### Architecture

```
┌─ MC Tick 1 ──► engineTickCount++ ──► world.tick() ──────────┐
│  NPC1: TransformOp → DONE (instant)                          │
│  NPC2: writes NavigationState → NavigationSystem starts move │
│  NPC3: writes NavigationState → NavigationSystem starts move │
│  → 2 CompletableFutures pending → gate closes                │
├─ MC Tick 2 ──► world.hasPendingAsyncOps() → skip engine      │
│  NPC2 arrives → future.complete(null) → auto-removed, 1 left │
├─ MC Tick 3 ──► still pending (1 future)                      │
│  NPC3 arrives → future.complete(null) → auto-removed, 0 left │
├─ MC Tick 4 ──► !hasPendingAsyncOps() → engineTickCount++     │
│  world.tick() → next logic frame                             │
└──────────────────────────────────────────────────────────────┘
```

### API

| Method | Returns | Caller | Purpose |
|--------|---------|--------|---------|
| `world.startAsyncOp(label)` | `CompletableFuture<Void>` | MC boundary (e.g. MoveOp executor) | Starts an async op, returns a future to complete later |
| `world.hasPendingAsyncOps()` | `boolean` | `Wandscape.onServerTick` | Gate: true → skip engine tick |
| `future.complete(null)` | — | MC event callback | Signal op success; `whenComplete` auto-removes from pending list |
| `future.completeExceptionally(ex)` | — | MC error handler | Signal op failure; engine receives error + cleanup |
| `future.orTimeout(30, SECONDS)` | `CompletableFuture<Void>` | MC boundary (setup) | Auto-reject after timeout; prevents stuck NPCs |

### V1 compatibility

All V1/V2 operations are synchronous (TransformOp, BlockInteractOp, EmitEventOp, etc.) — they return DONE immediately and never call `startAsyncOp()`. `pendingFutures` stays empty, the gate never blocks, and `world.tick()` runs every MC tick as before. The gating is **zero-overhead** for existing code.

### Future async ops

| Op | Async? | Resolution trigger |
|----|--------|--------------------|
| NPC movement (NavigationSystem) | Yes | Arrival / teleport → `NavigationState.future.complete()` |
| `RitualOp` (channeled) | Yes | Channel ticks elapsed → `future.complete()` |
| `TransformOp` | No | Instant via `BlockOps.setBlock()` |
| `BlockInteractOp` | No | Instant |
| `EntityInteractOp` | No | Instant |

### Safety

- **Timeout**: `future.orTimeout(30, SECONDS)` — NPC stuck? Auto-reject → engine advances with failure, NPC falls back to teleport
- **Exception**: `future.completeExceptionally()` — NPC died mid-op? Chunk unloaded? Clean failure, auto-removed from pending list via `whenComplete`
- **Single-threaded**: MC server tick is single-threaded; `complete()` called from server thread via events, no concurrency issues

---

## Source File Count

| Package | Files | Purpose |
|---------|-------|---------|
| `types/` | 10 | Value types (GridPos, ResourceId, etc.) |
| `ecs/` | 4 | ECS framework |
| `component/` | 8 | ECS components |
| `boundary/` | 5 | Adaptor interfaces |
| `op/` | 6 | AtomicOp + executors + conditions |
| `event/` | 7 | Domain events + bus |
| `task/` | 12 | Task system |
| `system/` | 10 | Systems + task sources |
| `core/` | 4 | CoreBootstrap, Config, Log, TemplateResolver |
| `demo/` | 1 | Mock boundary |
| `fxadapter/` | 1 | JavaFX visual adapter |
| **Total** | **67** | Core + adaptor |

---

## Testing

193 tests total (63 core engine + 130 adapter layer):

| Suite | Tests | Focus |
|-------|-------|-------|
| `ResourceWaitingFulfillTest` | 4 | Resource shortage → wait → fulfill → resume |
| `EventDrivenTaskSourceTest` | 5 | Hardcoded event → task mappings |
| `BlueprintEventSystemTest` | 23 | V2: EmitEventOp, IfConditionOp, triggers, batch, dedup, lifecycle |
| `CoreSystemsTest` | 31 | Scheduler scoring, RitualOp lifecycle, private queue, approval, templates, mana regen, interrupts |
| Adapter layer | 130 | BuildingConfig, NBT serialization, BlockOffset parsing, etc. |
