# Config + API 决策记录（执行底稿）

> 用途：把 `Config.java` 全部字段 + 本方案涉及的 API 增删逐一列清，**供人工逐条调整后我再执行**。
> 依据：`Config.java`（读取全文，82 键）+ 全仓读点 grep 核实。
> 缩写：
> - **留**＝保留在 Config（玩家向标量）
> - **Client**＝迁到 `ClientConfig`（`ModConfig.Type.CLIENT`）
> - **Bal**＝变**对应领域 API**可调（npc 域→`NpcApi`、transport→`WarehouseApi`、building→`BuildingApi`）——**无独立 `BalanceApi`**，见 Part B 按领域映射表
> - **Bal?**＝建议进对应领域 API（原方案漏/未列、但确有调优需求，**待你拍板**）
> - **常量**＝回 `WandscapeConstants` 常量固定（深度/节奏值，不再可调）
> - **删**＝删死键（全仓 0 读取）

---

## Part A — Config 全字段决策

### 通用
| 字段 | key | 作用 | 读点 | 打算 | 说明 |
|---|---|---|---|---|---|
| `DEBUG` | general.debug | 详细日志开关（INFO/DEBUG） | 活(Wandscape:585) | **留** | 简单布尔、通用，玩家/整合包常用；虽非玩家向但轻量，保留 |

### 客户端渲染/面板（迁 CLIENT）
| 字段 | key | 作用 | 读点 | 打算 | 说明 |
|---|---|---|---|---|---|
| `FLY_SPEED` | panel.flySpeed | V 面板相机飞行速度(格/秒) | 活(OverviewFlight:275, SplineEditor:298/342 均 client) | **Client** | 纯 client 读，安全 |
| `PREVIEW_RESOLUTION` | preview.resolution | 建筑预览 GIF 分辨率 | 活(WandscapeClient:200) | **Client** | 纯 client |
| `PREVIEW_FPS` | preview.fps | 预览 GIF 帧率 | 活(WandscapeClient:200) | **Client** | 纯 client |
| `PARTICLE_LEVEL` | particle.level | 粒子等级 OFF/LOW/NORMAL/HIGH | 活(ParticleService:38/43) | **Client** | ⚠️ **迁前先确认服务端是否也读**——若 `ParticleService` 在 dedicated server 侧读，纯 CLIENT 会读不到崩（见待拍板 8） |

### colony
| 字段 | key | 作用 | 读点 | 打算       | 说明 |
|---|---|---|---|----------|---|
| `COLONY_RADIUS` | general.colonyRadius | 默认殖民地半径(块) | **0 读(死)** | **删**    | 纯死；半径在别处硬编码 |
| `COLONY_OFFLINE_INCOME_MULTIPLIER` | colony.offlineIncomeMultiplier | 离线收益系数(0~1) | 活(ColonyActivation:44) | **留**    | 玩家向，明确可调 |
| `INITIAL_ELEMENT_COUNT` | colony.initialElementCount | 首建仓库每种元素初始量 | 活(EnqueueHelper:479) | **留**    | 玩家向 |
| `COLONY_MAX_LEVEL` | colony.maxLevel | 城镇等级上限 | 活(ColonyLevelManager:66/117) | **留**    | 玩家向上限 |
| `COLONY_EXP_EQUAL_LEVEL` | colony.expEqualLevel | 同级游客离场给的经验 | 活(ColonyLevelManager:99/103) | **留**    | 等级成长速度，整合包强需求；原方案漏列 |
| `COLONY_EXP_ABOVE_LEVEL` | colony.expAboveLevel | 超镇级游客离场给的经验 | 活(ColonyLevelManager:100) | **留** | 同上 |

### transport
| 字段 | key | 作用 | 读点 | 打算 | 说明 |
|---|---|---|---|---|---|
| `TRANSPORT_TICKS_PER_BLOCK_ON_ROAD` | transport.ticksPerBlockOnRoad | 物品贴路速度(tick/格) | 活(TransportItemEntity:56, ItemTransportManager:72) | **Bal** | 方案⑤首批 |
| `TRANSPORT_TICKS_PER_BLOCK_OFF_ROAD` | transport.ticksPerBlockOffRoad | 物品离路速度 | 活(TransportItemEntity:57, ItemTransportManager:73) | **Bal** | 方案⑤首批 |

