# core/ — ECS 核心框架

纯 Java 21，零 MC 依赖。ECS 世界 + 组件 + 边界接口 + 内部事件。

重构后：op/task/road/system 已独立为各自顶级包，core 只保留 ECS 框架本质。

## 入口

`CoreBootstrap.bootstrap(config)` → 返回装配好的 `World` 实例。`BootStrapConfig` record 打包所有边界接口实现 + 蓝图注册表 + TaskSource 列表。

## ECS 框架

World（中央容器：ComponentStore + System 列表 + 边界引用）、System（函数式接口）、ComponentStore、CoreBootstrap。内部事件通过 `SimpleEventBus` 在 tick 末批量派发。

## 组件

Position / TaskExecutor / NpcTaskQueue / Inventory / NavigationState / ColonyMember / ColonyMetadata / SuspensionContext（共 8 个）。

### NPC 属性模型

NPC 属性直接使用 vanilla Attribute 驱动，模组专属属性注册于 `WandscapeAttributes`（`engine/attribute/`）：`SPELL_POWER` / `WORK_SPEED` / `SPELL_SPEED` / `MAX_MANA` / `HEALTH_REGEN` / `MANA_REGEN`，战斗属性复用原版 `MAX_HEALTH` / `MOVEMENT_SPEED` / `ARMOR` 等。魔力为第 7 属性（默认 200），当前魔力/每魔法独立 CD/施法互斥锁在 `MagicState`（`core/component/`，由 `WandscapeNpc` 持有）。法杖属性通过 vanilla `ItemAttributeModifiers` 赋予，盔甲属性由原版 per-tick 装备结算（铁魔法自有属性经 `WandscapeNpc.syncIronArmorAttributes` 桥接为 transient 修饰符）。运行时各机制读取：
- `SPELL_POWER` → NPC 对敌对生物的魔法伤害倍率，在伤害核算入口统一乘（`guard/NpcSpellPowerHandler`，`LivingIncomingDamageEvent`；判定伤害源是 NPC 且目标为 `Enemy` 或 `canBeamHurt`，非目标/和平模式**整伤取消**——友军名单 `core/types/FriendlyForce`，铁魔法/召唤物也走此入口）
- `WORK_SPEED` → 采集/合成耗时：`实际 = 基础tick / WORK_SPEED`（`WandscapeBlockInteractExecutor`；建造 TransformOp 保持 1 tick 即时感，不随 WORK_SPEED），任务调度通过 `world.entityOps.getWorkSpeed(npcId)` 评分
- `SPELL_SPEED` → 各魔法 CD：`实际CD = 基础 / SPELL_SPEED`（光束 400、传送 300；施法时间不参与，CD 在施法锁结束后起算）
- `MAX_MANA` → 魔力上限（NPC 首 tick 满蓝 seed，每 `Config.npc.manaRegenTicks`=10 tick 结算回 `Config.npc.manaRegenFraction`=1% 上限 × MANA_REGEN）
- `HEALTH_REGEN` / `MANA_REGEN` → 生命与魔力回复倍率
- `MAX_HP` / `MOVE_SPEED` / `ARMOR` → 原版实体属性原生驱动

## 边界接口 (boundary/)

8 个纯 Java 接口定义 ECS 与 MC 世界的边界：BlockOps / EntityOps / RitualOps / MovementOps / ColonyResourceAccess / EventBus / ResourceAddedListener / ResourceShortageHandler。引擎层实现在 `engine/boundary/`。

## 内部事件

通过 SimpleEventBus 在 tick 末批量派发，与 shared/event/ 的 NeoForge 事件分离：TaskCompleted / CustomEvent（蓝图 emit 用）/ NarrativeEventTriggered。1:N 用事件，1:1 用边界接口注入。

## 测试覆盖

26+ 个测试文件，在 `src/test/java/` 对应路径下。
