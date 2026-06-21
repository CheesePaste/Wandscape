# 27 — 多方块建筑重构：SavedData 方案设计

**决策**：采用方案 A — SavedData 全托管，零自定义方块。

---

## 1. 当前架构（待重构）

```
玩家放置自定义方块 (wandscape:earth_node)
        │
        ▼
BlockPlaceHandler 检测 → 查 JSON config → 注册到 BuildingApiImpl
        │
        ▼
AbstractWandscapeBE (挂在自定义方块上)
  ├─ 任务队列 (Deque<WorkItem>)
  ├─ colonyId / shutdown / structureIntact
  ├─ NBT 持久化 (saveAdditional / loadAdditional)
  └─ hasWork() → BuildingTaskSource 轮询 → 发布到 GlobalTaskPool
```

**关键依赖链**：`BuildingTaskSource` → `BuildingApiImpl.getBuildingsWithPendingWork()` → `getBeAt(pos)` → `AbstractWandscapeBE.hasWork()` / `dequeueWork()`

**问题**：所有运行时状态耦合在 BE 中。BE 只能挂在自定义方块上。

---

## 2. 目标架构

```
任务派发（fill 命令 / GUI / 事件触发）
        │
        ▼
EnqueueHelper.registerIfAbsent() → BuildingSavedData 持久化
        │
        ▼  NPC 领取任务 → 执行 build:place_structure 蓝图
        │
        ▼  蓝图最后一步 emit_event("build_complete")
        │
        ▼
BuildCompleteHandler 监听事件 → 验证 pattern → 标记 structureIntact=true
        │
        ▼
建筑正式可用（接受新任务、提供殖民地属性值）
```

**核心变化**：
- 建筑状态从 BE 移到 `BuildingSavedData`（Level 级 SavedData）
- 建筑注册时机 = **任务提交时**（此时已知 anchor + type）
- 建筑**激活**时机 = `build_complete` 事件触发时（NPC 建完最后一块）
- 右键交互通过 `PlayerInteractEvent` 事件拦截

---

## 3. 核心卡点及解决方案

| 卡点 | 问题 | 解决 |
|------|------|------|
| A. 原版方块无 BE | `stone_bricks` 不能挂数据 | 数据存 SavedData，方块只是外观 |
| B. 右键拦截 | 原版方块 `use()` 返回 PASS | `PlayerInteractEvent.RightClickBlock` 事件 + 空间索引查询 |
| C. 持久化 | 没有 BE saveAdditional | `BuildingSavedData extends SavedData` + `setDirty()` |
| D. 发现/轮询 | BuildingTaskSource 原来读 BE | 改读 `BuildingSavedData` 内存 Map（接口不变） |
| E. 区块感知 | SavedData 全局存在，不随 chunk 卸载 | 派发任务前检查 `level.isLoaded(anchor)` |
| F. 建筑创建 | 无"放自定义方块"触发点 | **任务提交时即注册**（见下文详述） |

---

## 4. 建筑注册 — 基于现有任务流自然解决

### 当前任务提交流程（FillBuildingCommand 已验证）

```java
// FillBuildingCommand.execute():
EnqueueHelper.registerIfAbsent(pos, config, type);  // ① 注册建筑（内存）
WorkItem work = EnqueueHelper.buildWorkItem(config, pos, type, 10);  // ② 构建 WorkItem
world.taskPool.addTask(new TaskRequest(work...));   // ③ 提交到任务池
// → NPC 领取 → 执行蓝图 → 放置方块 → emit build_complete
```

**关键洞察**：建筑注册发生在**任务提交时**，而非建造完成后。这意味着：
- 注册时建筑 `structureIntact = false`（尚未建造）
- NPC 完成建造后 `build_complete` 事件 → 验证 pattern → `structureIntact = true`
- 建筑在 `structureIntact = false` 期间不接受新任务（但自身的建造任务已在池中）

### 三种注册入口（已存在 / 需新增）

| 入口 | 现状 | 重构后 |
|------|------|--------|
| `/wandscape fill` 命令 | ✅ 已有，调用 `EnqueueHelper.registerIfAbsent()` | 改为写入 SavedData |
| GUI 建造面板（玩家选择建筑类型 + 位置） | 🔲 未实现 | 同样调 `EnqueueHelper`，写入 SavedData |
| 事件触发（殖民地升级自动规划建筑） | 🔲 未实现 | 同上 |

**结论：无需额外 UX 设计。** 建筑注册就是"提交建造任务"的副作用，入口已有（命令），后续 GUI 面板只是包一层 UI。

---

## 5. BuildingSavedData 设计

### 5.1 数据模型

