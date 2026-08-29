# projection/ — 灵魂投影建造模式

`src/main/java/com/wsteam/wandscape/projection/`

## 职责

玩家以"灵魂投影"状态在俯瞰视角中选取、旋转、放置建筑，配合 V 面板使用。核心是**客户端放置预览 + 服务端放置验证**。

## 进入/退出

- V 键（`PROJECTION_TOGGLE`）开关面板；`openPanel()` 默认进入 OVERVIEW 子模式。点面板 tab0（BUILD_PROJECTION）→ enterSubMode 发 `ProjectionEnterPacket`。
- 服务端 `ProjectionEnterPacket.handleServer`：validateEntry 校验 → addProjecting → 回 ProjectionEnterResponsePacket；已投影则 toggle 关闭。客户端 handleClient granted 时 enterProjection + 自动开 building bar。
- 退出：ESC（面板未开）发 ProjectionExitPacket，或 exitCurrentSubMode。

## 玩家建造流程（新手引导与之一致）

1. **V 开面板** → 默认 OVERVIEW；**按 1**（或抬起鼠标——默认 Tab、可改绑——后点左侧建造图标）进入 BUILD_PROJECTION，下方出现建造列表（默认分类「全部」）。
2. 点卡片选中，**双击**卡片进入放置（列表关闭、建筑虚影出现）。
3. **虚影跟随准心**；按住右键拖动转视角、**左键**旋转建筑朝向。
4. 右侧面板（BuildPopPanel，右上角）显示坐标/朝向/状态，含【锁定】、【提交施工】和 **X-1/X+1/Y-1/Y+1/Z-1/Z+1 六个轴微调按钮**（每步一格，点击自动锁定——否则俯瞰下准星跟随下一帧会覆盖位移；重叠即时标红）；点【**提交施工**】→ 打开 ConstructionScreen → 点【**提交**】派发建造。
5. **放置政府建筑（市政厅）且该位置无殖民地** → 服务端自动发 `ColonyCreatePromptPacket`，**自动弹出命名界面**（TownHallCreateScreen），输入名称创建魔法小镇——**无需退出建造/右键**。

> 右键语义：**俯瞰（OVERVIEW）下点一下建筑 = 打开建筑界面**（BuildingInteractHandler）；俯瞰是**自由视角**（移动鼠标转视角、WASD 移动、滚轮缩放），右键拖动转视角只在建造/道路子模式。建造放置**不需要右键锁定**——锁定（右键/Enter）仅供 Gizmo 精确微调，非必选；放置走右侧面板【提交施工】。

## 客户端状态与控制

- `ProjectionClientState`：静态 volatile 字段——projecting/bodyAnchor/selectedSlotIndex/ghostPos/overlapDetected/pinned/rotationSteps(0-3, 90°CCW)/buildingSlots。enterProjection 重新装入服务端 slots、把 selectedSlotIndex 钳到合法区间、丢弃未 pin 的准星跟随位置，但**保留 rotation/pin/已选 slot**（会话内 suspend/resume 缓存）；播 PROJECTION_ENTER 音。suspendProjection 只落 projecting 标志、保留全部选取（切 tab/G/ESC/关面板用）；exitProjection 全清态（仅 `reset()` 登出时调）。`centerAnchor`（+ 纯逻辑 `BuildingCentering.rotatedCenterOffsets`）：把瞄准方块偏移到建筑**旋转后 x/z 包围盒中心**，y 保持瞄准高度不动——这样原点在角落（甚至包围盒外）的建筑也能以准心为中心放置；`rotate()` 绕建筑**当前中心**旋转（anchor 同步平移），建筑原地打转、中心不随旋转偏移。
- **选取缓存语义**：建筑/朝向/pin 在会话内跨模式切换（切 tab/按 G/ESC/关面板/开关建筑条）保留，仅登出（`WandscapePanelState.reset()`）或显式提交（ConstructionScreen.submit 后清虚影 + unpin）/撤销清空。建筑条的开/关不再重置分类/搜索/滚动。
- `ProjectionFlightController`：每 tick 输入处理，**仅当 projection 激活且 overview 未激活时运行**（overview 下 ghost 位置由 OverviewFlightController 每帧 raycast 更新）。64 格 raycast 求 ghost 落点 + overlap（落点经 `centerAnchor` 居中）；**左键 90° 旋转**建筑朝向；**右键仅切换 pin（锁定/解锁，供 Gizmo 精确微调）——不再打开施工界面**；**施工只能点右侧面板【提交施工】**（`BuildPopPanel` → `openConstructionScreen`）；面板未开时 ESC 退出；滚轮事件被取消。
- `ProjectionRenderer`：AFTER_TRIPWIRE_BLOCKS；用 BuildingGhostRenderer 渲染半透明幽灵方块，旋转后边界画白线框（pinned 非重叠）/红框（重叠）。ghost VBO 全局旋转用 `Axis.YP.rotationDegrees(-90°*steps)`，与 `rotateOffset`（x'=-z,z'=x）同向——若误用 +90°，steps=1/3（90°/270°）时 ghost 相对服务端建造/边界线框镜像偏位。
- `BuildPlacement`：**落点吸附**——射线命中草/花/蘑菇/树叶等不能立足的方块时，沿该列向下找第一个真正可立足的方块（草方块/泥土），把建筑锚点落回地面，避免被植物垫高一层；命中合规支撑（实心方块/墙体）时保持贴面放置（含贴墙/侧面）。判定口径与 `road/network/DestroyFillPacket` 找真实地面一致（跳过 `canBeReplaced()` 的可替换方块 + 空碰撞箱），额外显式排除 `minecraft:leaves` 标签（树叶碰撞箱完整但非建筑落脚点）。`resolve(BlockPos, Direction, SupportTest, int)` 为纯逻辑核心（无 MC 依赖，可单测）；`isStandable(Level, BlockPos)` 为 MC 世界实现。两个建造入口（ProjectionFlightController 步行模式 + OverviewFlightController 每帧落点）共用。

