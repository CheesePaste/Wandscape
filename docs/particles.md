# 粒子特效系统设计

本文梳理 Wandscape 的粒子现状、NeoForge 1.21.1 的粒子机制、建议的统一接入架构，以及各模块可加粒子特效的具体挂点。目标：**一次建好统一机制，之后加特效 = 选粒子 + 一个挂点一行调用**，后期换表现只改挂点。

> 范围约束：**只美化玩家世界内可见的事件**（建成、施法、游客、战斗、庆祝）。打开/关闭 GUI、面板、投影/俯瞰模式的进入退出等"界面层"事件一律不加——那个反馈本身就是 UI/模式本身，不算世界内效果。

## 1. 现状

- 已注册 2 个自定义粒子：`cast_bolt`（`Wandscape.java:206`）、`magic_glow`（`Wandscape.java:210`），各有 `particles/<id>.json`，客户端 Provider 在 `WandscapeClient.java:310` 注册。
- 已有一个**可染色点粒子**类：`magic/client/MagicCircleDotParticle.java`（复用 `minecraft:glow` 贴图 + RGB 染色，可控尺寸/透明度/寿命/淡出，全亮）。`CastBoltParticle.java` 是同类。
- 魔法阵特效已工作：`MagicCircleCastPacket`（服务端→客户端）→ `MagicCircleEmitter` 每 tick 撒粒子（`magic/client/MagicCircleEmitter.java`），垂直法杖朝向。这是"服务端发包→客户端渲染"的现成范例。
- **现有 3 处服务端粒子是坏的**：都在服务端调 `level().addParticle(...)`，而服务端 `addParticle` 是空操作（见 §2.4）——
  - `npc/entity/WandscapeNpc.java:731` `doWorkAnimation()`（WITCH）
  - `engine/boundary/WandscapeRitualOps.java:116` `executeRitual()` self_teleport（PORTAL）
  - `engine/boundary/WandscapeBlockInteractExecutor.java:557` `spawnCompletionParticles()`（HAPPY_VILLAGER）
  → 这些粒子玩家实际看不到。需先改为 `ServerLevel.sendParticles` 修好基础设施，再谈加新特效。
- 结论：粒子机制**已有雏形**（注册/客户端类/染色/网络包模式都在），缺的是"统一服务端广播入口 + 全局开关 + 覆盖到各模块挂点"。

## 2. NeoForge 1.21.1 粒子机制（已从源码核实）

### 2.1 两层结构

```
代码侧 ParticleType 注册 → particles/<id>.json（贴图）→ 客户端 Provider 渲染
        └──── SimpleParticleType 无参数数据：不能携带颜色/尺寸/寿命 ────┘
```

- `SimpleParticleType(false)` 只是"一种贴图 + 默认行为"的标记，**无法表达颜色/尺寸**。要染色粒子要么客户端类 + 网络包，要么自定义带数据的 `ParticleType`（后者要写 StreamCodec，重）。
- **推荐**：染色粒子复用 `MagicCircleDotParticle` + 一个 `ParticleBurstPacket`（仿 `MagicCircleCastPacket`），不逐类建 ParticleType。

### 2.2 注册 ParticleType

```java
// Wandscape.java（与 ITEMS/BLOCKS/PARTICLE_TYPES 并列，已有）
public static final DeferredRegister<ParticleType<?>> PARTICLE_TYPES =
        DeferredRegister.create(Registries.PARTICLE_TYPE, MODID);
public static final DeferredHolder<ParticleType<?>, SimpleParticleType> MAGIC_GLOW =
        PARTICLE_TYPES.register("magic_glow", () -> new SimpleParticleType(false));
```

- `src/main/resources/assets/wandscape/particles/<id>.json` 只写贴图：
  ```json
  { "textures": [ "minecraft:glow" ] }
  ```
- 客户端 Provider（`WandscapeClient.java:310`）：
  ```java
  event.registerSpriteSet(Wandscape.MAGIC_GLOW.get(), MagicCircleDotParticle.Provider::new);
  ```
