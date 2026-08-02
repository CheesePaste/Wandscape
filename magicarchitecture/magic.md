# magic/ — 魔法阵（Magic Circle）粒子渲染

战斗系统的视觉层。数据契约（`MagicCircleSpec` JSON）由独立 Web 编辑器导出，MC 端用粒子渲染同一份 spec——两端都只"画这份几何数据"，互不搬渲染管线。**渲染方案：粒子（非 shader）**，保证任何光影包下正常显示。

JSON 格式见 [data/magic-circles.md](magic-circles.md)。

- **设计原则**（怎么写才好看，从 UsefulMagic 提炼）→ [magic-design-principles.md](magic-design-principles.md)
- **示例 spec**（按原则设计，可在编辑器导入查看）→ `example-specs/`（如 `arcane_hexagram.json` 大型六芒星召唤阵）
- **第三方参考样例**（UsefulMagic 的 Kotlin 魔法阵定义，GPL-3.0，本地只读、不纳入版本）→ `usefulmagic-examples/`（见其 README）

## 包结构

```
magic/
  ├── data/MagicCircleSpec.java        record 镜像 + fromJson（纯数据，无 MC 依赖）
  ├── internal/MagicCircleLoader.java  dataconfig 注册 magic_circles 类目 + get(id)/getAll()
  ├── internal/MagicCastManager.java   服务端施法调度：法阵动画结束后生成信标光束（按施法者去重）
  ├── internal/MagicCaster.java        施放入口：发 MagicCircleCastPacket + 登记光束（玩家命令 / NPC 共用）
  ├── internal/MagicInteractHandler.java  shift+右键 NPC 触发施法（服务端拦截交互，防止打开信息界面）
  ├── entity/MagicBeamEntity.java      服务端显示实体：源点→目标（端点一次性定死，非子弹），
  │                                     目标/颜色同步，短命自毁；宽度动画 getWidthFactor
  ├── client/MagicCircleEmitter.java   客户端静态持有器：Map<UUID, ActiveCircle>，
  │                                     注册 ClientTickEvent.Post，每 tick 采样曲线撒粒子
  ├── client/MagicCircleDotParticle.java   可染色点粒子（glow/ember + glyph 放大点，v1 同一类），
  │                                      复用 minecraft:glow 贴图 + 元素 color 染色
  └── client/MagicBeamEntityRenderer.java  原版 BeaconRenderer.renderBeaconBeam 旋转朝目标 + 染色，
                                      beamRadius/glowRadius 随 getWidthFactor 动画
      shared/network/MagicCircleCastPacket.java  服务端→客户端：effectId/pos/axis/circleId
```

`MagicCircleSpec` 为单 record + `Element`（`ElementType` 判别：ring/arc/polygon/star/glyph）+ 嵌套 `Anim`/`Keyframe` record（编辑器就是单一 union，比 sealed 层级更贴合 fromJson/发射器）。数据模型风格照抄 `building/data/BuildingConfig.java`。

## 数据流

```
data/wandscape/magic_circles/*.json    ← Web 编辑器导出
  → WandscapeDataLoader（dataconfig 框架，双端注册）
  → MagicCircleLoader.register("magic_circles", MagicCircleSpec::fromJson)
  → SimpleDataRegistry（客户端/服务端均可按 id 查 spec）
```

施放触发（服务端 → 客户端）：

```
法杖右键 / 调试命令 / shift+右键 NPC（服务端）
  → MagicCaster.cast / castNpc：MagicCircleCastPacket(effectId=UUID, pos, axis=施法朝向, circleId)
      + MagicCastManager.schedule(法阵出现后约 10 tick 生成光束，寿命=法阵时长+尾部)
  → PacketDistributor.sendToPlayersTrackingChunk / sendToPlayersTrackingEntity
  → 客户端 payload handler → MagicCircleEmitter.add(level, pos, axis, loader.get(circleId))
  → ClientTickEvent.Post:  t = (nowTick - startTick) / duration
  → 采样 anim 曲线（scale/alpha/rotation）→ 当前几何位置撒粒子
  → t ≥ 1 自动移除
  → 服务端 MagicCastManager.tick（ServerTick）：到期生成 MagicBeamEntity（源点→目标，颜色，寿命）
  → 客户端 MagicBeamEntityRenderer：原版 BeaconRenderer.renderBeaconBeam 旋转朝目标渲染
  → 光束粗细随寿命动画：从特别细慢慢变宽（0→≈0.86）到法阵结束，再快速变细（≈0.86→1）消失
```

