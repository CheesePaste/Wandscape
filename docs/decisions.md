# 设计决策记录

本文件记录偏离直觉的设计选择及其原因，供后续开发快速理解「为什么这么做」。

## 2026-08：游客经济大改造（满意度→三条需求条 / interact_spots / 四类 category）

**需求**：把游客从「碰建筑进 CD 干晃悠」变成「真在城镇生活」：三条需求条无惩罚填条、画像驱动多样城镇、spot 占位做动作+排队、精力循环+relax、ATM 取现、停留上限防挂机。完整目标见 `architecture/plan/goal.md`。

**决策**：
- **满意度 → 三条需求条（Comfort/Magic/Wonder）**：删除单一 `satisfaction` 与 `typePreferences`（字段/NBT/接口/调用/配置全清）。填充无惩罚：`sat += round(值 × TOURIST_BAR_GAIN_COEFF)` 封顶 need；满条 = 三条 ratio 全 1，**满条夜晚离场才给经验**（防刷）。离场载荷 `registerDeparture(UUID, UUID, BarRatio)`，stats/HUD 走三条。
- **画像 + 等级缩放**：40% 均衡 / 20% 舒适 / 20% 魔法 / 20% 奇观；`totalNeed = BASE + (level-1)×PER_LEVEL` → 等级越高总需求越高、越难满足（自然难度曲线，不惩罚普通建筑）。
- **`interact_spots` 取代 `tourist_interact_aabb`**：每点带动作（`Activity` 子集 browse/eat/bathe/view/meditate/rest/withdraw），**spot 数量 = 同时交互人数上限**（全满排队，超 `TOURIST_QUEUE_WAIT_TOLERANCE_TICKS` 放弃）；交互时长由模式预设块 `interaction_duration_ticks` 决定（与 spot 无关）；0-spot 游客目标建筑不选（**无 spiral-scan 兜底**）。旧字段不保留 JSON 兼容解析。
- **交互位唯一真源 = world 里 `interact_spot_marker` 方块**：BE 不存 spot 列表；放置=标记、右键循环动作、潜行右键移除，action 存 blockstate（无 BE/NBT）。导出扫 boundary 内 marker → `interact_spots`，marker 格跳过 pattern（创作者自行留空该格）。
- **四类 category 保持独立（不合并）**：`shop`（卖物品）/`service`（产元素+耗精力，`max_occupancy>0`=旅店）/`relax`（回精力）/`atm`（取现 `min(withdrawAmount, travelFund)`）；统一成 `interact` 的 `interaction` 块 → **二阶段**（`architecture/plan/phase-2/`）。动作只决定游客活动状态/粒子，精力/经济效果由模式预设块决定。
- **目标选择 = Find-Best-Action，只看视野内**（`TOURIST_VISION_RADIUS` 且已加载）：`Σ(需求缺口×建筑值) + 精力紧急(relax) + 钱包紧急(atm) − 排队惩罚`；视野内无目标 → 闲逛；精力 0 → 只能去 relax、无则闲逛（**不离场**）。
- **停留上限 + `visitedBuildings` 不重置**：停留 2-4 天（`departureDeadline`），整个停留一栋建筑只逛一次，防挂机。
- **`Activity` 枚举放 `shared/data`**（building/data 要引用，避免跨模块直接引用）；`TouristState` 保持移动标签不扩展为状态机。
- **瞬时头顶条移除**：删 `SatisfactionBarRenderer`；气泡仍在（图标+文案），不画 before→after 进度条。

**为什么**：三条无惩罚 + 画像自组织 = 「多样城镇」由游客行为引导而非规则逼迫；spots/排队 = 多建同类型有实际收益（多交互位=排队短）；视野限制 = 省寻路开销且行为真实；满条才给经验 = 经验是里程碑不是流水。

**与方案文档的偏差**：
- `VisitMemory` 用**三维增量**（comfortDelta/magicDelta/wonderDelta）而非方案文档的单个 `barDelta` 聚合：面板行程逐维显示（`舒适+X 魔法+Y 奇观+Z`），`Emotion.fromDelta(三维之和)` 语义与 C4 的「三条 ratio 增量之和」一致。信息更丰富、贴近 goal 的三条表示，保留此实现。

## 2026-08：交互位朝向 facing + 预览假人 + 活动同步修复

**需求**（用户实测反馈）：交互位没有朝向，用餐等动作可能朝向不对；且希望能在交互位看到动作效果的循环预览。

