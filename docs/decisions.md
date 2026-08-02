# 设计决策日志

只记录非显而易见的决策——为什么选这个方案而非那个。实现细节见源代码和 architecture/。

## 架构决策

**为什么 core/ 禁止引用 MC 类？** 保证核心引擎可纯 JUnit 测试（不启动 MC）。所有 MC 交互通过 boundary/ 接口注入。

**为什么 BuildingSavedData 用 Level SavedData 而非自定义方块 BE？** BE 随方块破坏丢失。SavedData 独立于方块，建筑数据不因方块被破坏而消失。仓库物品同样原因迁到 ColonyItemBank。

**为什么 EngineBootstrap 在 ServerStarting 而非 FMLCommonSetup？** 引擎需要访问 ServerLevel。FMLCommonSetup 时世界尚未加载。

**为什么导航失败改传送而非重试寻路？** 动态建筑工地路径频繁作废，寻路不可靠。直接传送保证 NPC 到达目标。

**为什么法杖永不损坏？** 核心物品，损坏会阻断整个殖民地工作流。平衡通过魔力消耗实现。

**为什么殖民地经验仅来自 100% 满意度的游客？** 要求全满意才贡献经验，给予玩家优化游客体验的强动机。0/100/500 三级贡献鼓励玩家吸引高等级游客、提升殖民地满意度。

**为什么生成公式用 base + colonyLevel × bonus × (0.8~1.2)？** 乘法缩放保证殖民地等级越低游客越少（新殖民地起步慢节奏），每日随机浮动（±20%）制造日间波动不单调。

**为什么游客等级用 40/40/20 分布？** 以殖民地等级为中心的正态近似——大多数游客与殖民地同级（40%），少量低一级（40%），少数高一级（20%）。下限 1 防止等级溢出。

**为什么游客生成用三阶段日周期而非持续生成？** 集中生成窗口（1000-13000）模拟"清晨入城"，夜晚统一离城。给玩家一个完整的日间经营周期——上午游客涌入、下午互动、傍晚结算离城。

**为什么殖民地名称存在 ColonyLevelData 而非独立 SavedData？** ColonyLevelData 已经按 colonyId 建索引，加一个 name 字段不需要新增 SavedData 文件。名称和等级经验一起保存/加载，保证原子性。

**为什么名称编辑用 C→S 包而非直接修改本地数据？** 多人模式下名称必须在服务端持久化并同步到所有客户端。EditBox responder 每次修改都发送包，保证输入的实时性和停机不断电。30 字符上限防止存储滥用。

**为什么 OverviewInteractPacket 不自己维护建筑交互分发？** 先前的修法是在 OverviewInteractPacket 中为 town_hall 加一个特判，但每新增一种 "typeId 判定" 的建筑类型就要在两个地方同步改。抽取 `BuildingInteractHandler.handleInteraction()` 统一分发，新增建筑只需改这一处。

**为什么市政厅用 category=government 判定而非硬编码 buildingTypeId？** 建筑配置 id 由数据文件决定（如 townhall1.json），硬编码 `"town_hall"` 在配置改名后静默失效（`/wandscape colony create` 报 "config not found"）。category=government 是语义稳定的标识——任何配置为 government 的建筑都视为市政厅，改名/新增建筑无需改代码。客户端 `BuildingAreaSyncPacket` 因此携带 category 字段，让引导逻辑也能按分类判断。

**为什么 node 右键 UI 复用 workstation 的包/Screen 模式而非自建交互区？** 工作站已经验证了「BuildingInteractHandler 发数据包 → 客户端打开 MedievalScreen → TaskQueuePanel 管理队列」的完整链路，node 与工作站同样靠建筑任务队列运行，直接复用最小成本。发布采集任务量化为"收获次数"滑条，合并为一个 WorkItem（amount/mana 按次数缩放），同工作站 decompose 的 count 合并方式。取消采集 = TaskQueueModifyPacket("delete")，queue 系统已原生支持 node:gather 条目，无需重复实现。

**为什么移除 ImGui，样条编辑器并入原生 UI？** 7/29 `UI统一` 已把建筑/蓝图编辑器从 ImGui 迁到 vanilla Screen + `shared/ui/`；8/2 又有人把 ImGui 从 git 历史恢复来写道路样条面板，随后 4 个提交全在修集成摩擦（framebuffer 对齐、CJK 字体 glyph ranges 悬空指针/截断、H 键指南被 ImGui 抢占 ESC）。ImGui 只承担一个 370px 侧边面板，而世界交互层（射线拾取/gizmo/相机）本就与 UI 框架无关。双 UI 体系 = 双主题 + 双输入路径互相抢占 + GLFW/OpenGL 集成反复出 bug。故改为 `SplineEditorOverlay` 原生 HUD overlay（静态绘制 + 命中检测，同 `RoadPlacementOverlay` 约定），复用 `shared/ui` 主题与 `TabBar`；ROAD 栏 Spline 工具不再退出 V 面板，而是内嵌编辑。3D 交互层一行未动。

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


