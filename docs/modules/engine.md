# engine/ — 引擎适配层（MC 实现）

`src/main/java/com/wsteam/wandscape/engine/`

## 职责

实现 `core/boundary/` 全部接口，是模组唯一持有 MC 引用的实现层；负责边界实现、导航、服务、音效、运输、持久化与一次性装配。

## WandscapeEngine（静态单例）

持有：`World world`、`AsyncTransformExecutor asyncExec`、`WandscapeRitualOps ritualOps`、`WandscapeBlockInteractExecutor blockInteractExec`、`WandscapeMovementOps movementOps`、`BlueprintConfigLoader`、`TaskPoolSavedData`、`RoadSavedData`、`ItemTransportManager transporter`、`ResourceRequestExecutor resourceRequestExec`、`PlayerManualSource`、`GuardAttackExecutor`、`SelfDefenseExecutor`、`ColonyLevelManager`。

- `setWorld` 二次设置抛 IllegalStateException；`reset()` 置空 world/executors/SavedData（**刻意不置空 blueprintConfigLoader**，防重进世界破坏 DSL 蓝图注册）。
- 静态访问器：`getWorld/getAsyncExecutor/getTransporter/getRitualOps/getBlockInteractExec/getMovementOps/getResourceRequestExec/getColonyLevelManager` 等。

## EngineBootstrap.bootstrap() 装配顺序

1. BlueprintRegistry → 注册 JSON DSL 蓝图 + EventDrivenTaskSource/GuardBlueprints 默认蓝图；
2. SystemBlueprintRegistry；
3. 任务源列表：BuildingTaskSource / WorkbenchSource / GuardTaskSource；
4. 边界实现：BlockOps/EntityOps/RitualOps/MovementOps；
5. ColonyResourceAccess = WarehouseManager（否则 stub）；
6. CoreBootstrapConfig + BuildingTaskPool → `CoreBootstrap.bootstrap` 建 World；
7. Wire 仓库资源回调 → `taskPool::onResourceAdded`；`taskPool.setResourceShortageHandler` → ResourceSupplySystem.enqueueSynthesize；
8. 注册 DefaultOpExecutors + GuardAttackExecutor + SelfDefenseExecutor；
9. 注册系统 NavigationSystem、ResourceSupplySystem；
10. AsyncTransformExecutor（延迟=1tick）、ItemTransportManager、WandscapeBlockInteractExecutor、ResourceRequestExecutor；
11. setWorld；StatsService/AchievementService.register。

## boundary/（MC 实现）

- **WandscapeBlockOps**：目标=服务器主世界。setBlock 经 ChunkLoadManager 临时强加载、evacuateEntities 推开实体积压、放置/拆除音节流 10 tick。toggle/activate/open_gui；activate 失败回退红石脉冲；支持 `[prop=val]` 方块状态语法。
- **AsyncTransformExecutor**：execute 先扣 consumable 库存，`world.startAsyncOp` 取 Promise，pending 列表倒计时，到点放块 + 工作动画 + NPC_CAST 节流音；`effectiveDelay = delay / WORK_SPEED`；`tickAll` 每 MC tick 减计数（由 `Wandscape.onServerTick` 驱动）。
- **WandscapeMovementOps**：无内部状态；`navigateTo` 只写 NavigationState（PATHFINDING + target + future），由 NavigationSystem 驱动；`cancelNavigation` 复位 + 恢复 AI 游荡。
- **WandscapeRitualOps**：硬编码 channelTicks（self_teleport/item_teleport/player_summon=1，warding=200，group_vigor=400，rain_call/clear_weather=1200，portal_gate=1800）；`executeRitual` 目前仅实现 self_teleport（PORTAL 粒子 + 传送），其余 no-op。**self_teleport 落点经 `findSafeLanding` 搜索**：目标附近环形扫描，要求脚/头两格无碰撞、非液体、脚下实心地面且非房顶薄板（与 TouristTeleport 防房顶一致），杜绝传进建筑窒息/落房顶；找不到安全点回退原目标并 warn。
- **WandscapeEntityOps**：stub。
- **WandscapeBlockInteractExecutor**：toggle/activate/open_gui 同步；gather/decompose/synthesize/craft_wand/brew_potion 异步倒计时 + thenRun，缺料抛 ResourceShortageException → markAwaitingResources；WORK_SPEED 缩短。gather 加元素到 ColonyItemBank + 唤醒 AWAITING_RESOURCES + 元素飞行入库动画；synthesize/craft 等产物流入仓库 + 飞行动画。**注意：decompose 产物写 `colonyResources`（ResourceId 元素），而 synthesize/craft/brew 的元素消耗走 ColonyItemBank**（两类存储不同，详见 gaps）。
- **ResourceRequestExecutor**：**STAGGER_TICKS=5（每 5 tick 发一件，非 1/tick）**；全有或全无预检 + 整批 reserve；finish 入 NPC 库存 + ACTIVE；cancelForNpc 释放预留。

## colony/

- `ColonySavedData`（`wandscape_colonies`）：colonyId → origin + founder；saveNow 同步写盘绕过 NeoForge 异步 IO。
- `ColonyLevelData`（`wandscape_colony_levels`）：默认 level 1、exp 0、名"殖民地 XXXXXXXX"。
- `ColonyLevelManager`：`expToNext = level*1000`；贡献规则 tourist<colony→0、==→COLONY_EXP_EQUAL_LEVEL(200)、>→COLONY_EXP_ABOVE_LEVEL(500)；升级溢出转存 + `levelUpCallback`（发 ColonyLevelUpEvent record）+ 市政厅烟花/COLONY_LEVEL_UP 音。
- `ColonyApiImpl`：单例；createColony 同步持久化；getColonyId 256 格内最近原点查找；onBuildingIntact 不自动建殖民地仅链接；rebuildFromSavedData 优先 ColonySavedData、回退扫描 government 建筑。

