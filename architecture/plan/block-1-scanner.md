# Block 1 — 扫描器大改 + interact_spot_marker 方块

> 依赖 Block 0 的 `interact_spots` schema + 四类模式预设块（shop/service/relax/atm）+ `Activity` 枚举 +「0 spot 无兜底」约定。**不碰** `tourist/**`、`building/internal/**`（除 scanner 子包）。本块自包含，可单独一个 AI 开工。
> **主战场是 Creative Scanner**（`BuildingScannerBlockEntity` / `BuildingScannerScreen` / `BuildingScannerExportPacket` / `BuildingScannerRenderer` + 新 `interact_spot_marker`）。Survival Scanner 建筑永远是 `custom`（无游客交互、无四类模式预设编辑），只做机械收尾，不做新 UI。

## 目标

1. **交互位唯一真源 = world 里的 `interact_spot_marker` 方块**（用户拍板）：BE 不存 spot 列表；放置 marker=标记一个交互位，右键循环动作、潜行右键移除；**导出与渲染都直接扫 boundary 内 marker**。屏幕删掉 ZoneRow 六坐标手动输入。
2. **marker 占格即 spot 格**（用户拍板：创作者自行留空）：marker 是实体方块，占据 spot 格；导出时跳过 marker 不进 pattern/block_mapping——**创作者须把该格当作游客站位留空**（如柜台前空地），不要把 marker 压在必需的结构方块（地砖/柜台）上，否则导出缺该格。屏幕加提示。
3. 扫描器适应新 schema：删 `touristInteractZones` 手动 AABB；shop/service 编辑字段保留，新增 relax/atm 编辑字段（四类模式预设各自编辑）。
4. 导出 JSON 走新 schema（四类模式预设块 + `interact_spots`），删除 `tourist_interact_aabb` 导出分支。
5. 渲染交互位为点标记（按 `action` 配色）。

## 负责文件

| 文件 | 动作 |
|---|---|
| `building/scanner/BuildingScannerBlockEntity.java` | 删 `touristInteractZones` 字段/方法/NBT；保留 shop/service 编辑态，新增 relax/atm 编辑态 |
| `building/scanner/ScannerMode.java` | 死代码枚举（仅存取、无人按它分支）；可顺手清理，非必须 |
| `building/scanner/client/BuildingScannerScreen.java` | 删 ZoneRow 六坐标 + 添加区域按钮 → 交互位提示区（marker 计数 + 提示文案 + 清点）；shop/service 编辑区保留 + 新增 relax/atm 编辑区 |
| `building/scanner/network/BuildingScannerExportPacket.java` | 删 `tourist_interact_aabb` 导出分支；扫 marker → `interact_spots`；marker 跳过 pattern；新增 relax/atm 分支 |
| `building/scanner/client/BuildingScannerRenderer.java` | 删绿 AABB 画法 → 扫 world marker 画点（按 action 配色，低频扫描缓存） |
| `building/scanner/InteractSpotMarkerBlock.java` | **新建**：放置式方块 + `action` blockstate 属性 |
| `building/scanner/SurvivalScannerBlockEntity.java` | 删 `getTouristInteractZones()` 覆盖（基类方法已删）；其余沿用 |
| `building/scanner/client/SurvivalScannerScreen.java` | 沿用（category 锁 custom，无 spot/四类编辑 UI） |
| `Wandscape.java` | 注册 `interact_spot_marker` 方块 + 物品 + 创造标签 |
| 资源文件 | blockstate / model（按 action）/ lang（中英）/ recipe / 物品模型 |

## 具体改动

### 1. BuildingScannerBlockEntity（Creative 主战场）

当前（617 行）关键点：
- `touristInteractZones: List<BoundaryBox>`（:88）、增删改 clear（:249-271）、NBT key `tourist_interact_zones`（:40/:411/:500）、`ShopGoodData`（:589）。
- shop 编辑态：`ShopGoodData` + `getShopGoods/getShopProfitRate/getShopInteractionDurationTicks`（:299-302）。
- service 编辑态：`getServiceEnergyPerUse/getServiceElementOutput/getServiceMaxOccupancy/getServiceInteractionDurationTicks`（:306-311）。

改为：
- **删除** `touristInteractZones` 字段 + `getTouristInteractZones/add/remove/update/clear` 方法 + NBT key `tourist_interact_zones`（marker 唯一真源，BE 不存 spot）。
- shop 编辑态**保留**（goods/profit_rate/duration）。
- service 编辑态**保留**（energy_per_use/element_output/max_occupancy/duration）。
- **新增 relax 编辑态**：`relaxEnergyRestore`/`relaxInteractionDurationTicks` 字段 + getter/setter；NBT key `relax_energy_restore`/`relax_duration`。
- **新增 atm 编辑态**：`atmWithdrawAmount`/`atmInteractionDurationTicks` 字段 + getter/setter；NBT key `atm_withdraw_amount`/`atm_duration`。
- `detectBoundaryFromCorners/detectDoors/getWorldMin/getWorldMax` 不动。

### 2. interact_spot_marker 方块（新）

