# 道路系统

文档编号：NEW-28
版本：3.0-draft
状态：V1 已实施，V3 设计中（Jigsaw 模板扩展 + MST 约束）
依赖：01-shared-api, core/

---

## 一、定位

道路系统为殖民地建筑间提供自动化路网连接。道路**纯装饰**，不与寻路系统耦合。NPC/生物寻路不受道路影响。

## 二、核心设计决策

| # | 决策 | 结论 | 理由 |
|---|------|------|------|
| 1 | 道路定义 | **图网络**：建筑为节点，路段为边 | V1 点对点太简单，网络模型可自然扩展 |
| 2 | 初始路网 | **MST 自动生成**：殖民地建筑数 ≥ 阈值时触发生成 | 保证连通性，总路长最短 |
| 3 | 初始路网等级 | **土路（dirt_path）** | 视觉上可区分，后续可升级 |
| 4 | 主干道升级 | **V1 不做**，预留数据结构 | 玩家指定/升级主干道是后期功能 |
| 5 | 新建建筑 | **增量连接**：连到最近路网点（建筑或交叉口） | 不重算全局 MST |
| 6 | 环路/捷径 | **V1 不做**，预留数据结构 | 检测"绕路"并提示的功能后期扩展 |
| 7 | 路径形状 | **L 形（先 X 后 Z）** | 轴对齐，确定性强 |
| 8 | 距离度量 | **曼哈顿距离** | 与 L 形路径一致 |
| 9 | 高度/Y轴 | **跟随地形**：每格独立算地面 Y | 不悬空不埋地 |
| 10 | 障碍处理 | **V1 跳过**：水/岩浆上的格省略，不清理树木 | 土路不用太讲究 |
| 11 | 道路 vs 建筑 | **独立系统**，不混入 BuildingState | 路无三数值/维护/队列 |
| 12 | 模块归属 | **core/road/ + engine/road/** | 不增加独立模块编号 |
| 13 | 方块配置 | TOML + JSON（默认硬编码可覆盖） | 数据驱动 |
| 14 | 建筑阈值 | 殖民地建筑数 ≥ N 才修路 | N 存 TOML，默认 3 |
| 15 | 触发时机 | **事件驱动**：`build_complete` + 命令手动触发 | 不轮询 |
| 16 | 任务优先级 | **10**（最低，低于 node gather=15） | 纯装饰 |
| 17 | 分段策略 | 每段 ≤ 16 格，L 形转折处优先切 | 多 NPC 可并行修路 |
| 18 | 分段入池 | **V1 一次性全入 GlobalTaskPool** | 简单，后续可改连锁触发 |
| 19 | 路口处理 | 检测交叉点 → 记录为隐式节点 → **使用专用路口方块** | 视觉可区分 |
| 20 | 蓝图 | 新建 `road:build_segment` DSL 蓝图 | 路径为绝对坐标，与 build:place_structure 的 offset 模式不同 |
| 21 | 持久化 | `RoadNetworkSavedData`（Level SavedData） | 复用已验证模式 |
| 22 | 玩家命令 | `/wandscape road info` + `/wandscape road rebuild` | V1 最小命令集 |
| 23 | 建筑拆除 | **V1 不断路**，保留在地面（标记节点 ORPHAN） | 以后可加手动拆路 |
| 24 | 建筑重建 | 新 buildingId 替换旧节点 | 坐标不变，UUID 更新 |
| 25 | 殖民地删除 | **V1 不清理路网** | 殖民地系统未完成，RoadSavedData 保留在 Level |
| 26 | 任务完成回调 | **事件 + SavedData**：`road_segment_complete` → RoadTaskSource 监听 → 更新 edge 进度 → 全段完成标记 COMPLETE | 命令实时查询进度 |
| 27 | core/engine 边界 | **纯数据传参**：core 方法签名使用 `RoadBuildingData` record（id + x/y/z + typeId），不依赖 MC 类 | 纯函数，易单元测试 |
| 28 | 路径生成 | **PathGenerator 返回 `XZPoint`**（2D），engine 层逐格算 Y + 过关卡 + 选方块 | core 不碰 GridPos |
| 29 | NBT nodes 存储 | **不显式序列化 nodes**：加载时从 BuildingSavedData（建筑节点）+ path 交叉检测（路口节点）重建 | 避免冗余和位置不同步 |
| 30 | 路网重建策略 | **diff**：对比新 MST 与现有路网 → 保留/废弃/新建 | 不拆已建成的路 |

---

## 三、架构

```
┌─────────────────────────────────────────────────────────────┐
│  core/road/                                                  │
│  纯 Java 21，零 MC 依赖                                      │
│                                                              │
│  RoadBuildingData record { UUID id, int x, int y, int z }    │
│  XZPoint record { int x, int z }                             │
│                                                              │
│  RoadNetwork { nodes: Map<UUID,RoadNode>, edges: Map<UUID,RoadEdge> }│
│  RoadNode { nodeId, pos(GridPos), type(BUILDING|INTERSECTION|ORPHAN) }│
│  RoadEdge { edgeId, from, to, tier, path[], segmentTaskIds[], status }│
│                                                              │
│  MstCalculator:                                              │
│    ├─ prim(points, manhattanDist) → List<MstEdge>            │
│    └─ 纯函数：List<RoadBuildingData> → (List<XZPoint>[])     │
│                                                              │
│  PathGenerator:                                              │
│    ├─ LShape(from, to): List<XZPoint>                        │
│    └─ 固定先X后Z                                              │
│                                                              │
│  RoadPlanner（编排器）:                                        │
│    ├─ computeMST(buildings, threshold) → RoadNetwork         │
│    │     if count < threshold → 空                            │
│    │     else MstCalculator.prim → 每条边 PathGenerator →    │
│    │          splitIntoSegments → RoadNetwork                 │
│    ├─ incrementalAdd(network, newBuilding) → RoadNetwork     │
│    │     找最近节点(建筑+路口) → PathGenerator → 分段          │
│    ├─ rebuild(network, buildings) → NetworkDiff              │
│    │     新MST vs 现有 → 保留/废弃/新建                        │
│    └─ splitIntoSegments(path, maxLen, turnPoints)            │
│          → List<List<XZPoint>>  每段≤maxLen                  │
│                                                              │
│  IntersectionDetector:                                        │
│    └─ detect(pathA, pathB) → List<XZPoint> 交叉点            │
└──────────────┬──────────────────────────────────────────────┘
               │ 纯数据传参（RoadBuildingData, XZPoint）
               │ 不引 MC 类
┌──────────────▼──────────────────────────────────────────────┐
│  engine/road/                                                │
│  MC 适配层                                                    │
│                                                              │
│  RoadSavedData (Level SavedData):                            │
│    ├─ NBT 存 edges (path + segmentTaskIds + tier + status)  │
│    ├─ NBT 存 colonyId + buildingCount                        │
│    └─ 加载时重建 nodes: BuildingSavedData → 建筑节点,        │
│         path 交叉检测 → 路口节点                              │
│                                                              │
│  RoadBuilder:                                                │
│    ├─ terrainHeightAt(level, x, z) → int groundY             │
│    ├─ isPassable(level, pos) → boolean                       │
│    ├─ selectSurfaceBlock(tier) → String blockId              │
│    ├─ selectIntersectionBlock(tier) → String blockId          │
│    └─ buildTiles(level, path, tier, intersections)            │
│          └─ 每格: 算Y → 过关卡 → 选方块 → {pos, block}       │
│          └─ 返回 JsonArray[{pos: [x,y,z], block: "..."}]     │
│                                                              │
│  RoadTaskSource (事件驱动):                                   │
│    ├─ 监听 build_complete → RoadPlanner 检查 → 生成分段      │
│    ├─ 监听 road_segment_complete → 更新 edge 进度            │
│    └─ pollThroughBuildings() 从 BuildingSavedData           │
│         提取 List<RoadBuildingData> 传给 RoadPlanner          │
│                                                              │
│  RoadApiImpl:                                                │
│    ├─ getNetwork / getEdges / requestFullRebuild             │
│    └─ requestIncrementalUpdate                               │
│                                                              │
│  RoadConfig:                                                 │
│    └─ TOML [road] + JSON road_tiers                          │
└──────────────┬──────────────────────────────────────────────┘
               │ RoadApi
┌──────────────▼──────────────────────────────────────────────┐
│  shared/api/RoadApi.java                                     │
│                                                              │
│  RoadNetwork getNetwork(UUID colonyId)                       │
│  List<RoadEdge> getEdges(UUID colonyId)                      │
│  void requestFullRebuild(UUID colonyId)                      │
│  void requestIncrementalUpdate(UUID colonyId, UUID buildingId)│
│  int getBuildingThreshold()                                  │
│  String getRoadBlock(RoadTier tier)                          │
└──────────────────────────────────────────────────────────────┘
```

---

## 四、数据流

```
build_complete 事件
  │
  ▼
RoadTaskSource.onBuildComplete()
  │
  ├─ 从 BuildingSavedData 提取 List<RoadBuildingData>
  │
  ├─ 首次（现有路网为空 + buildingCount ≥ threshold）
  │   └─ RoadPlanner.computeMST(buildings, threshold) → RoadNetwork
  │       └─ 每条 MstEdge:
  │           PathGenerator.LShape(fromPos, toPos) → List<XZPoint>
  │             → splitIntoSegments(path, 16, turnPoints)
  │               → 每段: RoadBuilder.buildTiles(level, path, tier, intersections)
  │                   → JsonArray[{pos, block}]
  │                     → WorkItem("road:build_segment", {segment_id, tiles}, priority=10)
  │                       → GlobalTaskPool.addTask(TaskRequest)
  │                         → NPC 执行 for_each place
  │
  ├─ 增量（新建筑建成，路网非空）
  │   └─ RoadPlanner.incrementalAdd(network, newBuilding)
  │       └─ 找最近路网点(建筑节点+路口节点)
  │         → PathGenerator → split → buildTiles → 同上
  │
  └─ 重建（命令 /wandscape road rebuild）
      └─ RoadPlanner.rebuild(network, buildings) → NetworkDiff
          ├─ 保留边（MST 中有 + 路网已有）→ 不动
          ├─ 废弃边（MST 中无 + 路网已有）→ 标记 ORPHAN（不拆）
          └─ 新建边（MST 中有 + 路网无）→ 生成分段任务

road_segment_complete 事件
  │
  ▼
RoadTaskSource.onSegmentComplete()
  └─ 查 RoadSavedData → 找到所属 edge
      → 标记该 segment taskId 完成
      → 检查 edge 所有 segment 是否都完成
        → 是: 标记 EdgeStatus.COMPLETE
        → 否: 不更新 status（等待后续 segment）
```

---

## 五、Blueprint DSL — `road:build_segment`

```json
{
  "id": "road:build_segment",
  "params": {
    "segment_id": "string",
    "tiles": "list<map>"
  },
  "steps": [
    {
      "type": "for_each",
      "list": "$tiles",
      "var": "tile",
      "steps": [
        {
          "type": "place",
          "at": {"get": ["$tile", "pos"]},
          "block": {"get": ["$tile", "block"]}
        }
      ]
    },
    {
      "type": "emit_event",
      "event": "road_segment_complete",
      "data": {
        "segment_id": "$segment_id",
        "tiles_placed": {"size": "$tiles"}
      }
    }
  ]
}
```

**tiles 结构**（engine 侧构造 `JsonArray` 作为运行时参数）：
```json
[
  {"pos": [100, 64, 200], "block": "minecraft:dirt_path"},
  {"pos": [101, 64, 200], "block": "minecraft:dirt_path"},
  ...
  {"pos": [110, 64, 200], "block": "minecraft:stone_bricks"}
]
```

每个 tile 的 `block` 由 engine 层 `RoadBuilder.buildTiles()` 根据位置判定：路口 → 路口方块，普通 → 路面方块。

DSL 验证结论：`for_each` over list of maps + `MapGet` 提取 pos/block → `place` 全链路可行。tiles 必须作为运行时参数（`JsonArray`）注入。

---

## 六、RoadNetworkSavedData NBT 持久化

```
RoadNetworkSavedData (Level SavedData, HJSON copy-on-write)
  ├── colonyId: UUID
  ├── buildingCount: int
  └── edges: ListTag<CompoundTag>
        └── per edge:
              ├── edgeId: UUID
              ├── fromNodeId: UUID
              ├── toNodeId: UUID
              ├── tier: String ("dirt")
              ├── status: String ("PLANNED"|"BUILDING"|"COMPLETE")
              ├── path: ListTag<CompoundTag>
              │     └── per point: {x: int, y: int, z: int}
              └── segmentTaskIds: LongArrayTag
```

**节点不显式序列化**。加载时重建：
1. 遍历所有 COMPLETE edges → 收集 fromNodeId/toNodeId
2. 建筑节点：查 `BuildingSavedData` 按 buildingId 获取 anchor 坐标
3. 路口节点：检测 path 交叉（`IntersectionDetector.detect` across all edges）
4. 无主节点（建筑已被拆除但边保留）→ 标记 `ORPHAN`

---

## 七、配置

### TOML（config/wandscape-common.toml）

```toml
[road]
building_threshold = 3
segment_max_length = 16
```

### JSON（data/wandscape/road_tiers.json）

```json
{
  "tiers": {
    "dirt": {
      "surface_block": "minecraft:dirt_path",
      "intersection_block": "minecraft:stone_bricks"
    }
  }
}
```

---

## 八、命令

| 命令 | 功能 |
|------|------|
| `/wandscape road info` | 显示路网统计：节点数、边数、总路长、各边状态 |
| `/wandscape road rebuild` | 全局重算 MST，diff 现有路网，生成增删清单并创建建造任务 |
| `/wandscape roadtest <spacing> <count> [buildingType]` | 测试命令：以环形放置 N 个预建建筑，发射 build_complete 事件触发修路全链路 |

---

## 九、事件

| 事件 | 触发方 | 监听方 | 用途 |
|------|--------|--------|------|
| `build_complete` (已有) | build:place_structure 蓝图 | RoadTaskSource | 触发首次 MST / 增量连路 |
| `road_segment_complete` | road:build_segment 蓝图 | RoadTaskSource | 更新 edge 分段进度 → 全段完成标记 COMPLETE |

---

## 十、测试计划

### core 层（纯 JUnit 5，零 MC 依赖）

| 测试类 | 用例 |
|--------|------|
| `MstCalculatorTest` | 2 点 MST（1 边）、3 点等腰（2 边）、4 点正方形（MST 3 边）、单点（0 边）、空集（0 边）、5 点验证总距离最小 |
| `PathGeneratorTest` | A→B 水平、A→B 垂直、A→B 对角（L 形 2 段验证 X→Z 顺序）、同点（空路径）、负偏移 |
| `RoadPlannerTest` | 建筑数 < 阈值 → 不生成、= 阈值 → 生成 MST、增量新增建筑连到最近节点、rebuild diff（保留/废弃/新建）、空路网增量首次建筑 |
| `IntersectionDetectorTest` | 两条边交叉检测、平行不交叉、重合一段、交叉点坐标验证 |
| `RoadNetworkTest` | findNearestNode 返回正确节点、addEdge/removeEdge、splitIntoSegments（16 格段 + 转角切点）、全边标记 COMPLETE |

### engine/shared 层（集成测试，留待 GameTest 或手动）

| 范围 | 内容 |
|------|------|
| RoadBuilder | terrainHeightAt 不同地形（平地/坡/悬崖）、isPassable 判定（空气/水/岩浆/固体）、buildTiles 输出 JSON 结构验证 |
| RoadSavedData | NBT 写入→读取 round-trip、nodes 重建（从 BuildingSavedData + intersection）、空路网加载 |
| RoadConfig | TOML 默认值读取、JSON road_tiers 解析 |
| RoadApiImpl | 端到端：build_complete → RoadTaskSource → TaskRequest → GlobalTaskPool |
| 命令 | /wandscape road info / rebuild 交互 |

---

## 十一、实施顺序

```
① core/road/ 纯数据类 + 算法（零 MC 依赖，可独立单元测试）
    ├─ RoadBuildingData record
    ├─ XZPoint record
    ├─ RoadNetwork, RoadNode, RoadEdge (data classes)
    ├─ MstCalculator
    ├─ PathGenerator
    ├─ IntersectionDetector
    └─ RoadPlanner
    └─ 单元测试 → ./gradlew test 全绿

② shared/api/RoadApi.java 接口定义

③ data/wandscape/road_tiers.json
   config/wandscape-common.toml [road] 段落

④ engine/road/ MC 适配
    ├─ RoadConfig (TOML + JSON 加载)
    ├─ RoadSavedData (NBT 持久化)
    ├─ RoadBuilder (terrainHeightAt + isPassable + buildTiles)
    ├─ RoadTaskSource (事件监听 + 任务入队)
    └─ RoadApiImpl
    └─ WandscapeApis 注册 RoadApi

⑤ blueprints/road_build_segment.json DSL 蓝图

⑥ WandscapeEngine / EngineBootstrap 集成
    ├─ RoadConfig 加载
    ├─ RoadSavedData 注册
    ├─ RoadTaskSource 注册为事件监听器
    └─ RoadApiImpl 注入

⑦ Wandscape.java tick 集成（事件 dispatch → RoadTaskSource 触发）

⑧ 命令 /wandscape road info + rebuild

⑨ 手动集成测试 + GameTest
```

---

## 十二、V2 方向：混合原版 StructureTemplate 管道

> 2026-06-21：逆向分析原版村庄道路生成源码（NeoForge 21.1.233），提出结合方案。

### 12.1 原版村庄道路生成机制

原版村庄道路使用 **Jigsaw 扩展 + NBT 模板 + 处理器管道** 三层架构。

#### 12.1.1 Jigsaw 拓扑生成（贪心扩展，非全局规划）

```
town_center (起点，RIGID)
  → jigsaw "street" → 从 "village/plains/streets" 池随机取模板
    → 该模板的 jigsaw "street" → 再随机取下一个模板
      → ... 直到碰撞失败或超出 maxDepth
        → fallback "terminator" 模板结束道路
```

核心在 `JigsawPlacement.Placer.tryPlacingChildren()`：
- 每个 jigsaw 块从对应池里随机抽模板
- 尝试所有旋转方向匹配 jigsaw 连接
- `Shapes.joinIsNotEmpty` 检查与已放置结构碰撞
- 第一个无碰撞的就放置（非确定性贪心）
- 没有全局最短路径，没有最小生成树，没有优化目标

#### 12.1.2 NBT 模板 = 预制的道路片段（不是程序化逐格）

解析平原村庄 `straight_01.nbt`：
```
尺寸: 16×2×16
方块: 258 total
  dirt_path: 48 块（3格宽 × 16格长，占模板 18%）
  jigsaw:    2 个（z=0、z=15 两端，用于连接下一个模板）
  grass_block: 7 个（路边装饰锚点）
  air:       201 个（模板其余部分）
```

**道路宽度是在模板里手工设计的，不是公式算出来的**。

街道模板池（`PlainVillagePools`）：
| 模板类型 | 数量 | 权重 | 用途 |
|----------|------|------|------|
| `straight_*` | 6 种 | 29 | 直线段 |
| `corner_*` | 3 种 | 6 | 转角 |
| `crossroad_*` | 6 种 | 11 | 十字路口 |
| `turn_01` | 1 种 | 3 | 转弯 |
| terminator | 4 种 | 4 | 路尽头 |

关键发现：**crossroad 模板中的交叉口中心也是 dirt_path**，不用不同方块：
```
crossroad_01.nbt 布局 (Y=0 top-down):
  x=7,8,9 三列贯通（3格宽直道）
  z=7,8,9 三行贯通（3格宽直道）
  中心 3×3 区域全是 dirt_path
  没有 stone_bricks！
```

交叉口的识别是**形状**（道路变宽交叠），不是材质。

#### 12.1.3 三级处理器管道 = 自然感来源

`StructureTemplate.processBlockInfos()` 对模板每个方块依次运行：

```
Layer 1: BlockIgnoreProcessor(STRUCTURE_AND_AIR)
  → 跳过结构方块和空气（模板的脚手架块）

Layer 2: GravityProcessor(WORLD_SURFACE_WG, -1)
  → 每方块独立计算 Y = 地形高度 - 1 + 模板Y
  → 路面紧贴地形，不平铺

Layer 3: RuleProcessor(街区规则)
  → 方块级条件替换
```

**街区规则（`ProcessorLists.STREET_PLAINS`）**：
```java
规则 1: DIRT_PATH ∧ 世界水面 → OAK_PLANKS    (水上桥)
规则 2: DIRT_PATH (10%)      → GRASS_BLOCK   (磨损斑驳)
规则 3: GRASS_BLOCK ∧ 世界水面 → WATER        (水下淹没)
规则 4: DIRT ∧ 世界水面       → WATER
```

每条规则（`ProcessorRule.test()`）测试两个条件：
- `inputPredicate`：模板中应该放什么块
- `locPredicate`：世界中那个位置当前是什么块
- 都匹配 → `outputState` 替换

#### 12.1.4 地形适应细节

`GravityProcessor` 关键逻辑：
```java
int i = level.getHeight(heightmap, x, z) + offset;  // 地形Y + (-1)
int j = blockInfo.pos().getY();                       // 模板中 Y = 0（路面）
return new BlockPos(x, i + j, z);                    // = 地形高度 - 1 + 0
```

路面方块被放在地表一格高处。`DirtPathBlock` 形状只有 15/16 高，视觉上"嵌入"地表，形成踩踏痕迹。

#### 12.1.5 沙漠村庄特殊处理

沙漠村庄街道 **零处理器**：模板中的 dirt_path 直接放在沙子上。视觉上平淡但风格一致（沙漠 = 沙地路）。

### 12.2 原版 vs Wandscape V1 对比

| 维度 | 原版 | Wandscape V1 |
|------|------|-------------|
| **拓扑生成** | Jigsaw 贪心扩展（非确定性） | MST 全局最短（确定性） |
| **路径单元** | NBT 模板（16×16 预设计） | 逐格 L 形程序化计算 |
| **路宽** | 3 格（人工设计在模板里） | 2 格（perpDx/Dz 算法） |
| **交叉口** | 专用 crossroad 模板（3×3 加宽） | IntersectionDetector + stone_bricks 替换 |
| **地形适应** | GravityProcessor 每块重算 Y | terrainHeightAt 逐格扫描 |
| **方块多样化** | RuleProcessor 规则（水面桥/磨损斑驳/沙漠配适） | 硬编码 dirt_path/stone_bricks |
| **NPC 执行** | 结构级一次放置（模板内多个方块） | for_each 逐格 place |
| **自然感** | 高（随机斑驳 + 地形贴合 + 水上桥） | 低（清一色 dirt_path） |

### 12.3 V2 设计：保留逐格原子操作 + 引入路面规则系统

> grill-me 8 问全部确认后的最终方案。

**核心思想**：保持 V1 的 MST 规划 + PathGenerator 逐格路径 + NPC 逐格 place 原子操作不变。引入数据驱动的路面规则系统替换硬编码方块选择。参考原版 RuleProcessor 单条件模式，根据路面下方地面方块类型和水体状态决定输出。

**方案决策汇总**：

| # | 决策 | 结论 |
|---|------|------|
| 1 | NPC 参与 | **A: 保留逐格 place 原子操作**，不引入模板级批量放置 |
| 2 | 规则条件 | **单条件**：`(groundBlock, isWater) → outputBlock + probability` |
| 3 | 交叉口 | **B: 同材质**，不换 stone_bricks。保留 IntersectionDetector 数据结构供未来 |
| 4 | 规则分层 | **core/road/surface/ 纯逻辑 + engine/road/ 读 MC 方块** |
| 5 | 路宽 | **3 格默认**，TOML 可配 `road.default_width = 3` |
| 6 | Tier | **保留但简化**：`default_block` + `rules` 引用，删除 `intersection_block` |
| 7 | buildTiles | 削除 `intersections` 参数，通过 `RoadConfig` 获取规则 |
| 8 | 匹配算法 | **顺序首条命中**，`chance` 滚动。优先级：水面 → 具体地面 → 通配 → default_block |

#### 12.3.1 core/road/surface/ — 纯逻辑层

```java
// RoadSurfaceRule.java — 单条规则 record
public record RoadSurfaceRule(
    String groundBlock,    // 输入条件：路面下方方块 ID，"*" = 通配
    boolean isWater,       // 输入条件：路面位置是否水体
    String outputBlock,    // 输出：用什么方块
    double chance          // 命中概率，1.0 = 必定
) {}

// RoadSurfaceRules.java — 规则集 + 匹配算法
public class RoadSurfaceRules {
    private final List<RoadSurfaceRule> rules;
    private final String defaultBlock;

    /**
     * Match against the rule set.
     * Priority: water rules → specific ground → wildcard → defaultBlock.
     *
     * @param groundBlock the block below the road tile
     * @param isWater     whether the road position contains water
     * @param rng         random source for chance rolling
     * @return the output block ID (never null)
     */
    public String match(String groundBlock, boolean isWater, RandomSource rng) {
        // Phase 1: water rules (highest priority)
        for (var rule : waterRules) {
            if (rng.nextDouble() < rule.chance()) return rule.outputBlock();
        }
        // Phase 2: specific ground match
        var groundList = rulesByGround.get(groundBlock);
        if (groundList != null) {
            for (var rule : groundList) {
                if (rng.nextDouble() < rule.chance()) return rule.outputBlock();
            }
        }
        // Phase 3: wildcard (*)
        for (var rule : wildcardRules) {
            if (rng.nextDouble() < rule.chance()) return rule.outputBlock();
        }
        // Phase 4: fallback
        return defaultBlock;
    }
}
```

#### 12.3.2 路面规则 JSON

```json
// data/wandscape/road_rules/dirt.json
{
  "default_block": "minecraft:dirt_path",
  "rules": [
    { "ground": "minecraft:grass_block", "water": false, "output": "minecraft:dirt_path", "chance": 0.85 },
    { "ground": "minecraft:grass_block", "water": false, "output": "minecraft:grass_block", "chance": 1.0 },
    { "ground": "minecraft:sand",        "water": false, "output": "minecraft:dirt_path", "chance": 1.0 },
    { "ground": "minecraft:stone",       "water": false, "output": "minecraft:cobblestone", "chance": 0.7 },
    { "ground": "minecraft:stone",       "water": false, "output": "minecraft:stone_bricks", "chance": 1.0 },
    { "ground": "minecraft:dirt",        "water": false, "output": "minecraft:dirt_path", "chance": 1.0 },
    { "ground": "*",                     "water": true,  "output": "minecraft:oak_planks", "chance": 1.0 }
  ]
}
```

#### 12.3.3 简化的 road_tiers.json

```json
// data/wandscape/road_tiers.json
{
  "tiers": {
    "dirt": {
      "default_block": "minecraft:dirt_path",
      "rules": "wandscape:road_rules/dirt"
    }
  }
}
```

`intersection_block` 删除。

#### 12.3.4 RoadBuilder.buildTiles 新签名

```java
public static JsonArray buildTiles(
    Level level, List<XZPoint> path, String tier,
    Collection<BoundingBox> buildingBounds,
    Set<XZPoint> occupiedTiles
)
```

`intersections` 参数削除。每格方块由 `RoadSurfaceRules.match(groundBlock, isWater, rng)` 决定。

#### 12.3.5 RoadConfig 新增方法

```java
public int getDefaultWidth() { return Config.ROAD_DEFAULT_WIDTH.get(); }
public RoadSurfaceRules getSurfaceRules(String tier) { ... }
```

#### 12.3.6 Config.java 新增

```toml
[road]
default_width = 3     # V2: 默认路宽
```

### 12.4 V1→V2 变更清单

| V1 项 | V2 变更 |
|-------|--------|
| `road_tiers.json` `intersection_block` | 删除 |
| `RoadBuilder.buildTiles` `intersections` 参数 | 删除 |
| `RoadBuilder.buildTiles` 硬编码 `surfaceBlock` | 改为 `RoadSurfaceRules.match()` |
| 路宽 2 格硬编码 | TOML `road.default_width = 3` |
| `IntersectionDetector` 控制方块选择 | 仅保留数据结构（供未来） |
| 无规则系统 | 新增 `core/road/surface/` + `data/wandscape/road_rules/` |

### 12.5 V2 实施待定

- [ ] `core/road/surface/RoadSurfaceRule.java` + `RoadSurfaceRules.java` + 单元测试
- [ ] `data/wandscape/road_rules/dirt.json` 默认规则
- [ ] `engine/road/RoadConfig` 新增 `getSurfaceRules()` + `getDefaultWidth()`
- [ ] `RoadBuilder.buildTiles` 削除 `intersections` 参数，集成规则匹配
- [ ] `RoadBuilder` 宽度改为读取 `default_width` 配置
- [ ] `Config.java` 新增 `ROAD_DEFAULT_WIDTH`
- [ ] `road_tiers.json` 删除 `intersection_block`，新增 `rules` 引用

---
## 十三、待定问题

- [ ] 路网可视化（V1 是否需要在管理面板显示道路？优先级低）
- [ ] 建筑移址时如何处理关联节点（V1 不支持建筑移址，预留即可）

---
## 十四、V3 方向：Jigsaw 模板扩展 + MST 约束

> 2026-06-21：用户提出 "MST 是数学最优，但自然的本质是冗余与低效"。
> 决定跳过 V2（仍实施 L 形逐格），直接进 V3（模板拼接 + 约束满足）。

### 14.1 设计哲学

**MST 的问题**：4 个建筑在正方形四角，MST 必然缺一条边。如果缺的那条正是村民最常走的，路网就反直觉。MST 不知道"人怎么走"，只知道"距离最短"。

**Jigsaw/WFC 的美学**：预制碎片拼接，允许冗余、分叉、不规则弯曲。原版村庄的路很少是数学意义上的最短线——它们绕来绕去，但看起来像是"自然走出来的"。

**MST 不被替换，而是降级为"连通性保证层"** — 它回答"哪些建筑之间必须有路"。但路怎么走、长什么样，由模板扩展决定。

### 14.2 关键技术验证

**结论：不直接复用原版 `JigsawPlacement.generateJigsaw()`**。

| 原版组件 | 可复用性 | 原因 |
|----------|---------|------|
| `StructureTemplateManager.getOrCreate()` | ✅ 运行时可用 | 公开 API，从资源包加载 |
| `StructureTemplate.placeInWorld()` | ✅ 运行时可用 | `ServerLevel` 实现 `ServerLevelAccessor` |
| `StructurePlaceSettings` + processors | ✅ 全部公开 | `GravityProcessor`, `RuleProcessor` 等 |
| `JigsawPlacement.generateJigsaw()` | ❌ | 需要 `Holder<StructureTemplatePool>` 从注册表获取；依赖 ChunkGenerator/randomState 等 worldgen 上下文 |
| `StructureTemplatePool` | ❌ | 需要注册到 `Registries.TEMPLATE_POOL`，仅在 datapack worldgen 阶段加载 |

**V3 策略**：写自己的轻量级模板扩展器（core 层定义规则，engine 层执行），只复用原版的**放置管道**（`StructureTemplate.placeInWorld()` + processors），不碰 Jigsaw 注册系统。

### 14.3 grill-me 决策汇总

| # | 决策 | 结论 |
|---|------|------|
| 1 | 模板尺寸 | **16×16 原版 + short (3×8) 短变体** |
| 2 | NPC 参与方式 | **复用 processBlockInfos() → tiles → road:build_segment 蓝图 → NPC 逐格 place** |
| 3 | 模板来源 | **直接抓取原版村庄街道 NBT** (village/*/streets/*.nbt) |
| 4 | jigsaw 块处理 | **不处理**：structure_void 本身透明无碰撞 |
| 5 | 模板缩放 | **保持 16×16**，加 straight_short (3×8 裁剪版) |
| 6 | 模板元数据 | **JSON 外挂**：entry/exit 坐标 + budget_cost + weight |
| 7 | 接入点 | **自动推算**：建筑半宽 + margin → 朝目标方向延伸 |
| 8 | 分叉 | **V3 第一版不做**：先主线连通，分叉后续 |

### 14.4 架构概览（修正版）

```
┌──────────────────────────────────────────────────────────────────────┐
│  core/road/                                                          │
│                                                                      │
│  MST（约束层）                                                        │
│    ├─ computeMST(buildings, threshold) → 连通对列表                   │
│    ├─ 输出：List<ConnectivityConstraint>                             │
│    │    { fromA, toB, budget }  // budget = manhattanDist × 1.3      │
│    └─ 不决定路径形状                                                  │
│                                                                      │
│  AccessPoint 计算                                                    │
│    ├─ computeAccessPoint(building, targetDirection) → XZPoint        │
│    └─ anchor + 半宽 + margin = 从建筑边缘伸出 1 格                    │
│                                                                      │
│  TemplateExpander（模板链生成器）                                      │
│    ├─ 输入：Constraint + 接入点 + 方向                                │
│    ├─ 输出：List<TemplatePlacement>                                  │
│    │    { templateId, pos, rotation }                                │
│    ├─ 算法：贪心前向扩展（无分叉）                                     │
│    │    ├─ 从接入点出发，朝目标                                    │
│    │    ├─ 每一步：加权随机选模板 → 确定旋转 → 推进到出口点            │
│    │    ├─ 允许 ±1 格横向抖动                                         │
│    │    ├─ budget 耗尽时停止                                          │
│    │    └─ 接近目标末尾时用 straight_short 或 cap 收尾               │
│    └─ 纯函数，不依赖 MC 类                                           │
│                                                                      │
│  OrganicRoadPlanner（编排器，取代 RoadPlanner）                        │
│    ├─ 输入：List<BuildingData> + RoadNetwork                         │
│    ├─ 步骤：                                                          │
│    │    1. computeMST(buildings) → 连通对 (A,B)                      │
│    │    2. 每个建筑 → computeAccessPoint()                           │
│    │    3. 每条边：TemplateExpander.expand(accessA, accessB, budget)  │
│    │    4. 合并所有 TemplatePlacement                                 │
│    └─ 输出：PlanResult { placements[], budgetUsed }                   │
└──────────────┬───────────────────────────────────────────────────────┘
               │ TemplatePlacement[]