**~~为什么 TaskCreatePacket 传字符串参数而非序列化 JsonElement？~~（已移除）** 编辑器 UI 及相关网络包（TaskCreatePacket、TaskEditorOpenPacket、BlueprintListResponsePacket、TaskNetworkHandler）已在 2026-07-29 删除。PlayerManualSource 仍保留，可通过 API 直接调用。

**为什么两条 EventBus 不互通？** core `SimpleEventBus` 是引擎内部 tick-batch 模式，NeoForge `EVENT_BUS` 是实时模式。两者用途不同：引擎内部事件用于链式任务生成（`TaskAwaitingResources → synthesize → gather`），NeoForge 事件用于跨模块通知（`TaskPublishedEvent → UI 提示`）。`engine/` 层做唯一翻译点。

**为什么修复任务 priority=49 而不是 100？** GlobalTaskPool.addTask 对 priority ≥ 50 的任务进入 PENDING_APPROVAL 状态，等待玩家审批。建筑损坏修复是殖民地自治行为，绝不能卡在审批门后。49 在节点采集（15）之上，同时越过高优先级审批门。

**为什么 global.autoApproveTasks 默认关闭？** 建造类大任务（town_hall 等）涉及地形改造，默认审批让玩家有机会取消或推迟。殖民地自治只需开一次开关，之后所有建筑修复/建造任务全自动，无需再手动 `/wandscape approve`。开在 Config TOML 而非硬编码，保留玩家控制权。

**为什么任务的 TriggerDeclaration 在完成时取消订阅？** 防止内存泄漏。已完成任务不应继续响应事件。

## 维护费系统重构（2026-06-30）

**为什么从周期心跳改为每日结算？** 原 `MaintenanceSystem` 每 1200 tick（60秒）扫描一次，与游戏内"每天"的概念脱节。玩家无法直观理解"每60秒扣一次元素"——每天日出结算更符合殖民地模拟的直觉，也与其他每日事件（游客生成、商店进货、酒店退房）对齐。

**为什么按建筑类别分组优先级？** 不加优先级的均匀扣费会在元素不足时导致**所有**建筑同时 shutdown，包括正在产元素的 node 建筑。分组优先级（CRITICAL→HIGH→NORMAL→LOW）确保减产时先缩减非核心服务（装饰、服务类），保留元素生产（node）和生产加工（workstation）的运转。

**为什么在结算时保证 CRITICAL 优先？** node 和 basic 是殖民地的元素产出和结构基石。如果它们因元素不足 shutdown，殖民地将彻底丧失恢复能力——即使玩家补充元素也无 node 可采集。让 CRITICAL 优先扣费保证最后的火力始终在产元素的核心建筑上。

**为什么引入 MaintenanceForecastSystem 提前准备？** 原系统被动等待结算→元素不足→shutdown。玩家事后手动补元素已为时已晚。Forecast 系统在元素储备低于 N 天预期消耗时就触发节点采集，留给玩家足够的缓冲时间。这个系统不依赖玩家指令，全自动运作。

**为什么 Forecast 不直接调用 GlobalTaskPool，而是通过 BuildingApi.enqueueWork() 发给 Node 建筑？** 遵循现有架构模式。BuildingTaskSource 是建筑→任务的唯一桥接点，Forecast 在其上游注入高优先级 WorkItem。这样做的好处：(1) BuildingTaskPool 自动保证每建筑仅一个 head task；(2) 任务经正常调度器分配，NPC 按能力接取；(3) 排队机制天然防重复。直接调 GlobalTaskPool 会绕过建筑队列机制。

**为什么优先级分组写在 Java 而非 Config TOML？** 类别→优先级的映射通常不需要服务器管理员调整。硬编码在 Java switch 中代码简洁、零配置负担。如果未来出现需要自定义优先级的场景，可迁移到 Config TOML，当前阶段不增加不必要的配置复杂度。

**为什么 shutdown 建筑需要 shutdownReason 字段？** 区分"因维护费不足自动下线"和"手动 shutdown"。在结算时自动重启只针对 `reason="maintenance"` 的建筑，避免因玩家手动下线（如装饰建筑要改造）而被突然重启。

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

**为什么系统不再生成 FAILED 任务？** 原设计中 FAILED 用于"能力不匹配"场景（如无所需法杖），由 FailureAnalyzerSystem 自动 craft_wand 修复。2026-07-30 重构后，资源短缺走 AWAITING_RESOURCES → ResourceSupplySystem 自动补货路径，法杖制作等策略决策留给玩家手动管理。FAILED 枚举值保留（NBT 兼容），但 failTask() 和 TaskFailureReason 已删除。TaskState.FAILED 视为终态，与 COMPLETED 同等待遇（isActive=false、不计入 persistable、不计入 size）。

**为什么 FAILED 分析被 ResourceSupplySystem 替代？** 原 FailureAnalyzerSystem 计划实现两种自动修复：（1）WandRequirementUnmet → craft_wand，（2）ResourceUnavailable → gather。其中（1）是策略决策（选哪种法杖），应由玩家决定；（2）是执行层冗余（已有 ResourceShortageHandler + onResourceAdded 处理）。ResourceSupplySystem 专注于执行层:扫描 AWAITING_RESOURCES 任务 → 聚合需求 → 合成/采集，不涉及策略判断。

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

