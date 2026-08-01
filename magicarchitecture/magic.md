# magic/ — 魔法阵（Magic Circle）粒子渲染

战斗系统的视觉层。数据契约（`MagicCircleSpec` JSON）由独立 Web 编辑器导出，MC 端用粒子渲染同一份 spec——两端都只"画这份几何数据"，互不搬渲染管线。**渲染方案：粒子（非 shader）**，保证任何光影包下正常显示。

JSON 格式见 [data/magic-circles.md](magic-circles.md)。

- **设计原则**（怎么写才好看，从 UsefulMagic 提炼）→ [magic-design-principles.md](magic-design-principles.md)
- **第三方参考样例**（UsefulMagic 的 Kotlin 魔法阵定义，GPL-3.0，本地只读、不纳入版本）→ `usefulmagic-examples/`（见其 README）

## 包结构

```
magic/
  ├── data/MagicCircleSpec.java        record 镜像 + fromJson（纯数据，无 MC 依赖）
  ├── internal/MagicCircleLoader.java  dataconfig 注册 magic_circles 类目 + get(id)/getAll()
  ├── client/MagicCircleEmitter.java   客户端静态持有器：Map<UUID, ActiveCircle>，
  │                                     注册 ClientTickEvent.Post，每 tick 采样曲线撒粒子
  ├── client/MagicCircleDotParticle.java   ghost-trail 粒子：借用 vanilla SpriteSet + 移植
  │                                      quadSize 公式（尺寸/贴图 fidelity 表见 magic-circles.md），ring/arc 用
  └── client/MagicCircleRuneParticle.java   符文 sprite 粒子，glyph 用
```

`MagicCircleSpec` 为 `sealed interface Element permits RingElement, ArcElement, GlyphElement` + 嵌套 `Anim`/`Keyframe` record。数据模型风格照抄 `building/data/BuildingConfig.java`。

## 数据流

```
data/wandscape/magic_circles/*.json    ← Web 编辑器导出
  → WandscapeDataLoader（dataconfig 框架，双端注册）
  → MagicCircleLoader.register("magic_circles", MagicCircleSpec::fromJson)
  → SimpleDataRegistry（客户端/服务端均可按 id 查 spec）
```

施放触发（服务端 → 客户端）：

```
调试命令 / 未来 ritual 钩子（服务端）
  → MagicCircleCastPacket(effectId=UUID, pos, circleId)   (shared/network)
  → PacketDistributor.sendToPlayersTrackingChunk
  → 客户端 payload handler → MagicCircleEmitter.add(level, pos, loader.get(circleId))
  → ClientTickEvent.Post:  t = (nowTick - startTick) / duration
  → 采样 anim 曲线（scale/alpha/rotation）→ 当前几何位置撒粒子
  → t ≥ 1 自动移除
```

## 注册点

| 注册点 | 位置 |
|--------|------|
| PARTICLE_TYPES（1~2 个 SimpleParticleType） | `Wandscape.java` |
| MagicCircleLoader 挂到 DATA_LOADER | `Wandscape.java` 构造器 |
| MagicCircleCastPacket 注册 playToClient | `Wandscape.onRegisterPayloads` |
| 粒子 Provider 注册 | `WandscapeClient.onRegisterParticleProviders` |

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

- **不做 shader 渲染**：粒子方案 + 自定义符文贴图足够"好看"，绕开光影兼容风险。
- **不做真实战斗/探索逻辑**：本模块只做视觉层 + 施放触发钩子；战斗数值/怪物 AI 是后续独立工作。
- **不做多人编辑器协作**：Web 编辑器是本地单用户工具。
- **glyph 真符文 sprite 选择**：v1 用放大点粒子代替，真符文贴图留后续（需 sprite 索引机制）。
