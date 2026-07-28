# projection/ — 建筑投影（地面放置）系统

地面建筑放置模式 + 调试检查模式。V 键打开面板后，G 键从俯瞰模式切换过来（`SubMode.BUILD_PROJECTION`）。

## 交互逻辑

1. 射线检测（摄像机→64格），使用缓存的建筑区域数据判断命中
2. 右键：Build 栏有选中建筑 → PlacePacket 放置；未选中+命中建筑 → 交互
3. 左键：被消耗，不攻击/不破坏

## 网络包

ProjectionEnterPacket/ResponsePacket/ExitPacket / ProjectionPlacePacket / BuildingActionPacket（停工/重启/销毁）/ BuildingDebugRequestPacket/ResponsePacket

## 依赖

- shared/registry/WandscapeApis
- building/internal/BuildingSavedData / BuildingConfigLoader
- overview/client/OverviewClientState（跳过检查）
- overview/network/OverviewInteractPacket（共用放置交互流程）