- 放置式方块，类放 `building/scanner/InteractSpotMarkerBlock.java`。
- **`action` 用 blockstate 属性**（`EnumProperty<Activity>` 或其子集枚举，取值 `browse/eat/bathe/view/meditate/rest/withdraw`）——**无需 BlockEntity/NBT**：blockstate 随方块自动持久化，右键循环直接 `level.setBlock` 换值，渲染/导出直接读 blockstate（不查 BE，性能好）。右键循环序列 `BROWSE→EAT→BATHE→VIEW→MEDITATE→REST→WITHDRAW`（含 atm 用 `WITHDRAW`），给玩家 ActionBar 反馈（「该交互位：用餐」）。
- **放置** = 标记一个交互位；**潜行右键 = 移除**该 marker。
- **占格语义（用户拍板：创作者自行留空）**：marker 是实体方块，占据 spot 格；导出时跳过 marker 不进 pattern——**创作者须把该格当作游客站位留空**（如柜台前空地），不要压在必需的结构方块上，否则导出缺该格。屏幕加提示。
- **注册**：`Wandscape.java` `BLOCKS.register("interact_spot_marker", ...)`（BLOCKS 在 :285 附近）+ `ITEMS.register` 物品（:171 附近）+ 创造标签（:345 附近 `output.accept`）。资源：blockstate / model（按 action 可不同颜色贴图）/ lang（中英）/ recipe（合成，如木棍+线，或仅创造标签）/ 物品模型。

### 3. BuildingScannerScreen

当前：
- `CATEGORIES`（:47-50）：`"basic","government","node","storage","workstation","crafting_station","potion_station","tavern","shop","service","decoration","wonder","altar","custom"` → **新增 `"relax","atm"`**（保持 shop/service 独立，不合并）。
- `ZoneRow`（:571-602）：6 个 min/max 坐标输入框 + `+ 添加区域`（:282-287）→ **删除**。
- 改为「交互位」提示区：显示当前 boundary 内 marker 数（client 低频扫 world，或「清点」按钮刷新）+ 提示文案「放置 interact_spot_marker 标记交互位；右键循环动作；潜行右键移除」；**若 category ∈ {shop,service,relax,atm} 且 marker 数=0 → 提示「无交互位 = 游客不选该建筑」（Block 0 无兜底）**。
- shop 编辑区（:417）**保留**；service 编辑区（:453）**保留**；新增 relax 编辑区（energy_restore/duration）、atm 编辑区（withdraw_amount/duration）。
- NBT 载入（:1015-1027）同步。

### 4. BuildingScannerExportPacket

当前导出：
- `tourist_interact_aabb` JSON 数组（:228-246）→ **删除**。换成：扫 boundary 内 `interact_spot_marker` 方块，读 blockstate `action`（枚举名转小写），收集**相对 anchor（scanner 方块）偏移**（同 pattern 偏移算法：`pos - worldPosition`）→ `interact_spots` JSON 数组 `[{"pos":[x,y,z],"action":"<小写动作>"},...]`。
- 该方块**跳过 pattern**：在 pattern 扫描循环（:100-136）与 scanner 方块同列 skip（当前只 skip air + 两个 scanner 方块）。
- shop 分支（:259-274）、service 分支（:276-289）**保留**；新增 relax 分支（`relax{energy_restore,duration}`）、atm 分支（`atm{withdraw_amount,duration}`）。
- 其余（pattern/block_mapping/block_nbt/comfort/magic/wonder/maintenance/queue/unlock/boundary/door_offset/blueprint）不动。

### 5. BuildingScannerRenderer

当前画绿色 AABB 交互区（:55-61）→ 改为扫 boundary 内 marker 画**点标记**（小方块/粒子），按 `action` 配色（如 browse 青、eat 橙、bathe 蓝、withdraw 黄）。**注意性能**：不要每帧扫全 boundary——低频扫描（如 40 tick）缓存 spot 列表，或仅在「清点」后刷新。排队可见性**不做**（用户明确延后）。

### 6. SurvivalScanner（Creative 之外的机械收尾）

> Survival Scanner 建筑永远是 `custom`：无游客交互、无四类模式预设编辑、comfort/magic/wonder=0。**主改动全在 Creative**，这里只做收尾。

- `SurvivalScannerBlockEntity`（:28-50）：删掉 `getTouristInteractZones()` 覆盖（基类方法已删）；其余覆盖（category/maintenance/comfort/magic/wonder/shop 返回空）沿用。
- `SurvivalScannerScreen`：沿用（category 锁 custom，不显示 relax/atm/spot UI）。
- BE 新增的 relax/atm getter 被 Survival 继承后，因 category 恒为 custom、screen 不展示 → 无泄露风险；如需保险可覆盖返回 0/空（可选）。

## Done 判定

1. `./gradlew build` 绿。
2. Creative 扫描器：可编辑 shop/service/relax/atm 四类模式预设；可放置 marker、右键循环动作（含 withdraw）、潜行移除；屏幕看到 marker 计数 + 提示；category=四类之一但无 marker 时有「无交互位」提示。
3. 导出 JSON 为新 schema（四类模式预设块 + `interact_spots`，无 `tourist_interact_aabb`）；marker 格不进 pattern；即时可建。
4. 渲染交互位为点标记（按 action 配色），边界/门渲染不变。
5. SurvivalScanner 不崩、UI 沿用。