**为什么 EventDrivenTaskSource 之前只在测试中实例化？** 早期开发阶段，事件驱动任务生成（TaskAwaitingResources→gather）仅用于单元测试验证逻辑。生产环境中 BuildingTaskSource 的主动轮询（supplyNodeBuildings）覆盖了节点采集，但资源短缺的被动响应被遗漏。2026-06-25 在 EngineBootstrap 中实例化并注入 handler，补齐生产环境的被动响应链。

**为什么 synthesize/decompose/craft_wand/brew_potion 的 thenRun 中捕 ResourceShortageException 而非让 TaskExecutionSystem 处理？** 这四个操作是异步的（有 channel_ticks 倒计时），实际执行在 `tickAll()` 的 thenRun 回调中，与 TaskExecutionSystem 不在同一调用栈。thenRun 中捕获异常后直接调 `world.taskPool.markAwaitingResources()` 并释放 NPC，层级比 TaskExecutionSystem 更低但逻辑等价——任务进入 AWAITING_RESOURCES 后由 EventDrivenTaskSource 级联创建供应任务。

## 交互区设计修正（2026-06-26）

**为什么 interaction_radius 从"向外扩展范围"改为"包围盒内部作为游客AI寻路目标"？** 原始设计将 interaction_radius 理解为右击检测的扩展范围——interaction_radius>0 时从包围盒外也可交互。但用户实际意图是建筑的包围盒内部区域本身就是交互区，游客 AI 应导航到包围盒内的可步行位置与建筑交互。interaction_radius 的正确语义是：0=必须在包围盒内部交互（默认），>0=可从包围盒外额外扩展N格交互。

**为什么 getTouristInteractionTarget() 放 BuildingSavedData 而非 BuildingApiImpl？** BuildingSavedData 持有所有建筑索引和包围盒数据，计算交互目标(包围盒中心螺旋搜索可步行位置)是纯建筑数据的查询，不依赖 Level（Level 作为参数传入仅用于方块状态检查）。API 层仅做薄委托。

## 游客偏好与满意度系统（2026-06-26）

**为什么游客偏好从三维度（舒适/魔法/奇观）改为按建筑类型（buildingTypeId）？** 三维度偏好对玩家不可见，衰减逻辑（降低主导维度平分到另两个）难以理解。按建筑类型偏好更直观：游客用过体育馆 → 对体育馆偏好降低 → 下次更倾向选图书馆/商店等其他类型。偏好同时驱动建筑选择（加权随机）和满意度获取（matchScore = typePref × threeValueSum），一个值驱动两个行为。

**为什么满意度使用截断+平方根+硬上限公式？** 原始公式 `typePref × threeSum / divisor` 在默认值下可产生 120 点满意度，一次交互即拉满 100，level 完全不参与计算。新公式引入三层约束：(1) 截断——建筑三值和 < level × 3 时 Δsat=0，高级游客需要高品质建筑；(2) 平方根——递减收益，避免一次拉满；(3) 硬上限 25——保证至少 4 次不同建筑交互才满。这使满意度成为有梯度的长线追求。

**为什么建筑三值贡献从同类建筑二值（有/无）改为每实例独立计算？** 装饰辐射、shutdown 状态、商店货物库存都是每建筑实例独立变化的。同类建筑按 count × config 值计算无法区分"两栋都正常"和"一栋正常一栋 shutdown"。改为遍历 BuildSource.allBuildings()，每栋独立检查 isStructureIntact()/isShutdown()/shopHasStock/货物三值，精确反映每栋建筑的实际贡献。intactCounts 保留用于 isTypeContributing()/getIntactCount() 查询。

**为什么商店货物增加 comfort/magic/wonder 字段？** 商店的三值不再仅是建筑基础值，而是基础值 + 所有有货 goods 的三值合计。货物品类越多，商店三值越高，对殖民地总体贡献越大，游客满意度也越高。这使货物管理成为经营决策：玩家需要在"多进货提高三值"和"控制进货成本"之间权衡。

**为什么满意度 100% 后不立即离开（回归 jingying.md 原始设计）？** 立即离开会让满意游客瞬间消失，玩家失去看到"满意游客在殖民地中漫步"的视觉反馈。改为：满意度首次达到 100% 时法师简历即时存入酒馆，游客继续留在殖民地直到精力耗尽/夜间/超时后自然离开。100% 满意度游客不会入住宾馆（已无需继续消费），避免占用宾馆资源。

**为什么增加空闲超时作为第三离开条件（与 jingying.md 的两条件模型偏离）？** jingying.md 只有精力耗尽和夜幕两个离开条件。实际游戏中存在"游客在街上持续 idle 但不触发离开"的边界情况：建筑无货/无空位导致 planNextBuilding 返回空，游客无限期 idle。空闲超时（TOURIST_DESPAWN_TIMEOUT_TICKS）兜底清理这些僵尸游客，防止内存泄漏和世界实体堆积。

