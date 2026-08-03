# 音效系统设计

本文梳理 Wandscape 的音效现状、NeoForge 1.21.1 的音效机制、建议的统一接入架构，以及各模块可加音效的具体挂点。目标：**一次建好统一机制，之后加音效 = 加资源 + 一个挂点一行调用**，后期换音效只改 sounds.json。

## 1. 现状

- 全模组唯一音效：`wand/item/WandItem.java:45` 法杖右键播原版 `SoundEvents.AMETHYST_BLOCK_CHIME`。
- **无**自定义 `SoundEvent` 注册、**无** `sounds.json`、**无**音频资源目录（`assets/wandscape/sounds/` 不存在）。
- 结论：音效是空白，可从零建立统一接入机制，无历史包袱。

## 2. NeoForge 1.21.1 音效机制（已从源码核实）

### 2.1 三层解耦

```
代码侧 SoundEvent(id)  →  sounds.json(逻辑id → 音频文件)  →  .ogg 音频资源
        └──────────── 换音效/加变体只动这里，不改代码 ────────────┘
```

这是"后期更换"的最大杠杆：**换音频 = 改 sounds.json 或替换 .ogg 文件**。

### 2.2 注册 SoundEvent

```java
// Wandscape.java（与现有 ITEMS/BLOCKS/PARTICLE_TYPES 并列）
public static final DeferredRegister<SoundEvent> SOUNDS =
        DeferredRegister.create(Registries.SOUND_EVENT, MODID);
public static final DeferredHolder<SoundEvent, SoundEvent> MAGIC_CAST =
        SOUNDS.register("magic_cast",
                () -> SoundEvent.createVariableRangeEvent(
                        ResourceLocation.fromNamespaceAndPath(MODID, "magic_cast")));
// 构造器里：SOUNDS.register(modEventBus);
```

- `createVariableRangeEvent(RL)`：默认 16 格衰减范围（源码 `DEFAULT_RANGE = 16.0F`）。
- `createFixedRangeEvent(RL, range)`：固定范围，用于需要更远传播的声音（如袭击警报）。
- 声音距离衰减逻辑（`SoundEvent.getRange`）：variable range 且 volume > 1 时范围 = `16 × volume`。

### 2.3 sounds.json

`src/main/resources/assets/wandscape/sounds.json`：

```json
{
  "magic_cast": {
    "subtitle": "wandscape.subtitle.magic_cast",
    "sounds": [ "wandscape:magic/cast" ]
  },
  "building_place": {
    "subtitle": "wandscape.subtitle.building_place",
    "sounds": [ "wandscape:block/place_stone", "wandscape:block/place_stone2" ]
  }
}
```

- `sounds` 数组支持多个音频文件随机变体。
- 音频文件放 `assets/wandscape/sounds/<子目录>/<文件名>.ogg`（推荐 16-bit 44.1kHz mono，`.ogg` 是原版唯一支持的格式）。

### 2.4 播放 API（已核实签名）

```java
// 服务端广播到附近玩家（null = 所有人；传 player = 除该玩家外）
level.playSound(null, x, y, z, sound, SoundSource.BLOCKS, volume, pitch);
// BlockPos 重载（自动 +0.5 中心）
level.playSound(null, pos, sound, SoundSource.BLOCKS, volume, pitch);
// 实体声音（NPC/游客；服务端播给附近玩家，客户端本地播）
entity.playSound(sound, volume, pitch);            // Entity.playSound(SoundEvent, float, float)
// 客户端 UI 音效（走 SoundSource.MASTER = 主音量通道，无空间衰减）
Minecraft.getInstance().getSoundManager()
        .play(SimpleSoundInstance.forUI(sound, pitch));   // forUI(SoundEvent, float, float)
```

