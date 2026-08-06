# magic/ — 魔法模块

`src/main/java/com/wsteam/wandscape/magic/`

## 职责

施法表现层：**魔法阵**（客户端粒子，JSON 数据契约由 Web 编辑器导出）+ **信标光束**（服务端实体，可造成伤害）。魔法阵动画在客户端渲染，光束延迟到 fireTick 才生成。

## MagicCastManager

- 静态 PENDING 列表 + ACTIVE_CASTERS 集合，按施法者 UUID 去重（同一施法者同时只允许一个未发射施法）。
- `schedule(level, casterUuid, source, target, color, delayTicks, lifeTicks, casterNpc, targetNpc)`：fireTick = gameTime + max(1, delayTicks)，lifeTicks 下限 1。
- `tick()`（由 Wandscape.onServerTick 驱动）：到点生成 MagicBeamEntity、setCaster/bindCaster/bindTarget、addFreshEntity、播 MAGIC_BEAM 音（0.6f/1.0f）。

## MagicCircleSpec（JSON 数据）

- 顶层：id、duration_ticks(默认120)、height(0.1)、elements[]。
- Element 枚举：RING/ARC/POLYGON/STAR/GLYPH；record 字段：type、axis[3]、radius、particle/particles、color(hex)、rotationOffsetDeg、rotateSpeed、start、anim、mode(beads/continuous)、density、trailTicks、yOffset、intervalTicks、arcStart/SweepDeg、sides、points、innerRatio、count、sprite、glyphScale/headScale/tailScale。
- 动画：Easing LINEAR/SMOOTHSTEP；关键帧 `[[t,v],...]`；localTime 门控元素激活。GLYPH 默认 mode=beads、trail=8、scale 0.3/head 1.35/tail 0.35。
- 完整 JSON 树见 [data/magic_circles.md](../data/magic_circles.md)。

## 客户端渲染

- `MagicCircleEmitter`：静态 ACTIVE map；`tick()` 由 WandscapeClient 驱动；`followBeam` 若存在 casterUuid 对应的光束则跟随其源点与朝向。`spawnElement`：localTime→采样 alpha(≤0.001 跳过)/scale/rotation；intervalTicks 脉冲门控；按 type 分发（GLYPH→spawnGlyph、POLYGON/STAR→spawnShape、RING/ARC→beads 或 continuous 带 ±0.2 抖动拖尾）。
- 原版风格 id→vanilla ParticleType 映射（颜色不生效）；glow/ember/未知→自定义染色点粒子。
- `MagicCircleDotParticle`：TextureSheetParticle，复用原版 glow 贴图 + color 染色；quadSize 为半宽；tick 线性缩放 start→end、fadeOut 线性淡出；渲染 PARTICLE_SHEET_TRANSLUCENT、getLightColor 全亮。

## MagicBeamEntity

- 纯视觉实体（**非子弹**，整段同时可见、无位移）；同步字段 target/color/lifetime/casterUuid。
- 常量：STAFF_CENTER_OFFSET=1.0、BEAM_RANGE=200、PEAK_T=0.86、MAX_BEAM_RADIUS 0.5/GLOW 0.7、MIN_WIDTH 0.02、WIDTH_POWER 1.4、BEAM_DAMAGE=2.0。
- 服务端 trackTarget：NPC 面向目标、源点跟持杖手前移 1.0；终点=沿方向首个方块（穿透生物只被方块挡）。damageTargets 每 tick 对束内 Enemy 造成 magic 伤害（伤害∝宽度因子），重置 invulnerableTime；NPC 施法用 indirectMagic(casterNpc, this) 让怪记仇反击，否则 magic()；无击退。getWidthFactor 宽度动画：慢变宽→PEAK_T 后快变窄。
- 渲染 `MagicBeamEntityRenderer`：复用原版 BeaconRenderer.renderBeaconBeam，旋转 +Y→beam 方向。

## MagicInteractHandler

@Subscribe PlayerInteractEvent.EntityInteract，仅服务端；shift+右键 WandscapeNpc → cancel 事件(SUCCESS)，调 `MagicCaster.castNpc(DEFAULT_CIRCLE)`，成功后 `npc.startManualCast(spec.durationTicks)`（举杖窗口）。

## MagicCaster

- DEFAULT_CIRCLE=arcane_hexagram、DEFAULT_COLOR=0xFFA8E0FF、BEAM_SPAWN_DELAY=20、BEAM_TAIL=20、CAST_TARGET_RANGE=32、CAST_DISTANCE=1.5。
- `cast`（玩家调试命令）向追踪块玩家发包；`castNpcAt`（守卫用）：目标身体中心、faceTarget、持杖手中段施法。
- `MagicCircleCastPacket`（S→C）：(effectId,pos,axis,circleId)，handler 调 MagicCircleEmitter.add。每次登记施法即发包（玩家命令/NPC 施法）。

## 与其他模块关系

- `WandItem.use` 触发 `MagicCaster.cast`。
- `GuardCombat` 用 `castNpcAt` 施法（守卫攻击表现）。
- `NpcSpellPowerHandler` 用 SPELL_POWER 放大 NPC 施法伤害。
