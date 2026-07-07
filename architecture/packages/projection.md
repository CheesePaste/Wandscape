# projection/ — 建筑投影（地面放置）系统

地面建筑放置模式 + 调试检查模式。V 键打开面板后，G 键可从俯瞰模式切换到本模式（`SubMode.BUILD_PROJECTION`）。调试模式自动随本模式启停，无需独立按键。

## 数据 (data/)

- **BuildingSlot** (record) — 投影模式建筑选择列表的轻量 DTO：id/displayName/category

## 客户端 (client/)

- **ProjectionClientState** — 建筑放置投影模式静态状态持有者（选中建筑槽位/幽灵预览位置/重叠检测）
- **ProjectionFlightController** — 每 tick 输入处理器（相机射线检测/右键放置）。**地面模式下**玩家正常行走，幽灵预览从玩家脚下射线；**俯瞰模式下**跳过所有处理
- **ProjectionRenderer** — 世界空间渲染器（幽灵建筑预览/身体锚点光束）
- **BuildingDebugClientState** — 建筑调试检查模式状态（独立于灵魂投影），进入 BUILD_PROJECTION 子模式时自动激活/退出
- **BuildingDebugController** — 每 tick 射线检测，发送 BuildingDebugRequestPacket（由 BuildingDebugClientState 活跃状态驱动）
- **BuildingDebugOverlay** — HUD 层渲染建筑调试信息面板 + 停工/重启/销毁按钮

## 网络包 (network/) — 8 个文件

| 包 | 方向 | 用途 |
|----|------|------|
| ProjectionEnterPacket | C→S | 请求进入建筑放置投影模式 |
| ProjectionEnterResponsePacket | S→C | 响应进入，携带建筑选择列表+身体锚点位置 |
| ProjectionExitPacket | C→S | 退出建筑放置投影模式 |
| ProjectionPlacePacket | C→S | 在投影模式中放置选定建筑（被 overview/ 和 projection/ 共用） |
| ProjectionNetwork | Server | 服务端投影玩家集合管理器 |
| BuildingActionPacket | C→S | 对建筑执行管理操作（停工/重启/销毁） |
| BuildingDebugRequestPacket | C→S | 请求指定位置建筑调试数据 |
| BuildingDebugResponsePacket | S→C | 返回建筑调试数据快照 |

## 依赖

- shared/registry/WandscapeApis
- building/internal/BuildingSavedData / BuildingConfigLoader
- overview/client/OverviewClientState（跳过检查）
- overview/network/OverviewInteractPacket（共用放置交互流程）