### scheduler
| 字段 | key | 作用 | 读点 | 打算 | 说明 |
|---|---|---|---|---|---|
| `SCHEDULER_HEARTBEAT_TICKS` | scheduler.heartbeatTicks | 空闲NPC匹配任务心跳(tick) | 活(EngineBootstrap:129) | **常量** | 性能心跳，非玩家向 |
| `SAME_BUILDING_CONTINUATION_BONUS` | scheduler.sameBuildingContinuationBonus | 同建筑续task加分 | **0 读(死)** | **删** | 活源 `WandscapeConstants:21` |
| `STUCK_CHECK_INTERVAL_TICKS` | scheduler.stuckCheckIntervalTicks | 卡死检测间隔 | 活(NavigationSystem:151) | **常量** | `WandscapeConstants:61` 已有同值(=60) |
| `STUCK_MIN_MOVE_DISTANCE` | scheduler.stuckMinMoveDistance | 非卡死最小位移 | 活(NavigationSystem:154) | **常量** | `WandscapeConstants:62` 已有(=2.0) |
| `STUCK_MAX_RETRIES` | scheduler.stuckMaxRetries | 卡死重试次数 | 活(NavigationSystem:158) | **常量** | `WandscapeConstants:63` 已有(=3) |

### npc / revive
| 字段 | key | 作用 | 读点 | 打算      | 说明 |
|---|---|---|---|---------|---|
| `NPC_REGEN_GRACE_TICKS` | npc.regenGraceTicks | 受击后回血宽限 | 活(WandscapeNpc:321) | **Bal** | 方案⑤首批 |
| `NPC_REGEN_INTERVAL_TICKS` | npc.regenIntervalTicks | 每1HP回血间隔 | 活(WandscapeNpc:333) | **Bal** | 方案⑤首批 |
| `NPC_MANA_REGEN_TICKS` | npc.manaRegenTicks | 魔力回复结算间隔 | 活(WandscapeNpc:1110) | **Bal** | 方案首批漏；回蓝节奏，弱需求 |
| `NPC_MANA_REGEN_FRACTION` | npc.manaRegenFraction | 回蓝占总魔比例 | 活(WandscapeNpc:1111) | **Bal** | 方案⑤首批 |
| `NPC_WALK_THRESHOLD` | npc.walkThreshold | 寻路上限(超则传送) | 活(NavigationSystem:89/90) | **常量**  | 方案⑤首批；`WandscapeConstants:48` 已有(=64) |
| `REVIVE_NEAR_BUILDING_RANGE` | revive.nearBuildingRange | 建筑附近战死即复活半径 | 活(ReviveHandler:89) | **Bal** | 玩家向 |

### wand
| 字段 | key | 作用 | 读点 | 打算 | 说明 |
|---|---|---|---|---|---|
| `BASE_OPERATION_RANGE` | wand.baseOperationRange | 法杖操作基础范围 | **0 读(死)** | **删** | 活源 `WandscapeConstants:43` |
| `PER_WAND_LEVEL_RANGE` | wand.perWandLevelRange | 每法杖等级加范围 | **0 读(死)** | **删** | 活源 `WandscapeConstants:44` |

### element
| 字段 | key | 作用 | 读点 | 打算 | 说明 |
|---|---|---|---|---|---|
| `ELEMENT_DECOMPOSE_DIVISOR` | element.decomposeDivisor | 分解返回元素值的 1/N | 活(JEI:208, Workstation:398, InteractExec:449) | **留** | 玩家向经济值 |
| `ELEMENT_CRAFT_COST_MULTIPLIER` | element.craftCostMultiplier | 合成/制造消耗倍率 | 活(ProdEligibility:71, InteractExec:481) | **留** | 玩家向，有警告注释 |

### tourist —— 生成/上限
| 字段 | key | 作用 | 读点 | 打算    | 说明 |
|---|---|---|---|-------|---|
| `TOURIST_MAX_PER_COLONY` | tourist.maxPerColony | 每殖民地游客上限 | 活(Spawn:260) | **留** | 玩家向 |
| `TOURIST_BASE_SPAWN_COUNT` | tourist.baseSpawnCount | 1级每日生成下限 | 活(Spawn:256) | **留** | 玩家向 |
| `TOURIST_LEVEL_SPAWN_BONUS` | tourist.levelSpawnBonus | 每级生成加成 | 活(Spawn:257) | **留** | 生成节奏 |
| `TOURIST_SPAWN_RANGE_WIDTH` | tourist.spawnRangeWidth | 生成波动宽度 | 活(Spawn:258) | **留** | 生成节奏 |