## nav/

- `WandscapeNavigation`：继承 GroundPathNavigation，canOpenDoors/canPassDoors=true；A* 预算 = FOLLOW_RANGE×16。
- `RoadWalkPlanner`：用 `RoadRoutingHelper.planNpcWithRoads` 算路网 Dijkstra 路由，按 SAMPLE_INTERVAL=12 块采样成 waypoint；空列表→调用方回退直寻。

## service/

- **ChunkLoadManager**：单例引用计数强加载；`leaseBuilding` 按 footprint 强载、`releaseBuilding`；BuildingRemovedEvent 自动释放；init 释放上次遗留 lease。预算 **Config.MAX_CONCURRENT_BUILDINGS 默认 3**。
- `ChunkLeaseData`（`wandscape_chunk_leases`）：持久化 buildingId → chunks。
- **ColonyMetricsService**：getSnapshot 汇总舒适/魔法/奇观、等级/经验/名、游客数/过夜/满意度、停摆/破损建筑、NPC 空闲/总数、7 元素量。
- `StatsService`：订阅 NarrativeEventTriggered，onEvent 目前为 TODO 空实现。
- **AchievementService**：**授予原版进度**（PlayerAdvancements.award，授予对应殖民地 Founder 玩家），31 个 id；事件快路径 + 每 100 tick 全量重扫；条件：建筑数 1/5/10/20/50、等级 2/5/10/20/30、>50×50 奇观、酒店满员、商店全满、游客到访/满满意离场/过夜/在场峰值 10/30/50、法师简历/酒馆招募/法师数 5/10、元素存量 50k/500k、路网 15/50、自定义扫描建筑、袭击胜利（英雄 + omen≥5 风雨同舟）、连续 7 天无建筑停摆。
- `GuideProgressService`：服务端权威引导进度。纯函数 `computeStep` 返回 0-10 步（市政厅/仓库/存入/工作站/合成/铺路/面包店补货/节点发布采集/祭坛/旅馆入住），其中存入/合成/铺路/发布采集 4 步依赖 `ColonyItemBank` 的玩家动作计数；`GuideServerContext` 纯接口供单测。各动作包处理后调 `sendToPlayer` 即时推进。

## sound/

- `WandscapeSounds`：15 个 SoundEvent：magic_cast/magic_beam/building_place/projection_enter/projection_exit/overview_enter/warehouse/npc_cast/task_publish/guard_fire/colony_level_up/wonder_effect/road_place/colony_ambient_day/colony_ambient_night。
- `ColonyAmbientSystem`：客户端循环音，服务端 ColonyAmbientTracker 发包控制；setState(play, day) 切换昼夜循环；AmbientLoop 2D relative MASTER 通道、音量 0.1；tick 兜底停循环。

## source/

- **BuildingTaskSource**：POLL_INTERVAL=20 tick。poll：①清理已完成 head 任务、`btp.onHeadCompleted` 提升下一个、队列空释放 footprint lease；②按 UUID 排序建筑待办，预算内 `ChunkLoadManager.leaseBuilding`，`dequeueWork → btp.enqueue → pool.addTask`。
- `BlueprintConfigLoader`：解析 `data/wandscape/blueprints/` JSON → BlueprintDefinition AST，注册 `blueprints` 类别；支持 13 种步骤与表达式算子（$ 变量糖、算术、比较、get/size/keyof/map_to_items/format）。

## system/（ECS System，注册到 World）

- **NavigationSystem**：唯一移动驱动器。到达判定 5²；首帧距离²>64² → 切 ritual 传送；道路 waypoint 到点 2.25²、重寻路上限 5、超时 200 tick、卡死 60 tick×3 次 → 传送；远跳（>24²）走 RoadWalkPlanner；`switchToRitualTeleport` 受 `npc.tryCastSpell("teleport", 300, 30, 1)` 门控（互斥锁 + 传送独立 CD 300/SPELL_SPEED + 30 魔力，任一不满足回退走路），经 `world.ritualOps.beginRitual(SELF_TELEPORT)` 并写回 exec.pendingFuture。
- **ResourceSupplySystem**：40 tick 心跳。扫描 AWAITING_RESOURCES：可用即唤醒；缺料 `trySupplyResource → enqueueSynthesize`（去重 in-flight）→ `tryGatherElement`（node 建筑匹配元素）。

## transport/

- **ItemTransportManager**：管理仓库→NPC 间飞行物品动画。`send` 空路由回退直线；时长=离路段 10 tick/块、沿路段 5 tick/块；向 from 区块追踪玩家发 TransportStartPacket；`cancelForNpc` 退回 ownsItem 已消耗物品。
- **TransportItemEntity**：纯视觉 ItemEntity（shouldBeSaved=false）；客户端逐腿样条插值，离路段加 sin 弧；noGravity/noPhysics/无限拾取延迟；终点 discard。
- **TransportStartPacket**：S→C，handleClient 生成负 ID 实体。
- 渲染：`client/renderer/TransportItemEntityRenderer` 在物品上方画**金边暗灰气泡 + "xN" 数量文字**。
