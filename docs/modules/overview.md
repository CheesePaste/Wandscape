# overview/ — 俯瞰视角模式

`src/main/java/com/wsteam/wandscape/overview/`

## 职责

玩家以俯瞰（鸟瞰）相机观察殖民地，并可远程与建筑/实体交互。V 开面板默认进入。

## OverviewClientState

静态相机状态（camX/Y/Z、yaw、pitch）+ 目标（targetBlockPos/targetBuildingId/targetEntityId）；`enterOverview` 从玩家上方 20 格、pitch=90 俯视。

## OverviewFlightController

- 移动在 `RenderLevelStageEvent.AFTER_SKY` 处理（帧率无关 WASD/Space/Shift）；`MovementInputUpdateEvent` 清零玩家移动；滚轮沿视线推拉、Ctrl 调速；onMouseButtonPre 全取消。
- onClientTickPost raycast 方块 + 实体（WandscapeNpc/TouristEntity），若投影激活则同步 ghost 位置。右键三分支：投影中 → pinGhost + 开 ConstructionScreen；实体 → OverviewEntityInteractPacket；建筑 → OverviewInteractPacket。

## OverviewRenderer

AFTER_TRIPWIRE_BLOCKS，画目标建筑/实体 12 边白色线框。

## 进入与切换

- V 开面板默认 OVERVIEW（WandscapePanelState.SubMode）；面板开时 G 键在 overview↔ground 切换（WandscapePanelController）。
- `MixinOverviewCamera`：@Inject Camera.setup TAIL，overview 激活时用 OverviewClientState 覆盖位置/旋转。

## 网络

- `OverviewInteractPacket`（C→S）：服务端 getBuildingIdAt + fallback getBuildingIdInInteractionZone，委派 `BuildingInteractHandler.handleInteraction`。
- `OverviewEntityInteractPacket`（C→S）：按 id 查实体，对 TouristEntity/WandscapeNpc 调 mobInteract(MAIN_HAND)。

## 与 projection 的关系

BUILD/ROAD_PROJECTION 子模式可叠加在 overview 相机上；退出 build 子模式回到纯 overview。