**为什么货物种类由 JSON 固定而非玩家自由设定？** jingying.md 原始设计是"玩家设定进货清单"，但拖拽式进货清单需要物品浏览器+搜索+NBT匹配的完整 GUI，远超出 MVP 范围。JSON 固定货物种类实现商店类型差异化（面包店 vs 药水店由不同 JSON 定义），新增商店类型只需加 JSON 文件。玩家仍可通过 GUI 调整每种货物的 max_stock（库存深度决策），但不增减货物种类。

## 游客交互冷却与闲逛合并（2026-07-31）

**为什么冷却期间游客强制闲逛/逛景点而非站定？** 原实现中，服务交互后的全局冷却只在 `planNextBuilding` 里跳过服务建筑，但 `hasBuildingsAvailable()` 不检查冷却 → `decideNextMode` 反复选中 VISITING_BUILDING → `startBuildingVisit` 里 planNextBuilding 又失败 → 退回 WANDERING。这个每 15–25 秒一次的模式抖动会反复 stop 导航、甚至卡进 `tickOutdoorNav` 的空目标等待循环，视觉上"死死固定在一个点"。新实现：冷却期间 `decideNextMode` 直接短路为 WANDERING/EXPLORING_POI——游客自由移动但永不选择建筑访问，冷却结束自然恢复。这与现有闲逛状态合二为一，不引入新状态机。

**为什么冷却覆盖商店而不仅是服务？** 原实现只有服务建筑设置冷却，商店可被连续扫街（逛完一家立刻进下一家），节奏突兀。改为每次成功的商店/服务交互都进入一段休息期，形成"一次交互 → 闲逛休息 → 下一建筑"的稳定节奏。商店交互失败（无货）不触发冷却，游客可立即转投其他商店。

**为什么冷却期间允许逛景点？** 用户的"移动不受限制"指向自由移动——随机闲逛与 POI 游览都保留，只是不进入建筑交互。冷却期间 50% 概率逛景点（有 POI 时）、否则锚点附近闲逛。

**为什么交互时长与冷却合并（到达即交互，交互时长=冷却时长）？** 原设计里 `interaction_duration_ticks` 是到达交互点后的**站定倒计时**（商店/服务建筑 2400 tick=120 秒），游客站在交互点纹丝不动，效果（满意度/精力/行程记录）要等倒计时结束才一次性落地——视觉上"AI 死了"、行程记录一直为空，且永远不进入闲逛。这与"冷却期间自由移动"的设计相悖。合并方案：**到达交互点立即完成交互**（满意度/精力/行程记录当场记录），建筑的 `interaction_duration_ticks`（shop 与 service 都有）直接作为**冷却时长**——交互完游客立即进入闲逛/逛景点，直到该时长结束。全局 `SERVICE_COOLDOWN_TICKS` 配置移除，单一数据源改为每建筑的 JSON 字段。

**为什么硬兜底传送改为一次性救援而非每 tick 传送？** 原实现中 `totalNavTicks` 只在进入室内导航/切换模式时重置，硬兜底分支传送后只重置 `noMoveTicks`。游客在交互点站定（面包店 `interaction_duration_ticks=2400`，120 秒）时计数器照常累计，一旦超过 400 就**每 tick** 触发兜底：传送回固定点、提前 return 跳过交互倒计时 → 交互永不完成、游客永久卡死，且 /tp 或击打都被下一 tick 的 snap 回去（表现为 tp 免疫）。修复：(1) 三个兜底分支传送后同步重置 `totalNavTicks`，兜底变成"确实卡住才隔段时间拉一次"；(2) 交互结束进入 exitingPhase 时重置计数器，避免长倒计时后 exit 立即被兜底锁定。此后的交互时长合并方案进一步移除了站定倒计时，这类"交互中卡死"的整类问题不再存在。

## 综合面板 (WandscapePanel)

**为什么面板用 Overlay 渲染（RenderGuiEvent.Post）而非 Screen？** Screen 方案会隐藏准心、使投影控制器 `mc.screen != null` 提前返回导致所有子模式失效。Overlay 方案渲染在游戏 GUI 之上，不干扰世界渲染和输入系统，准心保留。Cursor 通过 C 键手动控制 MouseHandler.releaseMouse()/grabMouse() 实现 UI 交互切换。

**为什么建筑右键门控放服务端而非客户端取消事件？** BuildingInteractHandler 是服务端类，客户端取消 `RightClickBlock` 事件不可靠（服务端仍可能收到）。改为服务端维护 `PanelStateTracker.panelOpenPlayers` 集合，面板打开时 C→S 通知服务器，右键处理前检查该集合。面板关闭时移除，玩家断线自动清理。

**为什么 V 键从三态循环改为面板开关？** 原三态循环（Normal→Projection→Road→Normal）选择不直观，无法直接跳到目标模式。面板底部页签可任意切换模式，V 键简化为面板开关单一职责。旧版 V 键循环逻辑移至 WandscapePanelState.enterSubMode()/exitCurrentSubMode()。

## 统一指标聚合 (ColonyMetricsService，2026-07-29)

