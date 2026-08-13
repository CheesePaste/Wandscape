# tourist/ — 游客模块（模拟经营）

`src/main/java/com/wsteam/wandscape/tourist/`

## 职责

游客是短居访客（**非常驻市民**）：无职业/床位/住宅/状态机。游客沿道路来访，消费/服务建筑，满意度影响殖民地经验；法师游客（5%）满满意后留简历，可在酒馆被招募成 NPC 法师。核心机制：**实体 ↔ 影子模拟**（区块卸载时游客继续以影子数据活动）。

## TouristEntity

- `extends PathfinderMob implements VillagerLike, TouristStateHost`。
- 外观：`Appearance` 枚举 TOURIST/MAGE，`MAGE_CHANCE=0.05`；皮肤数运行时扫描 `textures/entity/tourist|wizard`。
- 属性：MOVEMENT_SPEED 0.5、FOLLOW_RANGE 64、MAX_HEALTH 20。AI：FloatGoal(0)/OpenDoorGoal(1)/TouristMoveGoal(2)/RandomLookAroundGoal(3)。`createNavigation` → WandscapeNavigation。
- 钱包：`wallet`+`initialWallet`，spendWallet 钳到 0；`travelFund`（总旅费 = ATM 取现池，见 simulation.md）。能量 0-100（`TOURIST_MAX_ENERGY`）。
- 法师属性 maxHp/moveSpeed/spellPower/workSpeed/spellSpeed/armorValue。
- **无物品背包**，仅 `recentVisits` 记忆（上限 24 FIFO）+ visitedBuildings（停留期不重置，ATM 缺钱 / relax 精力低豁免可重复）+ lastAtmWithdrawTime（取现冷却）。不可被推动，`removeWhenFarAway=false`。
- **救援传送（被困兜底，`TouristTeleport`）只落点建筑外**：优先最近已建成道路（`rescueRoadRadius`），无路则建筑外围（入口点/bbox 面/外扩环扫，`rescuePeripheryRadius`），绝不传进建筑或房顶；找不到安全点则不传送。影子→实体水合若落在建筑房顶也改传安全点（`TouristSimSystem.importToEntity`）。

## 生命周期（TouristSpawnSystem）

每 `CHECK_INTERVAL=100` tick 检查。时段由 Config 划分：生成窗口 [1000, 8000]（约 07:00–14:00，集中在上午、最晚下午到），离境窗口 [18000, 24000]。

- **生成数**：`toSpawn` = 均匀整数区间 `[base(5)+(lv-1)×levelSpawnBonus(1), +spawnRangeWidth(3)-1]`，即 1 级 5~7、2 级 6~8、3 级 7~9，clamp [1, TOURIST_MAX_PER_COLONY=100]；**每天固定新增 toSpawn 个**，不因殖民地已有游客（含住店客）而扣减；每个游客的到达时间在 [1000, 8000] 窗口内**随机**取（错峰到达）；`onServerTick` 对生成路径**每 tick flush**（不等 `CHECK_INTERVAL=100`），高 tick rate（如 1000）下窗口也不会被跳过——防护「来不及生成」。
- **条件**：需已注册殖民地 + 存在完整 shop/service 目标。生成点取道路网 COMPLETE 边端点，无路用建筑位置。等级分布：colonyLevel-1/+1 = 40/40/20%。生成时强制加载区块；**到达登记（`registerArrival`）与 shadow 收养统一在 `TouristEntity.onAddedToLevel` 单点完成**（覆盖系统生成/刷怪蛋/命令；sim 再水合实体与磁盘加载体排除，避免重复触发 TouristArrivedEvent）。
- **离开**：sat<50 或 sat=100 → 夜晚带 0-1500 tick 随机延迟离开；sat 50-99 → 引导去旅馆，无房则离开。白天/傍晚：能量耗尽、夜晚且空闲、空闲超时 `TOURIST_DESPAWN_TIMEOUT_TICKS=36000`。100% 满意度 → `grantExperience`。
- **住店客免疫清场**：入住后（`checkedInBuildingId` 常驻）只按停留截止（`departureDeadline`）或**满条当晚开心离场**（用户确认），不被夜晚/闲置清掉——`cleanupTourists`/`processNightDepartures`/sim `checkDeparture` 对住店客只判截止/满条。
- **清场窗口统一 18000–24000**：未观察的 sim 游客与观察中的实体游客一致，都在离场窗口内清无旅店/满条游客（原 sim 从 13000 起提前清人，已对齐）；夜晚阈值 `tourist.nightStart`（默认 14000）起游客优先旅店/可入住，多逛 40 分钟。

## 影子模拟（TouristSimSystem / TouristSimulation / TouristShadow / TouristSimRegistry）

