# warehouse/ — 仓库模块（元素银行）

`src/main/java/com/wsteam/wandscape/warehouse/`

## 职责

殖民地资源存储：**元素银行**（ColonyItemBank，SavedData）管理 7 元素与物品（含 NBT），是 `ColonyResourceAccess` 边界的实现（NPC 领料/退料走这里）。

## ColonyItemBank

- `SavedData`，每世界存档 `wandscape_colony_items`，跨 colony 共享。
- 存物品 `storage`(UUID→ItemKey→Long) 与元素 `elementStorage`(UUID→ElementType→Long)；另有 `seededColonies`、`purchaseCounts`、**纯内存 `reservations`（不持久化）**。
- 方法：count/available(=count-reserved)/addElement/consumeElement/add/consume/reserve/commit/release/recordPurchase/isSeeded/markSeeded。NBT 键：colonies/items/elements/seeded/purchases。

## WarehouseManager

同时实现 `WarehouseApi` 与 `ColonyResourceAccess`：

- `extractItem` 每次最多 64 塞入目标 Container；`insertItems` 入仓并通知 resourceAddedListener。
- `hasEnough/reserve/commit/release/available/addResource` 对元素（ResourceId 无冒号）与物品分路处理；**元素无保留语义（reserve 仅检查）**。
- 缺货经 10s 冷却后发 `ResourceInsufficientEvent`。
- `findStorageColony` 取第一个 storage 建筑。

## 双标签 GUI（WarehouseScreen）

- **Overview**：ElementPanel（7 元素存量）+ 可搜索物品列表。
- **Exchange**：点击提货（`WarehouseActionPacket("withdraw")`）、点背包格存入（`"deposit_from_slot"`）。

## 网络

- `WarehouseActionPacket`（C→S）：服务端校验建筑类别 = storage；withdraw 用 api.extractItem；deposit 用**服务端手部物品**（防客户端伪造）；deposit_from_slot 限制 slot 0-35；操作后回发 WarehouseDataPacket 刷新。
- `WarehouseDataPacket`（S→C）：buildingPos/colonyId/items/elements。
- `WarehouseNotificationHandler`：订阅 `ResourceInsufficientEvent`，广播给所有在线玩家。

## 与其他模块关系

- 建筑施工/合成扣料、游客消费入账都走 ColonyItemBank。
- 缺货 → ResourceInsufficientEvent → WarehouseNotificationHandler（聊天提示）+ ResourceSupplySystem 补货。
- 物品飞行动画：ItemTransportManager（仓库→NPC）。
