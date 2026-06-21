# 道路系统

文档编号：NEW-28
版本：3.3 — V1 回退 + V3 美学 + 端点推平
状态：已实施
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
| 9 | 高度/Y轴 | **端点线性插值**：PathGenerator 计算两端点间匀速坡度，RoadBuilder 无条件信任路径 Y | 两端一样高 → 推平中间起伏；两端有高差 → 均匀坡道 |
| 10 | 障碍处理 | **挖+填**：路径 Y 低于地形 → 挖出 2 格净空隧道；路径 Y 高于地形 → 填土方支撑 | 路是工程结构，地形是障碍物 |
| 11 | 道路 vs 建筑 | **独立系统**，不混入 BuildingState | 路无三数值/维护/队列 |
| 12 | 模块归属 | **core/road/ + engine/road/** | 不增加独立模块编号 |
| 13 | 方块配置 | TOML + JSON（默认硬编码可覆盖） | 数据驱动 |
| 14 | 建筑阈值 | 殖民地建筑数 ≥ N 才修路 | N 存 TOML，默认 3 |
| 15 | 触发时机 | **事件驱动**：`build_complete` + 命令手动触发 | 不轮询 |
| 16 | 任务优先级 | **10**（最低，低于 node gather=15） | 纯装饰 |
| 17 | 分段策略 | 每段 ≤ 16 格，L 形转折处优先切 | 多 NPC 可并行修路 |
| 18 | 分段入池 | **V1 一次性全入 GlobalTaskPool** | 简单，后续可改连锁触发 |
| 19 | 路口处理 | 检测交叉点 → 记录为隐式节点 → **同材质道路** | 视觉不区分路口/路面 |
| 20 | 蓝图 | `road:build_segment` DSL 蓝图 | 路径为绝对坐标，与 build:place_structure 的 offset 模式不同 |
| 21 | 持久化 | `RoadNetworkSavedData`（Level SavedData） | 复用已验证模式 |
| 22 | 玩家命令 | `/wandscape road info` + `/wandscape road rebuild` | V1 最小命令集 |
| 23 | 建筑拆除 | **V1 不断路**，保留在地面（标记节点 ORPHAN） | 以后可加手动拆路 |
| 24 | 建筑重建 | 新 buildingId 替换旧节点 | 坐标不变，UUID 更新 |
| 25 | 殖民地删除 | **V1 不清理路网** | 殖民地系统未完成，RoadSavedData 保留在 Level |
| 26 | 任务完成回调 | **事件 + SavedData**：`road_segment_complete` → RoadTaskSource 监听 → 更新 edge 进度 → 全段完成标记 COMPLETE | 命令实时查询进度 |
| 27 | core/engine 边界 | **纯数据传参**：core 方法签名使用 `RoadBuildingData` record（id + x/y/z + typeId），不依赖 MC 类 | 纯函数，易单元测试 |
| 28 | 路径生成 | **PathGenerator.LShape 返回 `XZPoint`**（2D），engine 层逐格算 Y + 选方块 | core 不碰 GridPos |
| 29 | NBT nodes 存储 | **不显式序列化 nodes**：加载时从 BuildingSavedData（建筑节点）+ path 交叉检测（路口节点）重建 | 避免冗余和位置不同步 |
| 30 | 路网重建策略 | **diff**：对比新 MST 与现有路网 → 保留/废弃/新建 | 不拆已建成的路 |
| 31 | 路宽 | **3 格**（TOML `road.default_width = 3`） | 匹配原版村庄道路宽度 |
| 32 | 方块选择 | **加权随机调色板**：`road.surfacePalette` TOML 字符串，位置哈希确定性随机选取 | 水面→planks 桥；陆地→调色板加权随机（默认石砖50%:安山岩25%:石头25%） |
| 33 | 地形适应 | **端点推平**：PathGenerator 计算路径 Y（端点间线性插值，|ΔY|≤1），RoadBuilder 执行 — 挖掉高处、填实低处 | 路是两点间最短可行走路径，地形是待改造的障碍 |
| 34 | 水上桥 | **不跳过水体**：水面位置抬高至水面高度，放置 planks | 路网跨越河流无缺口 |
| 35 | 建筑对齐 | **Building Y 提示**：路径首/末格使用建筑地板 Y，非地形 Y | 路接建筑入口不断层 |
| 36 | 切深度限制 | **`road.maxCutDepth`**（TOML，默认 8），超限记 WARN 日志 | V1 不硬阻止，供观察调参 |
| 37 | 填高度限制 | **`road.maxFillHeight`**（TOML，默认 6），超限记 WARN 日志 | 同上，填方过多可能是端点 Y 差不合理 |
| 38 | 道路装饰 | **边完成时触发**：edge COMPLETE → DecorationPlanner 扫描路径 → DecorationBuilder 展开多格 → 入池 | 与修路同三阶段流水线（Planner/Builder/蓝图） |
| 39 | 装饰类型 | **灯 + 长椅**：灯=lampPost+lampLight（2格高），长椅=stairs[facing]（1格） | V1 两种，后期 JSON 模板扩展 |
| 40 | 装饰位置 | **路肩外侧**：垂直于路径方向，偏移 `halfWidth+1` 格，左右交替 | 不占路面，视觉对称 |
| 41 | 装饰配置 | **TOML**：`road.decoration.*`（enabled/spacing/blocks） | 全部可调，0=关 |

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
│    └─ 纯函数：List<RoadBuildingData> → List<MstEdge>          │
│                                                              │
│  PathGenerator:                                              │
│    ├─ lShape3D(from, to): List<PathPoint>                        │
│    ├─ turnIndices(path): List<Integer>                       │
│    └─ 固定先X后Z                                              │
│                                                              │
│  RoadPlanner（编排器）:                                        │
│    ├─ computeMST(buildings, threshold) → RoadNetwork         │
│    │     每条 MST 边 → PathGenerator.lShape → RoadEdge       │
│    │     edges 直接带正确的 building UUID                     │
│    ├─ incrementalAdd(network, newBuilding) → RoadNetwork     │
│    │     找最近节点 → lShape3D → RoadEdge                       │
│    ├─ rebuild(network, buildings) → NetworkDiff              │
│    │     新MST vs 现有 → 保留/废弃/新建                        │
│    ├─ splitIntoSegments(path, maxLen) → List<List<PathPoint>>  │
│    └─ filterNewPath(path, occupied) → List<PathPoint>          │
│                                                              │
│  IntersectionDetector:                                        │
│    └─ detect(pathA, pathB) → List<XZPoint> 交叉点（仅数据结构）│
│                                                              │
│  DecorationPlanner:                                           │
│    ├─ planForEdge(edge, lampStep, benchStep, halfW)           │
│    │     沿路径采样 → 左右交替 → List<DecorationPoint>         │
│    └─ facingFromDelta(dx, dz) → cardinal ("north"/"south"/..) │
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
│    ├─ buildTiles(level, path, tier, buildingBounds,           │
│    │              occupiedTiles) → JsonArray                   │
│    │     └─ roadY = p.y()（无条件信任 PathGenerator 的 Y）      │
│    │     └─ 水面检测 → 抬高到水面 + oak_planks 桥               │
│    │     └─ 挖方: roadY < terrainTop → 清除 roadY+1..clearTop │
│    │     └─ 填方: roadY > terrainY → 填 dirt 从 terrainY..roadY-1│
│    │     └─ 3-wide 方块生成 + 加权随机调色板                          │
│    │          ├─ 水下 → oak_planks (桥)                             │
│    │          ├─ pickFromPalette(palette, x, z) 位置哈希确定性随机     │
│    │          ├─ 格式: "block=weight,..." (TOML road.surfacePalette) │
│    │          └─ 默认: stone_bricks=50,andesite=25,stone=25         │
│    └─ extractXZ(tiles) → Set<XZPoint>                         │
│                                                              │
│  RoadTaskSource (事件驱动):                                   │
│    ├─ 监听 build_complete → RoadPlanner 检查 → 生成分段      │
│    ├─ 监听 road_segment_complete → 更新 edge 进度            │
│    └─ pollThroughBuildings() 从 BuildingSavedData           │
│         提取 List<RoadBuildingData> 传给 RoadPlanner          │
│                                                              │
│  RoadEventListener:                                           │
│    ├─ 首次: RoadPlanner.computeMST → RoadBuilder.buildTiles  │
│    ├─ 增量: RoadPlanner.incrementalAdd → buildTiles           │
│    └─ 重建: RoadPlanner.rebuild → NetworkDiff → buildTiles    │
│                                                              │
│  RoadApiImpl:                                                │
│    ├─ getNetwork / getEdges / requestFullRebuild             │
│    └─ requestIncrementalUpdate                               │
│                                                              │
│  DecorationBuilder:                                          │
│    ├─ buildTiles(points, level, buildingBounds, config)       │
│    │     └─ 验证地形（solid地面、非水、非建筑）                  │
│    │     └─ lamp → fence post + lantern（2格）                 │
│    │     └─ bench → stairs[facing]（1格，面朝道路）             │
│    └─ columnIntersectsBuilding(x, yMin, yMax, z, boxes)       │
│                                                              │
│  RoadConfig:                                                 │
│    ├─ TOML [road] → threshold, segmentMaxLength, defaultWidth │
│    │                maxCutDepth, maxFillHeight, surfacePalette│
│    ├─ getSurfacePalette() → List<WeightedBlock>              │
│    └─ getDecorationConfig() → DecorationConfig record        │
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
│  String getRoadBlock(String tier)                             │
└──────────────────────────────────────────────────────────────┘
```

---

## 四、数据流

```
build_complete 事件
  │
  ▼
RoadEventListener.onBuildComplete()
  │
  ├─ 从 BuildingSavedData 提取 List<RoadBuildingData>
  │
  ├─ 首次（现有路网为空 + buildingCount ≥ threshold）
  │   └─ RoadPlanner.computeMST(buildings, threshold) → RoadNetwork
  │       └─ 每条 MST 边:
  │           PathGenerator.lShape3D(fromPt, toPt) → List<PathPoint>
  │           → RoadEdge(fromBuildingId, toBuildingId, path)
  │           → splitIntoSegments(path, 16)
          │                 → roadY = path[i].y（信任 PathGenerator）→ 挖/填/贴 → JsonArray[{pos, block}]
  │                 → JsonArray[{pos, block}]
  │                   → WorkItem("road:build_segment", {segment_id, tiles}, priority=10)
  │                     → GlobalTaskPool.addTask(TaskRequest)
  │                       → NPC 执行 for_each place
  │
  ├─ 增量（新建筑建成，路网非空）
  │   └─ RoadPlanner.incrementalAdd(network, newBuilding)
  │       └─ 找最近路网点 → lShape → RoadEdge
  │         → filterNewPath → split → buildTiles → 同上
  │
  └─ 重建（命令 /wandscape road rebuild）
      └─ RoadPlanner.rebuild(network, buildings) → NetworkDiff
          ├─ 保留边（MST 中有 + 路网已有）→ 不动
          ├─ 废弃边（MST 中无 + 路网已有）→ 标记 ORPHAN（不拆）
          └─ 新建边（MST 中有 + 路网无）→ 生成分段任务

road_segment_complete 事件
  │
  ▼
RoadEventListener.onSegmentComplete()
  │
  ├─ 查 RoadSavedData → 找到所属 edge
  │     ├─ 首次 COMPLETE 转换 → edge.setStatus(COMPLETE)
  │     └─ 触发装饰 → DecorationPlanner.planForEdge(edge, lampSpacing, benchSpacing, halfW)
  │           → List<DecorationPoint>
  │             → DecorationBuilder.buildTiles(points, level, buildingBounds, config)
  │               → JsonArray[{pos, block}]
  │                 → RoadTaskSource.enqueueDecoration(...)
  │                   → road:build_decoration 蓝图
  │                     → NPC for_each place
```

---

## 五、蓝图 DSL — `road:build_segment`

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
  {"pos": [101, 64, 200], "block": "minecraft:grass_block"},
  ...
  {"pos": [110, 64, 200], "block": "minecraft:cobblestone"}
]
```

每个 tile 的 `block` 由 `RoadBuilder.pickFromPalette()` 按位置哈希从 TOML 加权调色板中选取。

---

### `road:build_decoration` 蓝图

```json
{
  "id": "road:build_decoration",
  "params": {
    "decoration_id": "string",
    "edge_id": "string"
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
      "event": "decoration_complete",
      "data": {
        "decoration_id": "$decoration_id",
        "edge_id": "$edge_id"
      }
    }
  ]
}
```

tiles 由 `DecorationBuilder` 生成：
```json
[
  {"pos": [100, 64, 202], "block": "minecraft:oak_fence"},
  {"pos": [100, 65, 202], "block": "minecraft:lantern"},
  {"pos": [108, 64, -202], "block": "minecraft:oak_stairs[facing=east]"}
]
```

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
default_width = 3
max_cut_depth = 8
max_fill_height = 6
surfacePalette = "minecraft:stone_bricks=50,minecraft:andesite=25,minecraft:stone=25"

  [road.decoration]
  enabled = true
  lampSpacing = 8      # 路灯间距（格），0=不生成
  benchSpacing = 24    # 长椅间距
  lampPost = "minecraft:oak_fence"
  lampLight = "minecraft:lantern"
  benchBlock = "minecraft:oak_stairs"
```

---

## 八、命令

| 命令 | 功能 |
|------|------|
| `/wandscape road info` | 显示路网统计：节点数、边数、总路长、各边状态 |
| `/wandscape road rebuild` | 全局重算 MST，diff 现有路网，生成增删清单并创建建造任务 |
| `/wandscape roadtest <spacing> <count> [buildingType] [maxYVar]` | 测试命令：以环形放置 N 个建筑，发射 build_complete 事件触发修路全链路。maxYVar 控制建筑间随机高度差（默认0=平坦） |

---

## 九、事件

| 事件 | 触发方 | 监听方 | 用途 |
|------|--------|--------|------|
| `build_complete` (已有) | build:place_structure 蓝图 | RoadEventListener | 触发首次 MST / 增量连路 |
| `road_segment_complete` | road:build_segment 蓝图 | RoadEventListener | 更新 edge 状态为 COMPLETE |

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
| `DecorationPlannerTest` | 灯间距8→正确位置、长椅无重叠、双关=空、左右交替、面向道路方向、halfWidth偏移、短路径无装饰、facingFromDelta 四方向 |

### engine/shared 层（集成测试，留待 GameTest 或手动）

| 范围 | 内容 |
|------|------|
| RoadBuilder | 端点推平（挖/填/贴）、加权调色板随机选取验证、3-wide 宽度输出 JSON 结构验证 |
| RoadSavedData | NBT 写入→读取 round-trip、nodes 重建（从 BuildingSavedData + intersection）、空路网加载 |
| RoadConfig | TOML 默认值读取、surfacePalette 解析、decoration 配置读取 |
| DecorationBuilder | 地形验证（solid/水/建筑）、lamp→2格展开、bench→facing stairs、columnIntersectsBuilding |
| RoadApiImpl | 端到端：build_complete → RoadEventListener → RoadPlanner → RoadBuilder → TaskRequest → GlobalTaskPool |
| 装饰端到端 | road_segment_complete → RoadEventListener → DecorationPlanner → DecorationBuilder → TaskRequest → NPC放置 |

---

## 十一、V3 实验的教训

> 2026-06-21：V3 模板系统被放弃，回退到 V1 L-shape 路径 + V3 美学。

### V3 失败原因

| 问题 | 表现 |
|------|------|
| **旋转不匹配** | core 层用 CCW 旋转，engine 层用 CW 旋转。rotation≠0 时方块位置与链条连接点完全偏离 |
| **多链不连通** | MST 产生 N 条约束各自独立展开，在共享建筑处留有 ≤8 格间隙，无桥接机制 |
| **拓扑信息丢失** | 所有 MST 边合并为单条 RoadEdge，fromNode/toNode 用随机 UUID |
| **碎片化** | 10 个新文件（OrganicRoadPlanner, TemplateExpander, RoadTemplatePool, TemplateMeta, TemplatePlacement, EntryExit, CardinalFacing, ConnectivityConstraint, PlanResult, RoadTemplatePlacer）引入多层间接调用，调试困难 |
| **过度设计** | 模板 NBT 加载 + entry/exit 旋转匹配 + 贪心扩展 + budget 系统 — 用 400+ 行实现 L-shape 20 行就能做的事 |

### V3 被保留的部分

| 保留项 | 来源 | 位置 |
|--------|------|------|
| `pickFromPalette()` 加权调色板 | - (新增) | RoadBuilder |
| `Heightmap.WORLD_SURFACE` 地形参考（水面检测 + 挖填基准） | RoadTemplatePlacer | RoadBuilder |
| 3 格路宽 | 原版模板宽度 | `RoadConfig.getDefaultWidth()` |
| `road.default_width` TOML 配置 | V2 设计 | `Config.java` |

### V1 的优越性

- **确定性**：L-shape `先X后Z` 每次相同输入产出相同路径，无随机、无贪心
- **连通性保证**：MST → lShape → RoadEdge，每条边直接带 from/to building UUID，拓扑清晰
- **简单**：PathGenerator 20 行，RoadPlanner 70 行。整个 core 层 7 个文件
- **可测试**：零 MC 依赖，纯 JUnit 覆盖全部核心逻辑
