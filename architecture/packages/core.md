# core/ — ECS 核心框架

纯 Java 21，零 MC 依赖。ECS 世界 + 组件 + 边界接口 + 内部事件。

重构后：op/task/road/system 已独立为各自顶级包，core 只保留 ECS 框架本质。

## 入口

`CoreBootstrap.bootstrap(config)` → 返回装配好的 `World` 实例。`BootStrapConfig` record 打包所有边界接口实现 + 蓝图注册表 + TaskSource 列表。

## ECS 框架

World（中央容器：ComponentStore + System 列表 + 边界引用）、System（函数式接口）、ComponentStore、CoreBootstrap。内部事件通过 `SimpleEventBus` 在 tick 末批量派发。

## 组件

Position / EquipmentComponent / TaskExecutor / NpcTaskQueue / Inventory / NavigationState / ColonyMember / ColonyMetadata / SuspensionContext（共 9 个）。

### NPC 属性模型

NPC 只有 6 个属性：`MAX_HP` / `MOVE_SPEED` / `SPELL_POWER` / `WORK_SPEED` / `SPELL_SPEED` / `ARMOR_VALUE`。魔力系统已整体移除（ManaPool/ManaRegenSystem 已删）。属性值存于 `EquipmentComponent`：base（来自 `NpcAttributes`，招募/默认值） + 装备 modifier，**所有装备加成一律加法**（`effective = base + Σmodifier`，`ModifierOperation` 只有 ADDITION）。运行时各机制读取：
- `SPELL_POWER` → NPC 对敌对生物的魔法伤害倍率，在伤害核算入口统一乘（`guard/NpcSpellPowerHandler`，`LivingIncomingDamageEvent`；判定伤害源是 NPC 且目标为 `Enemy`）——任何未来魔法自动生效，不在单个魔法里写乘算
- `WORK_SPEED` → 采集/合成耗时：`实际 = 基础tick / WORK_SPEED`（`WandscapeBlockInteractExecutor`；建造 TransformOp 保持 1 tick 即时感，不随 WORK_SPEED）
- `SPELL_SPEED` → 魔法 CD：`实际CD = 基础 / SPELL_SPEED`（光束 40、传送 300；施法时间不参与）
- `MAX_HP` / `MOVE_SPEED` / `ARMOR_VALUE` → 推送到 vanilla 实体属性（WandscapeNpc 每 tick）

## 边界接口 (boundary/)

8 个纯 Java 接口定义 ECS 与 MC 世界的边界：BlockOps / EntityOps / RitualOps / MovementOps / ColonyResourceAccess / EventBus / ResourceAddedListener / ResourceShortageHandler。引擎层实现在 `engine/boundary/`。

## 内部事件

通过 SimpleEventBus 在 tick 末批量派发，与 shared/event/ 的 NeoForge 事件分离：TaskCompleted / CustomEvent（蓝图 emit 用）/ NarrativeEventTriggered。1:N 用事件，1:1 用边界接口注入。

## 测试覆盖

26+ 个测试文件，在 `src/test/java/` 对应路径下。
