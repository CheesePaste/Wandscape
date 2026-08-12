# road/ — 道路模块 + 物品运输

`src/main/java/com/wsteam/wandscape/road/`

## 职责

殖民地道路网络：玩家手动铺路/填平/销毁、样条编辑器、NPC/物品沿路寻路、物品运输动画。核心数据模型纯 Java，MC 实现在 `road/engine/`。

## 核心数据模型（road/core/）

- `RoadNetwork`：图结构，节点 map UUID→RoadNode、边 map UUID→RoadEdge。查询：findNearestNode（XZ 曼哈顿）、findNearestWalkablePathPoint（Y 可走性评分）、findEdgeBetween、updateNodeType、removeEdge/Node。
- `RoadNode(nodeId, GridPos, NodeType)`：NodeType INTERSECTION/ORPHAN/PLAYER。
- `RoadEdge`：可变边；from/to、tier、spline、cachedPath（spline.tessellate(0.5)）、detailedPathCache、segmentTaskIds、status(PLANNED/BUILDING/COMPLETE)、width(默认3)、pendingSegmentCount、placedBlocks；分段完成用 recordSegmentComplete 按 UUID 去重计数。
- `RoadBlobCache`：玩家自建道路连通块懒缓存（BFS，`wandscape:custom_roads` 标签）；块边界=任一 XZ 四邻不在本块；MAX_BLOB_SIZE=2000。纯 core。
- `RoadTemplate`：样条沿线阵列生成蓝图（RoadTemplateBlock 列表）。
- `SplineModel`：纯 Java 3D 三次贝塞尔样条；evaluate(u)→CurveSample(position,tangent,u)、tessellate(step)。
- `SplinePoint`：anchor + controlPrev/Next + locked（对称锁定）。
- `SplineVec3`：纯 3D double 向量。
- `TransportRoute(List<SplineLeg>)`：含 NBT 序列化。
- 支撑：CurveSample/PathPoint(3D 带 Y)/XZPoint(2D)/SplinePointCache/RouteSegment(遗留)/SplineLeg(spline,uStart,uEnd,offRoad)。

## 算法（road/algorithm/）

- `RoadRouter`：物品/NPC 运输路线规划：图=边内相邻点 + 跨断点桥接(MAX_GAP_XZ=6, MAX_GAP_Y=3) + 玩家块质心"虫洞"；Dijkstra 权重按 tick（上路 5/格、离路 10/格）；planNpc 拒绝离路段 |dy|>1；绕路容忍系数 walker 3.0 / item 1.5；合并共线 SplineLeg。

## MC 实现（road/engine/）

- `RoadApiImpl`：getNetwork/getEdges（从 overworld RoadSavedData）、getBlobCache、removeEdge。
- `RoadSavedData`（`wandscape_roads`）：仅持久化边（spline 的 a/p/n/l、segmentTaskIds、placedBlocks、status、width），节点加载时重建；兼容旧 "path" 字段。
- `RoadSegmentListener`：订阅引擎 CustomEvent `road_segment_complete`→recordSegmentComplete，全完成置 COMPLETE。
- `RoadRoutingHelper`：planWithRoads/planNpcWithRoads：经 RoadApi 取网络+缓存→RoadBlobExplorer.scanAndCache→RoadRouter.plan/planNpc；异常静默返回空。
- `WandscapeTags`：`Blocks.CUSTOM_ROADS` = wandscape:custom_roads（JSON 值：purpur_block/nether_bricks/dark_prismarine）。
- `RoadBlobExplorer`：两点周围扫描(半径16,垂直4)，对 custom_roads 块 BFS discoverBlob(6方向)。

## 预设与 JSON（road/data/）

- `RoadPreset`：record(id, displayName, blocks[WeightedEntry])；Gson Deserializer 读 `id/display_name/blocks[{blockId,weight}]`；pickBlock(x,z) Splitmix64 确定性加权。DEFAULT_PRESETS：dirt_path、road(stone5,gravel3,stone_bricks2)、grass、water、cobblestone、gravel、oak_planks。
- `RoadPresetLoader`：单例，注册 `road_presets` 类别，get/getAll/registerFromJson。

## 道路放置流程（玩家操作）

