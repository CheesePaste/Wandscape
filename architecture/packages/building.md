# building/ — 建筑管理

零自定义方块/BE。建筑状态全部通过 `BuildingSavedData` (Level SavedData) 管理。所有建筑使用原版方块，NPC 通过蓝图放置。

## 建筑类别 (category)

| 类别 | 说明 | 新增/现有 |
|------|------|----------|
| basic | 基础建筑（市政厅等） | 现有 |
| node | 节点建筑（元素采集） | 现有 |
| storage | 仓库建筑 | 现有 |
| workstation | 工作站（分解/合成） | 现有 |
| crafting_station | 制作站（法杖制作） | 现有 |
| potion_station | 魔药站 | 现有 |
| tavern | 酒馆（招募） | 现有 |
| **shop** | 商店（游客购物，带交互区） | **新增** |
| **service** | 服务建筑（游客交互，需进入建筑） | **新增** |
| **decoration** | 装饰建筑（范围辐射加成） | **新增** |
| **wonder** | 奇观（全局效果） | **新增** |

## 关键类

### 数据类 (data/)

- **BuildingConfig** (data/) — record：id/display_name/category/pattern/block_mapping/comfort/magic/wonder/queue/blueprint+bind。**新增字段**: `maintenanceCost`(MaintenanceCostConfig)/`decoration`(DecorationConfig)/`wonder`(WonderConfig)/`shop`(ShopConfig)/`service`(ServiceConfig)
- **BlockOffset** (data/) — [x,y,z] 相对偏移，含 toKey() 和 Gson Deserializer
- **MaintenanceCostConfig** (data/) — record: `intervalTicks`/`costs: Map<ElementType, Long>`
- **DecorationConfig** (data/) — record: `radius`(int, 曼哈顿辐射半径)
- **WonderConfig** (data/) — record: `effects: List<WonderEffect>` (sealed interface)
- **ShopConfig** (data/) — record: `goods: List<ShopGoodDef>`(itemId/comfort/magic/wonder/restockCost可选) + `profitRate`
- **ServiceConfig** (data/) — record: `energyPerUse`/`elementOutput: Map<ElementType, Long>`/`maxOccupancy`
- **ShopGoodDef** (data/) — record: `itemId: String`/`comfort: int`/`magic: int`/`wonder: int`/`restockCost: Map<ElementType, Integer>`(可选，默认反查 element_mappings 的 decompose_yield)。maxStock **不在 JSON 中**，由 ShopStockManager 按建筑按商品管理，玩家通过 GUI 滑动条 0–64 调整（默认 0，需玩家主动拉滑动条才补货）

### 状态管理 (internal/)

- **BuildingState** (internal/) — 可变建筑状态：buildingId/typeId/category/anchor/BoundingBox/colonyId/shutdown/structureIntact/taskQueue/currentTaskId/stats。**新增**: `maintenanceCost`(快照)/`lastMaintenanceTick`/`maintenancePaid`
- **BuildingSavedData** (internal/) — 3个索引(buildings/posIndex/chunkIndex) + NBT持久化 + AABB重叠检测。register() 检测 intersects()。**新增**: `getInteractionTarget(buildingId, level)` 返回包围盒内可步行位置供游客AI导航；`getBuildingIdInInteractionZone()` 改为检查所有建筑包围盒内部(不仅interaction_radius>0的建筑)
- **BuildingApiImpl** (internal/) — BuildingApi 实现：全部通过 BuildingSavedData 读写。**新增**: `getInteractionTarget(buildingId)` 委托给 BuildingSavedData.getInteractionTarget()
- **EnqueueHelper** (internal/) — 入队：读 BlueprintRef → resolve bind → 硬编码 anchor → 构建 WorkItem
- **BuildingInteractHandler** (internal/) — RightClickBlock → posIndex(chunkIndex fallback) O(1) → interaction zone 扩展(interaction_radius>0 时范围交互) → 按 category 分发：storage→仓库GUI / workstation/crafting_station/potion_station→生产站GUI / shop→商店GUI(ShopOpenPacket, ShopMaxStockPacket→调整maxStock) / service→服务GUI / tavern→酒馆GUI / 其他→信息打印
- **BuildingBreakHandler** (internal/) — BreakEvent/ExplosionEvent → 收集受损坐标 → structureIntact=false → 移除三值贡献 → 构造局部修复 WorkItem
- **BuildCompleteListener** (internal/) — 订阅引擎 build_complete CustomEvent → 扫描 → structureIntact=true → 添加三值贡献 → 仍有损坏 → 局部重试

### 三值评估

