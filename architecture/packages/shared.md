# shared/ — 公共 API + 事件 + UI

所有包的公共层。50+ 文件。无实现代码，只有接口/数据/事件/UI组件。

## api/ — 12 个模块接口

| 接口 | 状态 | 实现在 |
|------|------|--------|
| WandApi | ✓ 已实现 | wand/internal/WandApiImpl |
| ElementApi | ✓ 已实现 | element/internal/ElementApiImpl |
| BuildingApi | ✓ 已实现 | building/internal/BuildingApiImpl |
| NpcApi | ✓ 已实现 | npc/internal/NpcApiImpl |
| WarehouseApi | ✓ 已实现 | warehouse/WarehouseManager |
| RoadApi | ✓ 已实现 | engine/road/RoadApiImpl |
| AtomicExecutor | ✗ 未实现（被core/op替代）| — |
| ColonyApi | ✓ 已实现 | engine/colony/ColonyApiImpl |
| HouseApi | ✗ 未实现 | — |
| ManaPoolApi | ✗ 未实现 | — |
| TavernApi | ✓ 部分实现 | building/internal/TavernApiImpl |
| TouristApi | ✓ 已实现 | tourist/internal/TouristApiImpl |

## registry/ — 全局注册

- **WandscapeApis.java** — 静态服务定位器，持有全部 API 实现。未注册时抛异常。
- **WandscapeConstants.java** — 硬编码默认值（Config.java TOML 未覆盖时的 fallback）
- **WandscapeDataRegistry.java** — 泛型数据查询接口

## data/ — 共享数据类型

**枚举**: BehaviorType(8行为领域) / ElementType(9元素3层) / TaskStatus(6状态)

**Record**: AbilitySet(不可变能力并集) / WorkItem(blueprintId+params+priority) / BuildingData / NpcData / WandBehaviorData / WarehouseEntry / TaskTemplate / ItemKey / ExecutionResult / RecruitmentCandidate / InterruptRecord

**Sealed**: AtomicStep(4变体：OperationA/B/C/D) — 注意：这是旧设计，引擎实际用 core/op/AtomicOp(7变体)

## event/ — 16 个 NeoForge 事件

全部在 shared/event/，继承 `net.neoforged.bus.api.Event`。模块间通过发布/订阅通信。

| 事件 | 发布者 | 触发时机 |
|------|--------|---------|
| TaskPublishedEvent | engine | 任务入全局池 |
| TaskAssignedEvent | engine | NPC领取任务 |
| TaskCompletedEvent | engine | 任务完成 |
| TaskInterruptedEvent | engine | 任务中断 |
| TaskAwaitingMaterialsEvent | engine | 资源不足 |
| BuildingPlacedEvent | building | 建筑验证通过 |
| BuildingShutdownEvent | building | 建筑关停 |
| BuildingRestartedEvent | building | 建筑重启 |
| MaintenanceTickEvent | building | 维护结算 |
| ElementChangedEvent | warehouse | 元素储量变化 |
| ResourceInsufficientEvent | warehouse | 资源不足(10s冷却) |
| NpcDiedEvent | npc | NPC死亡 |
| NpcRecruitedEvent | — | 未实现 |
| NpcResurrectedEvent | — | 未实现 |
| ColonyEvaluationChangedEvent | building | 殖民地三值变化 |
| PanelStateTogglePacket | panel | 面板开关 |

## bridge/ — 类型桥接

**TypeBridge.java** — core 类型 ↔ shared 类型双向映射：BehaviourTag↔BehaviorType / TaskState↔TaskStatus / GridPos↔BlockPos / ResourceId↔String

## network/ — 共享网络包 (2 文件)

跨模块使用的数据包：
- **PanelStateTogglePacket.java** — C→S，通知服务器面板开关状态
- **ColonyStatsSyncPacket.java** — S→C，同步殖民地三值到面板
- **PanelStateTracker.java** — 服务端追踪哪些玩家面板打开，监听 ColonyEvaluationChangedEvent 推送更新

## ui/ — UI 组件库（33 文件）

中世纪魔法主题。CC0 精灵图(Tiny RPG Mana Soul GUI) + 程序化渲染混合。

**核心组件**: MedievalScreen(9-slice面板) / MedievalButton(精灵图4态) / TabBar / ScrollableList(虚拟滚动) / ElementPanel(9元素储量) / ItemGrid / ProgressIndicator / QuantitySlider / SearchBar / TaskQueuePanel(任务队列侧边栏)

**面板系统 (ui/panel/)**: WandscapePanelState(客户端静态状态+BuildPhase(BAR/PLACING)状态机) / WandscapePanelController(V/C/Escape键+鼠标页签点击+建筑栏键盘搜索+双击选择) / WandscapePanelOverlay(顶部三值栏+底部模式页签, RenderGuiEvent.Post渲染) / BuildingSelectionOverlay(进入Build模式自动弹出：种类分区+搜索框+建筑图标网格，双击选择建筑进入PLACING阶段，ESC从PLACING返回选栏)

**精灵图渲染**: SkinSprite(坐标定义) / SkinRender(9-slice/按钮/p标签/滚动条绘制)

**主题**: MedievalColors(羊皮纸/金色系/紫色系/功能色)