### tourist —— 时间窗
| 字段 | key | 作用 | 读点 | 打算 | 说明 |
|---|---|---|---|---|---|
| `TOURIST_SPAWN_WINDOW_START` | tourist.spawnWindowStart | 生成窗口开始(时刻tick) | 活(Spawn:183/268) | **常量** | 时间窗，偏内容配置 |
| `TOURIST_SPAWN_WINDOW_END` | tourist.spawnWindowEnd | 生成窗口结束 | 活(Spawn:184/269) | **常量** | 同上 |
| `TOURIST_DEPARTURE_WINDOW_START` | tourist.departureWindowStart | 离场窗口开始 | 活(Spawn:198, SimSystem:910) | **常量** | 同上 |
| `TOURIST_DEPARTURE_WINDOW_END` | tourist.departureWindowEnd | 离场窗口结束 | 活(Spawn:199, SimSystem:911) | **常量** | 同上 |
| `TOURIST_DEPARTURE_DELAY_MAX_TICKS` | tourist.departureDelayMaxTicks | 离场随机延迟上限 | 活(Spawn:545/561) | **常量** | 同上 |
| `TOURIST_NIGHT_START` | tourist.nightStart | 游客"夜晚"开始时刻 | 活(Sim:482, MoveGoal:263…) | **常量** | 作息起点，整合包可能想调（边界模糊，见待拍板 5） |

### tourist —— 停留/需求/钱包/交互
| 字段 | key | 作用 | 读点 | 打算          | 说明 |
|---|---|---|---|-------------|---|
| `TOURIST_STAY_MIN_DAYS` | tourist.stayMinDays | 最少停留天数 | 活(Spawn:384, Entity:906) | **留**       | 方案⑤首批 |
| `TOURIST_STAY_MAX_DAYS` | tourist.stayMaxDays | 最多停留天数 | 活(Spawn:385, Entity:907) | **留**       | 方案⑤首批 |
| `TOURIST_NEED_BASE` | tourist.needBase | 总需求基数 | 活(Spawn:424) | **留**       | 方案⑤首批 |
| `TOURIST_NEED_PER_LEVEL` | tourist.needPerLevel | 每级需求增量 | 活(Spawn:424) | **留**       | 方案⑤首批 |
| `TOURIST_BASE_WALLET` | tourist.baseWallet | 1级游客钱包 | 活(Spawn:835) | **留**       | 经济基数，整合包强需求；原方案首批漏 |
| `TOURIST_WALLET_PER_LEVEL` | tourist.walletPerLevel | 每级钱包增量 | 活(Spawn:835) | **留**       | 方案⑤首批 |
| `TOURIST_BAR_GAIN_COEFF` | tourist.barGainCoeff | 需求条增益系数 | 活(Sim:130/553) | **删，默认1.0** | 方案⑤首批 |
| `TOURIST_ENERGY_RESTORE_THRESHOLD` | tourist.energyRestoreThreshold | 精力偏低阈值 | 活(Sim:410/559) | **常量**      | 内容节奏 |
| `TOURIST_VISION_RADIUS` | tourist.visionRadius | 游客视野(目标选择) | 活(Sim:486) | **常量**      | 深度值 |
| `TOURIST_ARRIVAL_RADIUS`（`ARRIVAL_RADIUS`）| tourist.arrivalRadius | 到达判定距离 | 活(TouristMoveGoal:2173) | **常量**      | 深度值 |
| `MICRO_NAV_SWITCH_DISTANCE` | tourist.microNavSwitchDistance | 宏→微导航切换距离 | 活(TouristMoveGoal:419) | **常量**      | 深度值 |
| `TOURIST_QUEUE_WAIT_TOLERANCE_TICKS` | tourist.queueWaitToleranceTicks | 排队等待超时 | 活(SimSystem:519, MoveGoal:791) | **常量**      | 深度值 |
| `TOURIST_QUEUE_SLOT_SPACING` | tourist.queueSlotSpacing | 排队站位间距 | 活(Sim:242) | **常量**      | 深度值 |
| `TOURIST_ATM_TRAVEL_FUND_MULTIPLIER` | tourist.atmTravelFundMultiplier | ATM旅行资金倍率 | 活(Spawn:389) | **常量**      | 深度值 |
| `TOURIST_ATM_WITHDRAW_COOLDOWN_TICKS` | tourist.atmWithdrawCooldownTicks | ATM取现冷却 | 活(Sim:487) | **常量**      | 深度值 |
| `TOURIST_RESCUE_ROAD_RADIUS` | tourist.rescueRoadRadius | 救援寻路半径 | 活(Teleport:94) | **常量**      | 深度值 |
| `TOURIST_RESCUE_PERIPHERY_RADIUS` | tourist.rescuePeripheryRadius | 救援外围扫描半径 | 活(Teleport:162) | **常量**      | 深度值 |
| `TOURIST_HOTEL_TELEPORT_DISTANCE` | tourist.hotelTeleportDistance | 夜晚去旅店传送距离 | 活(MoveGoal:1137/1217) | **常量**      | 深度值 |
| `TOURIST_EVENING_ROUTING_START` | tourist.eveningRoutingStart | 傍晚路由开始 | 活(SimSystem:440/492, MoveGoal:267) | **常量**      | 时间窗 |

