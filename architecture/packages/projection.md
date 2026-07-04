# projection/ — 建筑投影系统

灵魂出窍投影模式 + 调试检查模式。客户端按 V 键进入投影模式、G 键进入调试模式。

## 数据 (data/)

- **BuildingSlot** (record) — 投影模式建筑选择列表的轻量 DTO：id/displayName/category

## 客户端 (client/)

- **ProjectionClientState** — 灵魂投影模式静态状态持有者（选中建筑槽位/幽灵预览位置/重叠检测）
- **ProjectionFlightController** — 每 tick 输入处理器（相机射线检测/右键放置）
- **ProjectionRenderer** — 世界空间渲染器（幽灵建筑预览/身体锚点光束）
- **BuildingDebugClientState** — 建筑调试检查模式状态（独立于灵魂投影），G 键切换
- **BuildingDebugController** — 每 tick 射线检测，发送 BuildingDebugRequestPacket
- **BuildingDebugOverlay** — HUD 层渲染建筑调试信息面板 + 停工/重启/销毁按钮

## 网络包 (network/) — 8 个文件

| 包 | 方向 | 用途 |
|----|------|------|
| ProjectionEnterPacket | C→S | 请求进入灵魂出窍投影模式 |
| ProjectionEnterResponsePacket | S→C | 响应进入，携带建筑选择列表+身体锚点位置 |
| ProjectionExitPacket | C→S | 退出灵魂出窍投影模式 |
| ProjectionPlacePacket | C→S | 在投影模式中放置选定建筑 |
| ProjectionNetwork | Server | 服务端投影玩家集合管理器 |
| BuildingActionPacket | C→S | 对建筑执行管理操作（停工/重启/销毁） |
| BuildingDebugRequestPacket | C→S | 请求指定位置建筑调试数据 |
| BuildingDebugResponsePacket | S→C | 返回建筑调试数据快照 |

## 依赖

- shared/registry/WandscapeApis
- building/internal/BuildingSavedData / BuildingConfigLoader
