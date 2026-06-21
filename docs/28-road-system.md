# 道路系统

文档编号：NEW-28
版本：2.0-draft
状态：V1 已实施，V2 设计中（混合原版 StructureTemplate 管道）
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

- [ ] V2 路面规则系统实施（见 12.5 清单）
- [ ] 路网可视化（V1 是否需要在管理面板显示道路？优先级低）
- [ ] 建筑移址时如何处理关联节点（V1 不支持建筑移址，预留即可）