### guard / scepter
| 字段 | key | 作用 | 读点 | 打算                             | 说明 |
|---|---|---|---|--------------------------------|---|
| `GUARD_RANGE` | guard.range | 守卫威胁/攻击半径 | 活(GuardTaskSource/Command/Blueprints 多) | **Bal**                        | 玩家向 |
| `GUARD_RELEASE_RANGE` | guard.releaseRange | 守卫释放(回滞带)半径 | 活(GuardTaskSource:73) | **Bal**                        | 方案⑤首批 |
| `GUARD_SELF_DEFENSE_RANGE` | guard.selfDefenseRange | NPC自卫半径 | 活(SelfDefenseExecutor:146/196) | **Bal**                        | 方案⑤首批 |
| `GUARD_HATE_RANGE` | guard.hateRange | NPC仇恨范围 | 活(SelfDefense:189, Npc:844) | **Bal**                        | 方案⑤首批 |
| `GUARD_HATE_DURATION_TICKS` | guard.hateDurationTicks | 仇恨记忆时长 | 活(SelfDefenseHandler:56/61) | **Bal**                        | 战斗手感，整合包可能调；原方案首批漏 |
| `GUARD_FOLLOW_ATTACK_DURATION_TICKS` | guard.followAttackDurationTicks | 追击记忆时长 | 活(WandscapeNpc:835) | **Bal**                        | 同上 |
| `GUARD_PEACE_FLEE_RANGE` | guard.peaceFleeRange | 和平模式逃离半径 | 活(SelfDefenseExecutor:111/264) | **合并到`GUARD_FLEE_START_DIST`** | 同上 |
| `GUARD_KITE_START_DIST` | guard.kiteStartDist | 风筝触发距离 | 活(GuardCombat:139) | **Bal**                        | 方案⑤首批 |
| `GUARD_KITE_STANDOFF` | guard.kiteStandoff | 风筝对峙距离 | 活(GuardCombat:140/559) | **Bal**                        | 方案⑤首批 |
| `GUARD_SWAY_FLIP_TICKS` | guard.swayFlipTicks | 侧移方向重掷间隔 | 活(WandscapeNpc:1124) | **常量**                         | 战斗手感，弱需求 |
| `GUARD_ENGAGE_STANDOFF` | guard.engageStandoff | 接敌落点距离 | 活(GuardCombat:440) | **Bal**                        | 战斗手感；原方案首批漏 |
| `GUARD_FLEE_HP_THRESHOLD` | guard.fleeHpThreshold | 低血量逃离阈值 | 活(GuardCombat:138) | **Bal**                        | 方案⑤首批 |
| `GUARD_FLEE_START_DIST` | guard.fleeStartDist | 逃离触发距离 | 活(GuardCombat:139) | **Bal**                        | 方案⑤首批 |
| `GUARD_FLEE_STANDOFF` | guard.fleeStandoff | 逃离对峙距离 | 活(GuardCombat:140) | **Bal**                        | 方案⑤首批 |
| `SCEPTER_HOSTILE_RANGE` | scepter.hostileRange | 敌对法杖强制目标范围 | 活(SelfDefense:178, AttackExec:144) | **Bal**                        | 深度值；原方案完全漏 |

