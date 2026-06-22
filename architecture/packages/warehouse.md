# warehouse/ — 仓库系统

## 关键类

- **WarehouseManager** — 实现 WarehouseApi + ColonyResourceAccess(引擎资源接口)。仓储逻辑
- **ColonyItemBank** — 物品存储（Level SavedData），独立于方块。方块破坏不丢失物品
- **WarehouseMenu** — ContainerMenu，仓库 GUI 后端
- **WarehouseScreen** (client/) — 仓库 GUI 前端
- **WarehouseNotificationHandler** — 监听 ResourceInsufficientEvent → 聊天栏通知在线玩家
- **WarehouseDataPacket** (network/) — 仓库数据同步网络包

## 注册

- 菜单：`wandscape:warehouse` (MenuType)

## 交互流

右键原版方块 → BuildingInteractHandler → posIndex O(1) → category=storage → ColonyItemBank snapshot → WarehouseMenu GUI

## 依赖

- shared/api/WarehouseApi, shared/event/ElementChangedEvent, ResourceInsufficientEvent
- shared/registry/WandscapeApis
- core/boundary/ColonyResourceAccess（WarehouseManager 直接实现此接口）
