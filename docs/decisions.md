# 设计决策日志

只记录非显而易见的决策——为什么选这个方案而非那个。实现细节见源代码和 architecture/。

## 架构决策

**为什么 core/ 禁止引用 MC 类？** 保证核心引擎可纯 JUnit 测试（不启动 MC）。所有 MC 交互通过 boundary/ 接口注入。

**为什么 BuildingSavedData 用 Level SavedData 而非自定义方块 BE？** BE 随方块破坏丢失。SavedData 独立于方块，建筑数据不因方块被破坏而消失。仓库物品同样原因迁到 ColonyItemBank。

**为什么 EngineBootstrap 在 ServerStarting 而非 FMLCommonSetup？** 引擎需要访问 ServerLevel。FMLCommonSetup 时世界尚未加载。

**为什么导航失败改传送而非重试寻路？** 动态建筑工地路径频繁作废，寻路不可靠。直接传送保证 NPC 到达目标。

**为什么法杖永不损坏？** 核心物品，损坏会阻断整个殖民地工作流。平衡通过魔力消耗实现。

## 数据设计

**block_mapping 为什么用逐键映射而非 palette+data？** 当前建筑规模（<50 类型，<1000 方块）无瓶颈。未来建筑规模扩大时迁移到调色板数组格式，空间节省约 20 倍。不向后兼容。

**为什么蓝图 DSL 不直接用 Java 代码？** JSON 数据驱动允许热重载（/reload），无需重启客户端。非程序员可编辑建筑定义。

**为什么 NBT 传出要 copy？** MC 的 CompoundTag 可变。传出引用允许外部修改破坏内部状态。copy() 代价低，防御性强。

**为什么元素和物品分开存储而非用 ELEMENT_TO_BLOCK 映射？** 旧设计将元素映射为 MC 物品（WOOD→oak_log, EARTH→dirt）存入 ColonyItemBank 物品存储。这导致节点采集产出物理方块而非抽象元素，分解/合成/法杖制作在物品和元素之间形成循环映射。改为在 ColonyItemBank 内建独立 `elementStorage`（Map<UUID, Map<ElementType, Long>>），物品和元素在同一个 SavedData 中完全分离。9 个 long 计数器不值得开独立 SavedData，同一 Bank 天然保证分解（消耗物品+注入元素）和合成（消耗元素+注入物品）的事务原子性。

## 任务系统

**为什么调度器评分用 proximity×0.5 + efficiency×0.3 + level×0.2？** 距离因素权重最高（减少 NPC 来回跑）。魔力效率次之（节省资源）。行为等级最低（所有 NPC 都能做基本任务）。

**为什么 GlobalTaskPool 直接用 long 作 task ID 而非 UUID？** 引擎内部性能优先。UUID 仅在与 MC 系统对接时通过 toTaskUuid() 桥接。

**为什么调度器在无合格 NPC 且仓库无对应法杖时放任任务保持 PENDING_ASSIGN？** 任务进入池后，SchedulerSystem 按能力匹配 NPC。若无人满足，任务保持 PENDING_ASSIGN 状态等待。这不是阻塞——当玩家通过 crafting_station 制作出所需法杖并存入仓库后，WandProvider 自动为 NPC 装备法杖，下一轮心跳即可接取。这种"等待机制"不靠聊天通知，玩家通过 TaskQueuePanel UI 直接看到队列状态。

## 法杖需求系统

**为什么 task.requirements 从 TaskSequence 自动推导而非蓝图声明？** 蓝图可能包含多种 op（TransformOp+BlockInteractOp），手动声明容易遗漏或出错。自动推导保证需求与操作一致，且零维护成本。WandRequirementDeriver.derive() 是纯函数，按 op 类型→BehaviourTag 映射，多个同 tag 取 max level。

**为什么不在任务发布前做能力检查而是让任务自然流入池？** 发布前检查需要预先编译蓝图和遍历 NPC，形成冗余——SchedulerSystem 心跳时已经做了同样的能力匹配。发布前拒绝 + 重新入队会导致无限循环（WorkItem 永不离开队列）。发布前通知玩家（chat message）是对全体玩家的 spam，不如让玩家通过 TaskQueuePanel UI 自行查看队列状态。SchedulerSystem 的"不满足则保持 PENDING_ASSIGN"是最简洁的等待机制。