- `SoundSource` 分类：`BLOCKS`（方块）、`PLAYERS`（玩家）、`NEUTRAL`（中立实体/NPC）、`HOSTILE`（敌对）、`AMBIENT`（环境）、`VOICE`（语音）、`MASTER`（UI 用）。
- 注意：本版本 **无** `SimpleSoundInstance.forUIClick`，GUI 点击用 `forUI(SoundEvents.UI_BUTTON_CLICK, pitch)`。

## 3. 统一 SoundService（建议架构）

**结论：需要一个"薄"的统一层，不做重型音频引擎。** 理由：声音是 cross-cutting（同 equipment 的定位），分散在 9+ 个包，不统一会出现音量/音源分类/节流各写各的；且后期换音效、调响度、改节流都要一个单点。

### 3.1 落位

| 职责 | 位置 | 说明 |
|------|------|------|
| SoundEvent 定义 | `engine/sound/WandscapeSounds.java` | 20 个自定义 SoundEvent；`Wandscape.java` 构造器注册 SOUNDS |
| 播放封装 + 节流 | `engine/service/SoundService.java` | 与 ColonyMetricsService/StatsService/AchievementService 等非 ECS 服务并列，纯 MC 侧 |
| 音频资源 + sounds.json | `assets/wandscape/sounds/` + `sounds.json` | 纯数据，不进 SavedData |

> 为什么不在 `core/`：`core/` 禁止 import MC 类，SoundEvent/Level 都是 MC 类型。声音是纯 MC 关注点，只能放 `engine/`、各模块 `internal/`、item/entity/network 等 MC 侧。

### 3.2 SoundService 接口草案

```java
// engine/service/SoundService.java —— 静态门面（参照现有非ECS服务模式）
public final class SoundService {
    private SoundService() {}

    /** 服务端在坐标处向附近玩家广播 */
    public static void playAt(ServerLevel level, double x, double y, double z,
                              DeferredHolder<SoundEvent, SoundEvent> sound,
                              SoundSource category, float volume, float pitch) { ... }

    /** 实体音效（NPC/游客），音量/音调围绕实体播放 */
    public static void playEntity(Entity entity,
                                  DeferredHolder<SoundEvent, SoundEvent> sound,
                                  float volume, float pitch) { ... }

    /** 客户端 UI 音效（只应在 CLIENT 侧调用） */
    @OnlyIn(Dist.CLIENT)
    public static void playUI(DeferredHolder<SoundEvent, SoundEvent> sound, float pitch) { ... }

    /** 带节流的播放：同一 sound 在 minIntervalTicks 内只播一次（防高频 tick 刷屏） */
    public static void playAtThrottled(ServerLevel level, double x, double y, double z,
                                       DeferredHolder<SoundEvent, SoundEvent> sound,
                                       SoundSource category, float volume, float pitch,
                                       int minIntervalTicks) { ... }
}
```

节流实现：`Map<ResourceLocation, Long> lastTick`（按音效 id 记服务器 tick），间隔不足则跳过。高频点（每 tick 结算、20tick 轮询）必须走节流版本。

## 4. 接入点清单（哪里可以加）

挂点按优先级分组；`文件:行号` 为当前快照，以方法名为准。所有点当前均无音效。

### P0 玩家直接操作（反馈最强烈，优先做）

| 挂点 | 文件:方法 | 触发 | 建议音效 |
|------|-----------|------|----------|
| 法杖施法起手 | `magic/internal/MagicCaster.java` `cast()`:57、`castNpcAt()`:111 | 玩家/守卫施法 | 幽森钟鸣 + 上升滑音，中低响度，`PLAYERS` |
| 法阵完成/光束发射 | `magic/internal/MagicCastManager.java` `tick()`:66-72 | 法阵动画结束生成光束 | 能量嗡鸣爆射瞬态，中高响度，`NEUTRAL` |
| 建筑蓝图放置确认 | `projection/network/ProjectionPlacePacket.java` `handleServer()`:82-93 | 玩家投影确认放置（任务已提交，NPC 后续施工） | 放置确认音，`BLOCKS` |
| 投影进入/退出 | `projection/client/ProjectionClientState.java` `enterProjection()`:56、`exitProjection()`:82 | 玩家进/出放置模式 | 进入低鸣启动 swoosh，`PLAYERS` |
| 俯瞰进入 | `overview/client/OverviewClientState.java` `enterOverview()`:41 | 玩家进入俯瞰 | 扬升 swoosh，`PLAYERS` |
| GUI 按钮点击 | 无需接线（`AbstractButton.onClick` 自带 `UI_BUTTON_CLICK`） | 所有按钮点击 | 原版已覆盖 |
| 仓库存取 | `warehouse/network/WarehouseActionPacket.java` `handleWithdraw()`:105、`handleDeposit()`:131 | 玩家存取元素 | 金属叮/铃，`PLAYERS` |

