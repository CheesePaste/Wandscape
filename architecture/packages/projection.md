# projection/ — 建筑投影（地面放置）系统

地面建筑放置模式 + 调试检查模式。V 键打开面板后，G 键从俯瞰模式切换过来（`SubMode.BUILD_PROJECTION`）。

## 交互逻辑

1. 射线检测（摄像机→64格），使用缓存的建筑区域数据判断命中
2. 右键：Build 栏选中建筑时 → 固定虚影（`ProjectionClientState.pinned`，ghost 不再跟随准星，渲染金色线框）
3. 固定后：左键撤销回手持跟随；右键 → `ConstructionScreen`（施工 UI：大 3D 预览 + X/Y/Z 坐标编辑 + Submit）
4. Submit 复用 `ProjectionPlacePacket` 发建造任务，成功回建筑选择条；重叠位置客户端拒绝
5. 左键（未固定）：旋转建筑 90°；固定后不可旋转（先撤销再转）
6. 建筑选择条：单击切换手上建筑（`selectedSlotIndex`），双击收回鼠标进入放置（PLACING）

## 网络包

ProjectionEnterPacket/ResponsePacket/ExitPacket / ProjectionPlacePacket / BuildingActionPacket（停工/重启/销毁）/ BuildingDebugRequestPacket/ResponsePacket

## 客户端类

- `ProjectionClientState`：投影状态（projecting / selectedSlotIndex / ghostPos / pinned / rotationSteps / buildingSlots）
- `ProjectionFlightController`：地面放置输入（射线/固定/施工 UI 入口）
- `OverviewFlightController`：俯瞰放置输入（共用 `ProjectionFlightController.openConstructionScreen`）
- `ProjectionRenderer`：ghost 渲染（重叠红色线框，固定金色线框）
- `ConstructionScreen`：施工 UI（`MedievalScreen` 300×230，`BuildingPreviewRenderer` 大预览 + EditBox 坐标 + Submit）

## 依赖

- shared/registry/WandscapeApis
- building/internal/BuildingSavedData / BuildingConfigLoader
- overview/client/OverviewClientState（跳过检查）
- overview/network/OverviewInteractPacket（共用放置交互流程）