**为什么 WandEquipOp/WandReturnOp 注入私人队列而非创建独立 task？** 法杖是执行主任务的**前置条件**，不是独立的后台工作。注入同一 NPC 的私人队列保证原子性：取法杖→执行→归还三条一体，不会被其他 NPC 抢走或调度器误分配。私人队列 LIFO 特性确保 WandEquipOp 先执行、WandReturnOp 最后执行。

**为什么 WandProvider 是 @FunctionalInterface 而非在 World 放仓储引用？** core/ 不能引用 MC 类（ColonyItemBank/SavedData）。WandProvider 将仓库查询抽象为纯 core 接口，engine 层通过 WandProvisionSystem 注入具体实现。SchedulerSystem 通过构造器接收，依赖关系清晰。

**为什么所有法杖共用物品 ID "wandscape:wand" 而非每种一个 ID？** 4 种法杖（builder/gatherer/crafter/ritual）只有 NBT "behaviors" 不同。NBT 驱动允许新法杖类型仅靠 JSON 添加，无需注册新 Item。ColonyItemBank 按 ItemKey(itemId, nbt) 区分，同一 ID 不同 NBT 自动成为不同条目。引擎通过 preset NBT 的 wand_color 匹配回 preset ID。

**为什么 craft_wand 和 synthesize 蓝图不硬编码 CRAFTING 需求，而是由配方 wand_level 控制？** `craft_wand` 和 `synthesize` 的 wand 能力要求不是由动作本身决定，而是由配方的 `wand_level` 字段控制。`craft_wand` 移除 CRAFTING 需求可打破冷启动死锁（无 wand → 不能做任何事 → 永远造不出 wand）。`synthesize` 同理：低级配方（`stone_bricks`）不需要 CRAFTING，高级配方可在 JSON 里通过 `"wand_level": {"crafting": 1}` 按需添加需求。两者统一由 `WandRequirementDeriver` 返回空 map，再通过 `GlobalTaskPool.mergeOverrides()` 把 `wand_level` JSON 字段合并进 `requirements`，0=删除、≥1=覆盖，行为一致。

**为什么法杖取还零魔力消耗？** 取/还法杖是殖民地物流操作，不是施法。消耗魔力只在实际执行任务操作（TransformOp/BlockInteractOp/RitualOp）时发生，符合"法杖是工具→工具不耗能→使用工具才耗能"的直觉。

## GUI 任务编辑器

**为什么 GUI 发布任务走网络包而非直接调 API？** 客户端代码在 `shared/ui/`，不能引用 `core/` 类（core 纯 Java 零 MC，不参与客户端编译）。网络包是 Minecraft 原生的客户端→服务端通信模式，也是 NeoForge 的标准做法。

**为什么 ParamTypeInfo 要重复定义而不是直接引用 core/ParamType？** `core/task/ParamType` 是 sealed interface，出现在 shared/data 会破坏 core 的零 MC 依赖。枚举镜像 `ParamTypeInfo` + `fromCore()` 转换器是干净的防腐层。

## 殖民地三值评估系统

**为什么贡献粒度从按建筑类型改为按建筑实例（2026-06-26 修正）？** 装饰辐射、shutdown 状态、商店货物库存都是每建筑实例独立变化的。同类建筑按 count × config 值计算无法区分"两栋都正常"和"一栋正常一栋 shutdown"。改为遍历 BuildSource.allBuildings()，每栋独立检查 isStructureIntact()/isShutdown()/shopHasStock/货物三值，精确反映每栋建筑的实际贡献。

**为什么事件广播从 0↔1 边界改为任意 snapshot 变化？** 改为每实例独立贡献后，1→2 也改变殖民地三值（从 1×config 变为 2×config），需要广播事件。改为 before/after snapshot 比较，仅在不相等时广播。

**为什么注册表放在 BuildingSavedData 而非 BuildingApiImpl？** `BuildingSavedData` 是所有建筑状态的单一真相来源（结构完整性变化、注册/注销都在这里发生），在 state change 同步发生时更新贡献缓存最自然。BuildingApiImpl 只读查询。

**为什么配方解锁用三字段 unlock_requirement 而非 legacy unlock_magic_value 单字段？** 三值（舒适/魔法/奇观）都是 ≥0 的自然数，缺省填 0 表示无要求，不存在歧义。三个 int 的结构清晰可读，不需要额外的 legacy 分支兼容逻辑。JSON 格式统一为 `{"min_comfort": x, "min_magic": y, "min_wonder": z}`，只需其中一个维度填非零值。

