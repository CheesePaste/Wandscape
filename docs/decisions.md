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

## 法杖需求系统

**为什么 task.requirements 从 TaskSequence 自动推导而非蓝图声明？** 蓝图可能包含多种 op（TransformOp+BlockInteractOp），手动声明容易遗漏或出错。自动推导保证需求与操作一致，且零维护成本。WandRequirementDeriver.derive() 是纯函数，按 op 类型→BehaviourTag 映射，多个同 tag 取 max level。

**为什么 WandEquipOp/WandReturnOp 注入私人队列而非创建独立 task？** 法杖是执行主任务的**前置条件**，不是独立的后台工作。注入同一 NPC 的私人队列保证原子性：取法杖→执行→归还三条一体，不会被其他 NPC 抢走或调度器误分配。私人队列 LIFO 特性确保 WandEquipOp 先执行、WandReturnOp 最后执行。

**为什么 WandProvider 是 @FunctionalInterface 而非在 World 放仓储引用？** core/ 不能引用 MC 类（ColonyItemBank/SavedData）。WandProvider 将仓库查询抽象为纯 core 接口，engine 层通过 WandProvisionSystem 注入具体实现。SchedulerSystem 通过构造器接收，依赖关系清晰。

**为什么所有法杖共用物品 ID "wandscape:wand" 而非每种一个 ID？** 4 种法杖（builder/gatherer/crafter/ritual）只有 NBT "behaviors" 不同。NBT 驱动允许新法杖类型仅靠 JSON 添加，无需注册新 Item。ColonyItemBank 按 ItemKey(itemId, nbt) 区分，同一 ID 不同 NBT 自动成为不同条目。引擎通过 preset NBT 的 wand_color 匹配回 preset ID。

**为什么法杖取还零魔力消耗？** 取/还法杖是殖民地物流操作，不是施法。消耗魔力只在实际执行任务操作（TransformOp/BlockInteractOp/RitualOp）时发生，符合"法杖是工具→工具不耗能→使用工具才耗能"的直觉。

## GUI 任务编辑器

**为什么 GUI 发布任务走网络包而非直接调 API？** 客户端代码在 `shared/ui/`，不能引用 `core/` 类（core 纯 Java 零 MC，不参与客户端编译）。网络包是 Minecraft 原生的客户端→服务端通信模式，也是 NeoForge 的标准做法。

**为什么 ParamTypeInfo 要重复定义而不是直接引用 core/ParamType？** `core/task/ParamType` 是 sealed interface，出现在 shared/data 会破坏 core 的零 MC 依赖。枚举镜像 `ParamTypeInfo` + `fromCore()` 转换器是干净的防腐层。

## 殖民地三值评估系统

**为什么贡献粒度按建筑类型而非建筑实例？** JSON 配置 `comfort/magic/wonder` 本身就是 per-type 的语义值。同类型第二栋不叠加符合"首次建造加成"的规则描述，且与 `unlock_requirement` 的 per-type 门槛设计保持一致。

**为什么只在 0↔1 边界跨越时广播事件而非每次检查都广播？** 殖民地常有多栋建筑同时运作，每次扫描都广播会触发大量不必要的订阅者执行。0↔1 是唯一真正改变解锁状态的时刻（第一栋建完→解锁，最后一栋损毁→锁定），以此为边界精确控制事件频率。

**为什么注册表放在 BuildingSavedData 而非 BuildingApiImpl？** `BuildingSavedData` 是所有建筑状态的单一真相来源（结构完整性变化、注册/注销都在这里发生），在 state change 同步发生时更新贡献缓存最自然。BuildingApiImpl 只读查询。

**为什么配方解锁用三字段 unlock_requirement 而非 legacy unlock_magic_value 单字段？** 三值（舒适/魔法/奇观）都是 ≥0 的自然数，缺省填 0 表示无要求，不存在歧义。三个 int 的结构清晰可读，不需要额外的 legacy 分支兼容逻辑。JSON 格式统一为 `{"min_comfort": x, "min_magic": y, "min_wonder": z}`，只需其中一个维度填非零值。


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

---

维护规则：新增决策追加到对应分类末尾。推翻的决策不删除，在行末标注"(已推翻: 日期 — 原因)"。