```java
public class BuildingSavedData extends SavedData {

    // 主索引：buildingId → BuildingState
    private final Map<UUID, BuildingState> buildings = new HashMap<>();

    // 空间索引：ChunkPos → 该 chunk 内的 buildingId 列表
    private final Map<ChunkPos, List<UUID>> chunkIndex = new HashMap<>();

    // 反向索引：BlockPos → buildingId（仅建筑占据的方块位置）
    private final Map<BlockPos, UUID> posIndex = new HashMap<>();
}
```

### 5.2 BuildingState（替代 AbstractWandscapeBE 的全部职责）

```java
public class BuildingState {
    UUID buildingId;
    String buildingTypeId;
    String category;
    BlockPos anchor;             // pattern 中 [0,0,0] 在世界中的位置
    BoundingBox bounds;          // 预计算 AABB（anchor + boundary）

    // ── 殖民地 ──
    UUID colonyId;

    // ── 运行状态 ──
    boolean shutdown;
    boolean structureIntact;     // false = 建造中或被破坏

    // ── 任务队列（原 AbstractWandscapeBE.taskQueue）──
    Deque<WorkItem> taskQueue = new ArrayDeque<>();
    UUID currentTaskId;          // 当前正在执行的任务

    // ── 属性值（从 BuildingConfig 复制，避免运行时反复查 JSON）──
    int comfort, magic, wonder;
    int maintenanceCost;
    int queueCapacity;
}
```

### 5.3 持久化格式（NBT）

```
BuildingSavedData.dat
└─ "buildings" : ListTag
    └─ CompoundTag per building:
        ├─ "id": UUID
        ├─ "type": String
        ├─ "category": String
        ├─ "anchor": IntArrayTag [x, y, z]
        ├─ "colony": UUID (optional)
        ├─ "shutdown": Boolean
        ├─ "intact": Boolean
        ├─ "queue": ListTag<CompoundTag>  // WorkItem serialization (same as current BE)
        ├─ "current_task": UUID (optional)
        └─ stats: comfort/magic/wonder/maintenance/capacity
```

### 5.4 空间索引构建

注册时：
```java
void register(BuildingState state, BuildingConfig config) {
    buildings.put(state.buildingId, state);

    // 计算所有占据的 BlockPos → posIndex
    for (BlockOffset off : config.pattern()) {
        BlockPos worldPos = state.anchor.offset(off.x(), off.y(), off.z());
        posIndex.put(worldPos, state.buildingId);
    }

    // 计算涉及的所有 ChunkPos → chunkIndex
    Set<ChunkPos> chunks = computeChunks(state.bounds);
    for (ChunkPos cp : chunks) {
        chunkIndex.computeIfAbsent(cp, k -> new ArrayList<>()).add(state.buildingId);
    }

    setDirty();
}
```

---

## 6. 交互拦截

### 6.1 右键事件处理

```java
@SubscribeEvent
public static void onRightClick(PlayerInteractEvent.RightClickBlock event) {
    if (event.getLevel().isClientSide()) return;

    BlockPos pos = event.getPos();
    BuildingSavedData data = BuildingSavedData.get(event.getLevel());

    UUID buildingId = data.getBuildingAt(pos);  // posIndex O(1) 查找
    if (buildingId == null) return;

    BuildingState state = data.getBuilding(buildingId);
    if (state == null) return;

    // 打开建筑 GUI（显示状态、队列、关停按钮等）
    openBuildingScreen(event.getEntity(), state);
    event.setCanceled(true);
}
```

### 6.2 性能分析

- `posIndex` 是 `HashMap<BlockPos, UUID>`，get 操作 O(1)
- 典型 town_hall 12 个方块 → posIndex 12 个 entry
- 100 个建筑 × 平均 20 方块 = 2000 entry，内存 ~64KB，查询无压力
- 对比 chunkIndex 方案：右键频率低（玩家操作），O(1) 已足够

---

## 7. BuildingTaskSource 重构

### 接口层无变化

```java
// BuildingApi 接口完全保留：
List<UUID> getBuildingsWithPendingWork(UUID colonyId);
WorkItem dequeueWork(UUID buildingId);
void setCurrentTask(UUID buildingId, UUID taskId);
void clearCurrentTask(UUID buildingId);
```

### 实现层变更

```java
// Before: getBeAt(pos).hasWork()
// After:  直接读 BuildingState.taskQueue

@Override
public List<UUID> getBuildingsWithPendingWork(UUID colonyId) {
    List<UUID> result = new ArrayList<>();
    for (BuildingState state : savedData.getAllBuildings()) {
        if (colonyId != null && !colonyId.equals(state.colonyId)) continue;
        if (state.shutdown) continue;
        if (!state.structureIntact) continue;  // 建造中的建筑不派发新任务
        if (state.currentTaskId != null) continue;
        if (state.taskQueue.isEmpty()) continue;
        if (!isChunkLoaded(state.anchor)) continue;  // 区块感知
        result.add(state.buildingId);
    }
    return result;
}
```

