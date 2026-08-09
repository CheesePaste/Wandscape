# magic/ — 魔法阵（Magic Circle）粒子渲染

战斗系统的视觉层。数据契约（`MagicCircleSpec` JSON）由独立 Web 编辑器（[magic-circle-editor](https://github.com/CheesePaste/magic-circle-editor)）导出，MC 端用粒子渲染同一份 spec——两端都只"画这份几何数据"，互不搬渲染管线。**渲染方案：粒子（非 shader）**，保证任何光影包下正常显示。

JSON 格式见 [magic-circles.md](magic-circles.md)。

- **设计原则**（怎么写才好看，从 UsefulMagic 提炼）→ [magic-design-principles.md](magic-design-principles.md)
- **示例 spec**（按原则设计，可在编辑器导入查看）→ `example-specs/`（如 `arcane_hexagram.json` 大型六芒星召唤阵）
- **第三方参考样例**（UsefulMagic 的 Kotlin 魔法阵定义，GPL-3.0，本地只读、不纳入版本）→ `usefulmagic-examples/`（见其 README）

## 包结构

```
magic/
  ├── data/MagicCircleSpec.java        record 镜像 + fromJson（纯数据，无 MC 依赖；套编辑器 normalize 默认值）
  ├── internal/MagicCircleLoader.java  dataconfig 注册 magic_circles 类目 + getSpec(id)/getAll()
  ├── internal/MagicCastManager.java   服务端施法调度：按施法者 UUID 去重，到 fireTick 生成信标光束
  ├── internal/MagicCaster.java        施放入口：发 MagicCircleCastPacket + 登记光束（守卫/自防御 castNpcAt 共用）
  ├── entity/MagicBeamEntity.java      服务端显示实体：源点→目标（端点一次性定死，非子弹），
  │                                     目标/颜色同步；每 tick 对束内 Enemy 造成伤害；宽度动画 getWidthFactor
  ├── client/MagicCircleEmitter.java   客户端静态持有器：Map<UUID, ActiveCircle>，
  │                                     注册 ClientTickEvent.Post，每 tick 采样曲线撒粒子；followBeam 跟随光束
  ├── client/MagicCircleDotParticle.java   可染色点粒子（glow/ember + glyph 放大点，同一类），
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
守卫/自防御执行器（GuardCombat/SelfDefenseExecutor 经 CastBrain 选魔法）
  → MagicCaster.castNpcAt → castNpcBeam
      · MagicCircleCastPacket(effectId=UUID, pos, axis=施法朝向, circleId) → PacketDistributor
      · 客户端 payload handler → MagicCircleEmitter.add(level, pos, axis, loader.get(circleId))
      · MagicCastManager.schedule(level, casterUuid, source, target, color,
          delayTicks=BEAM_SPAWN_DELAY(20), lifeTicks=法阵时长+BEAM_TAIL(20), casterNpc, targetNpc)
          fireTick = gameTime + max(1, delayTicks)
  → MagicCastManager.tick（ServerTick，Wandscape.onServerTick）：
      到点生成 MagicBeamEntity（setCaster/bindCaster/bindTarget）+ addFreshEntity + MAGIC_BEAM 音
  → 客户端 MagicCircleEmitter.tick（ClientTickEvent.Post）：
      t = (nowTick - startTick) / duration → 采样 anim 曲线 → 撒粒子；t ≥ 1 自动移除
      followBeam：存在 casterUuid=effectId 的光束时，法阵跟随其源点与朝向
  → MagicBeamEntity.trackTarget（服务端每 tick）：源点跟持杖手前移 STAFF_CENTER_OFFSET=1.0，
      终点 = 沿目标方向首个方块（穿透生物只被方块挡，BEAM_RANGE=200）
  → 光束粗细随寿命动画 getWidthFactor：慢变宽 → PEAK_T=0.86 后快变窄
```

**光束造成伤害**：`MagicBeamEntity.damageTargets` 每 tick 对束内 Enemy 造成 magic 伤害（`BEAM_DAMAGE=2.0`，伤害 ∝ 宽度因子），重置 invulnerableTime，无击退；NPC 施法用 `indirectMagic(casterNpc, this)` 让怪记仇反击，否则 `magic()`。NPC 施法伤害另经 `NpcSpellPowerHandler`（guard 模块）按 SPELL_POWER 放大。

光束寿命与法阵时长对齐（`spec.durationTicks + 尾部`），同步到客户端；长度固定 200 格（穿透地形，壮观）。默认色浅蓝 `0xFFA8E0FF`。

`axis` 由施放方传入并**覆盖** spec 元素 axis——攻击阵的"法阵垂直于施法朝向"就靠它实现（地面阵不传时回落到 spec 元素 axis）。

**NPC 施法（守卫/自防御）**：`GuardCombat`/`SelfDefenseExecutor` 经 `CastBrain` 选魔法后调 `MagicCaster.castNpcAt`，以最近敌对生物为目标，NPC 面向它施放。**动态跟踪**：光束每 tick 更新源点（跟随 NPC 持杖手，沿目标方向前移 1.0）与 DATA_TARGET（跟随生物坐标），NPC 随之转向；客户端 `MagicCircleEmitter.followBeam` 按施法者 UUID 匹配光束实体，法阵跟随其源点/朝向。按施法者 UUID 去重。原 shift+右键手动施放 / 玩家法杖右键入口已移除（测试完成）。

## 注册点

| 注册点 | 位置 |
|--------|------|
| PARTICLE_TYPES（`magic_glow` SimpleParticleType） | `Wandscape.java` |
| MagicCircleLoader 挂到 DATA_LOADER | `Wandscape.java` 构造器 |
| MAGIC_BEAM 实体（MobCategory.MISC） | `Wandscape.java` ENTITIES |
| MagicCircleCastPacket 注册 playToClient | `Wandscape.onRegisterPayloads` |
| MagicCastManager.tick（ServerTick） | `Wandscape.onServerTick` |
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

## 现有视觉的迁移（传送已完成，仪式施法圈规划中）

> 传送已迁移为 spec 驱动；`WandscapeNpcRenderer.spawnRitualCircle`（仪式施法圈）仍硬编码 3 环 ENCHANT，待迁移。

| 现有视觉 | 位置 | 迁移目标 | 状态 |
|----------|------|---------|------|
| 传送"魔法阵"（随机 PORTAL 爆点，20 粒） | `engine/boundary/WandscapeRitualOps.java` `self_teleport` | `self_teleport` spec 地面法阵（脚底+目标双端），传送仍是 ritual 行为 | 已迁移（引导开始发双端 packet，引导结束传送+末影人式 PORTAL 爆点） |
| 仪式施法圈（3 环 ENCHANT，硬编码环数/半径） | `npc/client/WandscapeNpcRenderer.java` `spawnRitualCircle()` | 由 `circle_id` 查 spec 渲染，删掉硬编码环数/半径 | 规划中 |

- **绑定方式**：仪式/法术通过 `circle_id` 引用一张魔法阵；施法时走 `MagicCircleCastPacket` → `MagicCircleEmitter`。环数/半径/动画全部来自 JSON。
- **触发链路**：`WandscapeRitualOps.beginRitual` 在**引导开始**即发 `MagicCircleCastPacket`（脚底+目标点各一），法阵时长 = 引导时长；引导结束 `executeRitual` 执行传送并喷 PORTAL 爆点。
- **道路样条线不在此系统内**：独立子系统（物流/插值），仅与魔法阵共享粒子管线的能力，不与魔法阵耦合。

## 依赖

- MC: `SimpleJsonResourceReloadListener`（经 dataconfig）、`TextureSheetParticle`、`ParticleEngine`
- 内部: `dataconfig/`、`shared/network/`（packet）、`npc/`（守卫调用 castNpcAt）、`guard/`（NpcSpellPowerHandler 伤害倍率）

## 不做（本模块边界）

- **不做 shader 渲染**：粒子方案 + 自定义符文贴图足够"好看"，绕开光影兼容风险。信标光束直接用**原版** `BeaconRenderer.renderBeaconBeam`（原版 beam shader，逐顶点染色），光影下正常。
- **不做施法机制/冷却**：施法频率由使用方决定（NPC 经 `npc.tryCastSpell` 门控——每魔法独立 CD + 施法互斥锁 + 魔力消耗，见 npc 模块；光束 CD/蓝/锁在 `MagicCaster`）。本模块只做视觉层 + 施放触发钩子 + 光束伤害。
- **不做多人编辑器协作**：Web 编辑器是本地单用户工具。
- **glyph 真符文 sprite 选择**：v1 用放大点粒子代替（`MagicCircleDotParticle` 彗星头尾），真符文贴图留后续（需 sprite 索引机制）。