┌──────────────▼───────────────────────────────────────────────────────┐
│  engine/road/                                                        │
│                                                                      │
│  RoadTemplatePlacer（取代 RoadBuilder.buildTiles）                     │
│    ├─ 对每个 TemplatePlacement：                                       │
│    │    1. StructureTemplateManager.get(vanillaTemplateId)            │
│    │    2. 构造 StructurePlaceSettings：                              │
│    │        ├─ setRotation(Rotation)                                 │
│    │        └─ addProcessor(GravityProcessor(WORLD_SURFACE, -1))      │
│    │           addProcessor(RuleProcessor(road_surface_rules))        │
│    │    3. processBlockInfos(level, template, settings)               │
│    │       → List<StructureBlockInfo> (已处理地形+规则的方块列表)     │
│    │    4. 转成 JsonArray tiles（格式与 V1 完全一致）                  │
│    │    5. 走现有 RoadTaskSource → road:build_segment 蓝图             │
│    └─ NPC 照常 for_each place 逐格建造                                │
│                                                                      │
│  模板 NBT（直接从原版 jar 抓取，放 data/wandscape/structure/road/）    │
│    ├─ straight.nbt       (原版 straight_01, 3×16 直道)               │
│    ├─ straight_short.nbt (裁剪版 3×8 短直道)                          │
│    ├─ corner.nbt         (原版 corner_01, L 型转角)                   │
│    ├─ crossroad.nbt      (原版 crossroad_01, 十字口)                  │
│    └─ cap.nbt            (新建 3×2 路尽头)                             │
│                                                                      │
│  模板元数据 JSON（data/wandscape/road_templates/）                     │
│    └─ 每种模板：id, template_ref, width, entries[], exits[],          │
│       budget_cost, weight                                             │
└──────────────────────────────────────────────────────────────────────┘
```

### 14.5 与原版 Jigsaw 的差异

| 维度 | 原版 Jigsaw | V3 自定义扩展器 |
|------|------------|----------------|
| **连接机制** | 模板内的 jigsaw block + pool 引用 | JSON 元数据的 entry/exit 坐标 |
| **池管理** | `StructureTemplatePool` 注册表 | 自定义 `RoadTemplatePool` 数据类 |
| **碰撞检测** | `Shapes.joinIsNotEmpty` (VoxelShape) | `BoundingBox` 简单矩形 |
| **方向匹配** | jigsaw 名称 + facing | 出口方向枚举 (NORTH/SOUTH/EAST/WEST) |
| **随机性** | `RandomSource` 控制池选取 | 同 |
| **终止** | terminator 模板 | cap 模板 or 接近目标 |
| **NPC 建造** | 世界生成时同步放置 | for_each place 逐格原子操作 |
| **模板来源** | 注册表动态加载 | 直接引用原版 NBT |

### 14.6 模板元数据模型

```json
// data/wandscape/road_templates/straight.json
{
  "id": "wandscape:road/straight",
  "template": "village/plains/streets/straight_01",
  "width": 3,
  "budget_cost": 16,
  "weight": 4,
  "entries": [
    { "dx": 7, "dz": 0, "facing": "south" }
  ],
  "exits": [
    { "dx": 7, "dz": 15, "facing": "south" }
  ]
}