**为什么移除 required_level 字段，统一用 C/M/W 三维控制配方可见性？** `required_level` 在 UI 仅显示 Lv.X 标签，不做任何过滤或校验，对实际游戏逻辑零贡献，是早期设计残留。C/M/W 三维已足够表达所有解锁场景（C=建筑规模、M=魔法研究、W=奇观积累），引入额外字段只会增加 JSON 配置负担和理解成本。移除后配方 record 更简洁，数据包体积减小，UI 渲染逻辑统一。

**为什么数据包增加 `locked_reason` 字段而非客户端根据 `maxAffordable==0` 和 `unlockRequirement` 推算？** `maxAffordable==0` 有两种不同原因（三维未满足 / 元素不足 / wand_level 要求），客户端无法仅靠 `maxAffordable` 和 `unlockRequirement` 区分——特别是当配方有 `wand_level` 但元素恰好为零时，误判为"三维锁定"会错误显示 C/M/W 门槛。`locked_reason` 是服务端单点计算的四值枚举（`unlocked / colony / elements / wand_level`），客户端零逻辑直接渲染，新增锁定类型只需服务端加一个分支。

**为什么 wand_level NBT 只随 locked_reason=wand_level 下发，而非始终携带？** 绝大多数配方不需要 wand_level（为空或全是 0），随每个配方下发空 map 浪费带宽。只在锁定原因是 wand_level 时才序列化，客户端渲染时按需读取。服务端 `hasNonZeroWandLevel()` 做轻量扫描，零 GC 压力。


**为什么 TaskCreatePacket 传字符串参数而非序列化 JsonElement？** 客户端 `EditBox` 产出字符串。在服务端解析为 JsonElement（`PublishBlueprintCommand.parseValue` 同逻辑），避免客户端依赖 Gson。

**为什么两条 EventBus 不互通？** core `SimpleEventBus` 是引擎内部 tick-batch 模式，NeoForge `EVENT_BUS` 是实时模式。两者用途不同：引擎内部事件用于链式任务生成（`ResourceLow → gather`），NeoForge 事件用于跨模块通知（`TaskPublishedEvent → UI 提示`）。`engine/` 层做唯一翻译点。

**为什么修复任务 priority=49 而不是 100？** GlobalTaskPool.addTask 对 priority ≥ 50 的任务进入 PENDING_APPROVAL 状态，等待玩家审批。建筑损坏修复是殖民地自治行为，绝不能卡在审批门后。49 在节点采集（15）之上，同时越过高优先级审批门。

**为什么 global.autoApproveTasks 默认关闭？** 建造类大任务（town_hall 等）涉及地形改造，默认审批让玩家有机会取消或推迟。殖民地自治只需开一次开关，之后所有建筑修复/建造任务全自动，无需再手动 `/wandscape approve`。开在 Config TOML 而非硬编码，保留玩家控制权。

**为什么任务的 TriggerDeclaration 在完成时取消订阅？** 防止内存泄漏。已完成任务不应继续响应事件。

## 任务队列 UI（Task Queue UI）

**为什么 QueueEntry 从纯文本 summary 扩充为 6 字段？** 纯文本 "Synthesize minecraft:stone_bricks x64" 超长且容易被截断。服务端结构化分类（category + itemOrRecipeId + quantity）让客户端能渲染为 `[icon] [Category] ×N` 三列，最长标签不超过 10 字符（"Synthesize"），彻底消除截断问题。

**为什么 itemOrRecipeId 解析图标只在客户端 TaskQueuePanel 做，服务端也填充？** 服务端填充结构化字段（不传方块/物品对象）避免序列化体积膨胀；客户端 fallback 到 legacy summary 保证与旧版协议兼容（向后兼容设计）。

**为什么 hit-test 从单重循环重构为两段式（先定位列再判断 active）？** 原始实现 `for col 0..2` 中 col=0 不可用时直接 `return empty()` 退出，导致 col=1/2 永远无法命中。两段式把"定位鼠标在哪一列"和"该列按钮是否可用"两个判断分离，互不干扰，逻辑更清晰。

**为什么 TaskQueuePanel 图标仅尝试解析 itemOrRecipeId，不解析 recipe outputItem？** Recipe 输出物品需要额外查 ProductionRecipeLoader，属于 production 模块内部细节。TaskQueuePanel 在 shared/ui/component/，不能跨模块引用。服务端 `extractItemId()` 已提取 recipe_id（字符串），客户端解析该字符串对应物品——若 recipe_id 对应无实际物品（纯配方 ID），icon 留空即可，文字标签不受影响。

## 道路系统

