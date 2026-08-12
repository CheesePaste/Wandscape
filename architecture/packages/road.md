# road/ — 道路系统

整合 `core/road/`（纯算法）+ `engine/road/`（MC 实现）+ `road/`（编辑器）。

纯数据模型和算法在 `core/` 和 `algorithm/`，零 MC 依赖。MC 实现在 `engine/`。

## 子包

| 子包 | 职责 |
|------|------|
| `core/` | 纯数据模型（RoadNetwork / RoadNode / RoadEdge / SplineModel 等） |
| `algorithm/` | 算法（RoadRouter：物品/NPC 沿路寻路） |
| `engine/` | MC 实现（RoadSavedData / RoadApiImpl / RoadSegmentListener / RoadBlobExplorer 等） |
| `client/` | 客户端放置渲染 + 交互（RoadPlacementState / RoadPlacementController / RoadPlacementRenderer / SplineEditorImGui 统一道路制作工坊）。ROAD 栏工具全部统一收口至右侧 ImGui 面板：Replace（直线地表替换，走 `road:build_segment`）、Fill（两角点立方体填充，走 `terrain:fill_box`）、Destroy/Fill（铲平/垫平，走 `terrain:flatten`）、Spline（样条曲线编辑器，走 `SplineBuildPacket`） |
| `network/` | 网络包 |
| `server/` | RoadEditorHandler |

关键数据设计：SplineModel（三次贝塞尔样条）/ SplineLeg（含起终点参数 u 及真弧长采样 `getApproxLength()`）。

## 依赖

- `op/api/AtomicOp`（通过 TaskSequence 引用）
- `task/engine/pool/TaskRequest`
- `core/types/GridPos`
- `shared/api/RoadApi`