- **BuildingContributionRegistry** (internal/) — 殖民地区三值聚合。**改为每建筑实例独立计算**：遍历 BuildSource.allBuildings()，每栋检查 isStructureIntact/isShutdown/category/shopHasStock。shop 三值 = 建筑基础值 + 所有有货 goods 的 comfort/magic/wonder 合计。any snapshot 变化广播 `ColonyEvaluationChangedEvent`
- **BuildingUnlockChecker** (internal/) — 静态工具：传入 colonyId + BuildingConfig → 查询 BuildingApi 三值 vs unlockRequirement → 返回是否解锁 + 锁因字符串

### 客户端 GUI (client/)

- **HotelScreen** — 宾馆入住/退房 GUI，显示房间容量和入住游客列表
- **ShopScreen** — 商店购物 GUI，显示商品列表+价格+存量
- **TavernScreen** — 酒馆交互 GUI

### 建筑编辑器 (editor/)

客户端侧的编辑器全套（与 `blueprint/editor/` 分开）：

- **BuildingEditorClientState** — 编辑器客户端静态状态持有者（锚点/AABB/方块图案/元数据/轴拖拽状态）
- **BuildingEditorController** — 每 tick 生命周期控制器（飞行移动/相机旋转/输入委托/快捷键）
- **BuildingEditorInputHandler** — 鼠标输入处理
- **BuildingEditorImGui** — ImGui 属性编辑面板（紧凑双列布局：ID/名称/分类/三值/队列/交互半径/Export/Preview）
- **BuildingEditorRenderer** — 世界空间渲染（选中方块高亮/AABB 线框/锚点标记）
- **BuildingEditorAxisRenderer** — 轴辅助渲染
- **BuildingEditorNetwork** — 编辑器网络通信
- **BuildingEditorExportService** — JSON 导出服务

### 网络包 (network/) — 12 个文件

| 包 | 方向 | 用途 |
|----|------|------|
| BuildingEditorEnterPacket | C→S | 请求进入建筑编辑模式 |
| BuildingEditorEnterResponsePacket | S→C | 服务端确认进入编辑器 |
| BuildingEditorExitPacket | C→S | 退出编辑模式 |
| BuildingEditorExportPacket | C→S | 导出当前编辑建筑配置 |
| BuildingEditorExportResultPacket | S→C | 导出结果反馈 |
| ShopOpenPacket | S→C | 打开商店 GUI 并传商品数据 |
| ShopMaxStockPacket | C→S | 调整商品最大库存量 |
| HotelOpenPacket | S→C | 打开宾馆 GUI |
| TavernOpenPacket | S→C | 打开酒馆 GUI |
| TavernRecruitPacket | C→S | 酒馆招募请求 |
| TaskQueueDataPacket | S→C | 任务队列数据同步 |
| TaskQueueModifyPacket | C→S | 任务队列修改（refresh/delete/move_up/move_down） |

### 模拟经营系统 (internal/)

- **DailySettlementSystem** (internal/) — 取代旧的 MaintenanceSystem。每游戏日 0:00 (time-of-day 0) 触发一次。按优先级分组（CRITICAL→HIGH→NORMAL→LOW）依次从 ColonyItemBank 扣建筑维护费元素。不够则 shutdown。宽限期内新建筑跳过。结算后有剩余元素则自动重启因维护费 shutdown 的建筑。发布 `DailySettlementEvent` / `MaintenanceShortfallEvent`。
- **DemolishCompleteListener** (internal/) — 订阅建筑拆除完成事件，清理 BuildingSavedData 中对应状态
- **MaintenanceForecastSystem** (internal/) — 每 6000 tick（1/4 天）扫描一次。预测殖民地未来维护费需求，当元素存量低于 `reserveDays` 阈值时，自动为闲置 node 建筑发布高优先级采集任务。发布 `MaintenanceForecastWarningEvent`。
- **DecorationBonusSystem** (internal/) — 心跳扫描 → 遍历非decoration/wonder功能建筑 → 曼哈顿距离 ≤ decoration.radius 的装饰加成累加 → cap(建筑自身基础值 × Config.decorationBonusCap) → 缓存 → BuildingContributionRegistry 查询时合并
- **DecorationBonusCache** (internal/) — 缓存每个功能建筑的当前装饰加成值，建筑变更时(注册/注销/shutdown/restart)失效重算
- **ShopStockManager** (internal/) — 管理商店库存：per-building stock (Map<UUID, Map<String,Integer>>) + per-building maxStock 设定(Map<UUID, Map<String,Integer>>, 默认16, 玩家GUI调0–64)。心跳驱动 restock 周期 → restock_cost 优先用 JSON 显式值，未指定则反查 `Wandscape.ELEMENT_MAPPING_LOADER.getItemDecomposeYield()` 自动推断。ensureStockInitialized() 首次打开立即补货。setMaxStock() 调整上限后触发即时补货。purchase() 游客购物消耗货品 → 殖民地获得 (1+profitRate)× 元素。clearUnsold() 清空未售出。stock 状态变化 → BuildingContributionRegistry.setShopHasStock() 开关三值。getGoodsBonusComfort/Magic/Wonder() 查询有货 goods 的三值合计
- **ShopInteractionHandler** (internal/) — 静态方法 interact()：游客 AI 调用 → 从 ShopStockManager 获取库存 → 选有货商品 → purchase
- **WonderEffectApplier** (internal/) — 订阅 BuildingShutdownEvent + BuildingRestartedEvent + ColonyEvaluationChangedEvent → 遍历 category=wonder 建筑 → 收集 intact+非shutdown 的 effects → StatMod 聚合 statCache / PriceMod 聚合 priceCache / RuleUnlock 写入 unlockedRules → 发 WonderEffectChangedEvent。提供 getStatMod/getPriceMod/isRuleUnlocked 静态查询