### raid
| 字段 | key | 作用 | 读点 | 打算 | 说明 |
|---|---|---|---|---|---|
| `RAID_TRIGGER_RANGE` | raid.triggerRange | 袭击触发半径 | 活(RaidTriggerScanner:104) | **常量** | 深度值；原方案漏 |
| `RAID_VILLAGE_RANGE` | raid.villageRange | 村庄判定半径 | 活(MixinServerLevel:27) | **常量** | 深度值 |
| `RAID_NEARBY_RADIUS` | raid.nearbyRadius | 袭击互斥半径 | 活(RaidTriggerScanner:66) | **常量** | 深度值 |
| `RAID_CHECK_INTERVAL` | raid.checkIntervalTicks | 袭击扫描间隔 | 活(RaidTriggerScanner:42) | **常量** | 深度值 |

### decoration / settlement / building
| 字段 | key | 作用 | 读点 | 打算 | 说明 |
|---|---|---|---|---|---|
| `DECORATION_BONUS_CAP` | decoration.bonusCap | 装饰加成上限(1.0=100%) | 活(BuildingContribution:256, DecorationSystem:104) | **Bal** | 装饰平衡，整合包可能调；原方案漏 |
| `DECORATION_SCAN_INTERVAL_TICKS` | decoration.scanIntervalTicks | 装饰重算间隔 | 活(DecorationBonusSystem:54) | **常量** | 性能间隔 |
| `SETTLEMENT_WINDOW_TICKS` | settlement.windowTicks | 每日结算窗口 | 活(DailySettlementSystem:60) | **常量** | 结算节奏 |
| `BUILDING_NO_SPAWN_IN_AREA` | building.noSpawnInBuildingArea | 建筑内禁自然刷怪 | 活(BuildingNoSpawnZoneHandler:29) | **留** | 玩家向开关 |

---

## Part B — API 增删

### 新增 / 变更（本方案）
| # | 接口 | 方法 | 类型 | 说明 |
|---|---|---|---|---|
| B1 | `WarehouseApi` | `addElement(UUID, ElementType, long)` | **签名改** void→boolean | 返回镇/仓库是否就绪；唯一经此接口的调用方 `ElementItem:64`（返回值可忽略，安全） |
| B2 | `WarehouseApi` | `addAllElements(UUID, Map<ElementType,Long>)` | 新增 | 批量加元素，返回成功 |
| B3 | `WarehouseApi` | `insertItems(UUID, List<ItemStack>)` | **签名改** void→boolean（可选） | 调用方全在 `WarehouseMenu`（4 处），改后返回值可忽略 |
| B4 | `ElementApi` | `registerMapping(String, Map<ElementType,Long>)` | 新增 | 程序化注册元素映射（减 JSON）；**签名待定**：去掉独立 `type`（我推荐）→ 见待拍板 2 |
| B5 | `ElementApi` | `unregisterMapping(String)` | 新增 | 撤销覆盖，回落 registry |
| B6 | `ColonyApi` | `getColonyLevel(UUID)` | 新增 | 查等级（0=无此镇） |
| B7 | `ColonyApi` | `getColonyExp(UUID)` | 新增 | 查经验（**建议 int** 对齐 `ColonyLevelData`）→ 见待拍板 3 |
| B8 | `ColonyApi` | `grantExperience(UUID, int)` | 新增 | 加经验+升级判定；**装配断点**：`ColonyApiImpl` 无 `ColonyLevelManager` 路径 → 见待拍板 4 |
### 按领域可调面（替代原 BalanceApi）—— 你标 **Bal** 的字段归属
> 无独立 `BalanceApi`；每个值 `getXxx()`/`setXxx(v)` 成对、运行时生效、**不追溯已生成实体**（契约同 §5）。
> | 领域 | API | 归它的 Bal 字段 |
> |---|---|---|
> | npc | `NpcApi` 扩 | 回血回蓝：`NPC_REGEN_GRACE_TICKS`/`NPC_REGEN_INTERVAL_TICKS`/`NPC_MANA_REGEN_TICKS`/`NPC_MANA_REGEN_FRACTION`；guard 战斗：`GUARD_RANGE`/`GUARD_RELEASE_RANGE`/`GUARD_SELF_DEFENSE_RANGE`/`GUARD_HATE_RANGE`/`GUARD_HATE_DURATION_TICKS`/`GUARD_FOLLOW_ATTACK_DURATION_TICKS`/`GUARD_KITE_START_DIST`/`GUARD_KITE_STANDOFF`/`GUARD_ENGAGE_STANDOFF`/`GUARD_FLEE_HP_THRESHOLD`/`GUARD_FLEE_START_DIST`/`GUARD_FLEE_STANDOFF`（`GUARD_PEACE_FLEE_RANGE` 你已合并进 `GUARD_FLEE_START_DIST`）；revive：`REVIVE_NEAR_BUILDING_RANGE`；scepter：`SCEPTER_HOSTILE_RANGE` |
> | warehouse | `WarehouseApi` 扩 | 运输：`TRANSPORT_TICKS_PER_BLOCK_ON_ROAD`/`TRANSPORT_TICKS_PER_BLOCK_OFF_ROAD` |
> | building | `BuildingApi` 扩 | 装饰：`DECORATION_BONUS_CAP` |
> | colony | `ColonyApi` 扩 | 操作/查询（非调优）：`grantExperience`/`getColonyLevel`/`getColonyExp`（见 B6–B8） |
> | element | `ElementApi` 扩 | `registerMapping`/`unregisterMapping`（见 B4–B5） |