---

## 8. build_complete 事件 → 建筑激活

### 现有机制

蓝图 `build:place_structure` 最后一步：
```json
{
  "type": "emit_event",
  "event": "build_complete",
  "data": {
    "building_name": "$name",
    "blocks_placed": {"size": "$offsets"}
  }
}
```

引擎已有 EventBus 事件系统。`DefaultOpExecutors.EmitEventExecutor` 执行 `EmitEventOp` → 发布到 `World.eventBus`。

### 新增：BuildCompleteListener

```java
// 订阅 build_complete 事件
eventBus.subscribe("build_complete", (eventData, context) -> {
    BlockPos anchor = extractAnchor(context);  // 从 TaskRequest.params["anchor"] 获取
    String buildingType = extractBuildingType(context);

    BuildingSavedData data = BuildingSavedData.get(level);
    BuildingState state = data.getBuildingAtAnchor(anchor);
    if (state == null) return;

    // 验证 pattern 完整性
    BuildingConfig config = BuildingConfigLoader.getInstance().get(buildingType);
    boolean intact = verifyPattern(level, anchor, config);

    state.structureIntact = intact;
    data.setDirty();

    if (intact) {
        LOGGER.info("[Building] {} at {} construction complete — now operational",
                buildingType, anchor);
    }
});
```

---

## 9. 结构完整性 — 被动监听

### 9.1 方块被破坏

```java
@SubscribeEvent
public static void onBlockBreak(BlockEvent.BreakEvent event) {
    BlockPos pos = event.getPos();
    BuildingSavedData data = BuildingSavedData.get(event.getLevel());
    UUID buildingId = data.getBuildingAt(pos);
    if (buildingId == null) return;

    BuildingState state = data.getBuilding(buildingId);
    if (state.structureIntact) {
        state.structureIntact = false;
        data.setDirty();
        // 可选：自动排入修复任务
    }
}
```

### 9.2 爆炸

```java
@SubscribeEvent
public static void onExplosion(ExplosionEvent.Detonate event) {
    for (BlockPos pos : event.getAffectedBlocks()) {
        // 同上逻辑，批量处理
    }
}
```

### 9.3 修复策略

建筑被破坏后：
- `structureIntact = false` → 不再接受新任务
- 现有队列中的任务保留（等待修复后恢复）
- 可自动/手动排入修复任务（复用 `build:clear_and_build` 蓝图）

---

## 10. 建筑 JSON 配置变更

### Before（依赖自定义方块）：

```json
{
  "id": "earth_node",
  "block_id": "wandscape:earth_node",
  "pattern": [[0, 0, 0]],
  "block_mapping": { "0,0,0": "wandscape:earth_node" }
}
```

### After（纯原版方块）：

```json
{
  "id": "earth_node",
  "display_name": "大地节点",
  "category": "node",
  "pattern": [
    [0, 0, 0], [1, 0, 0], [-1, 0, 0],
    [0, 0, 1], [0, 0, -1],
    [0, 1, 0]
  ],
  "block_mapping": {
    "0,0,0": "minecraft:lodestone",
    "1,0,0": "minecraft:moss_block",
    "-1,0,0": "minecraft:moss_block",
    "0,0,1": "minecraft:moss_block",
    "0,0,-1": "minecraft:moss_block",
    "0,1,0": "minecraft:flowering_azalea"
  },
  "boundary": {
    "min": [-1, 0, -1],
    "max": [1, 1, 1]
  },
  ...
}
```

**变更**：移除 `block_id` 字段（不再需要自定义方块 ID），pattern 和 block_mapping 全部引用原版方块。

---

## 11. 待删除代码

| 文件 / 类 | 原因 |
|-----------|------|
| `AbstractWandscapeBE` | 任务队列迁移到 SavedData，BE 无用 |
| `EarthNodeBE` / `ForestNodeBE` / `TownHallBE` / `GrandTowerBE` | 同上 |
| `WandscapeBuildingBlock` | 不再有自定义建筑方块 |
| `BlockPlaceHandler` | 不再监听自定义方块放置 |
| `Wandscape.java` 中 `EARTH_NODE_BLOCK` 等注册 | 不再需要 |

---

## 12. 保留代码（接口不变）

