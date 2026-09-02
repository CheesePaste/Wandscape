# Plan: CurseForge 人工审核去风险 —— 消除 1.11.0 引入的反射高危写法

> 背景（2026-08-29）：1.11.0 上传后进入人工审核。审查目标是把 jar 里的「反射访问非自有类私有状态」「反射改写外部 mod 静态字段」
> 等自动扫描高危模式全部清零，使后续版本走自动审核直发。本文档同时覆盖两处修复——Curios 反射镜像与任务面板反射——以及各自的风险判定依据。

## 目标

1. 移除 `CuriosCompatImpl` 对 Curios 内部类 `CuriosEntityManager` 的反射（`getDeclaredField("entitySlots")` + `setAccessible(true)` + `Field.set` 改写静态字段），改为 Curios **官方数据驱动机制**（`data/curios/curios/entities/*.json`），运行时零反射。
2. 移除 `TaskPanelSyncTracker` 对 `WandscapeEngine.buildingTaskPool` 的反射——该字段**根本不存在于 WandscapeEngine**，反射每次静默失败（`catch (Exception ignored)`），「建筑待办队列」段从未渲染过；用户确认删除整块，不修复。

## 背景：为什么 1.11.0 会被人工审核

CurseForge 上传流水线自动静态扫描，命中高危模式即转人工。1.11.0 窗口（v1.10.6..HEAD，63 commits）内踩中的信号：

| 信号 | 位置 | 判定 |
|------|------|------|
| **反射改写另一 mod 静态字段** | `CuriosCompatImpl.mirrorMageSlots`：`CuriosEntityManager.class.getDeclaredField("entitySlots")` + `setAccessible` + `field.set(SERVER, …)` | 头号高危。恶意 mod 惯用同套路关校验/复制物品/改写对方运行时状态。虽有正当用途，静态扫描无法区分，必转人工。 |
| 反射计数堆叠 | jar 内另有 3 处历史反射：`ReplayScreenGuard`（`Class.forName` 进 ReplayMod）、`HostileTargetingHandler`（反射原版 `NearestAttackableTargetGoal.targetType`）、`ProjectionNetwork`（`Class.forName` 自有类） | 叠加后命中「强反射」规则。 |

前者由本方案消除；后者属历史存量，非本窗口新增，维持现状（本次不动）。

## Fix #1：Curios 槽位映射改数据包声明（零反射）

### 现状与根因

`CuriosCompatImpl.mirrorMageSlots()` 的目的：把法师实体类型映射为「玩家标准槽位集」，写入 Curios 服务端实体槽位表，随 Curios 自带 sync 分发给客户端。实现走反射。`docs/plan/curios-mage-slots.md` 当初选反射的理由是「无需改 Curios 源码、注入后自动同步」。

### 源码证据（工作区 `Curios/`，1.21.1 分支）

- `CuriosEntityManager`（`neoforge/src/main/java/.../common/data/CuriosEntityManager.java`，即本 mod 反射目标）公开面只有 `getSyncPacket()` / `applySyncPacket()` 两个 static 与 `SERVER`/`CLIENT` 两个 static 实例，`entitySlots` 为 private——**9.5.x 无任何公开 SERVER 写入 API**。
- 更新版（16.0.0 / `CuriosSlotResources`）中 `CuriosEntityManager` 类已改名删除，字段与类结构全变——**实锤反射跨版本脆弱**（本 mod 在 Curios 16 上该反射必失败并静默降级）。
- 官方机制：`CuriosEntityManager` 是 `SimpleJsonResourceReloadListener`，扫描所有包的 `data/<ns>/curios/entities/*.json`，格式 `{"entities":[…], "slots":[…], "replace":…}`，且支持 NeoForge `ICondition`。该文件装入后自动进 sync 包分发客户端——**镜像反射想达成的效果，数据包全都有**。
- 1.21.1 标准槽位恰 10 个（`back/belt/body/bracelet/charm/curio/hands/head/necklace/ring`，Curios 自带 `curios/slots/*.json`），总是可用。

### 决策

1. **新增数据包文件 `src/main/resources/data/curios/curios/entities/wandscape_npc.json`**：
   - `entities` 列表 `wandscape:wandscape_npc`（注册名核对：`Wandscape.WANDSCAPE_NPC`）；
   - `slots` 列 1.21.1 的 10 个标准槽位；
   - `replace: false`（其它包可按叠加语义补槽）；
   - 带 `conditions": [{"type":"neoforge:mod_loaded","modid":"wandscape"}]`（与 Curios 解析器支持的 `ICondition` 对齐，语义自明）。
   - Curios 自身不发布 entities 文件，无双写冲突。