## 数据流

```
玩家/GUI 提交建造
  → EnqueueHelper → BuildingSavedData(structureIntact=false)
  → WorkItem → BuildingTaskSource.poll() → BuildingTaskPool (仅head入全局池)
  → GlobalTaskPool → SchedulerSystem(NPC匹配) → NpcTaskPackage
  → NPC领取 → 执行蓝图 → 放置原版方块
  → emit_event("build_complete")
  → BuildCompleteListener → findDamagedBlocks 扫描
  → 全部修复 → structureIntact=true
  → BuildingSavedData.addBuildingContribution()
  → BuildingContributionRegistry: intactCount(type) 0→1
  → 广播 ColonyEvaluationChangedEvent

建筑受损（Break/Explosion）
  → BuildingBreakHandler → 收集受损坐标 → structureIntact=false
  → BuildingSavedData.removeBuildingContribution()
  → BuildingContributionRegistry: intactCount(type) 1→0
  → 广播 ColonyEvaluationChangedEvent
  → 构造局部 WorkItem（offsets=仅受损偏移）→ addFirst 队首
  → NPC修复 → BuildCompleteListener 再次扫描 → 全部修复 → structureIntact=true
  → BuildingSavedData.addBuildingContribution() → intactCount 0→1 → 广播事件

维护费循环
  → DailySettlementSystem 每游戏日0:00触发
  → 按优先级分组：CRITICAL(node/basic/storage) → HIGH(production) → NORMAL(shop/tavern) → LOW(service/decoration)
  → 逐组逐建筑：shutdown跳过 → 宽限期内跳过
  → ColonyItemBank.consumeElements(maintenanceCost.costs)
  → 足够 → maintenancePaid=true
  → 不足 → shutdown(reason="maintenance") → 按category分级惩罚 → MaintenanceShortfallEvent
  → 剩余元素 → 自动重启已 shutdown 的 maintenance 建筑（同优先级顺序）

元素储量预警
  → MaintenanceForecastSystem 每6000tick扫描
  → 计算各殖民地每日维护费总需求
  → 当前存量 < reserveDays × 日需求 → 标记短缩元素
  → 查找对应 node 建筑 → 闲置则发布高优先级采集 WorkItem
  → 发布 MaintenanceForecastWarningEvent

商店运作
  → ShopStockManager.restock() → ColonyItemBank扣元素 → 填充goodSlots
  → 游客交互 → 消耗货品 → ColonyItemBank入元素(利润)
  → 有货/缺货 → 三值贡献开关

装饰辐射
  → DecorationBonusSystem → 功能建筑查曼哈顿距离内装饰
  → 累加 + cap → 缓存 → BuildingContributionRegistry计入三值
```

## JSON

位置：`data/wandscape/buildings/*.json`，8 个现有建筑。格式参见 [data/buildings.md](../data/buildings.md)。

## 依赖

- shared/api/BuildingApi, shared/data/BuildingData, shared/data/WorkItem
- shared/data/MaintenanceCost, shared/data/DecorationConfig, shared/data/WonderConfig, shared/data/WonderEffect, shared/data/ShopConfig, shared/data/ServiceConfig
- shared/event/BuildingPlacedEvent/BuildingShutdownEvent/BuildingRestartedEvent/ColonyEvaluationChangedEvent
- building/network/ShopMaxStockPacket (client→server, 调整maxStock, 服务器回复刷新ShopOpenPacket)
- shared/event/MaintenanceDueEvent/ShopRestockedEvent/WonderEffectChangedEvent
- shared/registry/WandscapeApis
- warehouse/ColonyItemBank (维护费扣元素 + 商店进货)
- dataconfig/WandscapeDataLoader
- core/event/CustomEvent（BuildCompleteListener 订阅引擎事件）
