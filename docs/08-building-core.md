# 建筑核心系统

文档编号：NEW-08
版本：1.0
状态：建筑注册(JSON) + 三数值 + 维护成本 + 队列 + 关停 + 统一交互入口
依赖：01-shared-api

---

## 一、职责边界

- 提供建筑 JSON 注册框架（所有建筑类型通过 JSON 定义）
- 管理殖民地级别的三数值（舒适/魔法/奇观）计算
- 统一建筑维护成本结算（定期扣木元素）
- 统一建筑队列机制（所有建筑都有队列）
- 建筑关停/重启
- 建筑与任务系统的桥接（建筑队列 → 全局任务池）
- 多方块建筑的统一交互入口（任意 pattern 方块右击 → BuildingSavedData.posIndex 查找 → 路由到对应逻辑）

**不包含：**
- 具体建筑的功能逻辑（节点建筑、制作站等各自模块负责）
- 仓库 GUI 和存储（仓库模块负责，物品存储由 `ColonyItemBank` SavedData 管理）
- 远程建造的 UI（管理面板模块负责）

### 1.1 架构变更 (2026-06-21)

建筑状态从 `AbstractWandscapeBE` (自定义方块挂载) 迁移到 `BuildingSavedData` (Level SavedData)。
所有建筑使用原版方块，NPC 通过蓝图放置。`block_id` 字段已从 JSON 配置中移除。

详见 `docs/27-multiblock-refactor-analysis.md`。

---

## 二、建筑 JSON 注册

### 2.1 所有建筑通过 JSON 定义

```json
// data/wandscape/buildings/mage_tower.json
{
  "id": "mage_tower",
  "display_name": "法师塔",
  "category": "wonder",
  "pattern": [
    [0, 0, 0],
    [1, 0, 0],
    [2, 0, 0],
    [1, 1, 0],
    [1, 2, 0]
  ],
  "block_mapping": {
    "0,0,0": "minecraft:stone_bricks",
    "1,0,0": "minecraft:stone_bricks",
    "2,0,0": "minecraft:stone_bricks",
    "1,1,0": "wandscape:rune_pillar",
    "1,2,0": "wandscape:mage_crystal"
  },
  "comfort": 2,
  "magic": 3,
  "wonder": 5,
  "maintenance_cost": 12,
  "shutdown_penalty": {
    "output_reduction": 0.5,
    "time_multiplier": 2.0
  },
  "queue": {
    "capacity": 60,
    "task_types": ["crafting", "ritual"]
  },
  "unlock_requirement": {
    "min_wonder": 15
  }
}
```

### 2.2 字段说明

| 字段 | 类型 | 说明 |
|------|------|------|
| id | string | 唯一标识 |
| display_name | string | 管理面板显示名 |
| category | enum |基础/节点/功能/奇观|
| pattern | array[BlockOffset] | 建筑结构方块相对坐标列表。单方块建筑填 `[[0,0,0]]` |
| block_mapping | object | 坐标→原版方块ID 映射，格式 `{"x,y,z": "minecraft:stone_bricks"}` |
| comfort | int | 首次建造提供的舒适值 |
| magic | int | 首次建造提供的魔法值 |
| wonder | int | 首次建造提供的奇观值 |
| maintenance_cost | int | 每周期消耗的木元素数量 |
| shutdown_penalty | object | 关停惩罚：`output_reduction` 控制产出减半（0.5=50%），`time_multiplier` 控制使用时间加倍（2.0=200%） |
| queue.capacity | int | 建筑队列容量 |
| queue.task_types | list | 该建筑可发布的任务类型 |
| unlock_requirement | object | 解锁条件（奇观值阈值等） |

### 2.3 建筑类型与队列容量

| 类别 | 队列容量 | 说明 |
|------|---------|------|
| 基础建筑 | 5 | 市政厅 5，其他视具体建筑 |
| 节点建筑 | 10 | 采集任务 |
| 功能建筑-工作站 | 60 | 分解/合成 |
| 功能建筑-制作站 | 60 | 法杖/装备制作 |
| 功能建筑-魔药站 | 10 | 药剂制作 |
| 功能建筑-仪式祭坛 | 10 | 仪式任务 |
| 功能建筑-房屋 | 5 | 分配任务 |
| 功能建筑-魔力池 | 10 | 充能任务 |
| 功能建筑-市政厅 | 5 | 建造新建筑任务 |
| 奇观建筑 | — | MVP 不实现 |

---

## 三、建筑统一交互入口

### 3.1 设计原则

多方块建筑由 pattern 定义的多个原版方块组成。任意 pattern 方块被右击时，通过 `BuildingSavedData.posIndex` (HashMap O(1)) 查找所属建筑，路由到对应的交互逻辑。

