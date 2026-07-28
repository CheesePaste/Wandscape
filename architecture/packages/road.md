# road/ — 道路系统

整合 `core/road/`（纯算法）+ `engine/road/`（MC 实现）+ `road/`（编辑器）。

纯数据模型和算法在 `core/` 和 `algorithm/`，零 MC 依赖。MC 实现在 `engine/`。

## 子包

| 子包 | 职责 |
|------|------|
| `core/` | 纯数据模型（RoadNetwork / RoadNode / RoadEdge / SplineModel 等） |
| `algorithm/` | 算法（MST via Prim / L 形 PathGenerator / RoadPlanner / NetworkDiff / RoadRouter / DecorationPlanner） |
| `engine/` | MC 实现（RoadBuilder / RoadSavedData / RoadTaskSource / RoadApiImpl / RoadEventListener / RoadBlobExplorer 等） |
| `client/` | 编辑器客户端渲染 + 交互（RoadEditorClientState / SplineEditorClientState 含 SVG 导入导出等） |
| `network/` | 网络包 |
| `server/` | RoadEditorHandler |

关键数据设计：SplineModel（三次贝塞尔样条）/ SplineLeg（含起终点参数 u 及真弧长采样 `getApproxLength()`）。

## 依赖

- `op/api/AtomicOp`（通过 TaskSequence 引用）
- `task/engine/pool/TaskRequest`
- `core/types/GridPos`
- `shared/api/RoadApi`
