# 02 — 共享 API (`shared/`)

所有模块的公共接口、事件类、数据类型、UI 组件库。50+ 文件。

## api/ — 模块 API 接口 (11 文件)

每个接口有一个 `*Impl` 实现类，在对应模块的 `internal/` 包中，通过 `WandscapeApis` 注册。

| 接口 | 作用 | 实现在 |
|------|------|--------|
| `WandApi.java` | 法杖 NBT 读取 + AbilitySet 并集计算 | `wand/internal/WandApiImpl` |
| `ElementApi.java` | 元素 ID→ElementType 查询 + BlockState→build_cost/decompose_yield | `element/internal/ElementApiImpl` |
| `BuildingApi.java` | 建筑注册/关停/重启/队列/三数值 | `building/internal/BuildingApiImpl` |
| `NpcApi.java` | NPC 查询/spawn/assignHouse | `npc/internal/NpcApiImpl` |
| `AtomicExecutor.java` | 原子步骤 A/B/C/D 执行入口 | 阶段 2 由 core 引擎 OpExecutor 替代 |
| `TaskApi.java` | 任务发布/审批/取消/挂起/查询 | 阶段 3 |
| `WarehouseApi.java` | 殖民地元素/物品存取 | 阶段 3 |
| `ColonyApi.java` | 殖民地创建/删除/查找 | 阶段 4 |
| `HouseApi.java` | NPC→房屋绑定/解绑/查询 | 阶段 4 |
| `ManaPoolApi.java` | 殖民地魔力池读写 | 阶段 4 |
| `TavernApi.java` | 酒馆候选人刷新/招募 | 阶段 4 |

## event/ — NeoForge 事件 (14 文件)

全部继承 `net.neoforged.bus.api.Event`，定义在 shared 层，由各模块发布。

| 事件 | 发布模块 | 触发时机 |
|------|---------|---------|
| `TaskPublishedEvent` | 06 task | 任务入全局池 |
| `TaskAssignedEvent` | 06 task | NPC 分配到任务 |
| `TaskCompletedEvent` | 06 task | 任务完成 |
| `TaskInterruptedEvent` | 06 task | 任务中断 |
| `TaskAwaitingMaterialsEvent` | 06 task | 元素/物品不足 |
| `BuildingPlacedEvent` | 08 building | 建筑结构验证通过 |
| `BuildingShutdownEvent` | 08 building | 建筑关停 |
| `BuildingRestartedEvent` | 08 building | 建筑重启 |
| `MaintenanceTickEvent` | 08 building | 维护结算周期 |
| `ElementChangedEvent` | 04 warehouse | 元素储量变化 |
| `NpcDiedEvent` | 07 npc | NPC 死亡 |
| `NpcRecruitedEvent` | 12 tavern | NPC 招募完成 |
| `NpcResurrectedEvent` | 13 ritual | 复活仪式完成 |
| `ColonyCreatedEvent` | 15 colony | 殖民地创建 |
| `ColonyDeletedEvent` | 15 colony | 殖民地删除 |

## data/ — 数据类型 (17 文件)

**枚举 (4)**

| 文件 | 作用 |
|------|------|
| `BehaviorType.java` | 8 行为领域：BUILDING / FARMING / MINING / LOGGING / CRAFTING / GATHERING / RITUAL / ENTITY_INTERACTION |
| `ElementType.java` | 9 元素 3 层：EARTH/WOOD/WATER(1) / FIRE/IRON/WIND(2) / GOLD/DIAMOND/ENDER(3) |
| `TaskStatus.java` | 6 状态：PENDING_APPROVAL / PENDING_ASSIGN / IN_PROGRESS / AWAITING_MATERIALS / INTERRUPTED / COMPLETED |

**记录 (7)**

| 文件 | 作用 |
|------|------|
| `AbilitySet.java` | 法杖能力并集快照：`Map<BehaviorType, Integer>` + merge / satisfies |
| `ExecutionResult.java` | `AtomicExecutor` 执行结果：ok() / fail(msg) |
| `InterruptRecord.java` | 任务中断记录：npcId + timestamp |
| `ItemKey.java` | 物品复合键：registryId + 可选 NBT |
| `RecruitmentCandidate.java` | 酒馆招募候选人属性：level / health / mana / spellPower / regen / wandIds |
| `TaskTemplate.java` | 任务蓝图：BehaviorType + level + AtomicStep 列表 + priority |
| `WarehouseEntry.java` | 仓库物品条目：itemId + NBT + 数量 |
| `WorkItem.java` | 建筑 FIFO 队列中的工作项：blueprintId + params + priority |

**接口 (4)**

| 文件 | 作用 |
|------|------|
| `BuildingData.java` | 建筑只读视图：id / colonyId / typeId / pos / shutdown / stats / queue |
| `ElementStore.java` | 元素存储只读视图：getAmount / has |
| `NpcData.java` | NPC 只读视图：id / health / mana / spellPower / abilities / house / task / death |
| `WandBehaviorData.java` | 法杖只读视图：color / behaviorLevels / range / manaMultiplier |

**Sealed 接口 (1)**