**决策**：
- **`interact_spots` 增加 `facing`（水平朝向）**：游客在该位做动作时面朝的方向。缺省 `south`，Y 轴/非法值回退 `south`；建筑旋转时随 `BuildingRotation.rotateDirection` 一起旋转（用户要求「旋转后方向也正确旋转」）。`TouristMoveGoal` 活动期间持续 `setYRot/yBodyRot/yHeadRot` 面向 spot（含 look control 拉偏兜底）。
- **marker 交互改为「右键循环动作、潜行右键循环朝向、敲掉=移除」**（用户拍板，放弃原来的潜行右键移除）。放置时 facing 取玩家面朝方向作为起点。marker 改为**无碰撞 + 贴地薄板模型**（`getCollisionShape` 返回空），让预览假人可站在同一格、且不被整格方块挡住。
- **预览假人（始终常态）**：`MarkerPreviewManager`（服务器端单例）为每个 marker 维护一个 preview 模式 `TouristEntity`——站桩循环播放该 spot 动作（复用现有游客渲染：姿态/粒子/朝向/动作名 name tag）。生命周期靠 `BlockEvent.EntityPlaceEvent`（放置生成）+ marker `useWithoutItem` 后回调（改动作/朝向即时更新）+ `BlockEvent.BreakEvent`（敲掉移除）+ `ChunkEvent.Load`（palette `maybeHas` 高效发现，chunk 卸载即消失、重载重建）+ 周期 reconcile 兜底。preview 不参与生成/离开/孤儿清除、不持久化、免疫伤害、不可交互。**为何不用客户端渲染**：服务器实体复用全部现有渲染（姿态/粒子/气泡开关），且多方可见；客户端 ghost 需自建模型渲染管线。
- **活动同步修复**：原 `currentActivity` 是普通字段，**不同步到客户端** → 游客姿态/粒子其实渲染不出来（红线 #10「看到游客真的在泡澡/排队」隐患）。改为 `DATA_ACTIVITY` synched data（ordinal，-1=无），客户端渲染直接读实体同步值，预览假人与真实游客一并受益。

## 2026-08：扫描器装饰实体用「修剪 NBT + 独立朝向字段」而非结构化 JSON

**需求**：物品展示框/画是实体，扫描器（只遍历方块格子）扫不到，NPC 建造也只会放方块。端到端补上：扫描捕获 → 导出 JSON → 建造重建（含旋转）。

**决策**：
- JSON 用 `entities` **数组**：`{offset, type, facing, nbt}`。数组而非按 offset 作 key 的 map——同一格空气正反两面可挂两个展示框。`nbt` 是**修剪后实体 NBT**（base64，与 `block_nbt` 同风格）：去掉 `UUID/Pos/Motion`，位置重定基为相对偏移（文件与绝对坐标解耦），`id` 显式写入。
- `facing` 独立成 Direction 字符串字段，**不塞进 base64**——建筑旋转时只转结构化字段（offset + facing），NBT 保持不透明，免去解码/重编码。
- 重建走 `EntityType.create(tag, level)` 通用往返，按类型写朝向字节（item_frame 用 `Facing`+3D 值，painting 用 `facing`+2D 值——两个原版字段名大小写不同，已核源码）。生成前先清除同格悬挂实体，避免新旧展示框共存互相 `survives()` 踢掉。
- 新原子操作 `SpawnDecorationOp` + DSL 步骤 `spawn_entity` + `EntityOps.spawnDecoration` 边界方法；执行器走 sync——建造时序已保证实体在方块后生成（异步 TransformOp 逐个完成推进 stepIndex 后才执行 `for_each $entities`）。

**为什么**：结构化 JSON 需要按类型解析实体 NBT（枚举性）；base64 NBT 往返是通用机制，与既有 `block_nbt` 一致，任何悬挂实体加白名单即可支持。朝向是唯一需要旋转的字段，独立出来把旋转成本压到最低。

**v1 边界**：白名单 = item_frame/glow_item_frame/painting（都是 BlockAttachedEntity）。盔甲架/display 实体位置重定基已通用，但朝向内嵌 NBT 无法随建筑旋转，留待后续。实体装饰不参与材料成本（`computeMaterialData` 只算方块）。修复路径（`BuildingBreakHandler`）不带实体。

## 2026-08：敌对测试法师（EvilMage）复用 NPC 施法管线

**需求**：实战测试法术系统强度——一个与殖民地法师外观/属性/施法完全一致的敌对生物，索敌生存玩家，创造模式右键可编辑施法表/策略。

