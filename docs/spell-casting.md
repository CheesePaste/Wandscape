# NPC 施法决策层 — 设计提案（规划中，未实现）

> **状态**：本文是面向"多魔法"的设计提案，**尚未实现**。当前代码仍是无决策层的状态（见「现状问题」）。
> 文档以 `docs/` 为权威；实现落地后本文转为模块文档，并与 `docs/modules/magic.md` 对齐。

## 一、现状问题：为什么需要决策层

魔法门控已经就绪，但**决策是散的**。当前 NPC 施法的真实路径：

| 调用方 | 触发 | 直接做的事 |
|---|---|---|
| `guard/executor/GuardCombat.engage`（GuardCombat.java:53） | 守卫任务：建筑区内最近敌对 | `MagicCaster.castNpcAt` 射 beam |
| `guard/executor/SelfDefenseExecutor` | 自防御：仇恨优先→半径内最近 | `MagicCaster.castNpcAt` 射 beam |
| `engine/system/NavigationSystem.switchToRitualTeleport`（NavigationSystem.java:256） | 寻路失败 | 直接 `npc.tryCastSpell("teleport", …)` |

共同点：**各自选魔法、各自施法**，靠门控（`MagicState` 的互斥锁）保证不撞车。魔法效果也只有一种——`MagicCaster` 只会射光束，`MagicBeamEntity` 是唯一伤害实体。NPC 没有"会哪些魔法"的概念。

**魔法一多必然崩**：每加一个魔法要挑一个调用方塞进去；两个调用方想施不同魔法时互相抢互斥锁。所以先有决策层，再谈优先级 UI。

**不需要重做的部分**（已稳固，保持不动）：
- 门控：`MagicState`（每魔法独立 CD + 施法互斥锁 + 魔力，`core/component/MagicState.java`）
- 统一入口：`WandscapeNpc.tryCastSpell`（npc/entity/WandscapeNpc.java:119）
- 视觉层：`MagicCircleSpec` 法阵 + `MagicCastManager` 调度 + `MagicBeamEntity`

## 二、目标

1. **任意数量魔法，新魔法零改动接入**（数据驱动，仿 `MagicCircleSpec` + dataconfig）。
2. **玩家可控但不繁琐**：只调一个"策略/优先级"维度，不写脚本。
3. **原子化**：决策层通过 `WandscapeApis` + 事件通信，不跨包引用（CLAUDE.md 铁律 1/2）。
4. **保持 `core/` 零 MC 依赖**：魔法定义/策略是纯数据 record；效果实现按"引擎是请求层、适配层是实现"放 `engine/` 或各模块 `internal/`。

## 三、参考模型谱系（知名游戏怎么管理 NPC 施法）

玩家对施法决策的控制力，从高到低：

| 方案 | 代表 | 机制 | 对本模组的适配度 |
|---|---|---|---|
| 全条件脚本 | FFXII Gambit / Dragon Age: Origins | 玩家写"HP<30%→回血"逐条判定 | 最可控，但 UI/翻译/理解负担最重；殖民地 NPC 该自治 |
| **分类权重** | 龙之信条 随从倾向 | 玩家调**分类**（救死扶伤/输出/实用）的排名，AI 自己挑具体技能 | **最贴合"分类+优先级"**；只排 3~4 个分类，UI 直觉 |
| 战术模式 | 女神异闻录 | 玩家只选"全力攻击/省蓝/辅助"策略，AI 在策略内自行决定 | 简单直觉，适合做**预设** |
| 每技能开关 | WC3/DotA 自动施放 | 每个技能一个勾选 + 内置触发条件 | 最轻量，适合做**兜底**/微调 |
| 纯 AI | 宝可梦 | 按得分表自动选 | 零负担但玩家无操控感 |

**结论**：本模组采用 **龙之信条（分类权重）+ 女神异闻录（策略预设）+ WC3（每技能开关）** 的组合——玩家只动一层（策略 + 优先级列表），其余 AI 自治。全条件脚本（FFXII）对 Minecraft 殖民地模组过重。

## 四、三层决策架构

