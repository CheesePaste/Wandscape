# shared/ — 公共 API + 事件 + UI

所有包的公共层。50+ 文件。无实现代码，只有接口/数据/事件/UI组件。

## api/ — 10 个模块接口

| 接口 | 状态 | 实现在 |
|------|------|--------|
| WandApi | ✓ 已实现 | wand/internal/WandApiImpl（无 behaviors 方法） |
| ElementApi | ✓ 已实现 | element/internal/ElementApiImpl |
| BuildingApi | ✓ 已实现 | building/internal/BuildingApiImpl |
| NpcApi | ✓ 已实现 | npc/internal/NpcApiImpl |
| WarehouseApi | ✓ 已实现 | warehouse/WarehouseManager |
| RoadApi | ✓ 已实现 | engine/road/RoadApiImpl |
| ColonyApi | ✓ 已实现 | engine/colony/ColonyApiImpl |
| TavernApi | ✓ 部分实现 | building/internal/TavernApiImpl |
| TouristApi | ✓ 已实现 | tourist/internal/TouristApiImpl |
| StatsApi | ✓ 已实现 | stats/internal/StatisticsCollector |

**已删除的旧桩（不再维护）：** AtomicExecutor、HouseApi、ManaPoolApi（未实现桩已移除）

## registry/ — 全局注册

- **WandscapeApis.java** — 静态服务定位器，持有全部 API 实现。未注册时抛异常。
- **WandscapeConstants.java** — 硬编码默认值（Config.java TOML 未覆盖时的 fallback）
- **WandscapeDataRegistry.java** — 泛型数据查询接口

## data/ — 共享数据类型

**枚举**: ElementType(9元素3层) / TaskStatus(6状态) / Emotion / NarrativeEventType / MaintenancePriority

**Record**: WorkItem(blueprintId+params+priority, 无 wandRequirementOverrides) / BuildingData / NpcData / TaskTemplate / ItemKey / ExecutionResult / RecruitmentCandidate / BlueprintInfo / MageResume / NarrativeEvent / ParamTypeInfo / VisitMemory / MaintenanceCost / InterruptRecord

## event/ — 12 个保留的事件

全部在 shared/event/，继承 `net.neoforged.bus.api.Event`。模块间通过发布/订阅通信。

| 事件 | 发布者 | 触发时机 |
|------|--------|---------|
| ColonyCreatedEvent | engine | 殖民地创建 |
| DailySettlementEvent | building | 每日维护结算 |
| MaintenanceForecastWarningEvent | building | 维护费预警（元素不足） |
| ShopRestockedEvent | building | 商店补货 |
| TouristArrivedEvent | tourist | 游客到达 |
| TouristDepartedEvent | tourist | 游客离开 |
| WonderEffectChangedEvent | building | 奇观效果变化 |
| BuildingPlacedEvent | building | 建筑验证通过 |
| BuildingShutdownEvent | building | 建筑关停 |
| BuildingRestartedEvent | building | 建筑重启 |
| ResourceInsufficientEvent | warehouse | 资源不足(10s冷却) |
| ColonyEvaluationChangedEvent | building | 殖民地三值变化 |

**已删除事件（从未 fire）：** TaskPublishedEvent、TaskAssignedEvent、TaskCompletedEvent、TaskInterruptedEvent、TaskAwaitingMaterialsEvent、MaintenanceTickEvent、ElementChangedEvent、NpcDiedEvent、NpcRecruitedEvent、NpcResurrectedEvent

## network/ — 共享网络包 (3 文件)

跨模块使用的数据包：

- **PanelStateTogglePacket.java** — C→S，通知服务器面板开关状态
- **ColonyStatsSyncPacket.java** — S→C，同步殖民地评估值（舒适度/魔力/奇观）到面板，客户端处理器更新 WandscapePanelState
- **PanelStateTracker.java** — 服务端追踪哪些玩家面板打开，监听 ColonyEvaluationChangedEvent 推送更新

## log/ — 日志工具

- **Log.java** — 集中式日志工具类，封装 `java.util.logging`。提供 debug/info/warn/error 静态便捷方法，支持 `String.format` 和 SLF4J 两种占位符格式。包含紧凑的 BriefFormatter 输出 `[LEVEL] tag | message`
- **LogFilter.java** — 运行时日志标签白名单过滤器（CopyOnWriteArraySet 线程安全）。启用时仅白名单标签通过。预设 dev/debug 标签集（Scheduler/Preview/BuildEditor/Projection/Panel 等）

## ui/ — UI 组件库（40+ 文件，8 个子包）

中世纪魔法主题。CC0 精灵图(Tiny RPG Mana Soul GUI) + 程序化渲染混合。

| 子包 | 内容 |
|------|------|
| **component/** | MedievalScreen(9-slice面板) / MedievalButton(精灵图4态) / TabBar / ScrollableList(虚拟滚动) / ElementPanel(9元素储量) / ProgressIndicator / SearchBar / TaskQueuePanel(任务队列侧边栏) / Slider / DemoScreen / HelpButton / IconButton / 方向按钮等 |
| **panel/** | WandscapePanelState(客户端静态状态+BuildPhase状态机) / WandscapePanelController / WandscapePanelOverlay(顶部三值栏+底部模式页签) / BuildingSelectionOverlay |
| **task/** | TaskEditorClientState(客户端任务编辑器状态) / TaskEditorScreen(蓝图列表+参数编辑+提交) |
| **editor/** | UIEditorScreen / UILayoutManager / WidgetLayout |
| **util/** | BuildingPreviewRenderer(独立3D等轴测建筑缩略图渲染器) |
| **animation/** | MedievalAnimation（动画辅助） |
| **skin/** | SkinSprite(精灵图坐标) / SkinRender(9-slice/按钮/p标签/滚动条绘制) |
| **theme/** | MedievalColors(羊皮纸/金色系/紫色系/功能色) |