### P1 NPC / 自动行为（环境反馈）

| 挂点 | 文件:方法 | 触发 | 建议音效 |
|------|-----------|------|----------|
| 方块放置 | `engine/boundary/WandscapeBlockOps.java` `setBlock()`:59-68 | 同步/异步建造都汇此 | 用方块自身原版放置音 `state.getSoundType(level,pos,null).getPlaceSound()`，`BLOCKS` |
| NPC 施法放置 | `engine/boundary/AsyncTransformExecutor.java` `execute()` `thenRun`:99-115（`doWorkAnimation` :110 处） | NPC 放置每块方块 | 自定义施法音，`NEUTRAL` |
| 玩家手动发布任务 | 4 个 road/network packet 的 `publish()` 调用处（RoadPlace/FillBox/DestroyFill/SplineBuild） | 玩家在 GUI 手动创建任务 | 低响度纸卷微音，`PLAYERS`。注：`task/` 是纯 Java（零 MC 依赖），`PlayerManualSource.publish` 不能播音，故在调用方播 |
| 守卫开火 | `guard/GuardCombat.java` `engage()`:54-79 | 守卫施法攻击（40 tick 节流） | 能量脉冲，`NEUTRAL` |
| NPC 完工 | `npc/entity/WandscapeNpc.java` `doWorkAnimation()`:731-743 | NPC 执行完动作（已有粒子无声音） | 最自然的完工反馈点，轻快叮 |
| 建筑建成/拆除 | `building/internal/BuildCompleteListener.java` `onBuildComplete()`:120、`DemolishCompleteListener.java` `onDemolishComplete()`:41 | NPC 蓝图施工完成 | 建成：沉稳确认音；拆除：崩塌 |
| 游客到达/离开 | `tourist/internal/TouristApiImpl.java` `registerArrival()`/`registerDeparture()`（按 UUID 在主世界查实体播 `playEntity`） | 游客生成/离场 | 到达：轻快入城音；离开：渐弱 |
| 商店补货 | `building/internal/ShopStockManager.java` `restock()`:362（发 ShopRestockedEvent） | 每日补货 | 金币轻响 |

### P2 模拟经营 / 全局（低频，可做音乐性提示）

| 挂点 | 文件:方法 | 触发 | 建议音效 |
|------|-----------|------|----------|
| 建筑关停/重启 | `building/internal/BuildingApiImpl.java`:197/:225（发 BuildingShutdown/RestartedEvent） | 维护费不足/恢复 | 关停：低沉闷响；重启：上升启动音 |
| 殖民地升级 | `shared/event/ColonyLevelUpEvent.java` | 殖民地升级 | 庄严升级音 |
| ~~公路铺路~~（跳过） | 玩家提交已有 `TASK_PUBLISH`、NPC 放方块已有 `setBlock` 原版放置音，再加会重复 | — | 若需要道路编辑器进入音可复用 `road_place` |
| 奇观生效 | `building/internal/WonderEffectApplier.java` `applyEffects()`/`removeEffects()`（发 WonderEffectChangedEvent） | 奇观效果应用/移除 | 神圣和声 |

### 4.3 事件钩子全表（15 个，模块间通信天然是音效钩子）