- 换贴图/样式只动 json 或粒子类，不改挂点调用。

### 2.3 服务端广播（唯一正确入口）

```java
// 向 32 格内玩家广播（longDistance=false）
serverLevel.sendParticles(type, x, y, z, count, xOffset, yOffset, zOffset, speed);
// 逐玩家 + 大范围（longDistance=true → 512 格）
serverLevel.sendParticles(player, type, true, x, y, z, count, xOffset, yOffset, zOffset, speed);
```

- 距离阈值（源码核实，`ServerLevel.sendParticles` → 私有 `sendParticles`）：`closerToCenterThan(..., longDistance ? 512.0 : 32.0)`。
- **大范围庆祝（建成烟花/殖民地升级/袭击胜利）必须用 longDistance=true 的逐玩家重载**，否则玩家在 32 格外看不到建成烟花——这与"美化要玩家能看见"的目标直接冲突。

### 2.4 陷阱：服务端 `Level.addParticle` 是空操作

- `Level.addParticle(...)` 基类为**空实现**，只有 `ClientLevel` 覆写它（本地渲染，不走网络）。
- `ServerLevel` 不覆写 → **服务端调 `level.addParticle()` 什么都不发生**。
- 现状 3 处坏粒子都踩了这个坑（§1）。修法：服务端一律走 `sendParticles`。
- 客户端"本地补效果"（如 `MagicCircleEmitter` 每 tick 撒点）走 `ClientLevel.addParticle` 或粒子类静态 `spawn`（`MagicCircleDotParticle.spawn`）。

### 2.5 客户端染色粒子复用

```java
// 客户端直接撒一个可染色点粒子（全亮，可控制尺寸/淡出/寿命）
MagicCircleDotParticle.spawn(level, x, y, z, r, g, b,
        startSize, endSize, alpha, fadeOut, lifetime);
```

服务端触发染色：发 `ParticleBurstPacket`（服务端→客户端，携带 pos/RGB/count/size/lifetime），`handleClient` 里调 `spawn`。完全复用 `MagicCircleCastPacket` 的注册/编解码写法。

## 3. 统一 ParticleService（建议架构）

**结论：一个"薄"静态门面，不做重型粒子引擎。** 理由：粒子分散在 9+ 个包，不统一会出现"有的走 sendParticles、有的走坏的 addParticle、有的自己发包"；全局开关、大范围广播、节流都要单点。

### 3.1 落位

| 职责 | 位置 | 说明 |
|------|------|------|
| 粒子注册 | `Wandscape.java`（已有 PARTICLE_TYPES） | 只保留确需新 ParticleType 的（多数走原版/染色包） |
| 广播/染色/节流封装 | `engine/service/ParticleService.java` | 与 SoundService/ColonyMetricsService 并列，纯 MC 侧 |
| ParticleBurstPacket | `shared/network/ParticleBurstPacket.java` | 仿 MagicCircleCastPacket，染色特效走它 |
| 全局开关 | `Config.java` 加 `PARTICLE_LEVEL` | 见 §6 |

> 为什么不在 `core/`：`core/` 禁止 import MC 类，ServerLevel/ParticleOptions 都是 MC 类型。粒子纯 MC 关注点，只能放 `engine/`、`shared/network`、各模块 MC 侧。

### 3.2 ParticleService 接口草案

```java
// engine/service/ParticleService.java —— 静态门面
public final class ParticleService {
    private ParticleService() {}

    /** 服务端向 32 格内玩家广播原版粒子 */
    public static void burstAt(ServerLevel level, ParticleOptions type,
                               Vec3 pos, int count, double spread, double speed);

    /** 服务端大范围广播（512 格，逐玩家 longDistance=true）—— 建成/升级/胜利庆祝用 */
    public static void burstAtFar(ServerLevel level, ParticleOptions type,
                                  Vec3 pos, int count, double spread, double speed);

    /** 服务端触发染色粒子：发 ParticleBurstPacket 给追踪玩家，客户端 spawn */
    public static void burstColored(ServerLevel level, Vec3 pos,
                                    float r, float g, float b,
                                    int count, float size, int lifetime);

    /** 带节流：同一 key 在 minIntervalTicks 内只放一次（防高频 tick 刷屏） */
    public static void burstAtThrottled(ServerLevel level, String key,
                                        ParticleOptions type, Vec3 pos,
                                        int count, double spread, double speed,
                                        int minIntervalTicks);
}
```

