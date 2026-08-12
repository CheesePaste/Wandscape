# overview/ — 俯瞰视角模式

`src/main/java/com/wsteam/wandscape/overview/`

## 职责

玩家以俯瞰（鸟瞰）相机观察殖民地，并可远程与建筑/实体交互。V 开面板默认进入。

## OverviewClientState

静态相机状态（camX/Y/Z、yaw、pitch）+ 目标（targetBlockPos/targetBuildingId/targetEntityId）+ 玩家旋转快照（prevYaw/prevPitch，每次进入刷新）+ 相机缓存标志 `aerialCacheValid`。

- `enterOverview`：每次刷新玩家快照；缓存失效（首次建立 / 玩家水平离开缓存锚点超 8 格）时算「角色后上方 45°」默认（camPitch=45、位置=脚位 − 水平前向×14、Y+14、camYaw=玩家朝向）并记锚点；否则原样保留 cam 字段（用户上次飞到的位置）。即「误触关闭原地重开」复用相机、「走远后重开」重算合适位置。
- `exitOverview`：suspend 语义——只落 active 标志 + 清瞬态准星目标，**保留 cam 字段与 aerialCacheValid**，下次进入按上方规则决定复用或重算。
- `hardReset`：清零全部含缓存标志与锚点，仅 `WandscapePanelState.reset()`（断开连接）调用，防止上一世界的相机位置泄漏到下一世界。

## OverviewFlightController

- 移动在 `RenderLevelStageEvent.AFTER_SKY` 处理（帧率无关 WASD/Space/Shift，速度固定走 `Config.panel.flySpeed`，不提供游戏内调速）；`MovementInputUpdateEvent` 清零玩家移动；滚轮沿视线推拉（缩放）；onMouseButtonPre 取消世界点击（豁免 top bar/sidebar 供面板 UI）。
- **渲染玩家实体**：`enter` 切第三人称（`CameraType.THIRD_PERSON_BACK`）、`exit` 恢复原相机类型；`onRenderLevelStage` 每帧 reconcile 相机类型（F5 在 `handleKeybinds` 早于 ClientTickPost 消费，drain 无效，必须每帧拉回）。
- **防玩家视角污染**：`onRenderLevelStage` 末尾每帧把玩家旋转（yRot/xRot/yRotO/xRotO + yBodyRot/yBodyRotO + yHeadRot/yHeadRotO）冻结回进入快照，抵消原版 `MouseHandler.turnPlayer`；`exit` 显式落定防退出瞬间甩头。两个「玩家视角」（原版 + 地面模式）共享这一份玩家旋转。
- **受伤自动退出**：`enter` 采样血量基线；`onClientTickPost` 检测血量下降沿或死亡 → `WandscapePanelState.closePanel()` 完全退出控制面板（保留空中相机缓存），回原版第一人称夺回操控。
- onClientTickPost raycast 方块 + 实体（WandscapeNpc/TouristEntity）；射线源按光标状态：抓取=准心（相机中心线）、抬起=鼠标射线。**交互仅在常态（OVERVIEW/NONE + 抓取）**：右键 → 实体 `OverviewEntityInteractPacket` / 建筑 `OverviewInteractPacket`。Build/Road/Stats 子模式不做建筑/NPC 交互（目标是建建筑不是交互）。投影 ghost 位置在 onRenderLevelStage `updateGhostPositionPerFrame` 同步。
- 进入音效 `OVERVIEW_ENTER` 在 `enter()` 播放（不在 OverviewClientState，保持状态 holder 纯净可测）。

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
