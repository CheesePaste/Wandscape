# shared/ — 共享层

`src/main/java/com/wsteam/wandscape/shared/`

## 职责

所有包可见的公共层：模块 API 接口、数据类、NeoForge 事件、日志、网络包、UI 组件库、新手引导、Markdown 文档阅读器。

## api/（12 个接口）

| 接口 | 主要方法 |
|---|---|
| `BuildingApi` | getBuilding/getBuildingAt/getColonyBuildings/getBuildingBounds；registerBuilding/unregisterBuilding；shutdown/restart；demolishBuilding；getColonySnapshot(→ColonySnapshot 三值)/getColonyComfort/Magic/Wonder；队列操作（enqueueWork/dequeueWork/moveUp/moveDown）；placeBuilding(→PlacementResult)；isFirstFreeClaimed/findBeds/sampleWalkableGround/getTouristInteractionTarget/getEntryPoint/getTouristInteractPoint |
| `ColonyApi` | createColony(origin[,founder])/getFounder/getColonyByFounder/getColonyId/deleteColony/isColonyOrigin/onBuildingIntact/onBuildingDestroyed/assignColonyIfPossible/getAllColonyIds/rebuildFromSavedData |
| `ColonyMetricsApi` | getSnapshot + 兜底 getSnapshotSafe |
| `ElementApi` | fromId/hasElementMapping/getBuildCost/getDecomposeYield/isDecomposable（BlockState + ItemStack 两组重载） |
| `GuideProgressApi` | sendToPlayer(player, colonyId) |
| `HouseApi` | getAssignedNpc/isOccupied/assignNpc/unassignNpc/getVacantHouses |
| `NpcApi` | getColonyNpcs/getIdleNpcs/getNpc/assignHouse + 默认 getNpcCount/getIdleNpcCount |
| `RoadApi` | getNetwork/getEdges/requestFullRebuild/requestIncrementalUpdate/getBuildingThreshold/getRoadBlock/getBlobCache/removeEdge |
| `TavernApi` | getCandidates/refreshCandidates/recruitCandidate/receiveMageResume/getMageResumes/recruitMage |
| `TouristApi` | getTouristCount/getTouristsInColony/spawnTourist/getAverageSatisfaction/registerArrival/registerDeparture/getOvernightStayerCount |
| `WandApi` | getWandColor(ItemStack) |
| `WarehouseApi` | getElement/getAllElements/consumeElement/addElement/getItemCount/extractItem/insertItems |

## registry/

- `WandscapeApis`：12 个静态 volatile 字段 + getter/setter。getter 未注入即抛 `IllegalStateException`。仅部分有 `getXxxSilently()`（@Nullable 不抛）：Warehouse/Npc/Building/Colony/Tourist/ColonyMetrics/GuideProgress。**无 `getGuideProgressApi()`（仅 Silently）**。
- `WandscapeConstants`：BUILDING_CATEGORY_GOVERNMENT="government"；SCHEDULER_HEARTBEAT_TICKS=40；SAME_BUILDING_CONTINUATION_BONUS=50.0；队列容量 QUEUE_TOWNHALL/HOUSE/TAVERN=5、QUEUE_POTION/RITUAL_ALTAR/NODE=10、QUEUE_WORKSTATION/CRAFTING=60；WORKSTATION_CRAFT_TICKS_PER_UNIT=10、CRAFTING_STATION_CRAFT_TICKS_PER_UNIT=1200；BASE_OPERATION_RANGE=16、PER_WAND_LEVEL_RANGE=8；DEFAULT_COLONY_RADIUS=128；NPC_WALK_THRESHOLD=64；卡死 STUCK_CHECK_INTERVAL_TICKS=60/STUCK_MIN_MOVE_DISTANCE=2.0/STUCK_MAX_RETRIES=3。
- `WandscapeDataRegistry`：泛型接口 get(id)/getAll()/contains(id)。

## data/（数据类）

