# road/ — 道路系统

整合原 `core/road/`（纯算法）+ `engine/road/`（MC 实现）+ `road/`（编辑器）。

纯数据模型和算法在 `core/` 和 `algorithm/`，零 MC 依赖。MC 实现在 `engine/`。

## 子包一览

| 子包 | 职责 | 原位置 |
|------|------|--------|
| `core/` | 纯数据模型（RoadNetwork, RoadNode, RoadEdge...） | core/road/ |
| `algorithm/` | 算法（MST, PathGenerator, RoadPlanner...） | core/road/ |
| `engine/` | MC 实现层（RoadBuilder, RoadSavedData...） | engine/road/ |
| `client/` | 编辑器客户端渲染 + 状态 | road/client/（不变） |
| `network/` | 网络包 | road/network/（不变） |
| `server/` | 编辑器服务端处理 | road/server/（不变） |

## core/ — 数据模型（纯 Java）

- **`RoadNetwork.java`** — 图网络：RoadNode(建筑/路口/孤儿) + RoadEdge(路段+状态+已放置方块记录)。查询：findNearestNode / findNearestWalkablePathPoint / findEdgeBetween / findNodeAtXZ
- **`RoadNode.java`** — 道路节点（路口/端点）
- **`RoadEdge.java`** — 可变 state：status(PLANNED→BUILDING→COMPLETE)、placedBlocks(Set\<PathPoint\>)、segmentTaskIds、decorationTaskId
- **`RoadBuildingData.java`** — 建筑极简快照，道路规划输入
- **`RoadBlobCache.java`** — 建筑区块缓存（斑块 BFS 发现 + 编号缓存），供 RoadRouter 寻路
- **`DecorationPoint.java`** — 装饰物放置点纯数据类
- **`PathPoint.java`** — 三维路径点
- **`RouteSegment.java`** — 运输路线直线段（from→to）
- **`XZPoint.java`** — 二维 XZ 平面点

## algorithm/ — 算法（纯 Java）

- **`MstCalculator.java`** — Prim 算法，曼哈顿距离，建筑 ≥ 阈值触发
- **`MstEdge.java`** — 最小生成树边，按点列表索引引用（非 UUID）
- **`RoadPlanner.java`** — 编排：MST 计算 → diff → 分段 → enqueueEdge。支持 incrementalAdd 增量添加新建筑
- **`NetworkDiff.java`** — 对比新旧 MST → 保留/废弃/新建
- **`PathGenerator.java`** — L 形路径（先 X 后 Z），3D Y 插值 + switchback 斜坡。public 方法可被客户端复用做预览计算
- **`DecorationPlanner.java`** — 路段完成后扫描 → 灯柱+长椅位置
- **`RoadRouter.java`** — 道路路由器，负责寻路计算

## engine/ — MC 实现层

- **`RoadBuilder.java`** — 执行路径方块放置：挖 + 填 + 水面桥 + 调色板加权随机选取。产出 JsonArray tiles
- **`RoadSavedData.java`** — 路网持久化（Level SavedData）。NBT 序列化 edges + placedBlocks + nodes
- **`RoadEventListener.java`** — 订阅 build_complete / road_segment_complete。enqueueEdge() 为 public，供道路编辑器调用
- **`RoadTaskSource.java`** — 轮询发布 pending road segments + decorations 到 GlobalTaskPool
- **`RoadApiImpl.java`** — RoadApi 实现
- **`DecorationBuilder.java`** — 执行装饰放置（灯柱+长椅）
- **`RoadBlobExplorer.java`** — 探索世界中建筑区块，为道路规划提供输入数据
- **`RoadRoutingHelper.java`** — 路由辅助工具，包装 RoadBlobCache + RoadNetwork 完成寻路
- **`RoadConfig.java`** — 道路系统配置（读取 road_rules JSON）
- **`WandscapeTags.java`** — Minecraft 标签（TagKey）定义，用于道路方块识别

## 客户端 (client/)

- **RoadEditorClientState** — 道路编辑器客户端状态管理
- **RoadEditorRenderer** — 道路编辑器客户端渲染
- **RoadProjectionClientState** — 道路投影模式状态（IDLE/PLANNING 双态机，路径点队列）
- **RoadProjectionController** — 每 tick 输入处理器（左键放置路径点/后退删除/回车提交）
- **RoadProjectionRenderer** — 世界空间渲染（路网边按状态着色/节点/锚点光束/路径点标记）

## 网络包 (network/) — 6 个文件

| 包 | 方向 | 用途 |
|----|------|------|
| RoadBatchPublishPacket | C→S | 批量发布道路边 |
| RoadEdgePlanPacket | C→S | 规划单条道路边 |
| RoadEdgeRemovePacket | C→S | 移除单条道路边 |
| RoadEditorTogglePacket | C→S | 切换道路编辑器模式 |
| RoadNetworkSyncPacket | S→C | 同步道路网络数据 |
| RoadEditorNetwork | — | 道路编辑器网络通信管理 |

## 服务端 (server/)

- **RoadEditorHandler** — 服务端编辑器操作处理（removeEdge/planEdge/batchPublish）

## 依赖

- `op/api/AtomicOp`（通过 TaskSequence 引用）
- `task/engine/pool/TaskRequest`
- `core/types/GridPos`
- `shared/api/RoadApi`
