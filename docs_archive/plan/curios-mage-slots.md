# Plan: 法师 Curios 饰品格子 —— 运行时镜像玩家槽位 + /wandscape curios 指令

> **已被取代（2026-08-29）**：本文的「运行时反射镜像」方案因 CurseForge 人工审核风险已废弃——
> 改为数据包声明槽位（`data/curios/curios/entities/wandscape_npc.json`，零反射）。
> 见 `docs/plan/curseforge-review-de-risk.md` 与 `docs/decisions.md`（2026-08-29 决策）。
> 下文保留作历史记录，不再作为实现依据。

> 方案决策（2026-08-28 修订）：默认槽位**不在 JSON 写死**，而是**运行时镜像玩家标准槽位集**——
> 这样铁魔法法术书槽位等其他模组给玩家加的新槽位，法师初始就拥有。兼配 `/wandscape curios` 指令
> 供模组/整合包作者精细调整。实现任务清单见 `.claude/plans/partitioned-tickling-music.md`（已批准，
> 槽位来源一节按本文档修订）。

## 目标

右键法师进入 `NpcScreen` 后，在法师 3D 缩略图左上角显示与玩家背包中同款的 Curios API 饰品图标按钮；
点击打开法师自己的饰品栏；可装备/卸下饰品。

槽位语义：

- **默认 = 运行时玩家标准槽位集**（`CuriosApi.getEntitySlots(EntityType.PLAYER, isClient)`）：数据包 reload /
  玩家加入时的集合，含其他模组（如铁魔法法术书槽位）与整合包通过 `curios/entities/*.json` 或 config 给
  玩家加的槽位——法师初始即为同款。
- **对齐"新玩家"而非实时追踪**：镜像发生在数据 reload / 玩家登录后；此后某玩家用 `/curios` 之类的
  实例级增长（`getSlotHelper().growSlotType`）获得的额外槽位，**不会**追传给法师。法师数量 = 当时的新玩家数量。
- **数据包可覆盖**：若某模组/整合包在自己的 `curios/entities/<file>.json` 里显式列出了
  `wandscape:wandscape_npc`（自定义槽位集），则该显式配置胜出，镜像让位。
- **指令补充**：`/wandscape curios list|mirror|set|add|remove` 便于模组/整合包作者查询与实例级调整。

## 可行性结论

- **无需修改 Curios 源码 / 无需附属模组（LGPL-3.0 无传染）**：镜像用反射把法师→玩家槽位集的映射注入
  Curios 服务端的 `CuriosEntityManager.SERVER.entitySlots`（不复制、不改写第三方代码，仅是运行时数据写入）；
  注入后 Curios 自带的 datapack sync（`getSyncPacket` + `SPacketSyncData`）自动把法师映射分发给客户端，
  客户端无须任何改动。指令/实例级增删走公开 API（`CuriosApi.getSlotHelper()`）。
- 反射风险兜底：字段名随 Curios 版本可能变动；带 try/catch + 日志，失败则法师无饰品槽（功能惰性降级），
  不影响其余功能。

## 落地实现

### 软依赖（同前）
- `gradle.properties`：`curios_version=9.5.1+1.21.1`
- `build.gradle`：`maven.theillusivec4.top` + `compileOnly "top.theillusivec4.curios:curios-neoforge:${curios_version}"`
- `neoforge.mods.toml`：`curios` 可选依赖声明

### 运行时镜像（`compat/curios/`，`CuriosCompat.isLoaded()` 门控）
- `CuriosCompat.init(modEventBus)`：加载时注册独立 `DeferredRegister<MenuType<?>>` 的 `NPC_CURIOS_MENU`。
- 服务端监听（`NeoForge.EVENT_BUS`）：
  - `OnDatapackSyncEvent`（`EventPriority.HIGHEST`，须先于 Curios 自己的 onDatapackSync 发包）刷新镜像；
  - `ReloadListenersReloadedEvent` 兜底刷新。
- `mirrorMageSlots()`：
  - `playerSlots = CuriosApi.getEntitySlots(EntityType.PLAYER, false)`；空则镜像为空（与玩家一致）。
  - 若 `CuriosEntityManager.SERVER.entitySlots` 已含 `wandscape:wandscape_npc` 显式数据包条目 → 跳过（数据层胜出）。
  - 否则反射重建 map：`put(WANDSCAPE_NPC.get(), ImmutableMap.copyOf(playerSlots))` 写回。
- 客户端：法师映射经 Curios 自己的 datapack sync 送达；法师实体能力解析是每查询重算，现存法师也能获得
  饰品 handler。

### 指令 `/wandscape curios`（`compat/curios/CuriosCommand`，服务端）
- `curios list [target]`：列出目标法师当前槽位类型与数量（默认所有殖民地法师）。
- `curios mirror`：强制刷新镜像（即便存在数据包显式覆盖也重写为玩家槽位集）。
- `curios set|add|remove <slot> <count> [target]`：实例级调整（`CuriosApi.getSlotHelper().setSlotsForType/
  growSlotType/shrinkSlotType`，`target` 默认所有殖民地法师；槽位参数用 Curios 的 `CurioArgumentType.slot()`
  自动补全）。实例级变化存在实体附件 NBT，reload 不清除。
- 在 `Wandscape.onRegisterCommands` 的 `/wandscape` 根下 `.then(CuriosCommand.node())`，（`isLoaded()` 守卫）。

### 容器/屏幕/按钮/网络包（同原计划，槽位来源改为 API 查询）
- `NpcCuriosMenu`：服务端从 `CuriosApi.getCuriosInventory(npc)`；客户端从 `CuriosApi.getEntitySlots(
  WANDSCAPE_NPC.get(), true)`（已含镜像映射）构建。槽位顺序按 `ISlotType` 自然序，两边一致。
- `NpcCurioSlot`：`SlotItemHandler` 镜像 `CurioSlot`（图标/校验/onEquipFromUse）。
- `client/NpcCuriosButton`（复刻玩家背包按钮纹样）+ `client/NpcCuriosScreen` + `NpcOpenCuriosPacket`。
- `npc/client/NpcScreen`：模型左上角按钮；`WandscapeClient` 注册屏幕；`Wandscape.onRegisterPayloads` 注册包。

### 文档
- `architecture/packages/compat.md`：`curios/` 小节。
- `docs/decisions.md`：镜像是"新玩家快照"而非持续追传、数据包显式覆盖优先、指令做实例级调整。

## 验证
1. `./gradlew build` / `./gradlew test` 通过。
2. 游戏内：
   - NpcScreen 模型左上角出现 Curios 按钮（同玩家背包纹样）→ 点击打开法师饰品栏。
   - 默认槽位数 = 玩家标准槽位集（含铁魔法法术书槽位若装了）。
   - 放/卸/Shift/拖拽生效；关闭重进饰品仍在（实体 NBT）；死亡掉落。
   - `/wandscape curios list|set|add` 生效；`/reload` 后：镜像刷新（新槽位出现）、数据包显式覆盖胜出、
     实例级调整保持。
   - 无 Curios 启动：无按钮、无崩溃。
3. `.gitignore` 已含 `Curios/`。