**为什么创建 ColonyMetricsService 而非让每个消费者各自查询不同 API？** 三个消费者（PanelStateTracker、PanelStateTogglePacket、AchievementService）都需要查询同一组殖民地指标。让每个消费者分别调 5+ 个 API 意味着：(1) ~150 行重复聚合代码 (2) 每个消费者必须知道所有 API 的存在 (3) 新增指标需要改所有消费者。ColonyMetricsService 是 Facade 模式——它不持有数据、不写数据、不包含业务逻辑，只是将已有 API 的查询结果聚合到一个 record。这减少了**消费者**的耦合度（一个依赖而非五个），而没有增加系统本身的耦合度（已有的跨 API 依赖已经存在）。

**为什么 ColonyMetricsService 放在 engine/service/ 而非作为独立模块？** engine/ 是唯一能合法引用所有模块的层（它需要调到 BuildingApi、TouristApi、NpcApi、WarehouseApi 和 ColonyLevelManager）。放在 engine/ 同时允许它访问 WandscapeEngine.getColonyLevelManager()——ColonyLevelManager 不像其他模块那样有 shared/api 接口，而是直接挂在 WandscapeEngine 上。

**为什么 ColonyMetricsSnapshot 包含 shutdown/broken 建筑名称列表这种 UI 数据？** 这些列表有两种消费者：HUD 警告浮层（UI）和成就系统（未来可能需要统计"累计 N 栋建筑关停"）。两种场景都需要按原样传递列表。若只传计数，成就系统无法派生列表。若切分成"UI 专用列表 + 核心数值"两个 record，消费者需要调用两个方法再自行组装——正好是我们想避免的重复组装。

**为什么面板从底部页签改为左侧侧边栏 + 顶部全宽 HUD？** 底部页签占据屏幕下方 48px，与 MC 原版快捷栏区域冲突，可显示信息量有限。新布局将模式切换移至左侧 36px 竖排侧边栏（Build/Road/Stats/Warning），顶部 28px 全宽 HUD 展示殖民地全貌（名称等级、三值、天数、游客、NPC、停摆、元素储量），信息密度更高且不遮挡中心视野。侧边栏警告图标带红色圆点徽章，点击弹出关停建筑列表浮层，让玩家在任意面板模式下都能感知殖民地健康状态。

## 懒加载道路斑块（Lazy Road Blob）系统（2026-06-28）

**为什么要加入玩家自定义道路寻路？** 当前 RoadRouter 只在系统 RoadNetwork（MST 生成的道路 edges）上寻路。玩家手动铺设的 cobblestone/stone_bricks 等方块不被识别为道路——NPC 走到玩家铺的路上不会获得 on-road 加速。自定义道路让玩家可以用铺路代替编辑器，玩法更自由。

**为什么选 BFS 懒发现 + 编号缓存而非监听 BlockPlace 事件？** 监听放置事件在玩家铺路时持续增加服务器负担（每方块触发一次事件），而 BFS 懒加载只在 NPC 需要寻路时才扫描。铺路时 0 开销，寻路时代价是一次性的（缓存后同区域后续寻路 O(1) 命中）。这符合 Minecraft 的性能优化哲学——"不见兔子不撒鹰"。

**为什么斑块用 centroid 虚拟节点作为虫洞而非 O(n²) 全网状连接？** 每个斑块可能有数百个边界点。若所有边界点互相连接（O(n²) edges），图会爆炸。改用 centroid 虚拟节点作为星型中心——每个边界点连接到 centroid（O(n) edges），路径 = entry→centroid→exit。代价是对非凸形状的高估（经过 centroid 绕路），但实践中玩家铺的道路多为矩形或直线，误差可接受。

**为什么不持久化 RoadBlobCache 到 NBT？** 斑块数据可从世界方块完全推导（BFS 扫描），不需要跨会话持久化。每次世界加载后首次寻路时重新扫描即可。避免 NBT 膨胀和脏数据漂移（块卸载/重加载导致部分方块不可见时缓存不完整）。

**为什么边界只在 XZ 平面检查（不检查 Y±1）？** 楼梯/斜坡的每个台阶天然是边界——NPC 可以在任意台阶上/下道路。检查 Y±1 会让平坦道路的内部方块也成为边界（因为上方无方块），失去"内部 vs 边界"的区分意义。Y 变体通过 centroid 虫洞自动处理——BFS 已经将楼梯的所有台阶纳入同一个斑块。

**为什么核心数据结构和引擎扫描分开为 core/RoadBlobCache + engine/RoadBlobExplorer？** 遵循 core/ 零 MC 依赖规则。RoadBlobCache 是纯 Java 集合操作（Map/Set/BFS），可在单元测试中验证。RoadBlobExplorer 需要 Level/BlockState/TagKey，放在 engine/。RoadRouter.buildGraph() 只读 RoadBlobCache，是纯核心逻辑。

## UI 主题统一（2026-07-29）

**为什么所有单页 Screen 统一用 MedievalScreen MINIMAL 而非保留 WandscapeTheme RTS 风格？** 项目存在 3 套视觉风格（FULL 羊皮纸精灵 / RTS 代码绘制 / 硬编码杂色），风格不统一。统一到 MINIMAL 后：所有 Screen 共享渐变玻璃面板 + 发光边框 + 紫色标题栏 + MedievalColors 调色板，玩家感受一致。新 Screen 只需 `extends MedievalScreen` 即自动获得整套风格。

