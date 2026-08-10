# tourist/ — 游客模块（模拟经营）

`src/main/java/com/wsteam/wandscape/tourist/`

## 职责

游客是短居访客（**非常驻市民**）：无职业/床位/住宅/状态机。游客沿道路来访，消费/服务建筑，满意度影响殖民地经验；法师游客（5%）满满意后留简历，可在酒馆被招募成 NPC 法师。核心机制：**实体 ↔ 影子模拟**（区块卸载时游客继续以影子数据活动）。

## TouristEntity

- `extends PathfinderMob implements VillagerLike, TouristStateHost`。
- 外观：`Appearance` 枚举 TOURIST/MAGE，`MAGE_CHANCE=0.05`；皮肤数运行时扫描 `textures/entity/tourist|wizard`。
- 属性：MOVEMENT_SPEED 0.5、FOLLOW_RANGE 64、MAX_HEALTH 20。AI：FloatGoal(0)/OpenDoorGoal(1)/TouristMoveGoal(2)/RandomLookAroundGoal(3)。`createNavigation` → WandscapeNavigation。
- 钱包：`wallet`+`initialWallet`，spendWallet 钳到 0。能量 0-200、满意度 0-100。
- 偏好 `typePreferences`：默认 40，范围 5-100。法师属性 maxHp/moveSpeed/spellPower/workSpeed/spellSpeed/armorValue。
- **无物品背包**，仅 `recentVisits` 记忆（上限 24 FIFO）+ visitedBuildings/serviceCooldowns/serviceCooldownEndTick。不可被推动，`removeWhenFarAway=false`。
- **救援传送（被困兜底，`TouristTeleport`）只落点建筑外**：优先最近已建成道路（`rescueRoadRadius`），无路则建筑外围（入口点/bbox 面/外扩环扫，`rescuePeripheryRadius`），绝不传进建筑或房顶；找不到安全点则不传送。影子→实体水合若落在建筑房顶也改传安全点（`TouristSimSystem.importToEntity`）。

## 生命周期（TouristSpawnSystem）

每 `CHECK_INTERVAL=100` tick 检查。时段由 Config 划分：生成窗口 [1000, 8000]（约 07:00–14:00，集中在上午、最晚下午到），离境窗口 [18000, 24000]。

- **生成数**：`rawTarget = base(6) + colonyLevel×levelSpawnBonus(3)`，`targetCount = round(rawTarget×(0.8~1.2))`，clamp [1, TOURIST_MAX_PER_COLONY=20] 且 ≤ MAX_TOURISTS=30；`toSpawn = targetCount - existing`（existing 用影子注册表计数）。
- **条件**：需已注册殖民地 + 存在完整 shop/service 目标。生成点取道路网 COMPLETE 边端点，无路用建筑位置。等级分布：colonyLevel-1/+1 = 40/40/20%。生成时强制加载区块、`registerArrival` + `sim.adoptTourist`。
- **离开**：sat<50 或 sat=100 → 夜晚带 0-1500 tick 随机延迟离开；sat 50-99 → 引导去旅馆，无房则离开。白天/傍晚：能量耗尽、夜晚且空闲、空闲超时 `TOURIST_DESPAWN_TIMEOUT_TICKS=36000`。100% 满意度 → `grantExperience`。

## 影子模拟（TouristSimSystem / TouristSimulation / TouristShadow / TouristSimRegistry）

- `TouristSimSystem` 每 tick：判定"有玩家观察"用**玩家 simulation distance**（非区块状态）。观察中 → 实体运行 AI，`exportToShadow` 镜像；未观察 → 实体 `UNLOADED_TO_CHUNK` 移除（影子存活），`simStep` 推进。
- 影子直线匀速移动 SPEED=0.5/tick、ARRIVE_RANGE=1.0、WANDER_RADIUS=24；到建筑锚点走 shop/service/hotel 交互与冷却；酒店满员由影子注册表派生。
- 实体↔影子转换：加载过渡**影子胜出**（importToEntity），冷却以各自 timeBase 互转；孤儿实体（无影子）被 discard。
- **影子↔身体 UUID 一致**：`spawnEntity` 用 `setUUID(shadow.touristId)` 生成身体——否则身体带随机新 UUID，`onAddedToLevel` 自动收养会把它注册成**另一个影子**，原影子沦为幽灵持续复活身体 → 卸载/重载指数级复制，且复制体 kill 不掉。磁盘加载的身体保留自身 UUID（与影子匹配）。
- **新鲜生成自动收养**：非磁盘加载的游客（刷怪蛋等）在 `onAddedToLevel` 自动 `sim.adoptTourist`（`loadedFromDisk` 区分：`readAdditionalSaveData` 置 true，`finalizeSpawn` 置 false）。磁盘加载且已离境的身体仍走孤儿 discard，避免复活。
- `TouristSimRegistry`（SavedData `wandscape_tourist_sim`）：`ConcurrentHashMap<UUID, TouristShadow>`。

