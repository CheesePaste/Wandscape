# road/ — 道路编辑器（客户端/网络/服务端）

顶层 road/ 包仅含客户端编辑器 + 网络通信 + 服务端处理。道路核心算法在 core/road/ 和 engine/road/ 中。

**相关包：**
- core/road/ — 纯数据/算法（RoadNetwork/MstCalculator/PathGenerator/NetworkDiff/RoadPlanner）
- engine/road/ — MC 实现层（RoadBuilder/RoadSavedData/RoadEventListener/RoadConfig/RoadRoutingHelper）
- shared/api/RoadApi — 公开 API 接口

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

- shared/api/RoadApi
- engine/road/RoadBuilder/RoadSavedData/RoadEventListener
- core/road/RoadNetwork/RoadPlanner/MstCalculator
