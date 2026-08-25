# projection/ — 建筑投影（地面放置）系统

地面建筑放置模式 + 调试检查模式。V 键打开面板默认俯瞰模式，建造页签进入本子模式（叠加在俯瞰相机上）。

## 交互逻辑

1. 射线检测（摄像机→64格），使用缓存的建筑区域数据判断命中
2. 左键：任意状态旋转建筑 90°（gizmo 轴悬停/拖拽时除外，那是 gizmo 的拖拽）
3. 右键：切换固定虚影（`ProjectionClientState.pinned`）——仅用于 3D 坐标轴微调；不再打开施工屏
4. 施工：仅通过面板「✓ 提交施工」按钮打开 `ConstructionScreen`（无需先锁定）
5. 施工 UI：大 3D 预览 + X/Y/Z 坐标编辑 + Submit/Close；坐标编辑时世界虚影实时移动
6. Submit 复用 `ProjectionPlacePacket` 发建造任务，成功回建筑选择条；重叠位置客户端拒绝
7. Close / X：关闭 UI，虚影保持固定 → 玩家可走动观察（白色线框）；再次「✓ 提交施工」重开
8. 建筑选择条：单击切换手上建筑（`selectedSlotIndex`），双击收回鼠标进入放置（PLACING）

## 网络包

ProjectionEnterPacket/ResponsePacket/ExitPacket / ProjectionPlacePacket / BuildingActionPacket（停工/重启/销毁）/ BuildingDebugRequestPacket/ResponsePacket

## 客户端类

- `ProjectionClientState`：投影状态（projecting / selectedSlotIndex / ghostPos / pinned / rotationSteps / buildingSlots）
- `ProjectionFlightController`：地面放置输入（射线/旋转/固定）
- `OverviewFlightController`：俯瞰放置输入（共用 `ProjectionFlightController.openConstructionScreen`）
- `ProjectionRenderer`：ghost 渲染（重叠红色线框，固定金色线框）
- `ConstructionScreen`：施工 UI（`MedievalScreen` 300×230，`BuildingPreviewRenderer` 大预览 + EditBox 坐标 + Submit）

## 依赖

- shared/registry/WandscapeApis
- building/internal/BuildingSavedData / BuildingConfigLoader
- overview/client/OverviewClientState（跳过检查）
- overview/network/OverviewInteractPacket（共用放置交互流程）