- `TouristSimSystem` 每 tick：判定"有玩家观察"用**玩家 simulation distance**（非区块状态）。观察中 → 实体运行 AI，`exportToShadow` 镜像；未观察 → 实体 `UNLOADED_TO_CHUNK` 移除（影子存活），`simStep` 推进。
- 影子直线匀速移动 SPEED=0.5/tick、ARRIVE_RANGE=1.0、WANDER_RADIUS=24；到建筑锚点走 shop/service/hotel 交互与冷却；酒店满员由影子注册表派生。
- **住店客同步**：sim 住店客白天外出逛街、夜晚回自己旅店（`routeToOwnHotel`，已到店不动）、清晨晨起保留登记；未满条住店客不被清（`checkDeparture` 只判截止/满条）；清场窗口与实体一致 **18000–24000**；16000 起无旅店游客 `routeToHotelForEvening` 打断当前交互去旅店。
- 实体↔影子转换：加载过渡**影子胜出**（importToEntity），冷却以各自 timeBase 互转；孤儿实体（无影子）被 discard。
- **影子↔身体 UUID 一致**：`spawnEntity` 用 `setUUID(shadow.touristId)` 生成身体——否则身体带随机新 UUID，`onAddedToLevel` 自动收养会把它注册成**另一个影子**，原影子沦为幽灵持续复活身体 → 卸载/重载指数级复制，且复制体 kill 不掉。磁盘加载的身体保留自身 UUID（与影子匹配）。
- **新鲜生成自动收养 + 到达登记**：非磁盘加载的游客（刷怪蛋等）在 `onAddedToLevel` 自动 `sim.adoptTourist` 并 `registerArrival`（`loadedFromDisk` 区分：`readAdditionalSaveData` 置 true，`finalizeSpawn` 置 false；registry 已有该 shadow 的再水合实体跳过）。磁盘加载且已离境的身体仍走孤儿 discard，避免复活。
- `TouristSimRegistry`（SavedData `wandscape_tourist_sim`）：`ConcurrentHashMap<UUID, TouristShadow>`。
- **游客计数权威来源**：`TouristApiImpl.getTouristCount(colonyId)` 按 shadow 的 colonyId 统计影子注册表——注册表持久化，重启后已存在的游客仍计入；内存 `colonyTourists` map 仅作 sim 未激活时的兜底。

## TouristState / TouristStateHost

- `TouristState` 枚举：VISITING/EXPLORING/WANDERING/IDLE/SLEEPING（lang key `tourist.wandscape.state.<lowercase>`）。
- **只是移动状态标签**，由 TouristMoveGoal 从内部 MoveMode 单向映射；命令 `forceMoveMode` 反向强制；SLEEPING 置 Pose.SLEEPING。
- `TouristStateHost`：实体与影子共享的状态接口，冷却以各自 timeBase 存储。

## 满意度与偏好

- 三值需求条（Comfort/Magic/Wonder）+ 画像 + 精力/钱包/spot 排队驱动的目标选择（Find-Best-Action 评分）是独立系统，见 [simulation.md](../simulation.md)——本文不重复。
- 历史：单一 `satisfaction`/`typePreferences` 与 sqrt 增益公式已在 2026-08 三值改造中删除，本小节不再描述旧机制。

## 酒店（HotelStayHandler）——住店客机制

- **住店客（resident）**：游客入住后 `checkedInBuildingId` **常驻**（实体/影子 NBT 持久化）——清晨只「晨起」（`wakeUp`：精力回 100、回入住前站位、住店晚数 +1），**名单不删**；白天照常外出逛街，夜晚回**自己**旅店睡觉。离场（截止/满条当晚/被杀）才 `checkOut` 从名单删除。
- **checkIn 条件**（TouristMoveGoal 把关）：夜晚（`tourist.nightStart` ≥14000）且未满条，且 ServiceConfig.maxOccupancy>0；`checkIn` 幂等——已是该旅店住店客（回店/磁盘加载）跳过容量检查，避免被自己占的床位挤掉。
- **提前入住（入住即时完成）**：游客**进入酒店建筑 bbox** 即 `tryHotelCheckIn`（bbox 外扩 +5 已去掉——大旅店不会在离门老远就入住）——**不占 spot、不等 `interaction_duration_ticks`**，进入即入住躺床；白天条件不满足则按普通服务建筑处理。夜晚意图入住但旅店满员 → 不当 service 逛/排队，放弃重新规划（避免排队拖到被清场）。
- **夜晚回店**：住店客夜晚（或凌晨 0-1000）空闲时 `returnToOwnHotel` 回自己旅店——已睡着停住、在店旁强制躺床、在路上继续走、否则开始回店；旅店被拆 → 解除登记按无旅店处理；**过远（> `tourist.hotelTeleportDistance` 64）直接传送**（省寻路开销）。
- **傍晚路由**：`tourist.eveningRoutingStart`（默认 16000）起，无旅店未满条游客**停止当前任务**去旅店（`TouristSimulation.findHotelTarget` 全殖民地找最近可用旅店，实体路径要求区块已加载）。
- **睡床（纯视觉）**：入住即 `settleIntoBed` **强制躺床**——有空床躺空床；床不够（全被占用）躺**最近一张床**（纯视觉可共用）；旅店一张床都没有 → **卡原地不动**。床判定 = 酒店 bbox 内 `BedBlock`（跳过原版 `OCCUPIED`），`setSleepingPos` + `Pose.SLEEPING`，不改床方块状态 → 无占用泄漏。
- 入住点存为 `wakeUpPos`（实体与影子 NBT 持久化，`exportToShadow`/`importToEntity` 同步）；清晨窗口 1000-1200 晨起：加载路径 `stopSleeping` 起床并**传送回入住点**，影子路径 simStep 同样恢复位置，发 HOTEL_WAKEUP 叙事。
- 床位占用由 HotelStayHandler 内存 `touristToBed` 跟踪（晨起/强制退房时清除），不依赖床方块 OCCUPIED 标记。
- 心跳每 20 tick（晨起窗口唤醒住店客；实体未加载但影子仍是住店客时跳过强制退房）；占用数从影子注册表派生。

