# warehouse/ — 仓库系统

## 关键设计

元素存储（`Map<UUID, Map<ElementType, Long>>`）与物品存储（`Map<UUID, Map<ItemKey, Long>>`）在同一个 SavedData 中，保证事务原子性。独立于方块——方块破坏不丢失数据。

## 交互流

右键原版方块 → BuildingInteractHandler → posIndex O(1) → category=storage → ColonyItemBank snapshot → WarehouseDataPacket → 客户端 WarehouseScreen（双标签页：概览 + 兑换）

## 依赖

- shared/api/WarehouseApi / shared/event/ResourceInsufficientEvent
- shared/registry/WandscapeApis
- core/boundary/ColonyResourceAccess
