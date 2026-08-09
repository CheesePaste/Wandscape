# projection/ — 灵魂投影建造模式

`src/main/java/com/wsteam/wandscape/projection/`

## 职责

玩家以"灵魂投影"状态在俯瞰视角中选取、旋转、放置建筑，配合 V 面板使用。核心是**客户端放置预览 + 服务端放置验证**。

## 进入/退出

- V 键（`PROJECTION_TOGGLE`）开关面板；`openPanel()` 默认进入 OVERVIEW 子模式。点面板 tab0（BUILD_PROJECTION）→ enterSubMode 发 `ProjectionEnterPacket`。
- 服务端 `ProjectionEnterPacket.handleServer`：validateEntry 校验 → addProjecting → 回 ProjectionEnterResponsePacket；已投影则 toggle 关闭。客户端 handleClient granted 时 enterProjection + 自动开 building bar。
- 退出：ESC（面板未开）发 ProjectionExitPacket，或 exitCurrentSubMode。

## 客户端状态与控制

- `ProjectionClientState`：静态 volatile 字段——projecting/bodyAnchor/selectedSlotIndex/ghostPos/overlapDetected/pinned/rotationSteps(0-3, 90°CCW)/buildingSlots。enterProjection 重新装入服务端 slots、把 selectedSlotIndex 钳到合法区间、丢弃未 pin 的准星跟随位置，但**保留 rotation/pin/已选 slot**（会话内 suspend/resume 缓存）；播 PROJECTION_ENTER 音。suspendProjection 只落 projecting 标志、保留全部选取（切 tab/G/ESC/关面板用）；exitProjection 全清态（仅 `reset()` 登出时调）。
- **选取缓存语义**：建筑/朝向/pin 在会话内跨模式切换（切 tab/按 G/ESC/关面板/开关建筑条）保留，仅登出（`WandscapePanelState.reset()`）或显式提交（ConstructionScreen.submit 后清虚影 + unpin）/撤销清空。建筑条的开/关不再重置分类/搜索/滚动。
- `ProjectionFlightController`：每 tick 输入处理，**仅当 projection 激活且 overview 未激活时运行**。64 格 raycast 求 ghost 落点 + overlap；左键 90° 旋转；右键 pin 并打开 ConstructionScreen；面板未开时 ESC 退出；滚轮事件被取消。
- `ProjectionRenderer`：AFTER_TRIPWIRE_BLOCKS；用 BuildingGhostRenderer 渲染半透明幽灵方块，旋转后边界画白线框（pinned 非重叠）/红框（重叠）。

## ProjectionNetwork（服务端）

UUID 集合 projectingPlayers，addProjecting/removeProjecting/isProjecting/removeByUuid。`getAvailableBuildings`：过滤 blueprint!=null && !deprecated，按 categoryPriority（government 最优先→node 最后）+displayName 排序，firstFreeAvailable 经 buildingApi.isFirstFreeClaimed 计算。`validateEntry` 检查配置与 API 可用。

## BuildingSlot / BuildingRotation

- `BuildingSlot(id, displayName, category, firstFreeAvailable)` record。
- `BuildingRotation`：纯静态 90°CCW 工具——rotateOffset x'=-z,z'=x；rotateBlockStateString 委托 MC BlockState.rotate(CLOCKWISE_90)；rotateBoundary 用 8 角点重算 AABB；rotateBlockMapping 处理 "x,y,z" 键。

## ConstructionScreen

中世纪风格屏：3D 预览、X/Y/Z 输入框实时改 ghost 位置 + overlap 检查；Submit 发 ProjectionPlacePacket，unpin、关屏、重开 building bar；overlap/非法坐标拒绝。

## 服务端放置

`ProjectionPlacePacket.handleServer`：校验 buildingType → `BuildingApi.placeBuilding(anchorPos, typeId, rotationSteps)`，失败给错误消息；成功播放音效、刷新 BuildingAreaSync、投影 slots（first-free 被认领后）、推送教程进度；政府建筑且无 colony 时发 ColonyCreatePromptPacket。

## 调试功能（BuildingDebug*）

- `BuildingDebugController`：每 tick 自动 raycast（64 格），200ms 限速 + 按建筑 UUID 去重发 BuildingDebugRequestPacket。
- `BuildingDebugClientState`：静态缓存 + 250ms 防抖窗口。
- `BuildingDebugOverlay`：渲染信息框（名称/类别/状态/三值/队列）+ Repair/Shutdown-Restart/Destroy 按钮（自左到右），点击发 BuildingActionPacket。
- `BuildingDebugRequestPacket` 服务端读 BuildingSavedData，shop 类别叠加库存商品加成；响应含 `needsRepair`（`BuildCompleteListener.findDamagedBlocks` 判是否有任意损坏块）；`BuildingActionPacket` 处理 shutdown/restart/destroy/repair。
- > **注意**：BuildingDebugClientState.setActive 现由 V 面板开合驱动；旧注释 "G key" 已过时（G 键现为 overview 切换）。

## network/ 包

S→C：ProjectionEnterResponsePacket / ProjectionSlotsRefreshPacket / BuildingDebugResponsePacket。C→S：ProjectionEnterPacket / ProjectionExitPacket / ProjectionPlacePacket / BuildingActionPacket / BuildingDebugRequestPacket。

## 与 overview 的关系

BUILD/ROAD_PROJECTION 子模式可叠加在 overview 相机上（enterSubMode 的 overview 分支保留相机），共享 ghost/ConstructionScreen；退出 build 子模式回到纯 overview。
