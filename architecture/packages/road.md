# road/ — 道路系统（无图路由）

整合 `core/road/`（纯算法）+ `engine/road/`（MC 实现）+ `road/`（编辑器）。

纯数据模型在 `core/`，零 MC 依赖。MC 实现在 `engine/`。

## 子包

| 子包 | 职责 |
|------|------|
| `core/` | 纯数据模型（RoadNetwork / RoadNode / RoadEdge / SplineModel 等） |
| `engine/` | MC 实现（RoadSavedData / RoadApiImpl / RoadSegmentListener / WandscapeTags）。RoadApiImpl 含 `cancelEdge(colonyId, edgeId)`：撤段任务 + 全额退料（仅当施工已开始）+ 直接 setBlock(air) 清已铺方块（不走执行器、不触发 salvage）+ 同步移除 edge 作幂等墓碑（防重复退料刷物品）。`RoadSiteData` 组装 Road 版 `ConstructionSiteDataPacket`（复用建筑的工地 UI） |
| `client/` | 客户端放置渲染 + 交互（RoadPlacementState / RoadPlacementController / RoadPlacementRenderer / RoadConstructionGhost 施工虚影 / RoadGhostRenderUtil 虚影渲染工具 / RoadStudioOverlay 原生道路制作工坊覆盖层）。ROAD 栏工具全部统一收口至右侧原生面板：Replace（直线平面替换，按起点高度，走 `road:build_segment`）、Fill（两角点立方体填充，走 `terrain:fill_box`）、Destroy/Fill（铲平削高/垫平，走 `terrain:flatten`，支持【垫平洼地】开关并提示预计消耗与拆除产出）、Spline（样条曲线编辑器，走 `SplineBuildPacket`）。在 Replace 与 Fill 中均支持【固定预设】与【程序化混合调色板】（可自定义添加方块、配置 1~10 权重比例，按确定性空间哈希加权随机填充）。施工虚影由服务端 `RoadAreaSyncPacket` 同步非 COMPLETE 道路边驱动（镜像建筑 ConstructionGhostRenderer）。准心右键施工中道路时 `RoadAreaSyncPacket.raycastUnderConstruction` 命中 footprint → 发 `RoadInteractPacket` 打开工地面板（无白框高亮；建筑仍保留白框） |
| `network/` | 网络包（全 playToServer：RoadPlacePacket / SplineBuildPacket / SplineEditorEnterPacket / DestroyFillPacket / FillBoxPacket / RoadInteractPacket（右键施工路→开面板）/ RoadWithdrawPacket（面板撤回道路））。施工同步包 `RoadAreaSyncPacket` 在 `shared/network/`（playToClient，镜像 BuildingAreaSyncPacket，含 `raycastUnderConstruction` + `RoadBoxHit`） |
| `server/` | RoadEditorHandler |

**无路由图**：RoadRouter（Dijkstra 图路由）与 RoadBlobCache/RoadBlobExplorer（懒扫描 blob）已删除（O(B²) buildGraph 曾致看门狗杀服）。道路只提供两类价值：
1. **元数据**：RoadEdge.cachedPath 供游客出生点/救援点（TouristSpawnSystem/TouristTeleport）与成就计数（AchievementService）使用。
2. **方块条件**：`wandscape:custom_roads` 标签被游客闲逛（目标选路块/脚下判路减速）与物品运输（直线采样判速）读取——O(1)/O(采样数)，无图。

关键数据设计：SplineModel（三次贝塞尔样条，编辑器 + RoadEdge 路径生成）。

## 依赖

- `op/api/AtomicOp`（通过 TaskSequence 引用）
- `task/engine/pool/TaskRequest`
- `core/types/GridPos`
- `shared/api/RoadApi`
