# warehouse/ — 仓库系统

## 关键类

- **WarehouseManager** — 实现 WarehouseApi + ColonyResourceAccess。元素和物品通过 ColonyItemBank 分开存储
- **ColonyItemBank** — 物品 + 元素双存储（Level SavedData），独立于方块。方块破坏不丢失数据
- **WarehouseScreen** (client/) — 仓库 GUI，双标签页 Overview（元素储量+物品列表）+ Exchange（物品兑换），支持 `deposit_from_slot` 操作
- **WarehouseActionPacket** (network/) — C→S 仓库操作，新增 `slotIndex` / `deposit_from_slot` 字段
- **WarehouseNotificationHandler** — 监听 ResourceInsufficientEvent → 聊天栏通知在线玩家
- **WarehouseDataPacket** (network/) — 携带物品列表 + 元素快照的网络包

## 交互流

右键原版方块 → BuildingInteractHandler → posIndex O(1) → category=storage → ColonyItemBank snapshot + elementSnapshot → WarehouseDataPacket → 客户端开 WarehouseScreen

## 存储设计

元素存储 (`elementStorage: Map<UUID, Map<ElementType, Long>>`) 与物品存储 (`storage: Map<UUID, Map<ItemKey, Long>>`) 在同一个 SavedData 中，保证事务原子性。

## 依赖

- shared/api/WarehouseApi, shared/event/ElementChangedEvent, ResourceInsufficientEvent
- shared/registry/WandscapeApis
- core/boundary/ColonyResourceAccess（WarehouseManager 直接实现此接口）
