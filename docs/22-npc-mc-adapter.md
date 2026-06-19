# NPC MC 适配层

文档编号：NEW-22
版本：1.0
状态：NPC 实体 + ECS 桥接 + EntityOps + RitualOps + NpcApi 实现
依赖：core-engine (v2), 01-shared-api, engine integration layer

---

## 一、背景

引擎调度链已完整：
- `CoreBootstrap.bootstrap()` → World 持有 SchedulerSystem + TaskExecutionSystem
- `SchedulerSystem` 每 2 tick `world.query(Position, ManaPool, TaskExecutor, WandCarrier, Inventory, ColonyMember)` 找空闲 NPC
- `TaskExecutionSystem` 每 tick 驱动 NPC 执行 AtomicOp 序列

**断点**：没有 MC 实体在 ECS World 中持有这 6 个组件。`SchedulerSystem` 日志 `"heartbeat - no idle NPCs"`。

## 二、架构总览

```
MC 层                                   │  ECS 层 (纯 Java, 零 MC 引用)
                                        │
WandscapeNpc (PathfinderMob)            │
  ├─ ecsEntityId: long ════════════════╪═→ World 中 6 个 ComponentStore
  ├─ colonyId: UUID                     │     Position / ManaPool / TaskExecutor
  ├─ currentMana / maxMana / regenRate  │     WandCarrier / Inventory / ColonyMember
  ├─ inventory                         │
  └─ 寻路 / AI / 渲染 (阶段2最小)       │  ← SchedulerSystem 查到这里
                                        │  ← TaskExecutionSystem 驱动执行
EntityComponentBridge                   │
  ├─ npcByEcsId: ConcurrentHashMap      │
  ├─ ecsIdByUuid: ConcurrentHashMap     │
  ├─ onNpcJoinWorld(npc, world)        │
  ├─ onNpcLeaveWorld(npc, world)       │
  └─ syncPositions(world)              │

WandscapeEntityOps : EntityOps          │
  └─ 通过 Bridge 映射施加效果/读位置     │

WandscapeRitualOps : RitualOps          │
  └─ self_teleport / item_transport     │
```

## 三、new-design decisions

### 决策 1：NPC 继承 PathfinderMob，不继承 Villager

**理由**：村民职业系统（`VillagerData`、`VillagerTrades`、`GossipManager`）完全不相关。`PathfinderMob` 提供寻路能力而无需任何村民副作用。

### 决策 2：单个 Bridge 类管理 MC↔ECS 映射

**理由**：双向查找能力（ecsId→NPC 用于 Op 执行、UUID→ecsId 用于 NpcApi 查询）。`ConcurrentHashMap` 保证服务端 tick 线程安全。

### 决策 3：ECS entity ID 不持久化

**理由**：ECS World 每次 ServerStarting 重建。NPC 实体 NBT 持久化 `ecsEntityId` 只用于同一次服务器会话中的区块加载/卸载，不跨重启。重启时 `onAddedToLevel` 重新调用 `EntityComponentBridge.onNpcJoinWorld()` 分配新的 ecsId。

### 决策 4：属性不存 ECS ComponentStore，存在 NPC 实体 NBT 中

**理由**：`ManaPool` 等 ECS 组件只在引擎 tick 循环中使用（调度评分查 mana 是否空、TaskExecutionSystem 扣 mana）。每次 tick 从 NPC 实体同步到 ECS 组件，而不是反过来。这样 NPC 实体是数据权威源，ECS 是只读镜像。

**同步机制**：

- `Position`：每 tick `EntityComponentBridge.syncPositions()` 从 NPC 实体更新
- `ManaPool`：NPC.tick() 恢复魔力 → 同步到 ECS
- 不想每 tick 全量同步 → 使用增量同步：
  - Position 每 tick 同步（寻路频繁变动）
  - ManaPool 只在 NPC.tick() 中更新 ecs 端（恢复/消耗后立即写）
  - WandCarrier 在法杖装备变化时重建

### 决策 5：阶段 2 不实现 NPC AI 任务驱动移动

**理由**：TaskExecutionSystem 驱动 Op 执行（TransformOp.place → WandscapeBlockOps.setBlock），但 NPC 实际走到目标位置是后续阶段的事。阶段 2 的 Op 执行通过引擎的 TransformOp 直接在世界中放置方块（范围检查简化：暂不做距离校验）。

### 决策 6：RitualOps self_teleport 直接传送，0 tick 引导

**理由**：传送是服务端操作，不需要 tick 引导。粒子特效留待阶段 3+。

## 四、文件清单

### 新建文件 (8 个)

| 文件 | 包 | 职责 | 预估行数 |
|------|-----|------|---------|
| `WandscapeNpc.java` | `com.wsteam.wandscape.npc.entity` | NPC 实体类，PathfinderMob 子类 | ~250 |
| `EntityComponentBridge.java` | `com.wsteam.wandscape.npc.internal` | MC↔ECS 双向映射管理 | ~120 |
| `NpcApiImpl.java` | `com.wsteam.wandscape.npc.internal` | NpcApi 接口实现 | ~100 |
| `NpcDataImpl.java` | `com.wsteam.wandscape.npc.data` | NpcData 实现类 | ~40 |
| `WandscapeEntityOps.java` | `com.wsteam.wandscape.engine.boundary` | EntityOps MC 实现 | ~60 |
| `WandscapeRitualOps.java` | `com.wsteam.wandscape.engine.boundary` | RitualOps MC 实现 | ~80 |
| `EntityComponentBridgeTest.java` | `src/test/.../npc/bridge` | 桥接单元测试 | ~80 |
| `NpcApiImplTest.java` | `src/test/.../npc/internal` | API 实现测试 | ~60 |