- 节流实现：`Map<String, Long> lastTick`（按 key + 服务端 tick），间隔不足跳过。高频点（光束每 tick 命中、逐块放置）必须走节流版。
- 所有方法内部先查 `Config.PARTICLE_LEVEL`：`OFF` 直接 return，`LOW` 减半 count，`NORMAL` 原样，`HIGH` 原样。开关一个点控制全局。

## 4. 接入点清单（哪里可以加）

挂点按优先级分组；`文件:方法` 为当前快照，以方法名为准。`服务端` = 用 `sendParticles` 广播；`客户端` = 走 `ParticleBurstPacket` 或本地撒点。

### P0 建成庆祝 + 玩家直接可见（反馈最强烈，优先做）

| 挂点 | 文件:方法 | 触发 | 建议特效 | 侧 |
|------|-----------|------|----------|----|
| 建筑建成 🎆 | `building/internal/BuildCompleteListener.java` `onBuildComplete()`:120（anchor 在手） | NPC 蓝图施工完成 | 原版 `FIREWORK` 升空 + `EXPLOSION_EMITTER` 于 anchor 上方，`burstAtFar` 大范围 | 服务端 |
| 建筑拆除完成 | `building/internal/DemolishCompleteListener.java` `onDemolishComplete()`:41（anchor 在手） | NPC 拆完所有方块 | `LARGE_SMOKE` 灰烟 + `CAMPFIRE_COSY_SMOKE` 灰烬 + 少量碎石，`burstAt` | 服务端 |
| 殖民地创建 | `command/ColonyCommand.java`:190（发 ColonyCreatedEvent，origin 在手） | `/wandscape colony create` | origin 上空少量烟花，`burstAt` | 服务端 |
| 殖民地升级 | `engine/colony/ColonyLevelManager.java` `addExperience()`:114（事件只有 colonyId） | 经验达阈值升级 | 市政厅位置烟花/垂直光柱；需先经 BuildingApi 找 government 建筑锚点，`burstAtFar` | 服务端 |

### P1 NPC / 自动行为（环境反馈）——含修复 3 处坏粒子

| 挂点 | 文件:方法 | 触发 | 建议特效 | 侧 |
|------|-----------|------|----------|----|
| NPC 完工粒子（**修复 no-op**） | `npc/entity/WandscapeNpc.java` `doWorkAnimation()`:731 | NPC 每放一块方块 | 改 `sendParticles(WITCH,...)`；大建筑逐块会多，建议 count 降到 1–2 | 服务端 |
| 采集/合成/酿造完成（**修复 no-op**） | `engine/boundary/WandscapeBlockInteractExecutor.java` `spawnCompletionParticles()`:557 | 异步动作完成 | 改 `sendParticles(HAPPY_VILLAGER,...)`，或按元素/产物染色 `burstColored` | 服务端 |
| NPC 自传送（**修复 no-op**） | `engine/boundary/WandscapeRitualOps.java` `executeRitual()`:116 | self_teleport 完成 | 起点+终点两端 `PORTAL` 爆开 | 服务端 |
| 守卫开火 | `guard/executor/GuardCombat.java` `engage()`:72 | 守卫施法（40 tick 节流） | 已发法阵粒子，可加杖尖 `burstColored` 爆闪（可选，避免叠堆） | 服务端 |
| 游客到达 | `tourist/internal/TouristSpawnSystem.java` `flushPendingSpawns()`:308（spawnPos 在手） | 游客生成 | 脚下 `HAPPY_VILLAGER`/`END_ROD` 小金星 | 服务端 |
| 游客离开 | `tourist/internal/TouristSpawnSystem.java` `onTouristDepart()`:511 | 游客离场 | 消散粒子（游客当前位置，淡出） | 服务端 |
| 游客购物/服务交互 | `tourist/internal/TouristMoveGoal.java` `interactWithShop()`:1288、`interactWithService()`:1327 | 交互完成加满意度 | 建筑交互点按 gain 撒星光（`HAPPY_VILLAGER` 或染色），`burstColored` | 服务端 |
| 酒店入住 | `tourist/internal/TouristMoveGoal.java`:585（checkIn 成功） | 夜晚入住 | 酒店口 `NOTE`/`CAMPFIRE_COSY_SMOKE` 舒适感（可选） | 服务端 |
| 商店补货 | `building/internal/ShopStockManager.java` `restock()`:362（发 ShopRestockedEvent） | 每日补货 | 商店口金币星光（可选；已有物品飞行视觉） | 服务端 |