```
L0 硬性覆盖（不可配置，保命/不打断）
  ├─ 自身血量 < 阈值           → 治疗魔法 / 脱离战场（无视玩家策略）
  ├─ LOS 被方块挡住           → 停止施法、转寻路（现有 GuardCombat 逻辑）
  ├─ 施法互斥锁被占用          → 不打断当前施法，等下一轮
  └─ 导航失败需传送           → 走 utility 传送（导航回退，属硬性路径）

L1 玩家策略层（可配置）
  ├─ 每个 NPC 一个策略预设（均衡/火力/支援/自定义）
  └─ 策略内一条可编辑的【施法优先级列表】
      每 tick 从上往下扫：第一个"CD 已过 + 蓝够 + 有有效目标 + 条件满足"的魔法即施放

L2 兜底层
  └─ 列表全不可施 → 基础攻击 / 走位 / 待命（现有行为保持）
```

关键点：**L0 必须永远先于 L1**（保命逻辑不能被玩家把治疗调成最低优先级而饿死）；L1 玩家改的是**分类或魔法间的排序**，不是触发条件本身。

## 五、数据结构（JSON 数据驱动）

### 5.1 `MagicDef` — 魔法定义（仿 `MagicCircleSpec`：record + fromJson + dataconfig）

```jsonc
{
  "id": "beam",                 // 魔法 id（tryCastSpell 的 magicId key）
  "category": "single_target",  // 分类：single_target / aoe / buff / heal / utility
  "mana_cost": 50,
  "base_cooldown": 40,          // tick；按 SPELL_SPEED 缩短（沿用 MagicState 现有逻辑）
  "range": 32,
  "target_mode": "hostile_nearest",
  "lock_ticks": 60,             // 施法占用互斥锁时长（可留空，由效果决定）
  "effect": {                   // 每魔法专属参数，由对应 MagicOp 消费
    "circle_id": "arcane_hexagram",
    "color": "0xFFA8E0FF"
  }
}
```

`target_mode` 决定"何时算有有效目标"，与 `category` 解耦：

| target_mode | 语义 |
|---|---|
| `hostile_nearest` | 半径内最近敌对（现有 beam 语义） |
| `hostile_lowest_hp` | 半径内血量最低敌对（集火） |
| `ally_lowest_hp` | 友方血量最低（治疗/buff） |
| `self` | 目标=自身（buff/护盾） |
| `none` | 无需目标（辅助/场地魔法） |

`category` 主要服务于**策略预设的默认排序**（火力预设=单攻/AOE 优先；支援预设=治疗/buff 优先），不承载触发逻辑。

### 5.2 `CastStrategy` — 施法策略（存于 NPC 数据）

```
预设: balanced / offensive / support / custom
priority: [magicId…]   // 玩家可编辑的优先级列表；custom 预设必须显式给全
```

- **预设提供默认优先级列表**（按分类排序），玩家可整体换预设，也可微调成 custom。
- 玩家不设任何东西 → 默认 `balanced` 的默认列表，零配置可用。

## 六、模块结构与关键类

```
shared/data/MagicDef.java          record 镜像 + fromJson（纯数据，仿 MagicCircleSpec）
shared/api/SpellcastingApi.java    决策层对外接口（getKnownSpells/getStrategy/setStrategy…）
core/component/SpellbookComponent.java   NPC 会哪些魔法（magicId 列表）
core/component/CastStrategyComponent.java 策略预设 + 优先级列表
magic/internal/SpellbookLoader.java dataconfig 注册 magic_spells 类目 + getSpec(id)/getAll()
magic/internal/MagicOp.java        魔法效果分发注册表（仿 op/api/AtomicOp 的 sealed interface）
magic/internal/CastBrain.java      统一施法脑：意图 → L0 覆盖 → L1 优先级扫描 → L2 兜底 → 选出魔法
```

### 6.1 `MagicOp` — 效果分发（模仿 `AtomicOp` 先例）

当前 `AtomicOp`（op/api/AtomicOp.java）是 sealed interface + `OpExecutorRegistry` 注册表：**纯数据请求 + 执行器按类型分发**。`MagicOp` 照此模式：

- sealed interface `MagicOp`，每个魔法一个 variant（`BeamOp`/`TeleportOp`/未来的 `AoEOp`/`BuffOp`…），携带该效果所需参数。
- 执行器按 magicId 注册：beam 效果走现有 `MagicCaster`/`MagicCastManager`/`MagicBeamEntity`；teleport 走 `engine/boundary/WandscapeRitualOps.self_teleport`（已有）。
- **效果实现仍留在各模块 internal**，`MagicOp` 只做分发——不违反"引擎是请求层、适配层是实现"。