### 本方案**不删**接口（避免混淆）
- `ColonyApi`/`ElementApi` 现方法全部保留，只加方法。
- 无接口被删除；只有 B1/B3 两处 **void→boolean 签名变更**（开发期不承诺二进制兼容，可接受）。

### 2e 砍面（已并入本方案，见 Part D）
- plan.md §三 设想的 `HouseApi`（死码）已删；`GuideProgressApi`/`ColonyMetricsApi`/`TavernApi`/`ScepterApi` 的砍削 + `BuildingApi`/`ColonyApi`/`TouristApi` 瘦内部桥——全部归 **Part D** 拍板，执行上独立于 Step 1。

---

## Part C — 待拍板（按方案改前请逐条定）

1. **①"镇不存在"判定口径**：改从 colony 注册表判（推荐，语义准）
2. **B4 `registerMapping` 签名**：去掉独立 `ElementType type`
3. **B7/B8 参数类型**：`getColonyExp`/`grantExperience` 用 **int**
4. **B8 装配**：`ColonyApi` 怎么拿到 `ColonyLevelManager`——注入式（推荐，在 `Wandscape.java` 装配时注入，不新增 `WandscapeEngine` getter 依赖）
5. **BalanceApi 首批扩多少**（我标 **Bal?** 的候选，你决定哪些进 BalanceApi）：
   - 经济：`COLONY_EXP_EQUAL_LEVEL`、`COLONY_EXP_ABOVE_LEVEL`、`TOURIST_BASE_WALLET`、`DECORATION_BONUS_CAP`
   - 战斗手感：`GUARD_HATE_DURATION_TICKS`、`GUARD_FOLLOW_ATTACK_DURATION_TICKS`、`GUARD_PEACE_FLEE_RANGE`、`GUARD_ENGAGE_STANDOFF`
   - 其余（游客时间窗 `SPAWN/DPARTURE/NIGHT/EVENING`、`VISION`、`SCEPTER_HOSTILE_RANGE`、scheduler `HEARTBEAT` 等）我默认**常量固定**；你若认为某类整合包会调，提升为 Bal?。
6. **④与⑤是否合并成一步**：合并
7. **`DEBUG`**：我建议留在 Config（通用、轻量），你也可以划到别处。
8. **`PARTICLE_LEVEL` 迁 Client 前**：先在代码确认 `ParticleService` 是否被 dedicated server 侧读（决定能否纯 CLIENT）。

---

---

## Part D — Tier 4 / 2e API 收敛（新增，待拍板）

