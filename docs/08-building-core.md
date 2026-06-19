# 建筑核心系统

文档编号：NEW-08
版本：1.0
状态：建筑注册(JSON) + 三数值 + 维护成本 + 队列 + 关停
依赖：01-shared-api

---

## 一、职责边界

- 提供建筑 JSON 注册框架（所有建筑类型通过 JSON 定义）
- 管理殖民地级别的三数值（舒适/魔法/奇观）计算
- 统一建筑维护成本结算（定期扣木元素）
- 统一建筑队列机制（所有建筑都有队列）
- 建筑关停/重启
- 建筑与任务系统的桥接（建筑队列 → 全局任务池）

**不包含：**
- 具体建筑的功能逻辑（节点建筑、制作站等各自模块负责）
- 仓库 GUI 和存储（仓库模块负责）
- 远程建造的 UI（管理面板模块负责）

---

## 二、建筑 JSON 注册

### 2.1 所有建筑通过 JSON 定义

```json
// data/wandscape/buildings/mage_tower.json
{
  "id": "mage_tower",
  "display_name": "法师塔",
  "category": "wonder",
  "block_id": "wandscape:mage_tower",
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
| block_id | string | 对应方块 ID |
| pattern | array[BlockOffset] | 建筑结构方块相对坐标列表。单方块建筑填 `[[0,0,0]]` |
| block_mapping | object | 坐标→方块ID 映射，格式 `{"x,y,z": "modid:blockid"}` |
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

## 三、三数值系统

### 3.1 计算规则

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

### 3.2 示例

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

## 四、维护成本

### 4.1 结算

- **周期**：每 20 分钟（`MAINTENANCE_INTERVAL_TICKS`）
- **材料**：统一使用木元素
- **扣除**：从殖民地仓库扣除所有未关停建筑的 maintenance_cost 之和
- **不足处理**：木元素不够 → 扣成负数 → 所有建筑**自动关停**。关停的建筑仍可使用，但**使用时间加倍、产出减半**（欠债惩罚）。玩家需手动补足元素使储量回正后重启建筑

### 4.2 关停机制

关停有两种触发方式：**手动关停**（玩家在管理面板操作）和**自动关停**（维护成本扣至负数触发）。两者效果相同：

- 关停后：三数值贡献暂时移除、维护成本清零、使用时间加倍、产出减半
- 可随时重启，数值恢复，效率恢复正常
- 自动关停的建筑，元素储量回正后仍需玩家手动重启

---

## 五、结构验证与自动修复

### 5.1 触发时机

建筑的结构完整性不依赖定时轮询。触发检测的时机：
- 建筑所在区块内发生方块破坏（`BlockEvent.BreakEvent`）
- 建筑所在区块内发生爆炸（`ExplosionEvent.Detonate`）

### 5.2 检测逻辑

```java
public abstract class AbstractWandscapeBE extends BlockEntity {
    protected UUID colonyId;  // 通过 ColonyApi.getColonyId(getBlockPos()) 首次查询后缓存
    protected BuildingConfig config;
    protected boolean isStructureIntact = true;

    // 被方块破坏/爆炸事件触发
    public void checkStructureIntegrity() {
        List<BlockOffset> missing = new ArrayList<>();
        for (BlockOffset offset : config.pattern()) {
            BlockPos target = worldPosition.offset(offset);
            BlockState expected = config.getBlockMapping().get(offset.toKey());
            BlockState actual = level.getBlockState(target);
            if (!actual.equals(expected)) {
                missing.add(offset);
            }
        }

        if (!missing.isEmpty()) {
            isStructureIntact = false;
            enqueueRepairTasks(missing);
        } else {
            isStructureIntact = true;
        }
    }

    private void enqueueRepairTasks(List<BlockOffset> missing) {
        for (BlockOffset offset : missing) {
            BlockPos target = worldPosition.offset(offset);
            BlockState expected = config.getBlockMapping().get(offset.toKey());
            Map<ElementType, Long> cost = ElementApi.getBuildCost(expected);
            TaskTemplate repairTask = new TaskTemplate(
                BehaviorType.BUILDING,
                config.getRequiredLevel("building"),
                List.of(
                    new OperationA(target, level.getBlockState(target), expected, false, cost)
                ),
                100 // 修复任务高优先级
            );
            TaskApi.enqueueBuildingTask(this.getId(), repairTask);
        }
    }