光束寿命与法阵时长对齐（`spec.durationTicks + 尾部`），同步到客户端；长度固定 200 格（穿透地形，壮观）。默认色浅蓝 `0xFFA8E0FF`。

`axis` 由施放方传入并**覆盖** spec 元素 axis——攻击阵的"法阵垂直于施法朝向"就靠它实现（地面阵不传时回落到 spec 元素 axis）。

**shift+右键 NPC**：`MagicInteractHandler` 服务端拦截 `PlayerInteractEvent.EntityInteract`（不打开信息界面），NPC 沿其当前朝向（不改朝向）施放，`startManualCast(duration)` 窗口内 `isCasting=true` 举起法杖。NPC 与玩家共用同一去重（按 UUID）。

## 注册点

| 注册点 | 位置 |
|--------|------|
| PARTICLE_TYPES（`magic_glow` SimpleParticleType） | `Wandscape.java` |
| MagicCircleLoader 挂到 DATA_LOADER | `Wandscape.java` 构造器 |
| MAGIC_BEAM 实体（MobCategory.MISC，noSave） | `Wandscape.java` ENTITIES |
| MagicCircleCastPacket 注册 playToClient | `Wandscape.onRegisterPayloads` |
| MagicCastManager.tick（ServerTick） | `Wandscape.onServerTick` |
| `/wandscape magic` 调试命令 | `Wandscape.onRegisterCommands` → `command/MagicCommand` |
| 粒子 Provider / 光束渲染器 / emitter tick | `WandscapeClient` |

## 复用点

| 复用点 | 文件 |
|--------|------|
| 现成魔法阵循环（3 环 ENCHANT 粒子） | `npc/client/WandscapeNpcRenderer.java` `spawnRitualCircle()` |
| 自定义粒子模式（SpriteSet + static spawn + Provider） | `npc/client/CastBoltParticle.java` |
| loader 注册模式（静态 fromJson 工厂） | `element/internal/ElementMappingLoader.java` |
| JSON 加载框架 | `dataconfig/internal/WandscapeDataLoader.java` |
| record 数据模型风格 | `building/data/BuildingConfig.java` |
| packet record + StreamCodec 模式 | `shared/network/BuildingAreaSyncPacket.java` |
| 客户端每 tick 钩子 | `WandscapeClient.onClientTick` |
| 调试命令 | `command/` 包 |

## 现有视觉的迁移（衔接点）

系统上线后，现有**硬编码**的魔法视觉效果应收编为 spec 驱动——MC 端不再散落魔法阵代码：

| 现有视觉 | 位置 | 迁移目标 |
|----------|------|---------|
| 传送"魔法阵"（随机 PORTAL 爆点，20 粒） | `engine/boundary/WandscapeRitualOps.java` `self_teleport` | 换成 spec 圆（如 `ritual_teleport`：地面环 + 竖直传送环），传送仍是 ritual 行为，视觉改走本系统 |
| 仪式施法圈（3 环 ENCHANT，硬编码环数/半径） | `npc/client/WandscapeNpcRenderer.java` `spawnRitualCircle()` | 由 `circle_id` 查 spec 渲染，删掉硬编码环数/半径 |

- **绑定方式**：仪式/法术通过 `circle_id` 引用一张魔法阵；施法时走 `MagicCircleCastPacket` → `MagicCircleEmitter`。环数/半径/动画全部来自 JSON。
- **触发链路已为此预留**：数据流中的"未来 ritual 钩子"就是 `executeRitual` 完成后发 `MagicCircleCastPacket`。
- **道路样条线不在此系统内**：独立子系统（物流/插值），仅与魔法阵共享粒子管线的能力，不与魔法阵耦合。

## 依赖

- MC: `SimpleJsonResourceReloadListener`（经 dataconfig）、`TextureSheetParticle`、`ParticleEngine`
- 内部: `dataconfig/`、`shared/network/`（packet）

## 不做（本模块边界）

- **不做 shader 渲染**：粒子方案 + 自定义符文贴图足够"好看"，绕开光影兼容风险。信标光束直接用**原版** `BeaconRenderer.renderBeaconBeam`（原版 beam shader，逐顶点染色），光影下正常。
- **不做真实战斗/探索逻辑**：本模块只做视觉层 + 施放触发钩子；光束目前**不造成伤害**，伤害/战斗数值是守卫系统（`docs/guard/`）阶段 1+ 的独立工作。
- **不做多人编辑器协作**：Web 编辑器是本地单用户工具。
- **glyph 真符文 sprite 选择**：v1 用放大点粒子代替（`MagicCircleDotParticle` 彗星头尾），真符文贴图留后续（需 sprite 索引机制）。
