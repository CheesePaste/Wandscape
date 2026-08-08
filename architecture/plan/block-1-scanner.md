# Block 1 — 扫描器大改 + interact_spot_marker 方块

> 依赖 Block 0 的 `interact_spots` schema + 四类模式预设块（shop/service/relax/atm）+ `BuildingConfig` 访问器。**不碰** `tourist/**`、`building/internal/**`（除 scanner 子包）。本块自包含，可单独一个 AI 开工。

## 目标

1. 扫描器适应新 schema：`touristInteractZones:List<BoundaryBox>` → `interactSpots:List<BlockOffset>`；shop/service 编辑字段**保留**，新增 relax/atm 编辑字段（四类模式预设各自编辑）。
2. 新增独立放置式方块 `interact_spot_marker`：放置=标记一个交互位，打掉=移除；扫描器导出时扫描 boundary 内该方块 → 生成 `interact_spots`。
3. 导出 JSON 走新 schema（四类模式预设块 + `interact_spots`），删除 `tourist_interact_aabb` 导出分支。
4. 渲染交互位为点标记。

## 负责文件

| 文件 | 动作 |
|---|---|
| `building/scanner/BuildingScannerBlockEntity.java` | 换字段/编辑态/NBT |
| `building/scanner/ScannerMode.java` | 当前是死代码枚举（BOUNDARY/DOOR/INTERACT/META/EXPORT），可废弃或复用 |
| `building/scanner/client/BuildingScannerScreen.java` | 删 ZoneRow 六坐标输入，改交互位列表 + marker 提示；shop/service 编辑区保留 + 新增 relax/atm 编辑区 |
| `building/scanner/network/BuildingScannerExportPacket.java` | 导出新 schema + 扫描 marker 方块 |
| `building/scanner/client/BuildingScannerRenderer.java` | 交互位画点标记（取代绿色 AABB） |
| `building/scanner/SurvivalScannerBlockEntity.java` / `SurvivalScannerScreen.java` | 沿用（category 锁 custom，spots/四类模式返回空） |
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
- shop 编辑态**保留**（goods/profit_rate/duration）。
- service 编辑态**保留**（energy_per_use/element_output/max_occupancy/duration）。
- **新增 relax 编辑态**：`getRelaxEnergyRestore/getRelaxInteractionDurationTicks`；NBT key `relax_*`。
- **新增 atm 编辑态**：`getAtmWithdrawAmount/getAtmInteractionDurationTicks`；NBT key `atm_*`。
- `detectBoundaryFromCorners/detectDoors/getWorldMin/getWorldMax` 不动。

### 2. interact_spot_marker 方块（新）

- 放置式方块，视觉小标记（非透明，类比 CORNER 块）。类可放 `building/scanner/InteractSpotMarkerBlock.java`。
- **放置**到目标位置 = 标记一个交互位（相对 anchor 偏移由扫描器导出时计算）。
- **右键循环动作种类**：`BROWSE→EAT→BATHE→VIEW→MEDITATE→REST→WITHDRAW`（Activity 子集，含 atm 用 `WITHDRAW`），方块 NBT 存当前 action；右键时给玩家文字/粒子反馈（如 ActionBar 提示「该交互位：用餐」）。**潜行右键 = 移除**该 marker。
- 交互位 action 决定游客在该点做的活动状态/粒子（精力/经济/回精力/取钱效果由建筑 category 模式预设块决定）。
- **注册**：block+item（`Wandscape.java` 或新 registry 类），blockstate/model（可用不同颜色/贴图区分动作）、lang（中英）、recipe（配 vanilla 合成，如木棍+线，或仅创造标签）、物品模型、创造标签。
- 导出时 BuildingScannerExportPacket 扫描 boundary 内该方块 → 读其 NBT action → 转相对 anchor 偏移 → `interact_spots`（含 action）；该方块**跳过 pattern**（像扫描器方块一样）。

### 3. BuildingScannerScreen

当前（client/BuildingScannerScreen.java）：
- `CATEGORIES`（:47-50）：`"basic","government","node","storage","workstation","crafting_station","potion_station","tavern","shop","service","decoration","wonder","altar","custom"` → **新增 `"relax","atm"`**（保持 shop/service 独立，不合并）。
- `ZoneRow`（:571-602）：6 个 min/max 坐标输入框 + `+ 添加区域`（:282-287）→ **删除**。改为「交互位列表」：显示 `interact_spots` 的点（来源：扫描器收集的 marker 方块 + 手动输入/删除），并加提示文案「放置 interact_spot_marker 标记交互位」。
- shop 编辑区（:417）**保留**；service 编辑区（:453）**保留**；新增 relax 编辑区（energy_restore/duration）、atm 编辑区（withdraw_amount/duration）。
- NBT 载入（:1015-1027）同步。

### 4. BuildingScannerExportPacket

当前导出（network/BuildingScannerExportPacket.java）：
- `tourist_interact_aabb` JSON 数组（:229-246）→ 换成：扫描 boundary 内 `interact_spot_marker` 方块，读各自 NBT 的 action，收集相对偏移 → `interact_spots` JSON 数组 `[{"pos":[x,y,z],"action":"<action>"},...]`。
- shop 分支（:259-273）、service 分支（:276-288）**保留**；新增 relax 分支（`relax{energy_restore,duration}`）、atm 分支（`atm{withdraw_amount,duration}`）。
- 其余（pattern/block_mapping/block_nbt/comfort/magic/wonder/maintenance/queue/unlock/boundary/door_offset/blueprint）不动。

### 5. BuildingScannerRenderer

当前画绿色交互区（getTouristInteractZones，:55-61）→ 改为在 `interactSpots` 各点画点标记（小方块/粒子），并按 action 用不同颜色区分（如 browse 青、eat 橙、bathe 蓝、withdraw 黄）。排队可见性**本方案不做**（用户明确延后）。

### 6. SurvivalScanner

`SurvivalScannerBlockEntity` 当前覆盖 `getTouristInteractZones()/getShopGoods()/getServiceElementOutput()` 返回空（:28-50）→ 改为覆盖 `getInteractSpots()/getShopGoods()/getServiceElementOutput()/getRelaxEnergyRestore()/getAtmWithdrawAmount()` 返回空。

## Done 判定

1. `./gradlew build` 绿。
2. 创造扫描器：可编辑 shop/service/relax/atm 四类模式预设，可放置 marker 方块、右键循环动作（含 withdraw）、潜行移除，并在列表看到点（含动作）。
3. 导出 JSON 为新 schema（四类模式预设块 + `interact_spots`），无 `tourist_interact_aabb`；即时可建。
4. 渲染交互位为点标记。
5. SurvivalScanner 不崩。
