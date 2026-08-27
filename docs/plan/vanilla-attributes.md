# 全量 vanilla Attribute 迁移方案

> 状态：规划中（2026-08-27）
> 目标：把 NPC 属性模型从 ECS `EquipmentComponent`（core 枚举）迁移到 vanilla `Attribute`（注册进实体属性表），对齐铁魔法的 vanilla 属性样式。

## 一、背景与动机

- **现状**：NPC 战斗属性（`MAX_HEALTH`/`MOVEMENT_SPEED`/`ARMOR`/`ARMOR_TOUGHNESS`/`KNOCKBACK_RESISTANCE`）已是 vanilla 属性驱动（vanilla 槽重构后原版每 tick 装备结算直接喂）；但模组专属属性（`SPELL_POWER`/`WORK_SPEED`/`SPELL_SPEED`/`MAX_MANA`）仍走 ECS `EquipmentComponent`（core 枚举 + 手工桥）。
- **动机**（用户）：模组兼容 / 附属模组开发 / 整合包调参。铁魔法的 vanilla 属性样式（注册进实体属性表 → 原版装备结算自动喂 → 自身管线消费）是行业标准：`/attribute` 指令、datapack、其它模组都能读写 NPC 属性，装备修饰符全走 vanilla 一条链路。
- **结论**：做全量迁移。`AttributeType`（core 枚举）保留为稳定 ID，engine 层映射到 vanilla `Attribute`，core 维持零 MC。

## 二、二次加成核对（用户要求）

### 结论：SPELL_POWER 双重叠加（装备部分），冷却/吟唱缩减不双重

**SPELL_POWER —— 双重（已确认，vanilla 槽重构引入的回归）**

伤害链路两端各乘一次装备的 SPELL_POWER：

1. **Wandscape 侧**：`NpcSpellPowerHandler.onLivingDamage`（`guard/NpcSpellPowerHandler.java:66`）按 `npc.getEffectiveAttribute(SPELL_POWER)` 乘算。该值是 ECS core SPELL_POWER，含 `IronSpellsAttributes.modifiersFor` 桥进来的装备 SPELL_POWER 加成。
2. **铁魔法侧**：`AbstractSpell.getEntityPowerMultiplier`（铁魔法源码 `api/spells/AbstractSpell.java:239-241`）＝ `base × getAttributeValue(ironspellbooks:spell_power) × 学派强度`。`ironspellbooks:spell_power` 被铁魔法的 `EntityAttributeModificationEvent` 自动注册进 NPC 属性表，盔甲进 vanilla 槽后原版每 tick 把装备修饰符喂进去。

同一件装备的 +5% SPELL_POWER，被乘两次 → 1.05 × 1.05 ≈ 1.1025。

> 根因：vanilla 槽重构前盔甲在自定义 `armorInventory`，原版喂不到 `ironspellbooks:spell_power`（铁魔法内部读到 base 1.0），只有桥乘一次；重构后原版开始喂，双重形成。**当前已提交状态就存在此回归。**

**COOLDOWN_REDUCTION / CAST_TIME_REDUCTION —— 不双重（已确认）**

NPC 施铁魔法路径（`compat/ironspellbooks/IronSpellsCaster.java`）用 **raw** `spell.getSpellCooldown()` / `spell.getCastTime()`，不走铁魔法自己的 `applyCooldownReduction` / `getEffectiveCastTime`。缩减只经桥进 core `SPELL_SPEED`，由 `MagicState`（冷却 ÷ SPELL_SPEED）与 `IronSpellsCaster`（吟唱锁 ÷ SPELL_SPEED）各生效一次。vanilla 喂进的 `ironspellbooks:cooldown_reduction`/`cast_time_reduction` 未被消费，无叠加。

**其余属性 —— 单消费点，不双重**：`MAX_MANA`（桥 → 魔力池；vanilla 喂的 `ironspellbooks:max_mana` 对 mob 无消费）、`MANA_REGEN`（待桥 → 回蓝公式）、各学派 `*_spell_power`/`spell_resist`/`summon_damage`（不桥，铁魔法自身管线消费 vanilla 喂值，单次）。

### 修法（并入迁移）

- **从 `IronSpellsAttributes` 移除 `SPELL_POWER` 映射**。装备 SPELL_POWER 改由铁魔法自身管线消费（vanilla 喂 `ironspellbooks:spell_power`）——只对铁魔法生效，符合"铁魔法下才生效"语义。
- **行为变化（需记录）**：铁魔法装备的 SPELL_POWER 不再加成原生 Wandscape 法术（2026-08-26"装备加成全伤"→ 改为"铁魔法装备只加铁魔法"）。Wandscape 自身 SPELL_POWER（法杖/训练/天生）仍经 `NpcSpellPowerHandler` 乘所有伤害（原生 + 铁魔法）。
- 冷却/吟唱/回蓝/魔力桥保留（它们不双重）。