**为什么删除 DecorationLevel 枚举？** 枚举定义了 FULL/MINIMAL/NONE 三个级别，但只有 MINIMAL 被使用。保留枚举造成虚假的灵活性——"未来可能切回 FULL"的假设无实际需求支撑。删除后 MedievalScreen 代码减少分支，render() 和 renderBackground() 不再有 switch/if。

**为什么 WandscapeTheme 限用于 V 面板覆盖层而非所有 UI？** BUILD/ROAD/STATS 覆盖层渲染在世界之上（HUD 层），使用 `RenderType.guiOverlay()` 和不同的透明度需求。Screen 渲染在独立的 GUI 层，有 dim 背景 + 居中面板。两层视觉上下文不同，强行统一到一套主题会牺牲各自的优势。WandscapeTheme 保留为覆盖层工具集，MedievalColors 作为 Screen 调色板。

**为什么边框用 2 环发光渐变而非斜边（beveled）？** 斜边边框在不同方向使用不同颜色（上/左亮，下/右暗），角部颜色突变突兀。发光边框每环四边颜色统一，靠内外环不同透明度制造景深，角部自然平滑。

## 包重构（2026-07-04）

**为什么把 core/ 拆分为 core/ + op/ + task/ + road/？** 原 `core/` 承载了 5 个无关子系统（ECS、原子操作、任务引擎、路网算法、调度系统），77 个文件混杂在一起。每个子系统有独立的演化速度和变更理由，放在同一包中导致：
- 开发者修改任务系统时不得不浏览路网代码
- 新增原子操作类型需要了解整个 ECS 框架
- 单元测试边界模糊（task 测试和 road 测试混在 core 测试文件夹）

拆分为独立顶级包后，每个包有清晰的职责边界和独立的变更范围。

**为什么 engine/road/ 移到 road/engine/，engine/colony/ 并入 engine 根？** 道路的 MC 实现（RoadBuilder、RoadSavedData）和纯核心算法（MstCalculator、RoadNetwork）属于同一子系统，不应因"一个零 MC 一个依赖 MC"就拆到两个顶级包。road/engine/ 作为 road/ 包内的实现层，自然保持与 core/ 同级的情理距离。ColonyApiImpl 只有 1 个文件，不值得独立子包。

**为什么 task/network/ 和 shared/ui/task/ 也并入 task/？** 任务的网络层和客户端 GUI 是任务系统的横向切片，与引擎层的 dsl/pool/scheduler 属同一子系统。放在 task/ 下后开发者只需要了解一个顶级包。编辑器 UI（TaskEditorScreen 及其相关网络包）已于 2026-07-29 删除，但包结构原则不变。

**为什么不把所有包统一成 api/internal 结构？** core/、op/、task/ 的 engine/dsl/scheduler 属于纯 Java 基础设施层，被多个 MC 模块引用但自身不引用 MC——它们是框架代码而非模块。road/algorith/、engine/boundary/、task/network/ 等仍是模块的标准 api/internal/client 模式。两套模式并行，取决于包的角色是"基础设施"还是"游戏模块"。

## 包重构第二轮：System 归属整理（2026-07-04）

**为什么 ManaRegenSystem 从 core/ecs/ 移到 core/component/？** `core/ecs/` 是 ECS 框架包（World、System 接口、ComponentStore），应只放抽象和基础设施。`ManaRegenSystem` 是具体实现——它做的事情就是遍历所有 ManaPool 并调用 `pool.regen()`，与 ManaPool 紧耦合。把实现和框架混在一起会模糊包的职责边界。放在 `core/component/` 后，开发者看到 ManaPool 就能在同一包找到其配套处理器，搜索成本更低。

**为什么 engine/system/ 拆分为 system/ + service/？** `engine/system/` 里混了两类完全不同的事物：(1) 实现 `core/ecs/System` 接口、注册到 `World.tick()` 的真正 ECS System；(2) 通过 `world.eventBus.subscribe()` 注册的纯事件订阅者。前者是 ECS 调度的组成部分，后者是旁路服务。混在一起让开发者无法从包名判断"这个 System 是 tick 驱动的还是事件驱动的"。拆分后 `engine/system/` 只放 ECS System，`engine/service/` 只放事件订阅者，各自职责单一。

**为什么 StatsSystem/AchievementSystem 改名？** 叫 "System" 意味着它跟 NavigationSystem 和 ResourceSupplySystem 是同类事物——但实际上它既不实现 `core/ecs/System`，也不被 tick 驱动。命名误导比命名不统一更糟糕，因为会让新开发者花费无意义的精力去理解它们之间的异同。`StatsService` 和 `AchievementService` 准确表达了它们的实际角色：记录数据、提供服务。

## 样条线模型编辑器 (Spline Model Editor) (2026-07-08)

**为什么样条线编辑器是独立几何模型编辑器而非直接接入 road/ 铺设？** 用户希望样条线专注于纯几何线段（类似 Houdini 风格的 Curve 节点），让其上的宽度、方块材质或高级平铺（Sweep/Array）完全由后续的蓝图阵列系统读取样条线来自由决定。这极大地解耦了“路径几何”与“物理平铺物”，提供极高的扩展性。

