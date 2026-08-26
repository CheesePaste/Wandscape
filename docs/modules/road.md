# road/ — 道路模块（无图路由）

`src/main/java/com/wsteam/wandscape/road/`

## 职责

殖民地道路网络：玩家手动铺路/填平/销毁、样条编辑器、道路方块建造任务。**无路网图路由**——移动/运输不再算 Dijkstra，路面只作为方块条件（`wandscape:custom_roads` 标签）影响游客闲逛与物品飞行速度。核心数据模型纯 Java，MC 实现在 `road/engine/`。

## 核心数据模型（road/core/）

- `RoadNetwork`：图结构（**仅元数据**，不用于寻路），节点 map UUID→RoadNode、边 map UUID→RoadEdge。查询：findNearestNode（XZ 曼哈顿）、findNearestWalkablePathPoint（Y 可走性评分）、findEdgeBetween、updateNodeType、removeEdge/Node。
- `RoadNode(nodeId, GridPos, NodeType)`：NodeType INTERSECTION/ORPHAN/PLAYER。
- `RoadEdge`：可变边；from/to、tier、spline、cachedPath（spline.tessellate(0.5) 去重）、segmentTaskIds、status(PLANNED/BUILDING/COMPLETE)、width(默认3)、pendingSegmentCount、placedBlocks；分段完成用 recordSegmentComplete 按 UUID 去重计数。cachedPath 供游客出生/救援取路面锚点。
- `RoadTemplate`：样条沿线阵列生成蓝图（RoadTemplateBlock 列表）。
- `SplineModel`：纯 Java 3D 三次贝塞尔样条；evaluate(u)→CurveSample(position,tangent,u)、tessellate(step)。
- `SplinePoint`：anchor + controlPrev/Next + locked（对称锁定）。
- `SplineVec3`：纯 3D double 向量。
- 支撑：CurveSample/PathPoint(3D 带 Y)/XZPoint(2D)。

## 路由（已删除）

RoadRouter（buildGraph/Dijkstra/断点桥接/虫洞）、RoadBlobCache/RoadBlobExplorer（懒扫描 blob）、RoadRoutingHelper、RoadWalkPlanner、TransportRoute/SplineLeg（spline 运输路线）已整体删除——buildGraph 端点×全点 O(B²) 是服务端看门狗杀服根因。替代为**方块条件**：

- 物品运输（engine/transport/）：直线采样地表方块，≥1/2 是 `custom_roads` → 上路速度 5 tick/块平飞，否则离路 10 tick/块抛物线。
- NPC/游客移动：vanilla A* 直寻；游客漫游目标选路块、脚下非路面减速 ×0.8。

## MC 实现（road/engine/）

- `RoadApiImpl`：getNetwork/getEdges（从 overworld RoadSavedData）、removeEdge、`cancelEdge(colonyId, edgeId)`——撤段任务 + 全额退料（仅当施工已开始，≥1 footprint 格是道路材料方块）+ 直接 `setBlock(air)` 清已铺方块（不走 transform 执行器，无 salvage，不双退）+ 同步移除 edge 作幂等墓碑（防重复退料刷物品）。
- `RoadSiteData`：组装 Road 版 `ConstructionSiteDataPacket`（材料需求取 `RoadEdge.materialCounts`，供应状态沿用 `ColonyItemBank`/`ResourceSupplySystem` 口径，`completed=status==COMPLETE`），复用建筑的工地面板。
- `RoadSavedData`（`wandscape_roads`）：仅持久化边（spline 的 a/p/n/l、segmentTaskIds、materialCounts、placedBlocks、status、width），节点加载时重建；兼容旧 "path" 字段。
- `RoadSegmentListener`：订阅引擎 CustomEvent `road_segment_complete`→recordSegmentComplete，全完成置 COMPLETE。
- `WandscapeTags`：`Blocks.CUSTOM_ROADS` = wandscape:custom_roads（JSON 值：dirt_path/cobblestone/stone_bricks/gravel/sand 等），游客/运输的方块条件。

## 预设与 JSON（road/data/）

- `RoadPreset`：record(id, displayName, blocks[WeightedEntry])；Gson Deserializer 读 `id/display_name/blocks[{blockId,weight}]`；pickBlock(x,z) Splitmix64 确定性加权。DEFAULT_PRESETS：dirt_path、road(stone5,gravel3,stone_bricks2)、grass、water、cobblestone、gravel、oak_planks。
- `RoadPresetLoader`：单例，注册 `road_presets` 类别，get/getAll/registerFromJson。

## 道路放置流程（玩家操作）

- `RoadPlacementState`：静态状态（RoadPhase BAR/PLACING、ToolMode REPLACE/FILL/DESTROY_FILL/SPLINE、start/end/ghost、预设由 ImGui 工坊下拉选择）。enterBar/enterPlacing 为纯相位翻转（不清位置/工具/参考块/预设）；suspendProjection 落 projecting 标志、保留全部选取；exitProjection 全清（仅 `reset()` 登出时调）；clearAll 仅清 start/end（提交/显式撤销用，保留工具）。
- **选取缓存语义**：道路的起终点/工具/预设/参考块在会话内跨模式切换（切 tab/按 G/ESC/关面板/C 切相位）保留，仅登出或提交（ImGui 面板按钮发包后 clearAll）/撤销清空。
- `RoadPlacementController`：每 tick：左键按下设 start、拖动扩 end（Replace/Fill/DestroyFill），Backspace 栈式清 end→start、ESC 退出；ghost 位由 64 格射线取 block。**提交走 ImGui 道路制作工坊按钮**（各模式【下发…任务】按钮发包），无键盘提交键。
- `RoadPlacementRenderer`：世界预览（绿 start/红 end、黄色矩形表面填充、FILL 完整 3D 盒）。
- 网络包（C→S，均经 PlayerManualSource 发布任务，优先级10）：
  - `RoadPlacePacket`：XZ 矩形，表面 Y=MOTION_BLOCKING-1，preset.pickBlock，>10000 格拒绝，蓝图 `road:build_segment`。新手引导「铺设道路」步在发布时仅登记 `RoadPlaceAttribution`（按 segment_id），路实际建好（`road_segment_complete`）后才计数。
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

- `ItemTransportManager`：管理仓库→NPC 间飞行物品动画。直线飞行（无图）：沿直线采样地表方块（`custom_roads` 标签，上限 128 采样），≥1/2 是路面 → 上路 5 tick/块平飞，否则离路 10 tick/块抛物线；向 from 区块追踪玩家发 TransportStartPacket（from/to/duration/onRoad）；cancelForNpc 退回 ownsItem 已消耗物品；tickAll 到期 complete。
- `TransportItemEntity`：纯视觉 ItemEntity（shouldBeSaved=false）；客户端直线插值，离路段加 `y += sin(t*PI)*1.5` 跳跃弧；终点 discard。
- `TransportStartPacket`：S→C，handleClient 生成负 ID 实体。
- 渲染：`client/renderer/TransportItemEntityRenderer` 在物品上方画**金边暗灰气泡 + "xN" 数量**。