## 三、目标架构

**注册 6 个 Wandscape vanilla Attribute**（engine 层 `WandscapeAttributes` 注册，core 不碰 MC）：

| AttributeType（core ID） | vanilla Attribute | 默认值 | 隐藏 |
|---|---|---|---|
| `SPELL_POWER` | `wandscape:spell_power` | 1.0 | 否 |
| `WORK_SPEED` | `wandscape:work_speed` | 1.0 | 否 |
| `SPELL_SPEED` | `wandscape:spell_speed` | 1.0 | 否 |
| `MAX_MANA` | `wandscape:max_mana` | 200.0 | 否 |
| `HEALTH_REGEN` | `wandscape:health_regen` | 1.0 | 是 |
| `MANA_REGEN` | `wandscape:mana_regen` | 1.0 | 是 |

- 生命/移速/护甲/韧性/击退：**复用 vanilla** `MAX_HEALTH`/`MOVEMENT_SPEED`/`ARMOR`/`ARMOR_TOUGHNESS`/`KNOCKBACK_RESISTANCE`（`Mob.createMobAttributes()` 自带），不注册新属性。`MAX_HP`/`MOVE_SPEED`/`ARMOR_VALUE` 这三个 core ID 映射到 vanilla 现有属性。
- **属性表 = 唯一事实来源**：base（招募/训练/复活 `setBaseValue`）+ 装备 transient（原版喂槽内 + 桥喂法杖/铁魔法）。vanilla 自动持久化（`Attributes` NBT tag），删自定义 NBT 字段。
- **`AttributeType` 保留为稳定 core ID**；engine 层 `WandscapeAttributes.toVanilla(AttributeType) → Holder<Attribute>` 映射。core 零 MC 不变。
- **移除 ECS `EquipmentComponent`**（`core/component/EquipmentComponent.java`）与 `CoreBootstrap.createNpc` 的属性装备逻辑（`EntityComponentBridge.NPC_COMPONENTS` 同步去掉）。

### 隐藏回蓝/回血属性（HEALTH_REGEN / MANA_REGEN）

两个隐藏属性专为兼容外部模组的魔力/生命回复加成而设（铁魔法 `mana_regen`、以及其它模组的回蓝/回血提升）。行为约束：

- **默认 1.0**（`RangedAttribute` 默认值 1.0，范围 [0, 大值]；无招募掷点、无 NpcAttributes 字段）。
- **不显示在任何面板**：不进 `MageHutAttributes.ORDER`（法师小屋属性面板不渲染）、不进 `NpcDataPacket`/`NpcScreen`、`TaskPanelSyncTracker`、`MageHutServerHandler` 的显示与训练数据。
- **不随升级改变**：不在 `MageHutAttributes.SPECS`（无 per-level 加成）——升级只动可训练属性，这两个 base 恒为默认值。
- **不能训练**：不在 `MageHutAttributes.SPECS`/`TRAIN_ELEMENTS`——训练 UI 不渲染这两项、训练请求不落到它们、`MageHutServerHandler`/`ReviveHandler` 的 `values()` 遍历不覆盖它们（全改走显式 `ORDER`）。
- **base 恒为 1.0，只有装备/外部修饰符改它**：原生 NPC 行为零变化（1.0 → 回蓝/回血速率不变）；铁魔法装备或其它模组的加成经修饰符叠加。

消费点：

- `MANA_REGEN` → `WandscapeNpc.tick()` 回蓝：`maxMana × Config.NPC_MANA_REGEN_FRACTION × getAttributeValue(wandscape:mana_regen)`（默认 1.0 → 现配置行为不变）。
- `HEALTH_REGEN` → `tickHealthRegen()` 脱战回血：`heal(getAttributeValue(wandscape:health_regen))`（默认 1.0 → 每间隔回 1 HP 不变）。

兼容落点：

- 铁魔法 `mana_regen` → `IronSpellsAttributes` + `syncIronArmorAttributes` 桥写 `wandscape:mana_regen`（transient）。
- **其它模组的回蓝/回血提升** → 因为这两个是**公开注册的 vanilla 属性**，附属模组/整合包可直接 `addTransientModifier`/datapack `/attribute` 调整，或把其属性桥到 `wandscape:mana_regen`/`wandscape:health_regen`——这是"便于兼容其它模组"的关键：属性公开可写，而非锁死在 ECS。
- 铁魔法**没有** health_regen 属性；`HEALTH_REGEN` 主要给其它模组/未来回血机制用。