**决策**：`EvilMage extends WandscapeNpc implements Enemy`，而非独立实现。

**为什么**：施法管线（`MagicCaster.castNpcAt`、`MagicCastManager`、`MagicBeamEntity`、`GuardCombat`、`CastBrain`、`MagicState` 门控、SPELL_POWER 倍率）全部绑定具体类型 `WandscapeNpc`。子类化即零成本复用全部施法/NBT/渲染/编辑能力；独立类需要把整个管线重构为接口，风险大且偏离「测试工具」定位。

**与殖民地解耦的三条钩子**（行为保持的扩展点）：
- `isColonyNpc()`（默认 true）：false 时不注册进 ECS / 不入任务调度，`NpcDeathHandler` 跳过死亡记录（不可复活），`onAddedToLevel` 仍做外观/魔法表/法杖初始化。
- `canBeamHurt(LivingEntity)`（默认 `instanceof Enemy`）：决定光束能伤害哪些目标。**普通 NPC 的光束永不伤玩家**；`EvilMage` 覆盖为「Enemy 或 生存玩家」。光束伤害（`MagicBeamEntity`）、SPELL_POWER 倍率（`NpcSpellPowerHandler`）、战斗快照敌数（`GuardCombat.countEnemies`）三处统一走此钩子，边界唯一。
- `tickCastingState()`（protected，默认 ECS 驱动）：`EvilMage` 覆盖为空，施法姿态改由 `EvilMageCastGoal` 驱动（不加入 ECS 故无 TaskExecutor）。

**其它**：
- `HostileTargetingHandler` 的村民级索敌谓词排除 `Enemy`——僵尸/灾厄不会追杀同为敌对的邪恶法师。
- `SpellcastingApiImpl.resolve` 桥查失败回退按 UUID 扫世界（界面编辑/显示的低频路径），使非 ECS 实体也能读写施法策略。
- 装备/护甲属性依赖 ECS `EquipmentComponent`，邪恶法师不进 ECS → 法杖换色生效（`getMainHandItem` 取色），属性加成不生效，恒为默认属性。

## 2026-08：光束连发无停顿 → 每魔法 CD 改为锁结束后起算

**需求**：实测邪恶法师几乎 0 间隔连发光束——`base_cooldown: 40` 明明设了却没停顿。

**根因**：`MagicState` 的每魔法 CD 与施法互斥锁**同时从施法开始倒计时**。光束锁 = 20(法阵延迟)+200(法阵时长)+20(拖尾) = 240 tick，锁 240 > CD 40，`canCast` 要求锁和 CD 都为 0 → 下一发由锁决定，恰在上一发光束消失时（240 tick）就绪，光束无缝衔接、CD 从未产生停顿。

**决策**：
- `MagicState.tickRegen`：锁占用期间每魔法 CD **冻结**，锁释放后才倒计时——CD 表示「施法结束后的恢复间隔」，施法时间不计入。总间隔 = 锁时长 + CD。
- beam CD 40 → **400**：光束（240 tick）结束后再停 400 tick，总间隔 640 tick。`beam.json base_cooldown` 与 `MagicCaster.BEAM_BASE_CD` 同步。

**为什么**：设计意图（「施法时间不参与 CD」）本应让 CD 在施法后追加一段间隔，但并发倒计时把它吸收成 0。冻结语义对传送（CD 300）同样成立且更合理——CD 不再被引导锁盖掉。

**影响**：所有走 `tryCastSpell` 的魔法 CD 语义统一改为「锁释放后起算」；`MagicStateTest.castingLockBlocksAllMagics` 随之更新（锁期间 CD 冻结断言）。

## 2026-08：光束伤害类型与命中节流

**需求**：实战测试发现原版 `magic`/`indirect_magic` 伤害类型都在 `damage_type/bypasses_armor` tag 里——护甲不减伤、耐久不掉，邪恶法师对穿甲玩家是真伤，太强。

**决策**：
- 新增自定义伤害类型 `data/wandscape/damage_type/beam.json`（`message_id: "magic"` → 死亡消息复用「被魔法杀死」，不在 bypasses_armor → 护甲减伤 + 耐久递减）。
- 光束保持**每 tick 结算**（`invulnerableTime = 0` 重置），帧伤节奏经实测确认保留（屏幕受击抖动为已知代价）；曾试过靠原版 20 tick 无敌帧节流到 1 次/秒（单次峰值 10=0.5×20，DPS 不变），实测后回退。