**为什么选 MST 自动生成而非玩家手动规划？** 保证连通性，总路长最短。玩家后期可手动调整（预留数据结构）。

**为什么路径选 L 形而非直线？** 轴对齐确定性强。曼哈顿距离与 L 形一致，MST 计算简单。

**为什么道路纯装饰不与寻路耦合？** 解耦降低复杂度。NPC 寻路不受道路有无影响。道路美观价值独立于功能。

## 道路编辑器

**为什么玩家干预建路用 [Enter] 确认而非右键即发？** 右键在编辑器中负载过重（选起点 / 加路径点 / 选终点）。终点选定后展示完整预览路面让玩家目视确认，Enter 键确认是明确的"执行"信号。Backspace 可逐级撤销（终点→路径点→起点），Escape 一键取消，容错性高。

**为什么放置方块记录到 RoadEdge.placedBlocks 而非运行时重新扫描？** 拆除时无法可靠区分道路方块和玩家放置的同款方块（如 stone_bricks）。RoadEventListener.enqueueEdge 和 triggerDecorationForEdge 在生成 tiles 时同步记录所有位置的 PathPoint 到 edge，确保拆除100%覆盖，不误删不残留。

**为什么编辑器点击用 GLFW 原生输入而非 mc.options.keyAttack.consumeClick()？** ClientTickEvent.Post 触发时 MC 主 tick 已消费按键事件，consumeClick() 返回 false。GLFW.glfwGetMouseButton/glfwGetKey 读取 OS 原生按键状态 + 上升沿检测绕过了 MC 的输入消费机制。同时 Pre tick 中 drain consumeClick 阻止原版攻击/交互响应。

**为什么预览路径在客户端计算而非发包请求服务端？** PathGenerator.lShape3D 在 core/ 层是纯函数，零 MC 依赖，客户端可直接调用。实时跟随准星更新预览（每帧 rebuild），发包会造成不必要的网络延迟和带宽消耗。

## 数据驱动法杖需求与立即失败（2026-06-23）

**为什么 wand_level 用统一的 JSON 对象而非分散字段（gathering_level / required_wand_level）？** 节点和配方共用同一套行为标签（BUILDING/GATHERING/CRAFTING/RITUAL/…），分开字段会导致命名不一致和解析代码重复。统一的 `{"gathering": 1, "building": 0}` 格式语义清晰：0=显式移除需求，缺省=deriver 默认值，≥1=覆盖等级。

**为什么 overrides 的 0 是"移除"而非"等级 0"？** 等级 0 的 BehaviourLevel 不存在（最低为 1）。用 0 表示移除是常见 DSL 惯例（类 Docker Compose 的 `replicas: 0`），避免引入额外的 nullable wrapper。

**为什么不满足需求的任务立即 FAILED 而非永远 PENDING_ASSIGN？** PENDING_ASSIGN 不释放建筑队列，任务永久挂在队列中阻挡后续任务。立即 FAILED 让 BuildingTaskSource 的 activeTasks 清理逻辑自动移除跟踪，建筑队列恢复流动。FAILED 是终态，与 COMPLETED 同等待遇（isActive=false、不计入 persistable、不计入 size）。

**为什么只在 requirements 非空时才 failTask？** 空 requirements 表示任何 NPC 可执行。若所有 idle NPC 因法力耗尽被跳过，任务是暂时不可分配而非永久不可满足——等待法力回复后下一轮心跳即可接取。failTask 仅适用于"能力永远不匹配"的场景。

**为什么失败分析器用 sealed interface 而非 enum + switch？** `TaskFailureReason` 是 sealed interface，每个变体是独立 record，携带类型安全的结构化数据（如 `WandRequirementUnmet` 携带 `Map<BehaviourTag, BehaviourLevel>`）。新增失败原因只需添加新 record + 分析器新增处理分支，无需修改已有代码。

**为什么失败恢复是"重新排队而非重试"？** 失败的任务保持 FAILED 终态。分析器检测到失败后，enqueue 新的 craft_wand 任务到 crafting station。原始任务对应的建筑（节点/工作站）由 BuildingTaskSource 的自然轮询重新生成新任务——此时仓库中已有法杖，调度器可直接分配。这种"等待外部条件满足→自然恢复"模式避免了重试循环和状态机复杂性。

**为什么失败分析器用任务 anchor 推断殖民地而非在任务中存 colonyId？** anchor 是任务参数中已存在的位置信息，从 anchor 反向查 BuildingSavedData 可获取殖民地。不修改 TaskRequest/WorkItem 数据模型，零侵入。对于无 anchor 的任务（如 EventDrivenTaskSource 的触发任务），分析器跳过恢复并记录警告。

