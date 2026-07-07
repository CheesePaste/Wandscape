# overview/ — 俯瞰（鸟瞰）视角模式

V 键打开面板时默认进入俯瞰视角。自由飞行摄像机、WASD 水平移动、滚轮缩放 FOV、准心射线检测建筑。

## 客户端 (client/)

- **OverviewClientState** — 俯瞰模式静态状态持有者（激活标志/摄像机位置旋转/FOV/目标建筑/鼠标跟踪）
- **OverviewFlightController** — 注册 6 个事件处理器：
  - `RenderLevelStageEvent.AFTER_SKY`：通过反射覆盖 Camera.setPosition()/setRotation() + 鼠标增量计算
  - `ClientTickEvent.Post`：WASD 物理移动 + 射线检测 + 右键边沿检测 + 消耗原版输入
  - `MovementInputUpdateEvent`：清零玩家移动输入（摄像机独立）
  - `InputEvent.MouseScrollingEvent`：FOV 缩放
  - `InputEvent.MouseButton.Pre`：取消所有鼠标按钮（右键在 tick 中处理）
  - `ViewportEvent.ComputeFov`：应用 FOV 缩放
- **OverviewRenderer** — 世界空间渲染：建筑包围盒顶部 1/3 高度线框环，注册于 `RenderLevelStageEvent.AFTER_TRIPWIRE_BLOCKS`

### 交互逻辑

1. 射线检测：从摄像机位置沿视线方向射出 64 格，使用 `BuildingAreaSyncPacket` 缓存的建筑区域数据判断命中
2. 右键交互分支：
   - Build 栏有选中建筑 → 发送 `ProjectionPlacePacket` 放置
   - 未选中建筑，射线命中建筑 → 发送 `OverviewInteractPacket` 交互
3. 左键：无效果（被消耗，不攻击/破坏）

### 玩家实体

玩家实体原地不动，仅通过反射覆盖摄像机位置/旋转。原版物品栏/热键栏不渲染（由 V 面板覆盖逻辑处理）。C 键抬升光标到面板层（同现有行为）。Esc 不退出俯瞰模式。

## 模式切换

| 操作 | 行为 |
|------|------|
| V | 打开面板 → 自动进入俯瞰模式（`SubMode.OVERVIEW`） |
| G（面板打开时） | 切换俯瞰/地面模式（`OVERVIEW ↔ BUILD_PROJECTION`） |
| G（面板关闭时） | 无效果 |
| V（再次） | 关闭面板 → 退出俯瞰模式 |
| C | 抬升/释放光标到面板 |

## 网络包 (network/)

| 包 | 方向 | 用途 |
|----|------|------|
| OverviewInteractPacket | C→S | 请求与俯瞰模式下射线命中的建筑交互，服务端代理到 BuildingInteractHandler 逻辑 |

## 依赖

- shared/network/BuildingAreaSyncPacket（建筑区域缓存）
- building/internal/BuildingConfigLoader（边界数据）
- building/internal/BuildingInteractHandler（交互逻辑复用）
- projection/client/ProjectionClientState（建筑放置投影状态）
- projection/network/ProjectionPlacePacket（放置建筑）
- shared/registry/WandscapeApis