| 文件 / 类 | 状态 |
|-----------|------|
| `BuildingApi` 接口 | ✅ 完全保留 |
| `BuildingData` 接口 | ✅ 保留，`BuildingState` 实现之 |
| `BuildingApiImpl` | 重构实现（读写 SavedData 而非 BE） |
| `BuildingTaskSource` | ✅ 接口调用不变，内部不再 getBeAt |
| `EnqueueHelper` | 重构：写入 SavedData 而非操作 BE |
| `BuildingConfigLoader` | ✅ 完全保留 |
| `BuildingConfig` record | 移除 `blockId()` 字段，其余保留 |

---

## 13. 实施阶段

| Phase | 内容 | 可独立部署 |
|-------|------|-----------|
| **1. BuildingSavedData** | 新增类，实现 NBT 序列化/反序列化 + 空间索引 | ✅ |
| **2. BuildingApiImpl 重构** | 内部状态从 BE 改为 SavedData 读写 | ✅（兼容旧 BE 双读） |
| **3. 事件拦截** | `BuildingInteractHandler` 右键 + `BuildingBreakHandler` 破坏检测 | ✅ |
| **4. build_complete 监听** | `BuildCompleteListener` 订阅事件、验证 pattern、激活建筑 | ✅ |
| **5. JSON 迁移** | 更新所有 building JSON，移除 `block_id`，改为原版方块 | ✅ |
| **6. 清理** | 删除自定义方块注册、BE 类、BlockPlaceHandler | ✅ |

---

## 14. 已决事项

| # | 问题 | 决策 |
|---|------|------|
| 1 | 建筑锚点如何定义？ | pattern 中 `[0,0,0]` 对应的世界坐标。提交任务时 `params["anchor"]` 已指定。 |
| 2 | 注册时机？ | 任务提交时注册（`structureIntact=false`），建造完成后激活。 |
| 3 | 区块未加载时任务如何处理？ | `getBuildingsWithPendingWork()` 检查 `level.isLoaded(anchor)`，跳过未加载。 |
| 4 | 跨维度建筑？ | 不支持。SavedData per-level 天然隔离，无需跨 level 通信。 |
| 5 | 同位置 / 包围盒重叠？ | **禁止**。注册时必须检测新建筑 AABB 与所有已有建筑 AABB 是否相交，有重叠则拒绝注册。 |
| 6 | 建筑升级（pattern 扩大）？ | unregister 旧建筑 → 新 config re-register。升级前需检测扩大后的 AABB 是否与邻居冲突。 |
| 7 | 建筑 GUI 具体交互？ | Phase 3 后设计。当前阶段右键输出 info 日志打印建筑状态验证。 |
| 8 | `EnqueueHelper.computeClearOffsets()` 跳过 anchor 逻辑 | 旧逻辑跳过 `0,0,0` 是为保护自定义方块 BE。新方案中 anchor 也是原版方块，**应该被 clear**。移除该特殊判断。 |

---

## 15. 包围盒碰撞检测（需新增）

### 现状

**当前代码无重叠检测。** `BuildingApiImpl.registerBuilding()` 仅做 `byPos.put(pos, id)` 单点注册。`EnqueueHelper.registerIfAbsent()` 只检查 anchor 位置是否已有建筑（`api.getBuildingAt(pos) != null`），未做 AABB 碰撞。

### 新增逻辑

注册建筑时，需将 boundary（相对 anchor 的 AABB）转为世界坐标 AABB，然后检测是否与任何已注册建筑的世界 AABB 相交：

```java
// BuildingSavedData.register() 内新增：
BoundingBox newBox = computeWorldBox(state.anchor, config.boundary());

for (BuildingState existing : buildings.values()) {
    BoundingBox existingBox = existing.bounds;
    if (newBox.intersects(existingBox)) {
        throw new BuildingOverlapException(
            "Building " + state.buildingTypeId + " at " + state.anchor
            + " overlaps with " + existing.buildingTypeId + " at " + existing.anchor);
    }
}
```

MC 原生 `BoundingBox.intersects(BoundingBox)` 可直接使用（`net.minecraft.world.level.levelgen.structure.BoundingBox`）。

### 性能

- 注册频率极低（玩家主动触发），全表扫描可接受
- 100 个建筑 → 100 次 AABB intersect 检测 = 微秒级
- 如未来建筑数量膨胀，可升级为 R-tree 空间索引（当前不需要）

### 影响的调用点

| 调用 | 变更 |
|------|------|
| `EnqueueHelper.registerIfAbsent()` | 需 catch `BuildingOverlapException`，返回 false 并 log |
| `/wandscape fill` 命令 | 需向玩家报告重叠错误 |
| 未来 GUI 建造面板 | 选址时预览 AABB、标红重叠区域 |