## 任务系统重构 v2（2026-06-25）

**为什么 NPC 队列存 NpcTaskPackage 而非裸 AtomicOp？** 裸 op 没有立场位置（stance）。NPC 执行到一半被紧急任务打断后，手里只剩一串不知道属于哪个位置的 op，无法正确导航回原位。NpcTaskPackage 是自包含工作单元：source + sequence + stance + priority，包切换时 NPC 自动导航到新 stance。

**为什么用包挂起栈 (suspensionStack) 而非简单抢占？** 紧急任务（法杖装配/传送）可能嵌套——NPC 在执行任务包时被卡住传送打断，传送完成后应恢复原任务包继续。挂起栈保存 (package, stepIndex, timestamp)，紧急任务完成后 resumeLatest() 恢复。

**为什么 BuildingTaskPool 只暴露 head task 到全局池？** 之前 BuildingTaskSource.poll() 每 20 tick 遍历建筑列表，对每个建筑取出一个 WorkItem 直接发布到 GlobalTaskPool，不检查建筑是否已有活跃任务。导致一个工作站队列中的 N 个任务同时进入全局池，多个 NPC 同时前往同一建筑。BuildingTaskPool 确保每建筑只有一个 head task 竞争，head 完成后 onHeadCompleted 才 promote 下一个。

**为什么法杖生命周期用显式状态机而非 idle timer？** 旧方案：TaskExecutionSystem 中 60 tick 空闲超时自动归还法杖。问题：NPC 可能在等待资源/魔力恢复，超时误归还法杖导致任务中断。WandLifecycle 显式状态机由 SchedulerSystem 在分配时预留法杖(WandLifecycle.reserve)，任务完成或失败时释放，消除借还循环。

**为什么 GlobalTaskPool 用 TreeSet 而非简单的 List + sort？** TreeSet 维护 assignableSet 的恒定排序（priority desc → createdAt asc → id asc）。状态变更时 add/remove 是 O(log n)，无需每次全量排序。优先旧任务打破平局，保证确定性。

**为什么 SchedulerSystem 从 task→NPC 贪心改为 NPC→task 反向匹配？** 旧方案：遍历 assignable 任务，每个任务找最佳 NPC，第一个匹配的任务就分配——导致高优先级任务"抢"走所有 NPC。新方案：遍历 idle NPC，每个 NPC 找最佳任务——NPC 优先，消除任务间竞争。

## 智能资源调度级联（2026-06-25）

**为什么资源短缺时不直接创建 gather 任务，而是先尝试 synthesize？** 并非所有资源都能直接采集。`stone_bricks` 等合成品只能通过 `production:synthesize` 生产。直接创建 `gather:stone_bricks` 任务会因仓库永远无此物品而永久卡在 AWAITING_RESOURCES。先检查合成配方→有则创建 synthesize 任务→合成缺元素时再由 synthesize executor 抛出 ResourceShortageException→自动级联创建 gather 任务。三级调度链（建筑→合成→采集）让殖民地全自动运作。

**为什么 ResourceShortageHandler 用 @FunctionalInterface 注入而非在 EventDrivenTaskSource 硬编码？** EventDrivenTaskSource 在 core/ 层，不能引用 production 模块的合成配方数据。FunctionalInterface 将合成判断逻辑推迟到 engine 层注入，core 只负责调用，不关心实现细节。EngineBootstrap 在 bootstrap 时设置 handler，形成干净的依赖方向。

**为什么 EventDrivenTaskSource 之前只在测试中实例化？** 早期开发阶段，事件驱动任务生成（ResourceLow→gather, TaskAwaitingResources→gather）仅用于单元测试验证逻辑。生产环境中 BuildingTaskSource 的主动轮询（supplyNodeBuildings）覆盖了节点采集，但资源短缺的被动响应被遗漏。2026-06-25 在 EngineBootstrap 中实例化并注入 handler，补齐生产环境的被动响应链。

**为什么 synthesize/decompose/craft_wand/brew_potion 的 thenRun 中捕 ResourceShortageException 而非让 TaskExecutionSystem 处理？** 这四个操作是异步的（有 channel_ticks 倒计时），实际执行在 `tickAll()` 的 thenRun 回调中，与 TaskExecutionSystem 不在同一调用栈。thenRun 中捕获异常后直接调 `world.taskPool.markAwaitingResources()` 并释放 NPC，层级比 TaskExecutionSystem 更低但逻辑等价——任务进入 AWAITING_RESOURCES 后由 EventDrivenTaskSource 级联创建供应任务。