2. **删 `CuriosCompatImpl.mirrorMageSlots`（含 `(boolean)` 重载）与其两个事件钩子**（`ServerStartingEvent`、`OnDatapackSyncEvent`）。`ServerHooks` 只保留 `onCurioChange`（铁魔法饰品属性桥，全公开 API，审查安全）。清掉 `java.lang.reflect.Field`、`CuriosEntityManager`、`ImmutableMap`、`EventPriority`、`OnDatapackSyncEvent`、`ServerStartingEvent` 等随删停用的 import。
3. **删 `/wandscape curios mirror` 子命令**（其实现即为反射调用）。`list/set/add/remove` 保留——全部经由公开 API（`CuriosApi.getCuriosInventory` / `ICuriosItemHandler.growSlotType/shrinkSlotType`），审查安全，属实例级调整、持久在实体 NBT。
4. 行为差异（取舍）：法师默认槽位从「运行时镜像玩家槽位集（含其它 mod 实时加的槽）」变为「静态标准槽位集」。模组/整合包作者如需给法师加槽，续写自己的 `data/curios/curios/entities/*.json`（`replace:false` 叠加 / `replace:true` 覆盖），datapack reload 即生效。**换取的是：标准数据驱动 + 零反射，通过人审不再看运气**。
5. 客户端零改动：法师槽位映射经 Curios 自带 sync 照旧分发，`NpcCuriosMenu`/`NpcCuriosScreen` 均不受影响。

**影响文件**：
- 新增：`src/main/resources/data/curios/curios/entities/wandscape_npc.json`
- `compat/curios/CuriosCompatImpl.java`（删镜像 + 钩子 + 清 import）
- `compat/curios/CuriosCommand.java`（删 mirror 子命令 + javadoc）
- `architecture/packages/compat.md`（curios 小节「槽位镜像」描述改为数据包声明）
- `docs/decisions.md`（记录「槽位改数据包声明」决策）

## Fix #2：任务面板反射删除（静默失败冗余块）

### 证据（静默失败坐实）

`TaskPanelSyncTracker.buildSnapshot` 第 209-242 行「Collect queued building WorkItems」先反射 `WandscapeEngine.class.getDeclaredField("buildingTaskPool")`。核对源码：

- `WandscapeEngine`（`engine/WandscapeEngine.java`）**不含** `buildingTaskPool` 字段（其字段为 `world/asyncExec/ritualOps/blockInteractExec/movementOps/blueprintConfigLoader/...`，无此项）；`getDeclaredField` 只查本类声明、不查继承。
- 字段真实所在：`core/ecs/World.java:53` `public BuildingTaskPool buildingTaskPool;`（public）。
- 因此反射每次抛 `NoSuchFieldException`，被 `catch (Exception ignored) {}` 吞掉 → `buildingTaskPool` 恒为 null → 该段永不渲染。
- 同一文件第 406 行 `buildProductionGroups` 已直接 `world.buildingTaskPool` 合法读取（工坊流水线 Tab 展示的排队项来自它），与步 2 语义重叠。

### 决策

用户确认：功能已然齐全，直接**删除**整块（第 208-242 行），不做「修好」处理:

- 删反射 try/catch 与整个「步 2」循环。
- 删 `task.engine.pool.BuildingTaskQueue` import（仅该块使用）；`BuildingTaskPool`/`WorkItem`/`WandscapeEngine`/`UUID` 在文件其它处仍在使用，保留。
- `WandscapeEngine.getWorld()`（第 118 行）后续照常（反射消了，但原本就该走 `World` 公开字段）。

**影响文件**：
- `shared/network/tasks/TaskPanelSyncTracker.java`

## 验证

1. `./gradlew build` / `./gradlew test` 全绿（编译期 + 单测）。
2. Jar 内新反射面复核：`grep -r 'getDeclaredField\|setAccessible\|Class.forName'` 应只剩历史存量（`ReplayScreenGuard`/`HostileTargetingHandler`/`ProjectionNetwork`），Curios 与 TaskPanel 相关清零。
3. 游戏内（有 Curios）：
   - 法师饰品按钮 → 打开饰品栏，槽位 = 10 标准槽；
   - `data/curios/curios/entities/wandscape_npc.json` 存在即生效（同步由 Curios sync 完成，客户端无感知）；
   - `curios list/set/add/remove` 正常；`mirror` 已消失；
   - 无 Curios 启动：不影响，无崩溃。

## 非目标

- 历史 3 处反射（Replay 检测 / 原版目标类 / 自有类 `Class.forName`）本次不动——非本窗口引入，改动范围扩大徒增回归风险；如后续仍被自动扫描点名再单独处理。
- 建筑扫描器 Base64/NBT 数据面——上版本修复已把它从「物品+实体完整导出」降级为纯方块结构，且非本窗口新增写法，本次不动。