## 四、分阶段实施

### Phase 1：注册 + 双写（无行为变化，可提交）

1. 新建 `WandscapeAttributes`（engine/npc 层）：`DeferredRegister<Attribute>` 注册 6 属性（`RangedAttribute`，范围对齐现有模型：spell_power/work_speed/spell_speed/regen [0, 大值]、max_mana [0, 1e6]）；提供 `AttributeType → Holder<Attribute>` 映射。
2. `WandscapeNpc.createAttributes()` 追加 `.add(WandscapeAttributes.SPELL_POWER, 1.0)` 等 6 项。
3. `applyEffectiveAttributes` 扩展：除 MAX_HEALTH/MOVEMENT_SPEED/ARMOR 外，把 ECS 计算出的 SPELL_POWER/WORK_SPEED/SPELL_SPEED/MAX_MANA/HEALTH_REGEN/MANA_REGEN 推成对应 vanilla attr base（带脏值防重复）。
4. 效果：`/attribute` 可见、datapack 可读；双写期 ECS 仍权威，行为零变化。

### Phase 2：装备走 vanilla（双写 → vanilla 喂装备）

1. **法杖**：`WandPresetLoader`/法杖合成时把预设 `attributes[]` 写进法杖物品的 `ItemAttributeModifiers`（`DataComponents.ATTRIBUTE_MODIFIERS`）——法杖在 MAIN_HAND（vanilla 槽），原版 `detectEquipmentUpdates` 自动喂，`syncWandAttributes` 删除。
2. **铁魔法桥**：`syncIronArmorAttributes` 改为对 4 个 vanilla 槽，把 `MAX_MANA`/`SPELL_SPEED`（冷却/吟唱缩减）/`MANA_REGEN` 桥写为 vanilla transient（先 remove 旧 modifier 再 add，防叠）；**删 SPELL_POWER 映射**（见第二节修法）。
3. `getEffectiveAttribute(AttributeType)` 改读 vanilla `getAttributeValue(WandscapeAttributes.toVanilla(type))`；ECS 兜底分支移除。

### Phase 3：去 ECS

1. 删 `EquipmentComponent`、`CoreBootstrap.createNpc` 的属性装备逻辑、`EntityComponentBridge.NPC_COMPONENTS` 里的 `EquipmentComponent.class`。
2. `applyEffectiveAttributes` 删除（属性值全在 vanilla）。
3. `seedBaseValues`（招募/训练/复活）→ `getAttribute(attr).setBaseValue(...)`（engine 映射，写 vanilla base）。
4. 消费点全部改读 vanilla（见第五节清单）。

### Phase 4：收 NBT 字段 + 存档迁移

1. 删 `WandscapeNpc` 自定义字段 `maxHp/moveSpeed/spellPower/workSpeed/spellSpeed/armorValue/maxMana` 与其 NBT 读写；改用 vanilla `Attributes` tag 持久化（`LivingEntity` 自带）。
2. **旧存档迁移**：`readAdditionalSaveData` 检测 vanilla `Attributes` 缺失/为空时，从旧自定义字段播种 vanilla base（同盔甲迁移模式）。
3. 调用点（招募/复活/死亡记录/命令）的字段读写改为经 `WandscapeNpc` 封装的 getter/setter（内部 `getAttribute(...).setBaseValue`）。

### Phase 5：测试 + 清理

1. 改 `EquipmentComponentTest`（组件删除 → 删除或改测 vanilla 逻辑，后者需 MC 运行时 → 集成测试）。
2. 改 `MageHutAttributesTest`：`AttributeType.values().length` 断言（现 7，将含隐藏 → 断言 `ORDER.size()==7` + 隐藏不在 ORDER）；`values()` 遍历全改 `ORDER`（防对隐藏属性 `SPECS.get` NPE）。
3. `MageHutServerHandler`/`MageHutResident`/`ReviveHandler` 的 `values()` 数组/遍历收尾（见第六节）。
4. 文档同步：`docs/modules/npc.md`、`architecture/packages/npc.md`/`equipment.md`/`compat.md`、`docs/decisions.md`。

## 五、消费点清单（全量）

读/写 core 属性或字段，迁移为读/写 vanilla attr 的文件：