    // 建筑功能是否可用（结构完整 + 未关停）
    public boolean isOperational() {
        return isStructureIntact && !isShutdown();
    }
}
```

### 5.3 修复规则

- 修复任务与建造任务逻辑完全一致：计算缺失方块 → 生成 OperationA 序列 → 消耗对应元素
- 修复任务自动以高优先级入队，排在玩家手动添加的任务之前
- 结构损坏不影响建筑队列中已有任务的发布顺序，但新任务的发布暂停直到修复完成
- 修复过程中建筑视为正常运行（只要还有一部分方块存在），不关停

---

## 六、建筑队列

### 5.1 统一模型

每个建筑实体（BlockEntity）内部维护一个 FIFO 队列：

```java
public abstract class AbstractWandscapeBE extends BlockEntity {
    protected UUID colonyId;                // 所属殖民地，首次查询后缓存
    protected final Queue<UUID> taskQueue = new ArrayDeque<>();
    protected UUID currentTaskId;

    @Override
    public void onLoad() {
        super.onLoad();
        // colonyId 通过坐标查询一次并缓存，后续直接用
        if (colonyId == null) {
            colonyId = ColonyApi.getColonyId(getBlockPos());
        }
    }

    // 条件满足时发布下一个任务
    public void tryPublishNext() {
        if (currentTaskId != null && !isTaskCompleted(currentTaskId)) return;
        if (taskQueue.isEmpty()) return;
        if (isShutdown()) return;

        UUID nextTask = taskQueue.poll();
        UUID published = TaskApi.publishTask(buildTask(nextTask), colonyId);
        setCurrentTask(published);
    }
}
```

> `colonyId` 在 BE 首次 `onLoad()` 时通过 `ColonyApi.getColonyId(pos)` 查询一次并缓存。建筑在殖民地内的坐标永不改变，无需重复查询。

### 5.2 队列源

- 玩家在建筑 GUI 中手动添加任务（合成、分解、仪式等）
- 节点建筑自动入队采集任务（冷却完毕后）
- 市政厅队列 = 建造新建筑任务（玩家在管理面板中下达）

---

## 七、核心 API

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

## 八、方块实体层级

```
AbstractWandscapeBE              ← 队列 + 关停 + 维护
    ├── NodeBuildingBE           ← 节点建筑（09 模块扩展）
    ├── ProductionStationBE      ← 制作站/工作站/魔药站（10 模块扩展）
    ├── HouseBE                  ← 房屋（11 模块扩展）
    ├── ManaPoolBE               ← 魔力池（11 模块扩展）
    ├── RitualAltarBE            ← 仪式祭坛（13 模块扩展）
    ├── TavernBE                 ← 酒馆（12 模块扩展）
    ├── WarehouseBE              ← 仓库（04 模块扩展）
    └── TownHallBE               ← 市政厅（15 模块扩展）
```

各模块只扩展自己需要的逻辑，核心的队列、关停、维护由 `AbstractWandscapeBE` 统一处理。

---

## 九、独立测试方案

### 单元测试

1. **JSON 加载**：所有 building JSON 正确解析为 `BuildingConfig`
2. **三数值计算**：首次建造加成 + 同种不叠加 + 关停移除 + 重启恢复
3. **维护扣除**：正常扣除、不足负数、不产生异常
4. **队列容量**：入队超过容量 → 拒绝
5. **结构检测**：给定 pattern + 部分方块不匹配 → 正确识别缺失列表
6. **修复任务生成**：缺失 N 个方块 → 生成 N 个 OperationA 修复任务

### 集成测试

1. 放置一种新建筑 → 三数值正确增加
2. 放置同种第二座 → 数值不变
3. 关停建筑 → 数值移除 → 重启 → 数值恢复
4. 维护周期到达 → 木元素正确扣除
5. 建筑队列：入队 3 个 → 逐个发布 → 全部完成
6. 破坏建筑的一个方块 → 检测到结构损坏 → 修复任务自动入队 → NPC 修复 → 结构恢复完整