- `BuildingData` interface：getBuildingId/getColonyId/getBuildingTypeId/getCategory/getPosition/isShutdown/getComfort/getMagic/getWonder/getQueueCapacity/isStructureIntact/getMaintenanceCost/...。
- `CharacterNames`：法师与游客共享的**双语**随机名池（44 个名字，`wandscape.character_name.<i>` lang key：zh 中文 / en 拼音），`generateRandomNameKey()` 生成 key，`displayComponent` 返回 translatable 组件（客户端按语言渲染），`localizedString` 解析当前语言（无 lang 条目时回退中文，旧存档纯中文名原样通过）。游客 `getTouristName()` 解析、`getTouristNameKey()` 存 key；NPC 自动命名 `setCustomName(displayComponent(key))`。
- `ColonyMetricsSnapshot` record：colonyId + 三评 + 名称/等级/经验 + 游客数/过夜数/平均满意度 + NPC 空闲/总数 + 七元素 + 停机/损坏名单；EMPTY、totalAnomalyCount()。
- `ElementType` enum：EARTH/WOOD/WATER/FIRE/METAL/WIND/DARK。
- `Emotion` enum：DELIGHTED/PLEASED/SATISFIED/NEUTRAL/DISAPPOINTED/UPSET。
- `ExecutionResult(success, errorMessage)`、`InterruptRecord(npcId, timestamp)`、`ItemKey(itemId, nbt)`、`MageResume`（见 npc 模块）、`RecruitmentCandidate`、`VisitMemory`（builder）、`BlueprintInfo(id, displayName, description, params)`。
- `MaintenanceCostConfig(Map<ElementType,Integer> costs)` NONE；`MaintenancePriority` CRITICAL/HIGH/NORMAL/LOW。
- `NarrativeEvent(type, gameTime, emotion, text)`；`NarrativeEventType` 10 种，chronicleWorthy（SATISFACTION_MILESTONE/PREFERENCE_SHIFT/MAGE_RECRUIT/DEPARTURE_SUMMARY）。
- `NpcData` interface：getNpcId/getName/getMaxHealth/getCurrentHealth/getSpellPower/getWorkSpeed/getSpellSpeed/getArmorValue/isIdle/...。
- `ParamTypeInfo` enum：STRING/INT/POS/LIST_POS/LIST_STRING/MAP_STRING_STRING。
- `ServiceConfig(energyPerUse, elementOutput, maxOccupancy, interactionDurationTicks)` NONE。
- `ShopConfig(goods, profitRate, interactionDurationTicks)`；`ShopGoodDef(itemId, comfort, magic, wonder)` DEFAULT_MAX_STOCK=0。
- `WonderConfig(List<WonderEffect>)`；`WonderEffect` sealed：StatMod/PriceMod/RuleUnlock + 按 type 字段反序列化。
- `WorkItem(blueprintId, params, priority)`。
- `GuideProgressSavedData`（`wandscape_guide_progress`）：内嵌 record GuideProgress(stepIndex, dismissed)。

## event/（16 个 NeoForge 事件）

| 事件 | 触发者 |
|---|---|
| BuildingPlacedEvent / BuildingRemovedEvent | BuildingApiImpl / BuildCompleteListener |
| BuildingShutdownEvent(reason) / BuildingRestartedEvent | BuildingApiImpl |
| ColonyCreatedEvent | ColonyCommand |
| ColonyEvaluationChangedEvent(old/new 三评) | BuildingContributionRegistry |
| **ColonyLevelUpEvent**（record，非总线） | ColonyLevelManager.levelUpCallback |
| ColonyRaidStartedEvent / ColonyRaidVictoryEvent | raid/ |
| DailySettlementEvent(带 SettlementReport) | DailySettlementSystem |
| MaintenanceForecastWarningEvent(shortfall/dailyCost) | MaintenanceForecastSystem |
| ResourceInsufficientEvent | WarehouseManager |
| ShopRestockedEvent | ShopStockManager |
| TouristArrivedEvent / TouristDepartedEvent | TouristApiImpl |
| WonderEffectChangedEvent(activeEffects) | WonderEffectApplier |

## log/

`Log`：java.util.logging 包装，`verbose` 开关（Config.DEBUG）切换 ROOT logger + ConsoleHandler 级别 FINE↔WARNING；info 仅当 verbose 且 LogFilter.allows(tag)；warn/error；BriefFormatter。`LogFilter`：enabled + CopyOnWriteArraySet 白名单 add/remove/clear/setWhitelist/allows；presetPreviewDebug()。

## network/

`PanelStateTracker`：服务端记录面板打开玩家（ConcurrentHashMap.newKeySet），监听 PlayerLoggedOut/ColonyEvaluationChanged/TouristArrived/Departed → syncHudForColony。其余包（均 record + STREAM_CODEC）：
- `ColonyStatsSync`（S→C 聚落快照）；`ColonyAmbient`（S→C 环境音开关）；`ColonyCreatePrompt`/`ColonyCreateRequest`/`ColonyNameUpdate`；`GuideProgressSync`/`GuideProgressUpdate`/`GuideTest`；`MagicCircleCast`（S→C 施法动画）；`PanelStateToggle`（C→S 面板开关）；`ParticleBurst`（S→C 染色粒子）；`BuildingAreaSync`（S→C 建筑条目列表，客户端缓存 getCached/findBuildingIdAt）。