- `RoadPlacementState`：静态状态（RoadPhase BAR/PLACING、ToolMode REPLACE/FILL/DESTROY_FILL/SPLINE、start/end/ghost、预设双击 400ms 确认）。enterBar/enterPlacing 为纯相位翻转（不清位置/工具/参考块/预设）；suspendProjection 落 projecting 标志、保留全部选取；exitProjection 全清（仅 `reset()` 登出时调）；clearAll 仅清 start/end（提交/显式撤销用，保留工具）。
- **选取缓存语义**：道路的起终点/工具/预设/参考块在会话内跨模式切换（切 tab/按 G/ESC/关面板/C 切相位）保留，仅登出或提交（Enter 发包后 clearAll）/撤销清空。
- `RoadPlacementController`：每 tick：右击设 start、左击设 end、Enter 按工具模式发包、Backspace/ESC 清理；ghost 位由 64 格射线取 block。
- `RoadPlacementOverlay`：预设网格 + 4 工具按钮 + 3D 方块预览。
- `RoadPlacementRenderer`：世界预览（绿 start/红 end、黄色矩形表面填充、FILL 完整 3D 盒）。
- 网络包（C→S，均经 PlayerManualSource 发布任务，优先级10）：
  - `RoadPlacePacket`：XZ 矩形，表面 Y=MOTION_BLOCKING-1，preset.pickBlock，>10000 格拒绝，蓝图 `road:build_segment`。
  - `FillBoxPacket`：完整 3D 立方（Y 钳制构建高度，跳基岩），`terrain:fill_box`。
  - `DestroyFillPacket`：参考块高/块，高出→break tiles，低→fill tiles 到地面，`terrain:flatten`。
  - `SplineBuildPacket`：tilesJson+splineJson；服务端建 RoadEdge 入 RoadSavedData（端点 3 格内吸附节点，否则建 ORPHAN 节点），发 `road:build_segment`。

## 样条编辑器

- `SplineEditorClientState`：静态状态（共享 SplineModel、EditMode ADD/EDIT、SelectionType、AxisDrag、自由/俯瞰相机、阵列参数 step/roll/pitch/yaw、模板注册表）；JSON 模板存读于 `config/wandscape/splines`。
- `SplineEditorController`：右拖相机、WASD 飞行、快捷键（ESC 退、H 指南、G 俯瞰、DEL 删点）；doBuildArray() 用切线建正交基 + roll/pitch/yaw 偏移沿曲线铺 RoadTemplate 块，去重成 tiles 发 SplineBuildPacket。
- `SplineEditorInputHandler`：点击选取（锚点 R0.25、手柄 R0.15 仅 EDIT 模式）、ADD 模式在方块表面加锚点(y+1)、轴向 Gizmo 拖拽（Shift 临时解锁对称）。REACH 128。
- `SplineEditorRenderer`：X-Ray 渲染样条(绿)、锚点/手柄盒、Gizmo 箭头、阵列预览真实方块模型。
- `SplineEditorOverlay`：右侧 HUD 面板，三页签（曲线编辑/阵列生成/模板工具），含"下发道路建造任务"按钮。
- 样条生成道路：doBuildArray 以 step(默认2.0) 细分为样条，经正交基矩阵投到世界坐标；服务端 SplineBuildPacket 建 tier="dirt" RoadEdge。
- `SplineEditorEnterPacket`：S→C 进入/退出编辑，由 `/wandscape spline edit|done` 触发。

## 物品运输（engine/transport/）

- `ItemTransportManager`：管理仓库→NPC 间飞行物品动画。send() 用预规划 route（空则直线回退），按 leg 累加 tick；向 from 区块追踪玩家发 TransportStartPacket；cancelForNpc 退回 ownsItem 已消耗物品；tickAll 到期 complete。速度：离路 10 tick/格、上路 5 tick/格。
- `TransportItemEntity`：纯视觉 ItemEntity（shouldBeSaved=false）；客户端逐腿样条插值，离路段加 `y += sin(t*PI)*1.5` 跳跃弧；终点 discard。
- `TransportStartPacket`：S→C，handleClient 生成负 ID 实体。
- 渲染：`client/renderer/TransportItemEntityRenderer` 在物品上方画**金边暗灰气泡 + "xN" 数量**。