**为什么采用绝对世界坐标编辑，相对第一个点保存？** 在 3D 世界中直接用绝对坐标能够让玩家直观地在场景中通过 Gizmo 轴在任意坐标拉伸曲线。而在序列化为 JSON 模板时，通过以第一个锚点为 $(0, 0, 0)$ 原点计算所有点和控制柄的偏移，实现了模板的局部化导出。这使得同一条曲线可以随意应用（平铺）到任意世界原点，消除了绝对坐标的耦合。

## 道路系统重构：Spline 物流系统与客户端平滑 (2026-07-09)

**为什么物资运输要转移到客户端使用 Spline 进行插值？** 原来的设计是服务端每 tick 计算一次坐标并同步给客户端，导致大量发包开销，而且一旦网络延迟，物资就会颠簸卡顿。改为在 TransportStartPacket 发送时，将整个运输路线（包含多个 SplineLeg）一次性发给客户端。客户端使用 `TransportItemEntity` 自主进行 60 FPS 帧率平滑插值（根据 elapsed / duration 计算 `u` 并调用 `spline.evaluate(u)`），既减少了服务器运算和网络带宽消耗，又实现了绝对丝滑的贝塞尔曲线飞行。服务端仅在 `ItemTransportManager` 中维持一个虚拟倒计时（`elapsed++`），到时间直接完成。

**为什么 SplineLeg 增加真正的 Arc Length 采样计算，而不用起终点直线距离？** 在升级为贝塞尔曲线（bz3）道路后，一条连续的弯曲样条线可能物理跨度只有 5 格（如掉头弯），但实际弧长有 50 格。如果只算起终点直线距离，那么 50 格的弯道会被错误地当做 5 格来分配持续时间（duration）。这会导致物资在客户端以惊人的超速（比如 0.25 秒内飞完 50 格）“瞬移”。通过在 `getApproxLength()` 中对 `uStart` 到 `uEnd` 按 10 个步长进行分段采样累加距离，以极小的 CPU 开销换取了极为精确的弧长，让物资在弯道上能保持严格匀速。

**为什么 ResourceRequestExecutor.finish 不再减去 alreadyHas？** 发现了一个深藏的“双重扣除”Bug。当任务中含有多个并发步骤，或者 NPC 因为先前任务失败（比如 TransformOp 因为某种原因被中断）而包里残留了 1 个资源时。`execute()` 阶段计算 shortfall = need - alreadyHas 已经是扣除后的净需求，并且按照 shortfall 从仓库发起运输。当这批货物运达时，`finish()` 直接 `inv.add(need)` 即可。如果 `finish()` 再减一次 `alreadyHas`，就会导致实际给 NPC 的比需要的还少 1 个，最终在 `AsyncTransformExecutor` 中因为 `inv.hasEnough` 失败而阻断后续任务。修正后彻底解决了道路建造中途意外中断的僵尸路段问题。

## 资源供需重构：有需求才采集（2026-07-31）

### 现状核验：Workstation 自动合成是否存在？现在这套逻辑对吗？

**结论：自动合成已实现，且链路完整正确。**

逐段核实源码后的链路：

1. **建造/补货发出请求**：蓝图的 `request_resource` op → `AtomicOp.ResourceRequestOp` → `ResourceRequestExecutor.execute`（`engine/boundary/ResourceRequestExecutor.java:104`）。
2. **仓库物品不足 → 抛异常**：`execute` 先对全部需求做 all-or-nothing `hasEnough` 检查（`:126`），任一不足即 `failedFuture(ResourceShortageException)`，不产生部分取物。
3. **任务挂起**：`TaskExecutionSystem` 捕获异常（`task/scheduler/TaskExecutionSystem.java:249`）→ `taskPool.markAwaitingResources` → 任务转 AWAITING_RESOURCES、保存 stepIndex、释放 NPC。
4. **自动合成**：`markAwaitingResources` 调 `resourceShortageHandler`（`task/engine/pool/GlobalTaskPool.java:270`）→ `EngineBootstrap.createShortageHandler`（`engine/bootstrap/EngineBootstrap.java:213`）：该资源有 `production:synthesize` 配方且未 in-flight → 找 crafting_station → 入队 `production:synthesize`（priority 40）。无配方则返回 false。
5. **兜底补货**：`ResourceSupplySystem.scanStuckTasks` 每 40 tick 扫描（`engine/system/ResourceSupplySystem.java:62`）：资源已够 → `wakeupTask`；不够 → `trySupplyResource` 先合成、再无配方时 `tryGatherElement` 采集元素。
6. **补足后继续**：合成/采集完成 → `resources.addResource` → `WarehouseManager.addResource`（`warehouse/WarehouseManager.java:254`）→ `resourceAddedListener.onResourceAdded` → `GlobalTaskPool.onResourceAdded`（`task/engine/pool/GlobalTaskPool.java:404`）→ 匹配资源的 AWAITING_RESOURCES 任务全部可满足时 → 回到 PENDING_ASSIGN → 重新分配 → NPC 从保存的 stepIndex 续跑。

即"元素/物品不足 → 自动合成任务 → 足了继续任务"的设想**已经是现状**。

