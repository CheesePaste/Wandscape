# NPC MC 适配层

文档编号：NEW-22
版本：1.3
状态：已实现（10 新文件 + 5 修改文件 + 施法动画 + 粒子特效 + debug 模式，全编译通过）
依赖：core-engine (v2.5), 01-shared-api, engine integration layer

---

## 一、背景

引擎调度链已完整：
- `CoreBootstrap.bootstrap()` → World 持有 SchedulerSystem + TaskExecutionSystem
- `SchedulerSystem` 每 2 tick `world.query(Position, ManaPool, TaskExecutor, WandCarrier, Inventory, ColonyMember)` 找空闲 NPC
- `TaskExecutionSystem` 每 tick 驱动 NPC 执行 AtomicOp 序列
- V2.5 新增 `CompletableFuture` 事件驱动 tick 门控

**断点**：没有 MC 实体在 ECS World 中持有这 6 个组件。`SchedulerSystem` 日志 `"heartbeat - no idle NPCs"`。

## 二、架构总览

```
MC 层                                   │  ECS 层 (纯 Java, 零 MC 引用)
                                        │
WandscapeNpc (PathfinderMob)            │
  ├─ ecsEntityId: long ════════════════╪═→ World 中 6 个 ComponentStore
  ├─ colonyId: UUID (阶段2=占位值)       │     Position / ManaPool / TaskExecutor
  ├─ currentMana (仅 NBT 持久化用)       │     WandCarrier / Inventory / ColonyMember
  ├─ maxMana / manaRegenRate            │
  ├─ inventory: SimpleContainer(27)     │  ← SchedulerSystem 查到这里 → assign
  ├─ FloatGoal + RandomStrollGoal       │  ← TaskExecutionSystem 驱动执行
  └─ tick() 空（魔力恢复由引擎管）        │  ← ManaRegenSystem 每引擎 tick 恢复魔力

EntityComponentBridge (单例工具类)        │
  ├─ npcByEcsId: ConcurrentHashMap      │
  ├─ ecsIdByUuid: ConcurrentHashMap     │
  ├─ onNpcJoinWorld(npc, world)        │
  ├─ onNpcLeaveWorld(npc, world)       │
  └─ syncPositions(world) — 门控前调用   │

Wandscape.onServerTick:                 │
  1. bridge.syncPositions(world)        │  ← 始终执行（引擎门控前）
  2. if (world.hasPendingAsyncOps())    │  ← V2.5 门控
         return;                        │
  3. world.tick(1.0f)                   │  ← 引擎逻辑帧推进

WandscapeEntityOps : EntityOps          │
  └─ 阶段 2 stub（EntityInteractOp 未使用）│

WandscapeRitualOps : RitualOps          │
  └─ self_teleport: 同步传送 → DONE     │
```

## 三、设计决策汇总（grill-me 17/17 确认）