- `BuildingInteractHandler` 订阅 `PlayerInteractEvent.RightClickBlock`
- `posIndex` 在 `BuildingSavedData.register()` 时构建，覆盖所有 pattern 方块位置
- 仓库建筑 (`category=storage`)：直接 `ColonyItemBank.getSnapshot()` → `WarehouseMenu` GUI
- 其他建筑：打印建筑状态信息（intact / shutdown / queue 大小）

### 3.2 不再使用 BuildingAnchorRegistry

`BuildingAnchorRegistry` 已被 `BuildingSavedData.posIndex` 替代。后者是 Level SavedData 的一部分，随 NBT 持久化，不再需要世界加载时重建路由表。

### 3.3 NPC 交互路径

NPC 与建筑的交互不经过右键事件（那是玩家路径），NPC 走引擎的操作链：

```
NPC 需要与建筑交互
  └→ OperationB(buildingId, action, params)
      └→ AtomicExecutor → 查 BuildingApi.getBuilding(buildingId)
          └→ 获取建筑坐标 → NPC 移动过去
          └→ 调用对应方法（charge / extract / craft / decompose ...）
```

NPC 不受 pattern 路由影响——`buildingId` 始终指向建筑，NPC 直接走到 anchor 坐标执行操作。

---

## 四、三数值系统

### 4.1 计算规则

```java
// 舒适值 = 所有未关停建筑中，每种建筑类型的首次建造 comfort 之和
public int getColonyComfort(UUID colonyId) {
    Set<String> builtTypes = getBuiltBuildingTypeIds(colonyId);
    return builtTypes.stream()
        .mapToInt(id -> getBuildingConfig(id).comfort())
        .sum();
}

// 魔法值、奇观值同理
```

**核心规则：**
- **首次建造加成**：每种建筑类型仅在殖民地中首次被建造时贡献数值
- **不叠加**：同种建筑重复建造不重复贡献数值
- **拆除后重建不重复贡献**：已解锁类型在殖民地中永久标记。拆除后重建同种建筑，不再次贡献数值（避免玩家靠拆建刷数值）
- **关停即归零**：建筑关停后，该类型的数值贡献暂时移除
- **即时永久解锁**：数值一旦获得，相关内容永久解锁。即使后续数值因关停暂时归零，已解锁内容不受影响。数值仅控制未来的解锁权限。

### 4.2 示例

```
首次建造"法师塔" → 舒适+2, 魔法+3, 奇观+5
建造第二座法师塔 → 数值不变
关停第一座法师塔 → 舒适-2
如果舒适值因此低于某招募门槛：
  - 已招募的高属性 NPC 不受影响
  - 无法招募新的同等 NPC
重启法师塔 → 舒适+2 恢复
```

---

## 五、维护成本

### 5.1 结算

- **周期**：每 20 分钟（`MAINTENANCE_INTERVAL_TICKS`）
- **材料**：统一使用木元素
- **扣除**：从殖民地仓库扣除所有未关停建筑的 maintenance_cost 之和
- **不足处理**：木元素不够 → 扣成负数 → 所有建筑**自动关停**。关停的建筑仍可使用，但**使用时间加倍、产出减半**（欠债惩罚）。玩家需手动补足元素使储量回正后重启建筑

### 5.2 关停机制

关停有两种触发方式：**手动关停**（玩家在管理面板操作）和**自动关停**（维护成本扣至负数触发）。两者效果相同：

- 关停后：三数值贡献暂时移除、维护成本清零、使用时间加倍、产出减半
- 可随时重启，数值恢复，效率恢复正常
- 自动关停的建筑，元素储量回正后仍需玩家手动重启

---

## 六、结构验证与自动修复

### 6.1 触发时机

结构完整性不依赖定时轮询。触发检测的时机：
- 方块破坏：`BuildingBreakHandler` 订阅 `BlockEvent.BreakEvent` → 检查 `posIndex` → `structureIntact = false`
- 爆炸：`BuildingBreakHandler` 订阅 `ExplosionEvent.Detonate` → 遍历受影响方块 → 同上
- 建造完成验证：`BuildCompleteListener` 订阅引擎 `build_complete` 事件 → `verifyPattern()` → `structureIntact = true`

### 6.2 被动损坏标记

`BuildingBreakHandler` 通过 `BuildingSavedData.getBuildingIdAt(pos)` 判断被破坏的方块是否属于已知建筑。若属于且 `structureIntact` 当前为 true，则标记为 false。不做实时 pattern 验证（被破坏=不完整，无需验证）。

### 6.3 建造完成验证

