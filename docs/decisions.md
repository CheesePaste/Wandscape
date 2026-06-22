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

## 任务系统

**为什么调度器评分用 proximity×0.5 + efficiency×0.3 + level×0.2？** 距离因素权重最高（减少 NPC 来回跑）。魔力效率次之（节省资源）。行为等级最低（所有 NPC 都能做基本任务）。

**为什么 GlobalTaskPool 直接用 long 作 task ID 而非 UUID？** 引擎内部性能优先。UUID 仅在与 MC 系统对接时通过 toTaskUuid() 桥接。

## GUI 任务编辑器

**为什么 GUI 发布任务走网络包而非直接调 API？** 客户端代码在 `shared/ui/`，不能引用 `core/` 类（core 纯 Java 零 MC，不参与客户端编译）。网络包是 Minecraft 原生的客户端→服务端通信模式，也是 NeoForge 的标准做法。

**为什么 ParamTypeInfo 要重复定义而不是直接引用 core/ParamType？** `core/task/ParamType` 是 sealed interface，出现在 shared/data 会破坏 core 的零 MC 依赖。枚举镜像 `ParamTypeInfo` + `fromCore()` 转换器是干净的防腐层。

**为什么 TaskCreatePacket 传字符串参数而非序列化 JsonElement？** 客户端 `EditBox` 产出字符串。在服务端解析为 JsonElement（`PublishBlueprintCommand.parseValue` 同逻辑），避免客户端依赖 Gson。

**为什么两条 EventBus 不互通？** core `SimpleEventBus` 是引擎内部 tick-batch 模式，NeoForge `EVENT_BUS` 是实时模式。两者用途不同：引擎内部事件用于链式任务生成（`ResourceLow → gather`），NeoForge 事件用于跨模块通知（`TaskPublishedEvent → UI 提示`）。`engine/` 层做唯一翻译点。

**为什么修复任务 priority=49 而不是 100？** GlobalTaskPool.addTask 对 priority ≥ 50 的任务进入 PENDING_APPROVAL 状态，等待玩家审批。建筑损坏修复是殖民地自治行为，绝不能卡在审批门后。49 在节点采集（15）之上，同时越过高优先级审批门。

**为什么 global.autoApproveTasks 默认关闭？** 建造类大任务（town_hall 等）涉及地形改造，默认审批让玩家有机会取消或推迟。殖民地自治只需开一次开关，之后所有建筑修复/建造任务全自动，无需再手动 `/wandscape approve`。开在 Config TOML 而非硬编码，保留玩家控制权。

**为什么任务的 TriggerDeclaration 在完成时取消订阅？** 防止内存泄漏。已完成任务不应继续响应事件。

## 道路系统

**为什么选 MST 自动生成而非玩家手动规划？** 保证连通性，总路长最短。玩家后期可手动调整（预留数据结构）。

**为什么路径选 L 形而非直线？** 轴对齐确定性强。曼哈顿距离与 L 形一致，MST 计算简单。

**为什么道路纯装饰不与寻路耦合？** 解耦降低复杂度。NPC 寻路不受道路有无影响。道路美观价值独立于功能。

---

维护规则：新增决策追加到对应分类末尾。推翻的决策不删除，在行末标注"(已推翻: 日期 — 原因)"。