## ProjectionNetwork（服务端）

UUID 集合 projectingPlayers，addProjecting/removeProjecting/isProjecting/removeByUuid。`getAvailableBuildings`：过滤 blueprint!=null && !deprecated，按 categoryPriority（government 最优先→node 最后）+displayName 排序，firstFreeAvailable 经 buildingApi.isFirstFreeClaimed 计算。`validateEntry` 检查配置与 API 可用。

## BuildingSlot / BuildingRotation

- `BuildingSlot(id, displayName, category, firstFreeAvailable)` record。
- `BuildingRotation`：纯静态 90°CCW 工具——rotateOffset x'=-z,z'=x；rotateBlockStateString 委托 MC BlockState.rotate(CLOCKWISE_90)；rotateBoundary 用 8 角点重算 AABB；rotateBlockMapping 处理 "x,y,z" 键。

## ConstructionScreen

**由点右侧面板【提交施工】打开**（`BuildPopPanel` 的 Submit 按钮 → `ProjectionFlightController.openConstructionScreen`）。中世纪风格屏：3D 预览、X/Y/Z 输入框实时改 ghost 位置 + overlap 检查；【提交】发 ProjectionPlacePacket，unpin、关屏、重开 building bar；overlap/非法坐标拒绝。关闭不提交则 ghost 保持 pin。

> 历史：旧版"右键 pin 并打开 ConstructionScreen"已废弃——右键现在只切换 pin，施工入口改为面板【提交施工】。

## 服务端放置

`ProjectionPlacePacket.handleServer`：校验 buildingType → `BuildingApi.placeBuilding(anchorPos, typeId, rotationSteps)`，失败给错误消息；成功播放音效、刷新 BuildingAreaSync、投影 slots（first-free 被认领后）、推送教程进度；政府建筑且无 colony 时发 ColonyCreatePromptPacket。

## 调试功能（BuildingDebug*）

- `BuildingDebugController`：每 tick 自动 raycast（64 格），200ms 限速 + 按建筑 UUID 去重发 BuildingDebugRequestPacket。**仅俯瞰(OVERVIEW)子模式运行**（`WandscapePanelState.isInspectContext()`），操作型子模式（建造/道路/统计/任务）不巡检、不发包。
- `BuildingDebugClientState`：静态缓存 + 250ms 防抖窗口。
- `BuildingDebugOverlay`：渲染信息框（名称/类别/状态/三值）+ Repair/Undo 与 Destroy 两按钮，点击发 BuildingActionPacket。**仅俯瞰(OVERVIEW)子模式渲染/响应点击**（2026-08-28 收窄，避免操作型子模式误触建筑操作）。
- `BuildingDebugRequestPacket` 服务端读 BuildingSavedData，shop 类别叠加库存商品加成；响应含 `needsRepair`（`BuildCompleteListener.findDamagedBlocks` 判是否有任意缺失方块）+ `underConstruction`/`constructionStarted`（未完工建筑状态显示"等待材料/建造中"，禁用修复按钮）；`BuildingActionPacket` 处理 destroy/repair/cancel。
- > **注意**：BuildingDebugClientState.setActive 现由 V 面板开合驱动；旧注释 "G key" 已过时（G 键的 overview 切换已移除，面板常驻俯瞰相机）。

## network/ 包

S→C：ProjectionEnterResponsePacket / ProjectionSlotsRefreshPacket / BuildingDebugResponsePacket。C→S：ProjectionEnterPacket / ProjectionExitPacket / ProjectionPlacePacket / BuildingActionPacket / BuildingDebugRequestPacket。

## 与 overview 的关系

BUILD/ROAD_PROJECTION 子模式可叠加在 overview 相机上（enterSubMode 的 overview 分支保留相机），共享 ghost/ConstructionScreen；退出 build 子模式回到纯 overview。