- `WandscapeNpc`：`getEffectiveAttribute`（读 vanilla）、`applyEffectiveAttributes`（删）、`syncWandAttributes`（删，改法杖 ItemAttributeModifiers）、`syncIronArmorAttributes`（改桥写 vanilla transient）、`tickHealthRegen`（读 `health_regen`）、`tick` 回蓝（乘 `mana_regen`）、7 个属性字段 + NBT。
- `npc/internal/EntityComponentBridge`：`CoreBootstrap.createNpc` 属性参数、`NPC_COMPONENTS`。
- `npc/internal/ReviveHandler`：`setFlat`/`npc.spellSpeed=` 等（改 vanilla base）；`values()` 遍历改 ORDER。
- `npc/network/NpcDataPacket`：`from` 读有效属性（改 vanilla）。
- `shared/network/tasks/TaskPanelSyncTracker`：读 SPELL_POWER/WORK_SPEED/SPELL_SPEED/护甲（改 vanilla）。
- `shared/data/MageHutAttributes`：`ORDER` 改显式 7 项（不再 `AttributeType.values()`）。
- `shared/data/MageHutResident`：`base` 数组尺寸改 `ORDER.size()`（非 `values().length`）。
- `building/internal/MageHutServerHandler`：`flat`/`setFlat` 改读/写 vanilla attr；3 处 `values()` 数组/遍历改 ORDER；穷举 switch 补 default。
- `building/client/MageHutScreen`：`attrKey`/`fallbackLabel` 穷举 switch 补 default（隐藏属性不可达）。
- `guard/NpcSpellPowerHandler`：读 vanilla `wandscape:spell_power`。
- `guard/GuardCombat`、`guard/NpcEscapeTeleport`、`engine/system/NavigationSystem`、`magic/internal/MagicCaster`、`magic/internal/MagicSpellExecutors`：`getEffectiveAttribute(SPELL_POWER/SPELL_SPEED/...)` 读 vanilla。
- `compat/ironspellbooks/IronSpellsCaster`：读 vanilla `wandscape:spell_speed`。
- `building/network/TavernRecruitPacket`、`shared/data/TavernRecruitStorage`、`tourist/**`：招募属性经 getter/setter 写 vanilla base。
- `npc/data/NpcDataImpl`、`npc/data/DeathRecord`、`npc/internal/ColonyDeathRegistry`、`npc/internal/NpcDeathHandler`：死亡记录读写改 getter/setter。
- `command/ColonyCommand`、`command/TouristCommand`、`command/TavernCommand`：`setArmorItem`/字段读写改 vanilla。

## 六、MageHut 属性数组 / 穷举 switch 收尾（随 Phase 5）

- `MageHutAttributes.ORDER`：`List.of(AttributeType.values())` → 显式 `List.of(MAX_HP, MOVE_SPEED, SPELL_POWER, WORK_SPEED, SPELL_SPEED, ARMOR_VALUE, MAX_MANA)`。
- `MageHutServerHandler` 3 处 `AttributeType.values()`（132/313/366）→ `MageHutAttributes.ORDER`；数组尺寸 → `ORDER.size()`；360 行 `new float[7]` → `ORDER.size()`。
- 穷举 switch expression（`MageHutServerHandler.flat`、`MageHutScreen.attrKey/fallbackLabel`）补 `default`（隐藏属性不显示不训练，`flat` default 返 1f）。
- `WandscapeNpc.getEffectiveAttribute` fallback switch 补 `HEALTH_REGEN/MANA_REGEN → 1f`（Phase 2 后整段删除，改 vanilla）。

## 七、风险与注意

1. **二次加成修法改变行为**：铁魔法装备 SPELL_POWER 改为只加铁魔法（记录到 decisions）。
2. **core/ 零 MC 约束**：`AttributeType` 保留为纯 ID；`WandscapeAttributes`（engine 层）持有 vanilla `Holder<Attribute>`，core 不 import MC。
3. **双写期（Phase 1-2）易漂移**：applyEffectiveAttributes 推 ECS→vanilla，装备桥同时写 ECS 与 vanilla，需保证同源；每阶段编译 + 全量 test + 提交。
4. **存档迁移**：Phase 4 旧存档从自定义字段播种 vanilla base。
5. **并发会话占树**：当前工作区被另一会话的 colony-id 重构（TaskRequest +colonyId，13 调用点未迁移）占着，不编译。实施前先让该会话收尾提交，或协商串行。
6. **测试价值下降**：EquipmentComponent 的纯逻辑单测随组件删除消失；vanilla 属性计算由原版保证。保留 MageHutAttributes/MagicState 纯逻辑测试（不依赖组件）。

## 八、提交与测试策略

- 每 Phase 独立提交（大重构逐步提交，保留回滚点），`./gradlew build` + `./gradlew test` 全绿再进下一 Phase。
- Phase 2 后需游戏内实测：铁魔法装备 SPELL_POWER 不再双重（对比重构前后伤害）、冷却/吟唱缩减仍单次生效、`/attribute` 可见。
- Phase 5 收尾后递增次版本号（功能重构）。
