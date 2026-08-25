# overview/ — 俯瞰（鸟瞰）视角模式

V 键打开面板时默认进入。自由飞行摄像机、WASD 水平移动、滚轮缩放 FOV、准心射线检测建筑。玩家实体原地不动，通过反射覆盖摄像机位置/旋转。

## 交互逻辑

1. 射线检测：摄像机沿视线方向 64 格，使用缓存的建筑区域数据
2. 右键分支：Build 栏有选中 → 发送 PlacePacket 放置；未选中命中建筑 → interact
3. 左键：被消耗

## 模式切换

| 操作 | 行为 |
|------|------|
| V | 打开面板 → 进入俯瞰（SubMode.OVERVIEW） |
| V（再次） | 关闭面板 → 退出 |

## 依赖

- building/internal/BuildingConfigLoader + BuildingInteractHandler
- projection/client/ProjectionClientState + network/ProjectionPlacePacket
- shared/registry/WandscapeApis