`shared/event/` 全部 15 个事件均可在订阅处播音效，按类别：

- **正面/庆祝**：`BuildingPlacedEvent`、`ColonyLevelUpEvent`、`WonderEffectChangedEvent`、`ShopRestockedEvent`
- **负面**：`BuildingShutdownEvent`
- **信息/恢复**：`BuildingRestartedEvent`
- **游客流**：`TouristArrivedEvent`、`TouristDepartedEvent`（另 `ColonyEvaluationChangedEvent` 评价值变化可做轻微提示音）

**已明确排除**：
- 袭击开始/胜利（原版已有袭击音效）
- 每日结算（`DailySettlementEvent`）
- 元素不足 / 维护费预警（`ResourceInsufficientEvent` / `MaintenanceForecastWarningEvent`）
- 自动派发的采集/合成任务不播音效（`BuildingTaskSource.poll` → `TaskRequest`），只有玩家手动任务（`PlayerManualSource.publish`）播

> 注意：事件是"通知"语义（CLAUDE.md 陷阱 4），音效是纯瞬时副作用，适合直接订阅事件播放，无需依赖事件顺序。

## 5. 命名与目录约定

- **SoundEvent id**：kebab-case 动词短语，`<对象>_<动作>`，如 `building_place`、`magic_cast`、`ui_click`、`tourist_arrive`。
- **音频目录**：`assets/wandscape/sounds/{block,item,entity,npc,tourist,ui,ambient,magic,raid}/`。
- **subtitle 翻译键**：`wandscape.subtitle.<id>`，进 `assets/wandscape/lang/zh_cn.json`（+ `en_us.json`）。
- **响度**：音频资源本身留 headroom，代码里 volume 控制在 0.4–1.0；UI 用默认 0.25。

## 6. 实现步骤（怎么加）

1. **准备音频**：把 `.ogg` 放 `assets/wandscape/sounds/<分类>/<名>.ogg`，写 `sounds.json`。
2. **注册**：建 `engine/sound/WandscapeSounds.java`（`DeferredRegister<SoundEvent>` + 静态 holder），`Wandscape.java` 构造器 `WandscapeSounds.SOUNDS.register(modEventBus)`。
3. **封装**：建 `engine/service/SoundService.java`（playAt / playEntity / playUI / playAtThrottled）。
4. **接线**：在 4 清单的挂点加一行 `SoundService.playXxx(...)`。
5. **翻译**：`zh_cn.json` / `en_us.json` 加 subtitle 键。
6. **验证**：`./gradlew build`；`runClient` 实测响度、距离衰减、节流是否生效。
7. **文档同步**：已更新 `architecture/README.md` 包地图（engine/service + engine/sound）；注册/播放约定见本文档，无需另建 packages/sound.md。
8. **commit + 版本号**：音频资源/代码进 jar → 递增 `gradle.properties` 的 `mod_version`（补丁号）。

## 7. 约定与陷阱

- **防刷屏**：高频结算点（光束每 tick 命中、20tick 任务轮询、每日结算）必须走 `playAtThrottled`，建议最小间隔 5–10 tick。`MagicBeamEntity.damageTargets()`（:199-235 每 tick 结算）不建议逐帧播命中声。
- **距离**：默认 16 格；需要远距离（袭击警报）用 `createFixedRangeEvent`。
- **服务端 vs 客户端**：`Level.playSound` 服务端广播给附近玩家；UI 音效必须客户端（`playUI`）；实体音效用 `Entity.playSound`。不要在 `core/` 或纯逻辑层播声音。
- **音量归一**：`SoundEvent.getRange` 在 volume > 1 时把范围放大到 `16 × volume`，想让声音传更远可适度提高 volume，但别超过 2.0。
- **与"零自定义方块/BE"一致**：声音是纯瞬时事件，不持久化、不进 SavedData、不进 ECS。
- **不要重复**：接入前先查 `SoundService` 是否已有该 id；不要在同一 tick 对同一事件既播事件音又播挂点音。