## ui/（UI 组件库）

- `MedievalScreen`（abstract extends Screen）：MINIMAL 风格——renderMinimalHeader/drawMinimalBox/drawGlowBorder；H 键/帮助按钮 → openHelpDocument() 加载 markdown。
- `MedievalButton` / `TabBar`（下划线高亮）/ `ScrollableList`（抽象泛型行渲染 + 滚轮）/ `TaskQueuePanel`（当前任务行 + 队列 Entry + 上移/下移/删除按钮）/ `ElementPanel`（按 ElementType 显示存量）。
- 面板：`WandscapePanelController`（MouseButton/Key/MovementInput/ClientTick，ESC 退出管线）、`WandscapePanelOverlay`（RenderGuiEvent.Post 画顶栏 TOP_BAR_H=28/侧栏 3 图标/警告浮层）、`WandscapePanelState`（enum SubMode{NONE,BUILD_PROJECTION,ROAD_PROJECTION,STATS,OVERVIEW}、BuildPhase{BAR,PLACING}、面板开合/游标抬起/聚落统计）。
- `I18n.name(key, fallback[, args])` → Component.translatableWithFallback。
- `ReplayScreenGuard`：反射检测 ReplayMod，`ScreenEvent.Opening` 取消任何 MedievalScreen。
- `WandscapeTheme`（RTS 风格颜色 + 图标 + drawRtsBox）；`MedievalColors`（羊皮纸/金边/紫罗兰配色）；`SkinRender`（9-slice 面板/按钮/箭头/条形图）。
- 按键：`WandscapeClient` — V=面板、C=游标、H=帮助文档、B=建筑区域、G=总览。

## guidance/（新手引导）

- **步骤硬编码**于 `GuideRegistry`（9 步）：townhall/warehouse/node/workstation/craft_station/shop/inn/tavern/level_up，每步三种文案（default/bar/placing）。
- `GuideStep(id, title, defaultLines, barLines, placingLines, hint)`；`linesFor(buildMode, isPlacing, isBar)`。
- `GuideSession`：客户端静态状态 serverStep/dismissed；applySync 弹 toast、dismiss() 发 GuideProgressUpdatePacket。
- `GuideRenderer`：屏幕右上角教程覆盖框。
- 权威计算在 engine：`GuideProgressService.computeStep` 纯函数返回 0-9 步；持久化 GuideProgressSavedData。

## markdown/（游戏内文档阅读器）

- `MarkdownParser`：逐行解析为 AST（HeaderNode/ListNode/TableNode/ImageNode/QuoteBlockNode/TextParagraphNode，图片支持 `=(W)x(H)` 语法）。
- `MarkdownRenderWidget`：滚动渲染 + 链接命中区（LinkHitBox）。
- `DocumentLoader`：`GUIDE_ROOT="assets/wandscape/guide/"`，按客户端语言解析 en/zh_cn/ 子目录，默认 zh_cn；loadMarkdown(location)。
- `DocumentHistoryStack`：前进/后退；`GifDecoder`：ImageIO 解码 GIF 帧 → GifFrame(NativeImage, delayMs)。
- 用途：**GuideTestScreen**（extends MedievalScreen）即文档阅读器；经 MedievalScreen.openHelpDocument、WandscapePanelController、SplineEditor 帮助触发。assets 下约 20 篇 md。

## client/ + entity/

- `SpeechBubbleRenderer`（世界空间聊天气泡，淡入淡出）；`TransientBubbleStore`（按实体 UUID 存 Event(iconKind,iconId,count,satBefore,satAfter,startTick)；iconKind ICON_NONE/ICON_ITEM/ICON_ELEMENT）；`SatisfactionBarRenderer`（满意度条 4 格）；`AmbientTextPools`（游客/NPC 随机文本池，key 为 Emotion×TouristState，i18n key `bubble.wandscape.tourist.*`）。
- `BuildingGhostRenderer`：放置虚影渲染，GHOST_ALPHA=0.40f，hideBuiltBlocks 跳过已建方块。
- `VillagerLike`：空标记接口，表示"原版敌对生物像对待村民一样索敌"（HostileTargetingHandler 消费）。