### P2 模拟经营 / 全局（低频，可做音乐性/庆祝）

| 挂点 | 文件:方法 | 触发 | 建议特效 | 侧 |
|------|-----------|------|----------|----|
| 奇观生效/移除 | `building/internal/WonderEffectApplier.java` `applyEffects()`:183、`removeEffects()`:156（发 WonderEffectChangedEvent，buildingId 在手） | 奇观 intact 且非关停 | 生效：金色圣光柱（`END_ROD`/`burstColored` 染金）；移除：淡出 | 服务端 |
| 建筑关停/重启 | `building/internal/BuildingApiImpl.java`:197（Shutdown）/`:225`（Restarted） | 维护费不足/恢复 | 关停：屋顶灰烟下坠；重启：上升星光（anchor 在手） | 服务端 |
| 袭击胜利 | `raid/ColonyRaidTracker.java`:60（发 ColonyRaidVictoryEvent，center 在手） | 玩家守城胜利 | 全殖民地大范围烟花 `burstAtFar`（512 格） | 服务端 |
| 袭击开始（可选） | `raid/RaidTriggerScanner.java`:70（发 ColonyRaidStartedEvent，center 在手） | 袭击触发 | 市政厅红色信号烟（`SMOKE` 上升）；原版已有袭击音效，可只做视觉 | 服务端 |
| 方块放置（建造/铺路过程，可选） | `engine/boundary/WandscapeBlockOps.java` `setBlock()`:59 | 每块方块落定 | ⚠️ 高风险：大建筑上千粒子。建议极淡 dust 或直接放弃——建成烟花已覆盖"结果" | 服务端 |

### 4.3 事件钩子全表（订阅处即粒子挂点）

`shared/event/` 的事件都带足够数据供订阅处播音效/粒子，按类别：

- **庆祝**：`ColonyCreatedEvent`(origin)、`ColonyLevelUpEvent`(colonyId→找市政厅)、`ColonyRaidVictoryEvent`(center)、`BuildingPlacedEvent`(→找 anchor)、`WonderEffectChangedEvent`(→找 anchor)
- **负面/恢复**：`BuildingShutdownEvent`、`BuildingRestartedEvent`（均→找 anchor）
- **游客流**：`TouristArrivedEvent`/`TouristDepartedEvent`（只有 id，无坐标 → 在 `TouristSpawnSystem` 发事件处撒，坐标在手）

> 多数事件只有 id 没有坐标。**坐标优先在"发事件的那一行"撒**（BuildCompleteListener/DemolishCompleteListener/ColonyRaidTracker 处 anchor/center 都在手），而不是在订阅处反向查——省一次 BuildingApi 查找，也避免订阅顺序问题。

