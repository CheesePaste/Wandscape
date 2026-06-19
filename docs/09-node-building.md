# 节点建筑

文档编号：NEW-09
版本：1.0
状态：节点建筑 = 可发布采集任务产出元素的建筑
依赖：01-shared-api, 08-building-core

---

## 一、设计简化

**不再使用"底座 + 核心"结构。** 每种节点建筑是一个独立的建筑类型，有自己的方块和模型（美观自由）。节点建筑唯一的功能：在无进行中任务时，自动发布采集任务到队列，任务完成后向殖民地仓库注入元素。

---

## 二、节点类型

### 2.1 第一层（开局可用）

| 节点 | 建筑 ID | 产出元素 | 每次产出量 | 默认引导时间 |
|------|---------|---------|-----------|-------------|
| 大地节点 | earth_node | 土 | 8 | 短 (200 tick) |
| 森林节点 | forest_node | 木 | 10 | 短 (200 tick) |
| 水域节点 | water_node | 水 | 6 | 短 (200 tick) |

### 2.2 第二层（奇观值解锁）

| 节点 | 建筑 ID | 产出元素 | 每次产出量 | 默认引导时间 |
|------|---------|---------|-----------|-------------|
| 地心节点 | fire_node | 火 | 4 | 中 (400 tick) |
| 深层大地节点 | deep_earth_node | 铁 | 4 | 中 (400 tick) |
| 高山节点 | wind_node | 风 | 4 | 中 (400 tick) |

### 2.3 第三层（高奇观值解锁）

| 节点 | 建筑 ID | 产出元素 | 每次产出量 | 默认引导时间 |
|------|---------|---------|-----------|-------------|
| 金矿节点 | gold_node | 金 | 2 | 长 (600 tick) |
| 钻石矿节点 | diamond_node | 钻石 | 1 | 长 (600 tick) |
| 虚空节点 | void_node | 末影 | 2 | 长 (600 tick) |

---

## 三、JSON 配置

```json
// data/wandscape/buildings/forest_node.json
{
  "id": "forest_node",
  "display_name": "森林节点",
  "category": "node",
  "block_id": "wandscape:forest_node",
  "comfort": 1,
  "magic": 0,
  "wonder": 1,
  "maintenance_cost": 2,
  "node_config": {
    "element": "wood",
    "amount_per_harvest": 10,
    "channel_ticks": 200,
    "required_behavior": "gathering",
    "required_level": 1
  },
  "queue": {
    "capacity": 10,
    "task_types": ["gathering"]
  },
  "unlock_requirement": {
    "min_wonder": 0
  }
}
```

---

## 四、工作流程

```
1. 节点当前无进行中任务 → 队列为空时自动入队一个采集任务
2. 建筑队列机制将任务发布到全局池
3. 调度器匹配持有 gathering N 法杖的空闲 NPC
4. NPC 传送至节点旁，执行建筑交互（操作 B，action="node_gathering"）
5. 引导完成 → 殖民地仓库.addElement(type, amount) → 触发 ElementChangedEvent
6. 节点释放 → 回到步骤 1
```

**关键**：节点不设独立冷却计时器。节奏完全由任务执行时间（引导时长 + NPC 调度延迟）自然控制。

---

## 五、节点与 NPC 私人物流

节点产出直接注入殖民地仓库（元素），不经 NPC 背包。因此节点采集任务不需要物资上缴步骤。流程极简：

```
节点入队采集任务 → 调度匹配 → NPC 引导 → 元素直入仓库 → 完成
```

---

## 六、MVP 节点清单

MVP 实现 3 种节点：

| 节点 | 元素 | 用途 |
|------|------|------|
| 森林节点 | 木 | 维护成本 + 万能工作站转换 |
| 大地节点 | 土 | 建造材料（圆石/石砖等） |
| 水域节点 | 水 | 魔药站法力药剂等 |

---

## 七、NodeBuildingBE

```java
public class NodeBuildingBE extends AbstractWandscapeBE {
    private String elementType;       // 从 JSON 读取
    private int amountPerHarvest;
    private int channelTicks;
    private int requiredLevel;

    @Override
    public void tick() {
        super.tick(); // 处理队列
        // 如果队列为空且无进行中任务，自动入队一个采集任务
        if (taskQueue.isEmpty() && currentTaskId == null && !isShutdown()) {
            enqueueGatheringTask();
        }
    }

    private void enqueueGatheringTask() {
        TaskTemplate template = new TaskTemplate(
            BehaviorType.GATHERING,
            requiredLevel,
            List.of(
                new OperationB(this.getUUID(), "node_gathering",
                    Map.of("element", elementType, "amount", amountPerHarvest, "channel_ticks", channelTicks))
            )
        );
        TaskApi.enqueueBuildingTask(this.getUUID(), template);
    }
}
```

---

## 八、不同节点的美观差异

每种节点是独立的方块类型，可拥有不同的模型和材质：
- 森林节点：木质外观，周围有树叶粒子
- 大地节点：石质外观，周围有尘土粒子
- 水域节点：水蓝色，周围有水滴粒子
- 等等

模型和材质由资源包/JSON 模型定义，不影响代码逻辑。

---

## 九、独立测试方案

### 单元测试

1. **JSON 配置加载**：所有节点配置正确解析
2. **自动入队**：队列为空 + 无进行中任务 → 采集任务自动入队
3. **元素注入**：采集完成后元素正确注入仓库
4. **不重复发布**：有进行中任务时不入队新任务

### 集成测试

1. 放置森林节点 → 采集任务自动发布 → NPC 执行 → 木元素增加
2. 关停节点 → 不发布新任务
3. 拆除节点 → 回收元素
4. 多个节点并行工作不冲突