> 背景：plan.md §三 公开契约形态 + 阶段序列 2e（"api 包启用、内部 0 泄漏"）。你已决定并入本次、一次规划。**执行建议分两步（见 D4），但同属本方案决策。**

### D1. 值不值得做（你的拍板：三个都做）
| 动作 | 判定 | 备注 |
|---|---|---|
| 解散 `WandscapeEngine` 定位器 | ✅ 做 | 36 引用改注入/直调；独立高危步（D3） |
| 砍纯内部搭桥接口（`ScepterApi` 等） | ✅ 做 | 解"为规避反向依赖搭桥"病 |
| `ColonyApi`/`BuildingApi`/`TouristApi` **接口瘦身**（删内部方法改直调） | ✅ 做 | 保留 addon 查询/操作面 |
| 新增"公开事件"流 | ⏸ 暂不做 | 算新功能；已有部分事件（`ColonyLevelUpEvent`/`TouristArrivedEvent`），无 addon 需求不加 |

### D2. api/ 接口逐方法去留（已确认，含消费方证据）
> 判定口径：**对 addon/整合包作者有明确使用场景 → 留；纯内部协作/钩子/深层状态 → 移出接口、改直调实现类**。跨包直调允许（CLAUDE.md）。消费方分布已 grep 核实。

**`ColonyApi`** —— 留：`createColony×2`、`getFounder`、`getColonyByFounder`、`getColonyId`、`deleteColony`、`isColonyOrigin`、`getAllColonyIds`、`getNamingStyle`、`setNamingStyle`；**移出**：`onBuildingIntact`、`onBuildingDestroyed`、`assignColonyIfPossible`、`rebuildFromSavedData`（building→colony 内部事件桥）。+新增 `grantExperience`/`getColonyLevel`/`getColonyExp`。

**`BuildingApi`** —— 留：`getBuilding`、`getBuildingAt`、`getColonyBuildings`、`getBuildingBounds`、`getBuildingsByCategory`、`getColonySnapshot`、`getColonyComfort/Magic/Wonder`、`demolishBuilding`、`isDemolishing`、`demolishBlockReason`、`cancelBuilding`、`placeBuilding`、`isFirstFreeClaimed`；**移出**：task 桥（`isBuildingOccupied`、`getBuildingsWithPendingWork`、`dequeueWork`、`dequeueWorkEligible`、`enqueueWork`、`setCurrentTask`、`getQueue`、`removeFromQueue`、`moveUp`、`moveDown`、`clearCurrentTask`）、`registerBuilding`、`unregisterBuilding`、tourist 导航查询（`findBeds`、`sampleWalkableGround`、`getTouristInteractionTarget`、`getEntryPoint`、`getTouristInteractPoint`→tourist 域直调）。+装饰调优 `DECORATION_BONUS_CAP`。

**`ElementApi`** —— 全留：`fromId`、`hasElementMapping`、`isDisabled`、`getBuildCost×2`、`elementItemId`；+`registerMapping`/`unregisterMapping`。无内部桥。

**`WarehouseApi`** —— 全留：`getElement`、`getAllElements`、`consumeElement`、`addElement`、`getItemCount`、`getItemSnapshot`、`extractItem`、`insertItems`；+`addAllElements`+transport 组。

**`RoadApi`** —— 全留：`getNetwork`、`getEdges`、`removeEdge`、`cancelEdge`。

**`WandApi`** —— 全留：`getWandColor`、`getWandPresetId`、`getWandModifiers`。

**`NpcApi`** —— 全留：`getColonyNpcs`、`getIdleNpcs`、`getNpcCount`、`getIdleNpcCount`、`getNpc`、`assignHouse`；+guard/npc/revive/scepter 调优。

**`TouristApi`** —— 留：`getTouristCount`、`getTouristsInColony`、`spawnTourist`、`getOvernightStayerCount`；**移出**：`registerArrival`、`registerDeparture`（系统→tourist 内部事件钩子）。

**`SpellcastingApi`** —— 全留：`getKnownSpells`、`getStrategyPreset`、`getPriority`、`setEquippedAndStrategy`（addon 魔法集成核心面）。

**`NpcAttributesApi`** —— 全留（属性规则覆盖，整合包直接用途）。