### 但"NPC 一直在 gather"的根因

自动合成没问题，问题在**采集侧有三个触发器并存**，其中一个是无条件触发：

| 触发器 | 触发条件 | 优先级 | 需求驱动 |
|---|---|---|---|
| `BuildingTaskSource.supplyNodeBuildings` | **无条件**，每 20 tick 对所有空闲 node 入队 gather | 15 | ❌ |
| `MaintenanceForecastSystem.enqueueGatherTasks` | 元素储备 < 维护费×reserveDays | 55 | ✅ 维护需求 |
| `ResourceSupplySystem.tryGatherElement` | 生产任务缺元素 | 40 | ✅ 生产需求 |

`supplyNodeBuildings`（`engine/source/BuildingTaskSource.java:72` 调用、`:126-169` 实现，commit 95b71d3 引入，早于 ResourceSupplySystem）是"NPC 一直在 gather"的主因：即使仓库已满，每个空闲 node 永远排着一个 gather，NPC 永远不停。用户日志中 wind 已累计 10139 仍在采集，正符合该无条件行为。

### 目标系统：有需求才采集

采集只在两类需求下发生：

1. **维护需求**：维护费 × `reserveDays`（默认 2 天）→ `MaintenanceForecastSystem` 入队采集。
2. **生产需求**：Workstation / Crafting_Station 等消耗元素时元素不足 → `ResourceShortageException` → `ResourceSupplySystem` 采集。

无需求不采集。两个需求触发器已存在，只需**删除无条件触发器**并修复以下缺口。

### 修改方案

1. **删除 `BuildingTaskSource.supplyNodeBuildings`**（BuildingTaskSource.java:72 调用 + :126-169 方法）。两处需求采集已覆盖全部采集场景，无条件采集只会造成"永远在 gather"。

2. **修正采集入仓的 colony 归属**：`executeGather` 的 `addResource` 走 `WarehouseManager.findStorageColony()`——取**跨殖民地的首个 storage 建筑**。多殖民地时，node 采集的元素可能入到别的 colony，而 Forecast 按 **node 所在 colony** 读 `getElement()`，导致需求永不满足 → 每周期再入队高优先级采集。修正：`executeGather` 从 WorkItem 的 `anchor` 解析 node 所在 colony，直接 `addElement(nodeColony, ...)` + 触发 onResourceAdded（或给 `addResource` 增加 colony 参数）。

3. **Forecast 无匹配 node 兜底**：缺的元素没有对应 node 时，`enqueueGatherTasks` 一个都不入队，但 shortfall 仍在 → 每 6000 tick 报警一次、永不解决。改为：无匹配 node → `Log.warn` 一次并进入冷却（防刷屏），不反复报警。

4. **Forecast 采集优先级越过高审批门**：`FORECAST_GATHER_PRIORITY=55 ≥ 50`，而 `autoApproveTasks` 默认 false → forecast 的采集任务会进 PENDING_APPROVAL 等玩家审批，"自动预防维护关机"实际失效。对齐修复任务先例（priority=49 绕过审批门，见上文），改为 **49**（仍高于生产采集 40、手动发布 15）。

5. **（可选）Forecast 采集量封顶**：当前对 shortfall 元素的所有空闲 node 全部入队，不按缺口量封顶。可改为按 shortfall / amountPerHarvest 计算所需采集次数再入队，避免过量采集。

### 不做的事

- 不改 `WarehouseApi` / `ColonyResourceAccess` 的**跨殖民地求和**语义——`available()` 求和正是生产需求的正确定义（任何殖民地的元素都能补给生产任务）。
- 不改 `DailySettlementSystem`（维护费扣费与自动重启逻辑已正确）。

## 守卫系统设计（2026-08-02）

守卫闭环：怪物进入建筑 AABB 水平 +10 区 → 发布 `guard:attack` → 空闲 NPC 原地视线施法 → 光束每 tick 伤害束内 Enemy → 直到 +15 区内无怪才完成。

- **滞回区间**：攻击/目标区 = 建筑包围盒水平 X/Z ± 10，Y 不扩展；任务完成/脱离区 = ± 15，Y 不扩展。有怪进 +10 触发守卫，持续到 +15 无怪才结束——避免怪物卡在 10 格边缘导致守卫反复进/出。
- **Y 不扩展**：只做水平扩展，否则会索敌到地下洞穴怪物，光束打不到。
- **不走路**：守卫 op `target() = null`（无 stance/导航），NPC 原地施法；射程由光束 200 格覆盖，只需视线（LOS）。
- **持续任务**：一次守卫 = 一个持续 `guard:attack` 任务，执行器在 `tickAll` 循环（施法→等光束→重选最近→再施法），期间 NPC 保持 ACTIVE 不被改派；+15 区无怪才 complete。
- **复用**：伤害与视觉完全复用 `MagicCaster.castNpcAt`/`MagicCastManager`/`MagicBeamEntity`（每 tick magic 伤害），不写 EntityOps stub。
- **优先级 49**：< 50 避开 `autoApproveTasks=false` 的 PENDING_APPROVAL 门（同修复任务先例），且高于普通建造任务 ~40。