### 6.2 `CastBrain` — 统一施法脑

- **纯决策函数**，不是新的任务系统：`select(World, npcId, 意图) → MagicOp?`。
- 调用方（守卫/自防御/导航）把**意图**喂进来（意图 = 目标线索 + 可用魔法集合的边界），`CastBrain` 返回选定的魔法，调用方执行。
- 决策每 ~10 tick 一轮（沿用现有守卫执行器节奏），不额外起线程。

## 七、决策流程（数据流）

```
触发源：守卫任务循环 / 自防御循环 / 导航回退 / 手动 shift+右键
  → 构造施法意图（目标线索 + 魔法集合）
  → CastBrain.select()
      ├─ L0 硬性覆盖：血量危机/LOS/互斥锁 → 硬性魔法 或 直接返回"不施放"
      ├─ L1 按 priority 列表从上往下扫：
      │     MagicState.canCast(magicId) && 蓝够 && target_mode 命中 → 选中
      └─ L2 兜底：基础攻击/走位/待命
  → 选出 MagicOp → 执行器（BeamOp→MagicCaster/MagicCastManager/MagicBeamEntity；
      TeleportOp→WandscapeRitualOps）
  → 门控仍走 npc.tryCastSpell（MagicState 不变）
```

## 八、对接现有代码（迁移路径）

1. **beam 迁移为第一个 `MagicOp`**：`id="beam"`、沿用 `BEAM_BASE_CD=40` / `BEAM_MANA_COST=50` / `DEFAULT_CIRCLE`，行为不变。数据从 `MagicCaster` 常量挪进 `magic_spells/beam.json`（新魔法才能纯 JSON 接入）。
2. **teleport 迁移为第二个 `MagicOp`**：导航回退的传送属于 L0 硬性路径（`NavigationSystem` 的 `tryCastSpell("teleport")` 保留，只是 magicId 定义进 `MagicDef`）。
3. **`GuardCombat.engage` / `SelfDefenseExecutor` 改造**：不再直接 `MagicCaster.castNpcAt`，改为构造意图 → `CastBrain.select()` → 执行选出的 `MagicOp`。
4. **`MagicCaster` 瘦身**：从"只会射光束"变成"`MagicOp` 分发器的 beam 实现"；玩家调试命令 `cast` / shift+右键 `castNpc` 保留（免费，测试功能，走 L0 调试路径不进玩家策略）。
5. **手动施法**（shift+右键）保持免费、不占蓝（现注释即"测试功能"），只占用 CD 与互斥锁——不归玩家策略管。

## 九、分阶段实施

| 阶段 | 内容 | 验收 |
|---|---|---|
| **P1 数据与分发** | `MagicDef` + `SpellbookLoader`（dataconfig）+ `MagicOp` 注册表；把 beam/teleport 迁成 `MagicOp` | 行为与现在完全一致（纯重构） |
| **P2 决策集中** | `CastBrain`；GuardCombat/SelfDefense 改为喂意图；L0 覆盖落地 | 守卫/自防御不再各自选魔法 |
| **P3 玩家策略** | 策略预设 + 优先级列表 + `CastStrategyComponent` + `NpcScreen` 策略 UI | 玩家可在 NPC 界面调"火力/支援/自定义+排序" |

P1/P2 玩家无感知（内部重构），P3 才见 UI。每个阶段完成即按 CLAUDE.md 提交规则 commit。

## 十、依赖与边界

- `MagicDef` / `CastStrategy` 是纯数据 record → 放 `shared/data/`（所有包可见，仿 `MagicCircleSpec`）。
- `SpellbookComponent` / `CastStrategyComponent` → `core/component/`（纯 Java 零 MC，仿 `MagicState`）。
- 效果实现 → `magic/internal/`（beam）与 `engine/boundary/`（teleport），`MagicOp` 只做分发。
- 模块间通过 `SpellcastingApi` + 事件，不跨包 new 类（铁律 1）。
- **不做全条件脚本**（FFXII 式）：触发器是 `target_mode` 内置枚举，不是玩家可写的判定——避免翻译与理解负担。