`BuildCompleteListener.verifyPattern()` 遍历 `BuildingConfig.pattern()` 中每个 offset，检查世界中方块是否与 `block_mapping` 完全匹配。全部匹配则 `structureIntact = true`。

### 6.4 修复策略

- `structureIntact = false` → 建筑不接受新任务
- 现有队列中的任务保留（等待修复后恢复）
- 可自动/手动排入修复任务（复用 `build:clear_and_build` 蓝图）

---

## 七、建筑队列

### 7.1 统一模型

每个 `BuildingState`（存储在 `BuildingSavedData` 中）内部维护一个 FIFO 队列：

```java
public class BuildingState implements BuildingData {
    private final UUID buildingId;
    private final Deque<WorkItem> taskQueue = new ArrayDeque<>();
    @Nullable private UUID currentTaskId;
    // ...
}
```

`BuildingTaskSource` 每 1 秒轮询 `BuildingApiImpl.getBuildingsWithPendingWork()`：
- 过滤：`isStructureIntact() && !isShutdown() && hasWork() && currentTaskId == null && level.isLoaded(anchor)`
- 出队 → 提交到 `GlobalTaskPool`
- 标记 `currentTaskId` 直到任务完成

队列和 currentTaskId 通过 `BuildingSavedData` NBT 持久化，服务器重启后恢复。

### 7.2 队列源

- 玩家在建筑 GUI 中手动添加任务（合成、分解、仪式等）
- 节点建筑自动入队采集任务（冷却完毕后）
- 市政厅队列 = 建造新建筑任务（玩家在管理面板中下达）

---

## 八、核心 API

```java
public interface BuildingApi {
    BuildingData getBuilding(UUID buildingId);
    BuildingData getBuildingAt(BlockPos pos);
    List<BuildingData> getColonyBuildings(UUID colonyId);

    boolean shutdown(UUID buildingId);
    boolean restart(UUID buildingId);

    int getColonyComfort(UUID colonyId);
    int getColonyMagic(UUID colonyId);
    int getColonyWonder(UUID colonyId);

    boolean isBuildingOccupied(UUID buildingId);

    // 注册新建筑类型（从 JSON 加载）
    void registerBuildingType(BuildingConfig config);
    BuildingConfig getBuildingConfig(String buildingTypeId);
}
```

---

## 九、建筑状态存储

```
BuildingSavedData (Level SavedData)
  ├── Map<UUID, BuildingState> buildings      ← 主索引
  ├── Map<BlockPos, UUID> posIndex            ← 空间索引 (O(1) 右键查找)
  ├── Map<ChunkPos, Set<UUID>> chunkIndex      ← 区块索引（区块卸载感知）
  └── BuildingState
        ├── buildingId / typeId / category / anchor / BoundingBox
        ├── colonyId / shutdown / structureIntact
        ├── Deque<WorkItem> taskQueue / currentTaskId
        └── comfort / magic / wonder / maintenanceCost / queueCapacity
```

`AbstractWandscapeBE` 及其所有子类（`TownHallBE`, `ForestNodeBE`, `EarthNodeBE`, `GrandTowerBE`）以及 `WarehouseBlock` + `WarehouseBE` 已全部删除。模组**零自定义方块/BE**。

各模块未来扩展时通过 `BuildingState` 的 `extra` 字段或模块专属 SavedData 存储自定义数据。

---

## 十、独立测试方案

### 单元测试

1. **JSON 加载**：所有 building JSON 正确解析为 `BuildingConfig`
2. **三数值计算**：首次建造加成 + 同种不叠加 + 关停移除 + 重启恢复
3. **维护扣除**：正常扣除、不足负数、不产生异常
4. **队列容量**：入队超过容量 → 拒绝
5. **结构检测**：给定 pattern + 部分方块不匹配 → 正确识别缺失列表
6. **修复任务生成**：缺失 N 个方块 → 生成 N 个 OperationA 修复任务
7. **交互路由**：`BuildingAnchorRegistry` register → getAnchor 返回正确主 BE 坐标 → unregister 后返回 null

### 集成测试

1. 放置一种新建筑 → 三数值正确增加
2. 放置同种第二座 → 数值不变
3. 关停建筑 → 数值移除 → 重启 → 数值恢复
4. 维护周期到达 → 木元素正确扣除
5. 建筑队列：入队 3 个 → 逐个发布 → 全部完成
6. 破坏建筑的一个方块 → 检测到结构损坏 → 修复任务自动入队 → NPC 修复 → 结构恢复完整
7. 多方块建筑：右击非锚点 pattern 方块 → 路由到主 BE 的 `onPlayerInteract`