## 交互区设计修正（2026-06-26）

**为什么 interaction_radius 从"向外扩展范围"改为"包围盒内部作为游客AI寻路目标"？** 原始设计将 interaction_radius 理解为右击检测的扩展范围——interaction_radius>0 时从包围盒外也可交互。但用户实际意图是建筑的包围盒内部区域本身就是交互区，游客 AI 应导航到包围盒内的可步行位置与建筑交互。interaction_radius 的正确语义是：0=必须在包围盒内部交互（默认），>0=可从包围盒外额外扩展N格交互。

**为什么 getInteractionTarget() 放 BuildingSavedData 而非 BuildingApiImpl？** BuildingSavedData 持有所有建筑索引和包围盒数据，计算交互目标(包围盒中心螺旋搜索可步行位置)是纯建筑数据的查询，不依赖 Level（Level 作为参数传入仅用于方块状态检查）。API 层仅做薄委托。

## 游客偏好与满意度系统（2026-06-26）

**为什么游客偏好从三维度（舒适/魔法/奇观）改为按建筑类型（buildingTypeId）？** 三维度偏好对玩家不可见，衰减逻辑（降低主导维度平分到另两个）难以理解。按建筑类型偏好更直观：游客用过体育馆 → 对体育馆偏好降低 → 下次更倾向选图书馆/商店等其他类型。偏好同时驱动建筑选择（加权随机）和满意度获取（matchScore = typePref × threeValueSum），一个值驱动两个行为。

**为什么满意度使用截断+平方根+硬上限公式？** 原始公式 `typePref × threeSum / divisor` 在默认值下可产生 120 点满意度，一次交互即拉满 100，level 完全不参与计算。新公式引入三层约束：(1) 截断——建筑三值和 < level × 3 时 Δsat=0，高级游客需要高品质建筑；(2) 平方根——递减收益，避免一次拉满；(3) 硬上限 25——保证至少 4 次不同建筑交互才满。这使满意度成为有梯度的长线追求。

**为什么建筑三值贡献从同类建筑二值（有/无）改为每实例独立计算？** 装饰辐射、shutdown 状态、商店货物库存都是每建筑实例独立变化的。同类建筑按 count × config 值计算无法区分"两栋都正常"和"一栋正常一栋 shutdown"。改为遍历 BuildSource.allBuildings()，每栋独立检查 isStructureIntact()/isShutdown()/shopHasStock/货物三值，精确反映每栋建筑的实际贡献。intactCounts 保留用于 isTypeContributing()/getIntactCount() 查询。

**为什么商店货物增加 comfort/magic/wonder 字段？** 商店的三值不再仅是建筑基础值，而是基础值 + 所有有货 goods 的三值合计。货物品类越多，商店三值越高，对殖民地总体贡献越大，游客满意度也越高。这使货物管理成为经营决策：玩家需要在"多进货提高三值"和"控制进货成本"之间权衡。

**为什么满意度 100% 后不立即离开（回归 jingying.md 原始设计）？** 立即离开会让满意游客瞬间消失，玩家失去看到"满意游客在殖民地中漫步"的视觉反馈。改为：满意度首次达到 100% 时法师简历即时存入酒馆，游客继续留在殖民地直到精力耗尽/夜间/超时后自然离开。100% 满意度游客不会入住宾馆（已无需继续消费），避免占用宾馆资源。

**为什么增加空闲超时作为第三离开条件（与 jingying.md 的两条件模型偏离）？** jingying.md 只有精力耗尽和夜幕两个离开条件。实际游戏中存在"游客在街上持续 idle 但不触发离开"的边界情况：建筑无货/无空位导致 planNextBuilding 返回空，游客无限期 idle。空闲超时（TOURIST_DESPAWN_TIMEOUT_TICKS）兜底清理这些僵尸游客，防止内存泄漏和世界实体堆积。

**为什么货物种类由 JSON 固定而非玩家自由设定？** jingying.md 原始设计是"玩家设定进货清单"，但拖拽式进货清单需要物品浏览器+搜索+NBT匹配的完整 GUI，远超出 MVP 范围。JSON 固定货物种类实现商店类型差异化（面包店 vs 药水店由不同 JSON 定义），新增商店类型只需加 JSON 文件。玩家仍可通过 GUI 调整每种货物的 max_stock（库存深度决策），但不增减货物种类。