## 酒馆招募

- 法师游客（5%）满意度到 100% → `storeMageResume` → `TavernApi.receiveMageResume`。法师 7 属性按**加法 + 偏斜 random²** roll：生命 20–40 + 2/级、魔力 150–250 + 15/级、移速 0.2–0.4 + 0.02/级、法强/工速/施速 0.5–1.5 + 0.05/级、护甲 0–8 + 0.5/级（`random²` 偏向低值、偶发高值 → 自然出专精；等级做加法叠加，更公平）。掷点公式集中在 `shared/data/MageAttributeRoller`（游客掷简历与酒馆招募共用）。
- `TavernRecruitStorage`（SavedData `wandscape_tavern_recruits`）每殖民地最多 5 条，超出逐出最旧。
- `TavernRecruitPacket.handleRecruitMage`：取出简历 → 生成 WandscapeNpc（MobSpawnType.COMMAND），写入 resume 的 7 属性 + 满蓝入职，setPersistenceRequired + colonyId。`getCandidates/refreshCandidates/recruitCandidate` 为占位（返回空/false）。
- 酒馆「招募 NPC」按钮（`TavernRecruitPacket` spawn_npc）：用同一掷点公式、以**殖民地当前等级**立即生成一名法师——`MageAttributeRoller.roll(colonyLevel)`（random² 偏斜 + (殖民地等级−1) 加成），满蓝入职并播种 ECS，等价于「模拟殖民地等级游客投出的简历」。**计费（仅此按钮）**：每殖民地首次免费，之后每次需每种元素（7 种）各 10000（`WandscapeConstants.TAVERN_RECRUIT_COST_PER_ELEMENT`）；生成成功后才扣费并计数（`TavernApi.canAffordRecruit/chargeRecruit`，计数存 `TavernRecruitStorage.recruitCounts`）。「Mages」雇佣简历法师不收费。

## 叙事生成

- `NarrativeTemplates` 两级解析：建筑专属 `data/wandscape/narratives/buildings/<type>.json` → 全局类别 `zh_cn.json` category_templates → Java 硬编码兜底。占位符 `{name}/{building}/{item}/{emotion_adj}/{visit_count}`。
- `NarrativeGenerator` 各事件选模板组：visit/arrival/departure/hotel checkin/wakeup/satisfaction milestone。事件经 `NarrativeEventTriggered` 发到 world eventBus。

## client/ 与 network/

- `TouristScreen`：MedievalScreen 300×300，显示三条需求条（舒适/魔法/奇观 fill/need）、画像标签、精力、等级、钱包、停留（已住 N 晚 / 共 X 天）、行程列表。
- `TouristRenderer`：HumanoidMobRenderer + HumanoidModel(PLAYER)，运行时枚举贴图；渲染 SpeechBubbleRenderer + SatisfactionBarRenderer。
- `TouristDebugRenderer`：RenderLevelStageEvent.AFTER_TRIPWIRE_BLOCKS，扫描半径 80，X-ray 画线/十字（青=入口、品红=交互点）。
- `TouristDataPacket`：S→C 信息屏（含 VisitEntry 轻量子集）；`TouristBubblePacket`：购买/服务后触发 TransientBubbleStore.trigger。

## 经济交互

- **购物**：TouristMoveGoal → TouristSimulation.performShopInteraction → ShopInteractionHandler → ShopStockManager.purchaseAffordable → purchase 扣库存、按 profitRate 向 ColonyItemBank 存元素、recordPurchase；游客 spendWallet、精力 -20。
- **服务**：精力 -energyPerUse，elementOutput 全部写入 ColonyItemBank.addElement。
- **ATM 取现**：单次取现 = 初始钱包随机 20%~50%（封顶 travelFund 池子）；`atmReusable` 判定（池子有余额 + 钱包低于初始 1/4 + 取现冷却已过）通过时豁免 visited 可重复取现（分批取现），池子空/冷却中不选——不会因偏好白跑 ATM 取 0。`visitedBuildings` 停留期不重置（红线 #8；ATM 缺钱 / relax 精力低是豁免例外）。
- **满意度→经验**：离境且 sat=100 → ColonyLevelManager.computeExpContribution：游客等级<殖民地→0；==→250；>→500。升级公式 `expToNext=(level+1)×500`（1→2=1000、每级 +500），等级上限 `colony.maxLevel`（默认 30）。