| 文件 | 作用 |
|------|------|
| `AtomicStep.java` | sealed 接口 + 4 变体：OperationA(block transform) / B(building action) / C(entity effect) / D(ritual channeling) |

## bridge/ — 类型桥接 (1 文件)

| 文件 | 作用 |
|------|------|
| `TypeBridge.java` | core 类型 ↔ shared 类型双向映射：`BehaviourTag↔BehaviorType` / `TaskState↔TaskStatus` / `GridPos↔BlockPos` / `ResourceId↔String` |

## ui/ — UI 组件库 (30 文件)

中世纪魔法主题可复用 UI 组件。程序化渲染 + CC0 精灵图混合：面板背景、按钮、页签、关闭按钮等核心元素使用 Tiny RPG Mana Soul GUI 精灵图，其余组件程序化渲染。

**skin/** — 精灵图渲染层

| 文件 | 作用 |
|------|------|
| `SkinSprite.java` | 精灵图坐标定义 + 所有 sprite sheet 的 ResourceLocation + 9-slice 参数 |
| `SkinRender.java` | 精灵图渲染器：`drawPanel9Slice` / `drawButton` / `drawCloseButton` / `drawTab*` / `drawHeader` / `drawBar` / `drawHelpButton` / `drawOptionButton` / `drawExitButton` / `drawLessButton` / `drawMoreButton` / `drawLeftArrow` / `drawRightArrow` / `drawUpArrow` / `drawDownArrow` 等 |

纹理文件位于 `assets/wandscape/textures/gui/skin/`，共 20 个 PNG（来自 CC0 包 Tiny RPG - Mana Soul GUI）。

**theme/** — 色板常量

| 文件 | 作用 |
|------|------|
| `MedievalColors.java` | 羊皮纸层/金色系/紫色系/文字色/功能色/滚动条色 常量 |

**component/** — UI 组件

| 文件 | 作用 |
|------|------|
| `MedievalScreen.java` | 基础 Screen：9-slice 精灵图面板 + header 标题栏 + 动画钩子 |
| `MedievalButton.java` | 主题按钮：精灵图 4 态（normal/hover/pressed/disabled）+ 居中文字 |
| `TabBar.java` | 水平页签栏：选中页签金下划线 + 金文字（程序化） |
| `ScrollableList.java` | 泛型虚拟滚动列表：抽象 `renderRow()` + 金滚动条 |
| `ElementPanel.java` | 9 元素储量面板：程序化彩色圆形图标 + 名称 + 格式化数值 |
| `SearchBar.java` | 搜索输入框：包裹 `EditBox` + 主题背景 |
| `QuantitySlider.java` | 数量滑条：金滑块 + 数值标签（程序化） |
| `ProgressIndicator.java` | 进度条：精灵图背景 + 金填充 + 可选文字标签 |
| `ItemGrid.java` | 物品网格：ItemStack 渲染 + 虚拟滚动 + 数量角标 |
| `IconButton.java` | 小图标按钮：支持精灵图（如 close_button）+ 字符 fallback |
| `LessButton.java` | 减少(-)按钮：less_button 精灵图 4 态 |
| `MoreButton.java` | 增加(+)按钮：more_button 精灵图 4 态 |
| `LeftArrowButton.java` | 左箭头按钮：left_arrow 精灵图 3 态 |
| `RightArrowButton.java` | 右箭头按钮：right_arrow 精灵图 3 态 |
| `HelpButton.java` | 帮助(?)按钮：help_button 精灵图 4 态 |
| `OptionButton.java` | 选项(齿轮)按钮：options_button 精灵图 4 态 |
| `DemoScreen.java` | 开发调试用 Demo 界面：展示所有组件 |

**animation/** — 动画接口

| 文件 | 作用 |
|------|------|
| `MedievalAnimation.java` | 动画接口：`isComplete()` / `tick()` / `render()`。暂无实现 |

**util/** — 渲染工具

| 文件 | 作用 |
|------|------|
| `RenderUtil.java` | `drawPanelBg` 羊皮纸渐变 / `drawPanelBorder` 双层金边 / `drawCornerDecorations` 四角金块 / `drawHLineDecorative` 装饰分隔线 / `drawScrollbar` 金滚动条 / `formatLargeNumber` 大数格式化 |

**editor/** — UI 位置编辑器

| 文件 | 作用 |
|------|------|
| `UIEditorScreen.java` | 游戏内 UI 布局编辑器：拖拽组件调整位置 / 方向键微调 / 网格吸附 |
| `WidgetLayout.java` | 布局数据模型：widgetId + x/y/width/height + ScreenLayout 聚合格 + JSON 序列化 |
| `UILayoutManager.java` | 布局持久化：保存/加载到 `config/wandscape/ui_layouts/` |

## registry/ — 全局注册表 (3 文件)

| 文件 | 作用 |
|------|------|
| `WandscapeApis.java` | **静态服务定位器**：持有全部 11 个 API 接口实现，getter 在未注册时抛异常 |
| `WandscapeConstants.java` | 可调常量：scheduler 心跳 / NPC 默认值 / 队列容量 / 操作射程 / 殖民地半径 |
| `WandscapeDataRegistry.java` | 泛型数据查询接口：`get(id)` / `getAll()` / `contains(id)` |