### 修改文件 (2 个)

| 文件 | 变更 |
|------|------|
| `Wandscape.java` | +`DeferredRegister<EntityType<?>> ENTITIES`、+`NpcApiImpl` 注册、+tick 中 `syncPositions()` |
| `EngineBootstrap.java` | 替换 stub `EntityOps`/`RitualOps` 为 `WandscapeEntityOps`/`WandscapeRitualOps` |

## 五、类规格

### 5.1 WandscapeNpc

```java
public class WandscapeNpc extends PathfinderMob {
    // === 引擎桥接 ===
    long ecsEntityId = -1;
    UUID colonyId;

    // === 核心属性 ===
    int currentMana = 100;
    int maxMana = 100;
    int manaRegenRate = 2;
    int spellPower = 1;

    // === 状态（不存 NBT，运行时） ===
    NpcState state = NpcState.IDLE;
    int stuckTicks = 0;
    BlockPos lastCheckPos;
    BlockPos targetPos;  // 当前任务目标（阶段 3+ 用于寻路）

    // === 背包 ===
    SimpleContainer inventory = new SimpleContainer(27);

    // === 生命周期 ===
    @Override void onAddedToLevel(Level level)  // 服务端: EntityComponentBridge.onNpcJoinWorld()
    @Override void tick()                       // 魔力恢复 + 卡死检测
    @Override void onRemovedFromLevel(RemovalReason)  // EntityComponentBridge.onNpcLeaveWorld()

    // === NBT ===
    @Override void addAdditionalSaveData(CompoundTag)
    @Override void readAdditionalSaveData(CompoundTag)
}
```

### 5.2 EntityComponentBridge

```java
public final class EntityComponentBridge {
    static final EntityComponentBridge INSTANCE = new EntityComponentBridge();
    // ecsEntityId → NPC (WeakReference 避免内存泄漏)
    final Map<Long, WandscapeNpc> npcByEcsId = new ConcurrentHashMap<>();
    // MC UUID → ecsEntityId
    final Map<UUID, Long> ecsIdByUuid = new ConcurrentHashMap<>();

    public void onNpcJoinWorld(WandscapeNpc npc, World world) {
        // 1. 计算 WandCarrier
        // 2. CoreBootstrap.createNpc() → 注册 6 组件
        // 3. 建立双向映射
    }

    public void onNpcLeaveWorld(WandscapeNpc npc, World world) {
        // 从 6 个 ComponentStore 移除 → 双向映射清理
    }

    /** 每 tick 同步 MC 位置 → ECS Position */
    public void syncPositions(World world) {
        // 遍历 npcByEcsId → world.addComponent(id, new Position(...))
    }

    // 辅助查询
    @Nullable WandscapeNpc getNpc(long ecsId);
    @Nullable Long getEcsId(UUID uuid);
}
```

### 5.3 WandscapeEntityOps

```java
public class WandscapeEntityOps implements EntityOps {
    // 暂不实现真实逻辑————EntityInteractOp 阶段 2 不使用
    // 后续阶段通过 EntityComponentBridge 查找 MC 实体

    @Override void applyEffect(EntityId target, EffectId effect, int strength, int duration) { /* stub */ }
    @Override GridPos getPosition(EntityId entity) { return GridPos.ORIGIN; /* stub */ }
}
```

### 5.4 WandscapeRitualOps

```java
public class WandscapeRitualOps implements RitualOps {
    // self_teleport: 通过 EntityComponentBridge 找 NPC → npc.teleportTo()
    // item_transport: stub（阶段 3 实现）

    @Override void beginRitual(RitualId ritual, GridPos target, World world, long casterId) { }
    @Override OpResult pollRitual(RitualId ritual, GridPos target, World world, long casterId) {
        // self_teleport → 直接传送 → DONE
        return OpResult.DONE;
    }
}
```

## 六、注册和启动流程

```
Wandscape() 构造器:
  ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, MODID)
  ENTITIES.register("wandscape_npc", ...)
  ENTITIES.register(modEventBus)
  WandscapeApis.setNpcApi(new NpcApiImpl())

commonSetup:
  // ENTITIES 已注册，无需额外操作

ServerStarting:
  EngineBootstrap.bootstrap()  // 传入真实的 WandscapeEntityOps / WandscapeRitualOps

ServerTick:
  EntityComponentBridge.INSTANCE.syncPositions(wandscapeWorld)
  world.tick(1.0f)
```

## 七、阶段 2 验证路径

```
1. 用 spawn egg 生成 wandscape_npc
2. 日志: [INFO] CoreBootstrap | createNpc #1 pos=(...) mana=100 caps=none colony=<uuid>
3. 日志: [Scheduler] heartbeat - 1 idle NPCs, 1 assignable tasks
4. 放置建筑方块 → 右键入队 demo task
5. 日志: [Scheduler] assigned #1 'build:stone_bricks' → NPC 1 (score=1.00)
6. 日志: [TaskExec] NPC 1 - TransformOp DONE (mana -1 = 99)
7. 世界中目标位置出现方块 ← 闭环验证成功
```

## 八、不做（留给阶段 3+）

- ❌ NPC 寻路到目标再执行 Op（当前引擎直接调 WandscapeBlockOps.setBlock）
- ❌ 卡死检测 → 自动入队 self_teleport（需要 05-atomic-operations）
- ❌ 房屋绑定 / 魔力恢复 ×3
- ❌ 死亡掉落 / 坟墓
- ❌ AI 行为树 / idle 漫游
- ❌ 背包 GUI / 管理面板
- ❌ EntityInteractOp 真实实现
- ❌ item_transport ritual 实现
