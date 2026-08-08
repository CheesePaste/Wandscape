# Block 1 — 扫描器大改 + interact_spot_marker 方块

> 依赖 Block 0 的 `InteractionConfig` + `interact_spots` schema + `BuildingConfig` 访问器。**不碰** `tourist/**`、`building/internal/**`（除 scanner 子包）。本块自包含，可单独一个 AI 开工。

## 目标

1. 扫描器适应新 schema：`touristInteractZones:List<BoundaryBox>` → `interactSpots:List<BlockOffset>`；shop/service 两套编辑字段 → 一个 `InteractionConfig` 编辑态。
2. 新增独立放置式方块 `interact_spot_marker`：放置=标记一个交互位，打掉=移除；扫描器导出时扫描 boundary 内该方块 → 生成 `interact_spots`。
3. 导出 JSON 走新 schema（`interaction` + `interact_spots`），删除 shop/service 导出分支。
4. 渲染交互位为点标记。

## 负责文件

| 文件 | 动作 |
|---|---|
| `building/scanner/BuildingScannerBlockEntity.java` | 换字段/编辑态/NBT |
| `building/scanner/ScannerMode.java` | 当前是死代码枚举（BOUNDARY/DOOR/INTERACT/META/EXPORT），可废弃或复用 |
| `building/scanner/client/BuildingScannerScreen.java` | 删 ZoneRow 六坐标输入，改交互位列表 + marker 提示 |
| `building/scanner/network/BuildingScannerExportPacket.java` | 导出新 schema + 扫描 marker 方块 |
| `building/scanner/client/BuildingScannerRenderer.java` | 交互位画点标记（取代绿色 AABB） |
| `building/scanner/SurvivalScannerBlockEntity.java` / `SurvivalScannerScreen.java` | 沿用（category 锁 custom，interaction/spots 返回空） |
| `building/scanner/network/BuildingScannerSyncPacket.java` | 若有字段需同步则跟 |
| `Wandscape.java`（或新建 registry） | 注册 `interact_spot_marker` 方块+物品 |
| 资源文件 | blockstate/model/lang/recipe/物品模型/创造标签 |

## 具体改动

### 1. BuildingScannerBlockEntity

当前（617 行）关键点：
- `touristInteractZones: List<BoundaryBox>`（:88），增删改 clear（:253-269），NBT key `tourist_interact_zones`（:40,:411,:500）。
- shop 编辑态：`ShopGoodData` record（:589）+ `getShopGoods/getShopProfitRate/getShopInteractionDurationTicks`。
- service 编辑态：`getServiceEnergyPerUse/getServiceElementOutput/getServiceMaxOccupancy/getServiceInteractionDurationTicks`。

改为：
- `touristInteractZones` → `interactSpots: List<BlockOffset>`；NBT key `interact_spots`；新增 `addSpot/removeSpot/clearSpots/getInteractSpots`。
- shop/service 两套 → 一个 `InteractionConfig` 编辑态：字段 `interactionEnergy/interactionTradeGoods/interactionProfitRate/interactionOutput/interactionBeds/interactionDurationTicks`（或一个 `InteractionEdit` record 聚合）；NBT key `interaction_*`。保持与 `shared/data/InteractionConfig` 字段一一对应。
- `detectBoundaryFromCorners/detectDoors/getWorldMin/getWorldMax` 不动。

### 2. interact_spot_marker 方块（新）

- 放置式方块，视觉小标记（非透明，类比 CORNER 块）。类可放 `building/scanner/InteractSpotMarkerBlock.java`。
- 玩家放置到目标位置 = 标记一个交互位（相对 anchor 偏移由扫描器导出时计算）；打掉 = 移除。
- **注册**：block+item（`Wandscape.java` 或新 registry 类），blockstate/model（简单方片模型）、lang（中英）、recipe（配 vanilla 合成，如木棍+线，或仅创造标签）、物品模型、创造标签。
- 导出时 BuildingScannerExportPacket 扫描 boundary 内该方块 → 转相对 anchor 偏移 → `interact_spots`；该方块**跳过 pattern**（像扫描器方块一样）。

### 3. BuildingScannerScreen

当前（client/BuildingScannerScreen.java）：
- `CATEGORIES`（:47-50）：`"basic","government","node","storage","workstation","crafting_station","potion_station","tavern","shop","service","decoration","wonder","altar","custom"` → **把 `"shop","service"` 换成 `"interact"`**。
- `ZoneRow`（:571-602）：6 个 min/max 坐标输入框 + `+ 添加区域`（:282-287）→ **删除**。改为「交互位列表」：显示 `interact_spots` 的点（来源：扫描器收集的 marker 方块 + 手动输入/删除），并加提示文案「放置 interact_spot_marker 标记交互位」。
- shop/service 编辑区（:417/:453）→ 合并为一个 `interaction` 编辑区（energy/trade goods/profit_rate/output/beds/duration_ticks）。
- NBT 载入（:1015-1027）同步。

### 4. BuildingScannerExportPacket

当前导出（network/BuildingScannerExportPacket.java）：
- `tourist_interact_aabb` JSON 数组（:229-246）→ 换成：扫描 boundary 内 `interact_spot_marker` 方块，收集相对偏移 → `interact_spots` JSON 数组 `[[x,y,z],...]`。
- shop 分支（:259-273）、service 分支（:276-288）→ 删除，统一导出 `interaction` 块（energy/trade/output/beds/duration_ticks）。
- 其余（pattern/block_mapping/block_nbt/comfort/magic/wonder/maintenance/queue/unlock/boundary/door_offset/blueprint）不动。

### 5. BuildingScannerRenderer

当前画绿色交互区（getTouristInteractZones，:55-61）→ 改为在 `interactSpots` 各点画小方块/粒子标记。

### 6. SurvivalScanner

`SurvivalScannerBlockEntity` 当前覆盖 `getTouristInteractZones()/getShopGoods()/getServiceElementOutput()` 返回空（:28-50）→ 改为覆盖 `getInteractSpots()/getInteraction()` 返回空。

## Done 判定

1. `./gradlew build` 绿。
2. 创造扫描器：可编辑 `interaction`（energy/trade/output/beds/duration），可放置 marker 方块并在列表看到点。
3. 导出 JSON 为新 schema（`interaction` + `interact_spots`），无 `shop`/`service`/`tourist_interact_aabb`；即时可建。
4. 渲染交互位为点标记。
5. SurvivalScanner 不崩。