## TouristState / TouristStateHost

- `TouristState` 枚举：VISITING/EXPLORING/WANDERING/IDLE/SLEEPING（lang key `tourist.wandscape.state.<lowercase>`）。
- **只是移动状态标签**，由 TouristMoveGoal 从内部 MoveMode 单向映射；命令 `forceMoveMode` 反向强制；SLEEPING 置 Pose.SLEEPING。
- `TouristStateHost`：实体与影子共享的状态接口，冷却以各自 timeBase 存储。

## 满意度与偏好

- `satisfactionGain`：`threeSum` = comfort+magic+wonder（商店叠加 ShopStockManager.getGoodsBonus）；`threshold = level × TOURIST_LEVEL_SATISFACTION_THRESHOLD(3)`；threeSum<threshold → 惩罚 `-sqrt(typePref×(deficit+1))` 下限 -15；否则增益 `sqrt(typePref×(threeSum-threshold+1))` 上限 TOURIST_MAX_SATISFACTION_PER_VISIT=30。
- `preferenceDecay=15`：每次访问后从该建筑类型偏好扣减。
- `TouristApiImpl.getAverageSatisfaction` = 满意度之和/人数；注册表 colonyId→(touristId→sat)。

## 酒店（HotelStayHandler）

- checkIn 条件（TouristMoveGoal 把关）：sat≥50 且 <100 且（夜晚 或 精力耗尽），且 ServiceConfig.maxOccupancy>0、有空房；入住后移动停止。
- **提前入住**：到达酒店建筑 bbox 内 5 格（microNavSwitchDistance）即入住（`tryHotelCheckIn`），无需走到精确交互点——避免夜晚在酒店门口站定触发卡死传送。白天条件不满足则按普通服务建筑处理。
- **睡床（纯视觉）**：入住后 `settleIntoBed` 在酒店 bbox 内找最近一张没人睡的床（跳过 `OCCUPIED` 与已分配床），传送上床并置 SLEEPING 姿态（`setSleepingPos` + `Pose.SLEEPING`，不改床方块状态 → 无占用泄漏）；无空床则原地站着。
- 入住点存为 `wakeUpPos`（实体与影子 NBT 持久化，`exportToShadow`/`importToEntity` 同步）；清晨窗口 1000-1200 自动退房：加载路径 `stopSleeping` 起床并**传送回入住点**，影子路径 simStep 退房时同样把位置恢复到入住点（不再水合在酒店内），精力恢复 100、发 HOTEL_WAKEUP 叙事。
- 床位占用由 HotelStayHandler 内存 `touristToBed` 跟踪（退房/强制退房时清除），不依赖床方块 OCCUPIED 标记。
- 心跳每 20 tick；占用数从影子注册表派生。

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
- `TouristDebugRenderer`：按 G 开关（RenderLevelStageEvent.AFTER_TRIPWIRE_BLOCKS），扫描半径 80，X-ray 画线/十字（青=入口、品红=交互点）。
- `TouristDataPacket`：S→C 信息屏（含 VisitEntry 轻量子集）；`TouristBubblePacket`：购买/服务后触发 TransientBubbleStore.trigger。

## 经济交互

- **购物**：TouristMoveGoal → TouristSimulation.performShopInteraction → ShopInteractionHandler → ShopStockManager.purchaseAffordable → purchase 扣库存、按 profitRate 向 ColonyItemBank 存元素、recordPurchase；游客 spendWallet、精力 -20。
- **服务**：精力 -energyPerUse，elementOutput 全部写入 ColonyItemBank.addElement。
- **满意度→经验**：离境且 sat=100 → ColonyLevelManager.computeExpContribution：游客等级<殖民地→0；==→100；>→500。升级公式 `expToNext=(level+1)×1000`。