// straight_short.json
{
  "id": "wandscape:road/straight_short",
  "template": "wandscape:road/straight_short",
  "width": 3,
  "budget_cost": 8,
  "weight": 2,
  "entries": [
    { "dx": 7, "dz": 0, "facing": "south" }
  ],
  "exits": [
    { "dx": 7, "dz": 7, "facing": "south" }
  ]
}

// corner.json — 基于原版 corner_01
{
  "id": "wandscape:road/corner",
  "template": "village/plains/streets/corner_01",
  "width": 3,
  "budget_cost": 16,
  "weight": 2,
  "entries": [
    { "dx": 7, "dz": 0,  "facing": "south" }
  ],
  "exits": [
    { "dx": 15, "dz": 7, "facing": "east" }
  ]
}

// crossroad.json
{
  "id": "wandscape:road/crossroad",
  "template": "village/plains/streets/crossroad_01",
  "width": 3,
  "budget_cost": 16,
  "weight": 1,
  "entries": [
    { "dx": 7, "dz": 0,  "facing": "south" },
    { "dx": 7, "dz": 15, "facing": "north" },
    { "dx": 0,  "dz": 7, "facing": "west" },
    { "dx": 15, "dz": 7, "facing": "east" }
  ],
  "exits": [
    { "dx": 7, "dz": 15, "facing": "north" },
    { "dx": 7, "dz": 0,  "facing": "south" },
    { "dx": 15, "dz": 7, "facing": "east" },
    { "dx": 0,  "dz": 7, "facing": "west" }
  ]
}
```

### 14.7 扩展算法（V3 第一版，无分叉）

```
expand(entryPos: BlockPos, target: XZPoint, budget: int, pool: RoadTemplatePool, obstacles: Set<BoundingBox>):
    result = []
    pos = entryPos
    heading = direction toward target
    
    while budget > 0 and distance(pos, target) > 8:
        // 1. 选模板：过滤出口方向与 heading 偏差 < 45° 的模板
        candidates = pool.filterTemplate(exitsFacingToward(heading))
        if candidates.isEmpty(): candidates = pool.straights()
        if candidates.isEmpty(): break
        
        template = weightedRandomPick(candidates)
        
        // 2. 确定旋转：使出口尽可能朝向目标
        rotation = bestRotationForExitToward(template, heading)
        
        // 3. 碰撞检测
        placedBbox = computeBoundingBox(template, pos, rotation)
        if collides(placedBbox, obstacles):
            // 横向抖动 ±1 格
            pos = pos + lateralJitter(heading)
            if still collides: continue
        
        // 4. 记录放置
        result.add(TemplatePlacement(template.id, pos, rotation))
        budget -= template.budgetCost
        
        // 5. 推进到出口位置
        exitLocal = template.exits[0].rotate(rotation).toWorldOffset()
        pos = pos + exitLocal
        heading = direction toward target  // 重新校准
    
    // 6. 收尾
    if distance(pos, target) <= 8 and budget > 0:
        result.add(TemplatePlacement("wandscape:road/straight_short", pos, rotation))
    
    return result