**`TavernApi`** —— **砍**。消费方全为 mod 内部（`TouristCommand`/`TavernCommand`/`BuildingInteractHandler`/`AchievementService`/`TouristSpawnSystem`/`TavernRecruitPacket×5`/`TouristSimSystem`），无 addon 价值；改直调 building 域 tavern 实现类（招法师跨域耦合允许）。

**`GuideProgressApi`** —— **保留（改判）**。消费方 10+ 跨域（`PanelStateTogglePacket`/`WarehouseMenu`/`RoadSegmentListener`/`RequestProductionTaskPacket`/`ProjectionPlacePacket×2`/`BuildingInteractHandler`/`ColonyCreateRequestPacket`/`RequestGatherTaskPacket`）——横切教程推进服务，砍会导致 10+ 处装配混乱；保留作横切服务接缝。

**`ColonyMetricsApi`** —— **保留（改判）**。消费方 `PanelStateTracker`/`PanelStateTogglePacket`/`ProjectionEnterPacket`/`ColonyNameUpdatePacket`（foundation/ui + colony 网络包）；是"镇指标快照"查询、addon 想看（统计/成就），且作 `foundation/ui→colony` 合理接缝。

**`ScepterApi`** —— **砍**。消费方仅 4 处全在 npc/guard（`WandscapeNpc`/`GuardTaskSource`/`SelfDefenseExecutor`/`GuardAttackExecutor`），改直调 scepter 实现类（npc→items 跨域直调允许）；解"防反依赖搭桥"病。

**`NpcInteractHook`/`NpcSneakInteractHook`** —— 移出 `api/`→`content/items`（标记接口非公开契约；addon 物品做成 NPC 交互点则留在 items 域作扩展点）。

**`WandscapeApis`** —— 瘦身：删已砍接口 getter（`getTavernApi`/`getScepterApi`）与 set 样板 getter；保留真公开 getter（ColonyApi/ElementApi/WarehouseApi/RoadApi/WandApi/NpcApi/TouristApi/SpellcastingApi/NpcAttributesApi + ColonyMetricsApi/GuideProgressApi）。silently getter 若消费方已改直调则一并删。

### D3. `WandscapeEngine` 解散
- 30 getter/setter、36 引用。域服务（`getColonyLevelManager`/`getTransporter`/`getGuardExecutor`/`getSelfDefenseExecutor`/`getRoadSavedData`/`getTaskPoolSavedData`/`getPlayerManualSource`/…）→ 消费方在 `Wandscape.java` 装配时**注入/构造**，不再静态 getter。
- **例外**：`getWorld()`（ECS `World` 真全局单例）必须仍由装配注入（不能 new），消费方经装配拿——这是唯一保留的钩子，但也是注入而非静态 getter。
- 风险高（改 36 处），须独立 + 每步 `compileJava` 兜底。

### D4. 执行切分建议（待你拍板）
- **强烈建议：同一方案文档 + 决策表，但分两步、两个 commit，中间留可编译点**：
  - **Step 1（本次 config-api）**：① `WarehouseApi` 加固 ② `ElementApi.registerMapping` ③ `ColonyApi.grantExperience` ④ Config 瘦身 + `ClientConfig` + **按领域可调面**（`NpcApi`/`WarehouseApi`/`BuildingApi` 调优）。`compileJava` 绿。
  - **Step 2（2e，单独）**：砍面（Scepter/Tavern/GuideProgress + Colony/Building/Tourist 瘦内部桥）+ 解散 `WandscapeEngine`。验收"api/ 只留真公开 + 内部 0 泄漏"。
- **理由**：两者都动 Config / api 实现 / `Wandscape.java`，合一个 commit 审查与回滚都难；2e 是重构高危，plan 本就列 2e 独立阶段；分开保留回滚点。
- 若你坚持一次全做成一个 commit：可行，但至少把"Config 瘦身"与"崩面/解散定位器"拆成**顺序**两个 commit，中间务必有一个能编译的检查点。

---

> 你调整完（Part A 打算列 / Part C / Part D 判断）告诉我。**要点**：Step 1 与 Step 2 是否分开？D2 各接口砍/留/瘦身是否照你的意思？D1 的"暂不做公开事件"认不认可？定稿后我按 Step 1 → Step 2 逐步 commit。