**已明确排除（非玩家世界可见 / UI）**：
- 打开/关闭 GUI、面板（仓库/商店/酒馆/市政厅/任务编辑器等）
- 投影进入/退出、俯瞰进入/退出（放置/俯瞰模式覆盖层——反馈即模式本身；投影全息图已是世界内视觉）
- 每日结算 `DailySettlementEvent`、元素不足 `ResourceInsufficientEvent`、维护费预警 `MaintenanceForecastWarningEvent`（HUD/数值事件，无世界坐标）
- 自动任务轮询 `BuildingTaskSource.poll`、任务编辑器内部

## 5. 命名与目录约定

- **粒子 id**：kebab-case 动词短语，`<对象>_<动作>`，如 `building_complete`、`tourist_arrive`、`wonder_active`（仅当确需新 ParticleType 时）。
- **贴图**：通用光点一律复用 `minecraft:glow`（粒子类 `MagicCircleDotParticle` 已用）；不要为每种颜色建贴图。
- **染色**：统一走 `ParticleBurstPacket` + `MagicCircleDotParticle.spawn`，不逐类建 ParticleType。
- **原版粒子**：直接 `ParticleTypes.FIREWORK/SMOKE/PORTAL/HAPPY_VILLAGER/END_ROD/EXPLOSION_EMITTER` 等（`ParticleTypes` 常量已从源码核对）。

## 6. 实现步骤（怎么加）

1. **先修 3 处坏粒子**（基础设施）：`doWorkAnimation` / `WandscapeRitualOps` / `spawnCompletionParticles` 的服务端 `addParticle` → `sendParticles`。
2. **建 `engine/service/ParticleService.java`**（burstAt / burstAtFar / burstColored / burstAtThrottled）+ `Config.PARTICLE_LEVEL` 开关（`ModConfigSpec.ConfigValue<String>`，取值 `OFF/LOW/NORMAL/HIGH`，语法同现有 `ROAD_SURFACE_PALETTE`）。
3. **建 `shared/network/ParticleBurstPacket.java`**（仿 `MagicCircleCastPacket`：pos/RGB/count/size/lifetime，`Wandscape.java onRegisterPayloads` 里 `playToClient`），handleClient 调 `MagicCircleDotParticle.spawn`。
4. **P0 接线**：建成烟花（BuildCompleteListener 或 BuildingPlacedEvent 订阅处）→ `burstAtFar` 大范围。这是用户要的头号效果。
5. **P1 接线**：游客到达/离开、交互、守卫（可选）。
6. **P2 接线**：升级/胜利/奇观/关停重启（低频庆祝）。
7. **验证**：`./gradlew build`；`runClient` 实测 32 格/512 格范围、节流、开关是否生效（建成烟花离远点看是否仍可见）。
8. **commit + 版本号**：代码/资源进 jar → 递增 `gradle.properties` 的 `mod_version`（补丁号）。

## 7. 约定与陷阱

- **服务端广播用 `sendParticles`，客户端本地才用 `addParticle`/静态 spawn；永远不要服务端调 `level.addParticle`**（空操作，§2.4）。
- **大范围庆祝必须 `burstAtFar`（512 格）**：建成烟花/升级/胜利如果走默认 32 格，玩家离建筑远一点就看不到，违背"世界内可见"的目标。
- **防刷屏**：光束每 tick 命中、逐块放置、20tick 轮询等高频点必须走 `burstAtThrottled`，最小间隔 5–10 tick。`MagicBeamEntity.damageTargets()`（:199 每 tick 结算）不建议逐帧撒粒子；要做就在目标死亡时撒一次。
- **别叠堆**：守卫/施法已有法阵粒子，施法起手别再撒一大把，会糊成一片。加"杖尖爆闪"要克制（count ≤ 5）。
- **全局开关一个点**：所有粒子走 `ParticleService`，`PARTICLE_LEVEL=OFF` 一键全关；不要绕过门面自己 addParticle。
- **不进 SavedData/ECS**：粒子是纯瞬时表现，不持久化、不参与任务/结算。
- **与音效共用节奏**：`docs/sounds.md` 与本文是同一批挂点、同一优先级分组；加音效和加粒子的位置重叠时，两者在同一行各写一个调用即可，互不冲突。