```

### 14.8 V1→V3 变更总览

| 组件 | V1 | V3 |
|------|-----|-----|
| **路径形状** | L 形 code-generated | 模板拼接，允许非直线 |
| **PathGenerator** | LShape(from, to) | **废弃**（模板决定形状） |
| **RoadPlanner** | computeMST + incrementalAdd + rebuild | **改为** `OrganicRoadPlanner`：MST 约束 + expand |
| **RoadBuilder** | buildTiles（逐格 JsonArray） | **改为** `RoadTemplatePlacer`：`processBlockInfos()` → tiles |
| **NPC 操作** | 逐格 for_each place | **不变**！仍逐格 place，只是 tile 来源变了 |
| **路宽** | 2 格硬编码 | 原版模板 3 格 |
| **方块选择** | 硬编码 dirt_path | RuleProcessor（水面桥 + 磨损斑驳） |
| **交叉口** | IntersectionDetector + stone_bricks | 模板自然重叠融合 |
| **持久化** | edges + path 点列表 | edges + 模板放置列表 |
| **接入点** | 无（直接从 anchor 算路径） | 自动推算 accessPoint |

### 14.9 V3 实施清单

| # | 任务 | 层 |
|---|------|-----|
| 1 | 抓取原版模板 NBT → `data/wandscape/structure/road/` | engine |
| 2 | 制作 straight_short.nbt (3×8 裁剪) + cap.nbt (3×2) | engine |
| 3 | 模板元数据 JSON（entry/exit + budget_cost + weight） | engine |
| 4 | `AccessPointCalculator`（从 BuildingData → 接入点） | core |
| 5 | `ConnectivityConstraint` record + `RoadTemplatePool` | core |
| 6 | `TemplateExpander`（贪心前向扩展，无分叉） | core |
| 7 | `OrganicRoadPlanner`（MST 约束 + expand） | core |
| 8 | 单元测试（AccessPoint, TemplateExpander, RoadTemplatePool） | core |
| 9 | `RoadTemplatePlacer`：`processBlockInfos()` → tiles → 现有管道 | engine |
| 10 | `RoadEventListener.processPlannedEdges` 改为新路径 | engine |
| 11 | `Config.java` 新增 `road.default_width` | shared |
| 12 | `road_tiers.json` 改为 `default_block` + `rules` 引用 | data |
| 13 | `road_rules/dirt.json` 路面规则 | data |