| # | 决策 | 结论 | 理由 |
|---|------|------|------|
| 1 | 继承 PathfinderMob | ✅ | 自带寻路能力，无村民职业系统副作用 |
| 2 | ManaPool 权威源 | ECS 是运行时权威 | NPC NBT 序列化时从 ECS 读，反序列化后写入 ECS |
| 3 | Position 同步策略 | 每 tick 全量同步 | O(N) 遍历 <50 NPC 微秒级，不做过早优化 |
| 4 | WandCarrier 阶段 2 | 始终 `WandCarrier.EMPTY` | 无法杖装备需求，预留 `recomputeWandCarrier()` 方法 |
| 5 | EngineBootstrap 注入 | 直接 new 真实实现 | 已 import MC 类（WandscapeBlockOps 等），不是 core 层 |
| 6 | NPC 生成方式 | SpawnEgg `DeferredSpawnEggItem` | 创造模式点击生成，暗紫底(#4B0082)+金高亮(#FFD700) |
| 7 | 区块卸载/加载 | UNLOADED_TO_CHUNK 保留 ECS 组件 | 只 KILLED/DISCARDED 时移除；同会话重连复用 ecsEntityId |
| 8 | ecsEntityId NBT 持久化 | 存 NBT，同会话重连复用 | 重启后旧值失效 → 置 -1 → 分配新 ID |
| 9 | EntitySyncSystem | ❌ 不做 | 改为 `Wandscape.onServerTick` 中门控前调用 `bridge.syncPositions()` |
| 10 | syncPositions 时机 | 门控前（①位置） | 引擎阻塞时 NPC 仍在 MC 层移动，位置应始终最新 |
| 11 | NPC.tick() 内容 | 空 tick + FloatGoal + RandomStrollGoal | 魔力由 ManaRegenSystem 管，基本 idle 漫游防溺水 |
| 12 | SpawnEgg 实现 | `DeferredSpawnEggItem(supplier, bg, hl, props)` | 接受 `Supplier<EntityType<? extends Mob>>`，NeoForge 标准 |
| 13 | NPC 尺寸 + 颜色 | 0.6f×1.8f，暗紫底+金高亮 | 标准村民尺寸，"法师"配色 |
| 14 | colonyId 来源 | 占位 UUID `00000000-...-00000000` | 阶段 2 无殖民地创建，占位让调度链完整运行 |
| 15 | EntityComponentBridge 角色 | 单例工具类，非 System | 双向映射 + syncPositions；不加 ECS System |
| 16 | syncPositions 精确位置 | `onServerTick` 门控前 | 始终同步，门控阻塞时也不例外 |
| 17 | self_teleport 实现 | 同步传送 → DONE | 阶段 2 无 MoveOp；传送是瞬时操作，不走 CompletableFuture |

### 关键设计规则

**运行时 ECS 是 ManaPool 权威源**：
```
NPC 实体                    ECS ManaPool
  ├─ NBT save → 从 ECS 读 currentMana
  ├─ NBT load → 恢复后 onAddedToLevel → 写入 ECS
  └─ tick()    → 不自行恢复魔力（ManaRegenSystem 管）
```

**Position 同步流**：
```
MC tick → onServerTick → syncPositions() → hasPendingAsyncOps? → world.tick()
           ↑ 始终执行         ↑ MC→ECS 数据桥    ↑ V2.5 门控
```

## 四、文件清单

### 新建文件 (10 个)

| 文件 | 包 | 职责 | 预估行数 |
|------|-----|------|---------|
| `WandscapeNpc.java` | `com.wsteam.wandscape.npc.entity` | NPC 实体，PathfinderMob 子类 + FloatGoal + RandomStrollGoal + 施法状态 + debug 模式 | ~280 |
| `EntityComponentBridge.java` | `com.wsteam.wandscape.npc.internal` | MC↔ECS 双向映射 + syncPositions + onNpcJoin/LeaveWorld | ~120 |
| `NpcApiImpl.java` | `com.wsteam.wandscape.npc.internal` | NpcApi 实现：spawnNpc / getColonyNpcs / getIdleNpcs 等 | ~100 |
| `NpcDataImpl.java` | `com.wsteam.wandscape.npc.data` | NpcData 实现类，包装 WandscapeNpc 字段 | ~40 |
| `WandscapeEntityOps.java` | `com.wsteam.wandscape.engine.boundary` | EntityOps MC 实现（阶段 2 stub） | ~40 |
| `WandscapeRitualOps.java` | `com.wsteam.wandscape.engine.boundary` | RitualOps MC 实现：self_teleport 同步传送 | ~80 |
| `WandscapeNpcModel.java` | `com.wsteam.wandscape.npc.client` | 自定义 HumanoidModel：施法时右臂抬高，角度跟随 NPC pitch |
| `WandscapeNpcRenderer.java` | `com.wsteam.wandscape.npc.client` | 客户端渲染：施法时从右手发射彩色射线粒子 |
| `CastBoltParticle.java` | `com.wsteam.wandscape.npc.client` | 施法粒子：全亮度静止星星，最后 20% 生命缩小消失 |
| `EntityComponentBridgeTest.java` | `src/test/.../npc/bridge` | 桥接注册/注销/重连/syncPositions 测试 | ~80 |
| `NpcApiImplTest.java` | `src/test/.../npc/internal` | NpcApi spawnNpc / getColonyNpcs 测试 | ~60 |

### 修改文件 (3 个)

| 文件 | 变更 |
|------|------|
| `Wandscape.java` | +`DeferredRegister<EntityType<?>> ENTITIES`、+`DeferredRegister<ParticleType<?>> PARTICLE_TYPES`、+`SpawnEggItem` 注册、+`CAST_BOLT` 粒子注册、+`NpcApiImpl` 注册、tick 中门控前 `syncPositions()`、+`debugDiamondTarget` 追踪钻石块放置 (`BlockEvent.EntityPlaceEvent`) |
| `WandscapeClient.java` | +`CastBoltParticle.Provider` 注册 (`RegisterParticleProvidersEvent`) |
| `EngineBootstrap.java` | 替换 stub `EntityOps`/`RitualOps` 为 `WandscapeEntityOps`/`WandscapeRitualOps` |

### 新增资源文件

| 文件 | 作用 |
|------|------|
| `particles/cast_bolt.json` | 粒子纹理引用 `wandscape:cast_bolt` |
| `textures/particle/cast_bolt.png` | 8×8 十字星粒子贴图 |

## 五、类规格

### 5.1 WandscapeNpc

```java
public class WandscapeNpc extends PathfinderMob {
    // === 引擎桥接 ===
    long ecsEntityId = -1;       // ECS World 中的 entity ID
    UUID colonyId = PLACEHOLDER_COLONY;  // 阶段 2 占位 UUID

    // === 属性（权威值在 ECS，此处仅用于 NBT 持久化中转） ===
    int currentMana = 100;
    int maxMana = 100;
    int manaRegenRate = 2;
    int spellPower = 1;

    // === 背包 ===
    SimpleContainer inventory = new SimpleContainer(27);

    // === 构造器 ===
    public WandscapeNpc(EntityType<? extends PathfinderMob> type, Level level) { ... }

    // === AI ===
    @Override void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));           // 防溺水
        goalSelector.addGoal(5, new RandomStrollGoal(this, 0.6)); // 基本 idle 漫游
    }

    // === 生命周期 ===
    @Override void tick() { super.tick(); /* 空 — 魔力恢复由 ManaRegenSystem 管 */ }

    @Override void onAddedToLevel(Level level) {
        super.onAddedToLevel(level);
        if (!level.isClientSide) {
            EntityComponentBridge.INSTANCE.onNpcJoinWorld(this, WandscapeEngine.getWorld());
        }
    }

    @Override void onRemovedFromLevel(RemovalReason reason) {
        if (!level().isClientSide && (reason == RemovalReason.KILLED || reason == RemovalReason.DISCARDED)) {
            EntityComponentBridge.INSTANCE.onNpcLeaveWorld(this, WandscapeEngine.getWorld());
        }
    }

    // === NBT ===
    @Override void addAdditionalSaveData(CompoundTag tag) {
        // 从 ECS 读取当前值再写入 NBT
        World world = WandscapeEngine.getWorld();
        if (world != null) {
            ManaPool mana = world.get(ecsEntityId, ManaPool.class);
            tag.putInt("currentMana", mana != null ? mana.current() : currentMana);
        }
        tag.putLong("EcsEntityId", ecsEntityId);
        tag.putInt("maxMana", maxMana);
        tag.putInt("manaRegenRate", manaRegenRate);
        tag.putUUID("colonyId", colonyId);
    }

    @Override void readAdditionalSaveData(CompoundTag tag) {
        ecsEntityId = tag.getLong("EcsEntityId");
        currentMana = tag.getInt("currentMana");
        maxMana = tag.getInt("maxMana");
        manaRegenRate = tag.getInt("manaRegenRate");
        if (tag.hasUUID("colonyId")) colonyId = tag.getUUID("colonyId");
    }
}
```

### 5.2 EntityComponentBridge

```java
public final class EntityComponentBridge {
    public static final EntityComponentBridge INSTANCE = new EntityComponentBridge();

    // ecsEntityId → NPC
    private final Map<Long, WandscapeNpc> npcByEcsId = new ConcurrentHashMap<>();
    // MC UUID → ecsEntityId
    private final Map<UUID, Long> ecsIdByUuid = new ConcurrentHashMap<>();

    public static final UUID PLACEHOLDER_COLONY =
        UUID.fromString("00000000-0000-0000-0000-000000000000");

    /** NPC 加入世界 → 在 ECS World 注册 6 个组件 */
    public void onNpcJoinWorld(WandscapeNpc npc, World world) {
        if (world == null) return;
        // 1. 检查是否同会话重连
        if (npc.ecsEntityId > 0 && world.get(npc.ecsEntityId, Position.class) != null) {
            // 同会话重连：更新 Position，保持其他组件不变
            world.addComponent(npc.ecsEntityId,
                new Position(new GridPos(npc.getBlockX(), npc.getBlockY(), npc.getBlockZ())));
            npcByEcsId.put(npc.ecsEntityId, npc);
            ecsIdByUuid.put(npc.getUUID(), npc.ecsEntityId);
            return;
        }
        // 2. 全新注册
        UUID colony = npc.colonyId != null ? npc.colonyId : PLACEHOLDER_COLONY;
        long ecsId = CoreBootstrap.createNpc(world,
            npc.getBlockX(), npc.getBlockY(), npc.getBlockZ(),
            WandCarrier.EMPTY, colony, npc.maxMana, npc.manaRegenRate);
        // write initial mana from NBT
        ManaPool mana = world.get(ecsId, ManaPool.class);
        if (mana != null && npc.currentMana < mana.max()) {
            mana.consume(mana.current() - npc.currentMana);
        }
        npc.ecsEntityId = ecsId;
        npcByEcsId.put(ecsId, npc);
        ecsIdByUuid.put(npc.getUUID(), ecsId);
    }

    /** NPC 离开世界 → 从 ECS 移除所有组件 */
    public void onNpcLeaveWorld(WandscapeNpc npc, World world) {
        if (world == null || npc.ecsEntityId < 0) return;
        for (Class<?> comp : NPC_COMPONENTS) {
            // 通过 stores() 获取 ComponentStore 并 remove
            ComponentStore<?> store = world.stores().get(comp);
            if (store != null) store.remove(npc.ecsEntityId);
        }
        npcByEcsId.remove(npc.ecsEntityId);
        ecsIdByUuid.remove(npc.getUUID());
    }

    /** 每 tick 同步 MC 位置 → ECS Position */
    public void syncPositions(World world) {
        for (var entry : npcByEcsId.entrySet()) {
            WandscapeNpc npc = entry.getValue();
            if (npc != null && !npc.isRemoved()) {
                world.addComponent(entry.getKey(),
                    new Position(new GridPos(npc.getBlockX(), npc.getBlockY(), npc.getBlockZ())));
            }
        }
    }

    @Nullable public WandscapeNpc getNpc(long ecsId) { return npcByEcsId.get(ecsId); }
    @Nullable public Long getEcsId(UUID uuid) { return ecsIdByUuid.get(uuid); }

    private static final Class<?>[] NPC_COMPONENTS = {
        Position.class, ManaPool.class, TaskExecutor.class,
        WandCarrier.class, Inventory.class, ColonyMember.class
    };
}
```

### 5.3 WandscapeEntityOps

```java
public class WandscapeEntityOps implements EntityOps {
    // 阶段 2 stub: EntityInteractOp 不使用
    @Override public void applyEffect(EntityId target, EffectId effect, int strength, int duration) {}
    @Override public GridPos getPosition(EntityId entity) { return GridPos.ORIGIN; }
}
```

### 5.4 WandscapeRitualOps

```java
public class WandscapeRitualOps implements RitualOps {
    // 阶段 2: 同步实现 self_teleport，item_transport stub

    @Override
    public void beginRitual(RitualId ritual, GridPos target, World world, long casterId) {
        if ("self_teleport".equals(ritual.id())) {
            WandscapeNpc npc = EntityComponentBridge.INSTANCE.getNpc(casterId);
            if (npc != null) {
                npc.teleportTo(target.x() + 0.5, target.y(), target.z() + 0.5);
            }
        }
    }

    @Override
    public CompletableFuture<Void> beginRitual(RitualId ritual, GridPos target, World world, long casterId) {
        // 阶段 2: 所有仪式瞬发 → completedFuture
        return CompletableFuture.completedFuture(null);
    }
}
```

### 5.5 NpcApiImpl

```java
public class NpcApiImpl implements NpcApi {
    @Override
    public List<NpcData> getColonyNpcs(UUID colonyId) {
        // 从 ECS World 查询 ColonyMember.component = colonyId → 映射回 WandscapeNpc
    }
    @Override
    public List<NpcData> getIdleNpcs(UUID colonyId) { ... }
    @Override
    public NpcData getNpc(UUID npcId) { ... }
    @Override
    public boolean assignHouse(UUID npcId, UUID houseId) { return false; /* 阶段 4 */ }
    @Override
    public UUID spawnNpc(UUID colonyId, BlockPos pos, RecruitmentCandidate candidate) {
        // 1. new WandscapeNpc(level) + 设置属性
        // 2. level.addFreshEntity(npc)
        // 3. return npc.getUUID()
    }
}
```

### 5.6 NpcDataImpl

```java
record NpcDataImpl(UUID npcId, String name, int maxHealth, int currentHealth,
                   int maxMana, int currentMana, int spellPower, int manaRegenRate,
                   AbilitySet abilities, boolean isIdle, UUID assignedHouseId,
                   UUID currentTaskId, boolean isDead, UUID graveBlockEntityId)
    implements NpcData {}
```

## 六、注册和启动流程

```
Wandscape() 构造器:
  ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, MODID)
  WANDSCAPE_NPC = ENTITIES.register("wandscape_npc", () ->
      EntityType.Builder.of(WandscapeNpc::new, MobCategory.CREATURE)
          .sized(0.6f, 1.8f).build("wandscape_npc"));
  ENTITIES.register(modEventBus)

  WANDSCAPE_NPC_EGG = ITEMS.register("wandscape_npc_spawn_egg", () ->
      new DeferredSpawnEggItem(() -> (EntityType<? extends Mob>) WANDSCAPE_NPC.get(),
          0x4B0082, 0xFFD700, new Item.Properties()));

  WandscapeApis.setNpcApi(new NpcApiImpl());

ServerStarting:
  EngineBootstrap.bootstrap()  // 注入真实的 WandscapeEntityOps / WandscapeRitualOps

ServerTick (Post):
  EntityComponentBridge.INSTANCE.syncPositions(world);  // ① 位置同步（始终）
  if (world.hasPendingAsyncOps()) return;                // ② V2.5 门控
  engineTickCount++;
  world.tick(1.0f);                                      // ③ 引擎逻辑帧
```

## 七、阶段 2 验证路径

```
1. 用 spawn egg 生成 wandscape_npc
2. 日志: [INFO] CoreBootstrap | createNpc #1 pos=(...) mana=100 caps=none colony=00000000
3. 日志: [Scheduler] heartbeat - 1 idle NPCs, 1 assignable tasks
4. 放置建筑方块 → 右键入队 demo task
5. 日志: [Scheduler] assigned #1 'build:stone_bricks' → NPC 1 (score=1.00)
6. 日志: [TaskExec] NPC 1 - TransformOp DONE (mana -1 = 99)
7. 世界中目标位置出现方块 ← 闭环验证成功
```

### 施法动画验证（Debug 模式）

```
1. 放置钻石块（会被自动追踪到 debugDiamondTarget）
2. 用 spawn egg 生成 wandscape_npc
3. 右键 NPC → 提示 "[Debug] NPC casting ON — targeting (x,y,z)"
4. NPC 右臂抬起指向钻石块方向（高低角度自适应）
5. NPC 右手位置发射彩色射线粒子到钻石块中心（不会穿透）
6. NPC 无法移动（施法锁止）
7. 再次右键 → 施法关闭，恢复移动
```

## 八、与 docs/07-npc-system.md 的差异（V1 vs 完整设计）

本文档是 **阶段 2 V1 最小可扩展实现**，`docs/07-npc-system.md` 是完整的 NPC 系统设计目标。以下差异需要在后续阶段逐项对齐，**后期实现 NPC 完整功能前必须先阅读 07 文档**。

| 特性 | docs/07 (完整设计) | docs/22 (V1 实现) | 对齐阶段 |
|------|-------------------|-------------------|---------|
| 类名 | `WandscapeVillager` | `WandscapeNpc` (PathfinderMob) | 无需对齐（设计演进） |
| 魔力恢复 | NPC.tick() 自管，房屋内 ×3 | ManaRegenSystem 每引擎 tick 恢复，无房屋加成 | 阶段 4 |
| 魔力权威源 | NPC 实体字段 | ECS ManaPool（ECS 写，NBT save 时回读） | 持续 |
| 移动决策 | <64 寻路 / >=64 传送 / 卡死 3×3s 检测 | 无移动（阶段 2 Op 瞬发） | 阶段 3 |
| 卡死→传送 | 自动入队私有 self_teleport（Operation D） | 未实现 | 阶段 3 |
| self_teleport | 走标准 Operation D 路径（JSON mana_cost、粒子特效） | 同步传送 `npc.teleportTo()`，无 mana 消耗，无粒子 | 阶段 3 |
| 死亡 | 物品掉落 + `NpcDiedEvent` | 未实现 | 阶段 3 |
| 坟墓 | 永久存物品直到玩家手动移除 | 未实现 | 阶段 4 |
| 复活 | Operation D 仪式，重生在祭坛旁，清空装备 | 未实现 | 阶段 5 |
| 房屋绑定 | 绑定/解绑，空闲返回房屋，魔力恢复 ×3 | 未实现 (assignHouse 返回 false) | 阶段 4 |
| 法杖能力并集 | 背包法杖 NBT 并集 + 默认 ritual:1 | 始终 `WandCarrier.EMPTY` | 阶段 3 |
| 法杖切换 | 接取任务自动选最匹配的法杖作主手 | 无 | 阶段 3 |
| 背包管理 | 管理面板只读查看，亲自交互才能放取 | 仅 SimpleContainer(27)，无 GUI | 阶段 5 |
| 状态机 | IDLE/WORKING/STUCK/DEAD 四状态 | 阶段 2 仅 IDLE/WORKING（引擎 ExecutorState 驱动） | 阶段 3 |
| NBT 字段 | `colonyId`, `currentMana`, `assignedHouseId`, `inventory.save()` | `ecsEntityId`, `maxMana`, `manaRegenRate`, `colonyId`, `spellPower`, `DATA_CASTING` (synced), `DATA_DEBUG_TARGET` (synced) | 持续扩展 |
| 施法动画 | 无设计 | 右臂角度自适应 pitch 的施法 pose，全亮度射线粒子，施法时锁定移动 | 已实现 |
| Debug 模式 | 无设计 | 右键 NPC 切换 debugCasting，追踪钻石块坐标 | 内测工具 |

### 关键架构差异

1. **NPC 从"自治实体"变为"引擎的外壳"**：07 设计中 NPC 自己管魔力恢复、卡死检测、传送入队。22 把这部分交给了引擎（ManaRegenSystem + TaskExecutionSystem + CompletableFuture），NPC 实体主要提供 MC 层的外观/寻路/NBT 持久化。这是有意的架构演进 — NPC 逻辑集中在引擎更可控、可测试。

2. **`WandscapeVillager` → `WandscapeNpc`**：类名变更反映继承链变更（Villager → PathfinderMob）。07 写的是早期草图名字，不需要对齐。

3. **ManaPool 权威源**：07 设计 NPC 字段是权威源，22 设计 ECS 是权威源。后期阶段不会再改回 NPC 自治 — 这是最终决策。

## 九、施法动画与粒子系统

### 9.1 施法状态同步

NPC 通过 `EntityDataAccessor<Boolean> DATA_CASTING` 将施法状态从服务端同步到客户端。服务端每 tick 轮询 ECS `TaskExecutor` 状态（`state==ACTIVE && hasWork()`）或 debug 模式强制施法，客户端据此渲染动画和粒子。

### 9.2 手臂动画

`WandscapeNpcModel.setupAnim()` 在 `isCasting()` 时覆盖右臂角度：
- **水平瞄准**（pitch=0）：右臂 `xRot = -1.2`（大致水平前伸，略上扬）
- **上下瞄准**：`xRot = -1.2 + pitchRad`，pitch 正值（目标在下）→ 手臂下压，pitch 负值（目标在上）→ 手臂抬升
- **左手不动**，保持默认姿态

### 9.3 施法粒子

`CastBoltParticle` 设计要点：
- **全亮度**：`getLightColor()` 返回 `15728880`（等同 `end_rod` 粒子），黑夜可见
- **静止**：构造函数和 `tick()` 中显式锁定 `xd=yd=zd=0`，仅标记射线路径
- **消退**：生命最后 20% 期间 `quadSize` 从初始值线性缩小至 0
- **渲染类型**：`PARTICLE_SHEET_TRANSLUCENT`（半透明粒子表）
- **贴图**：8×8 十字星纹理（`cast_bolt.png`），通过 `SpriteSet` 按年龄切换帧

### 9.4 射线方向

`WandscapeNpcRenderer.spawnCastRay()`：
- **起点**：右手位置，根据手臂角度动态计算 Y 和前后偏移（`armLen=0.75`，`armAngle=-1.2+pitchRad`）
- **方向**：优先对准 `DATA_DEBUG_TARGET`（钻石块中心）；无目标时用 NPC 朝向 `(-sin, cos, 0)`
- **射程**：有目标时止于目标点（`range=len`），无目标时默认 `RAY_RANGE=5.0`
- **步长**：`RAY_STEP=0.4`（约 12 个粒子/5 格）

### 9.5 Debug 模式

验证 NPC 施法动画的临时调试入口（不用于正式游戏流程）：
1. 放置**钻石块**（Wandscape 监听 `BlockEvent.EntityPlaceEvent` 记录坐标到 `debugDiamondTarget`）
2. 右键 NPC → 切换 `debugCasting`，NPC 主手获得法杖，`DATA_DEBUG_TARGET` 同步钻石块坐标
3. NPC 锁定移动、面向目标、发射射线粒子到目标点
4. 再次右键 → 关闭调试模式

## 十、不做（留给阶段 3+）

- ❌ NPC 寻路到目标再执行 Op（需要 MoveOp + CompletableFuture 异步模型）
- ❌ 卡死检测 → 自动入队 self_teleport
- ❌ 房屋绑定 / 魔力恢复 ×3
- ❌ 死亡掉落 / 坟墓
- ❌ AI 行为树 / 任务驱动移动
- ❌ 背包 GUI / 管理面板
- ❌ EntityInteractOp 真实实现（通过 Bridge 查找 MC 实体）
- ❌ item_transport ritual 实现
- ❌ 法杖装备监听 → WandCarrier 重建
- ❌ 殖民地创建流程（colonyId 目前是占位值）
