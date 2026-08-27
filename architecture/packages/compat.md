# compat/

第三方模组联动与兼容适配包。

## 职责

1. 封装对第三方模组（如 Iron's Spells 'n Spellbooks）的编译期与运行期依赖。
2. 通过 `ModList.get().isLoaded(...)` 门控提供软依赖隔离，确保在第三方模组未安装时零硬编码耦合、不发生类加载异常并优雅降级。
3. 桥接第三方物品、实体、法术状态与 Wandscape 的 ECS 组件和施法决策脑。

## 子包与类

### `ironspellbooks/`
- `IronSpellsCompat`：兼容总入口，检查加载状态。
- `IronSpellsHelper`：识别铁魔法卷轴物品堆、提取/构建带等级的卷轴、生成动态 `MagicDef`（蓝耗 1:1）。
- `IronSpellsCaster`：瞬发（`INSTANT`）、长蓄力（`LONG`）与持续引导（`CONTINUOUS`）法术的执行与生命周期维护。蓝耗 1:1 直接扣 NPC 魔力；所有类型施法开始一次性扣全量。
- `IronSpellsAttributes`：把铁魔法装备物品堆的 `ItemAttributeModifiers` 映射为 Wandscape 属性修饰符——`MAX_MANA`→`MAX_MANA`、`SPELL_POWER`→`SPELL_POWER`、`COOLDOWN_REDUCTION`/`CAST_TIME_REDUCTION`→`SPELL_SPEED`（百分比映射 `MULTIPLY_BASE` 乘区）；vanilla `MOVEMENT_SPEED` **不再映射**（盔甲进 vanilla 槽后由原版直接结算，再映射会双重叠加）。各学派 `*_spell_power`/`casting_movespeed`/`mana_regen`/`summon_damage`/`spell_resist` 与各系抗性等铁魔法特色属性不映射。供 `WandscapeNpc.syncIronArmorAttributes` 桥进 ECS 盔甲槽。

> **铁魔法伤害倍率不在 compat 包乘**：铁魔法 `DamageSources.applyDamage` 发完 `SpellDamageEvent`
> 后仍会调用 `target.hurt()`，同一伤害会连续触发 `SpellDamageEvent` 与 `LivingIncomingDamageEvent`
> 两个事件。SPELL_POWER/魔力强化倍率统一由 `guard/NpcSpellPowerHandler` 在伤害入口乘**一次**，
> compat 包不再订阅 `SpellDamageEvent`（曾加的 `IronSpellsDamageHandler` 因双重乘算使伤害随法术
> 强度二次方暴涨而移除）。

## 依赖关系

- **上游依赖**：`core/`、`shared/`、`npc/`、`magic/`
- **下游依赖**：被 `Wandscape`（生命周期接线）、`NpcStrategyMenu`（卷轴槽位放置）、`CastBrain`（动态 `MagicDef` 解析）、`MagicSpellExecutors`（法术分发）调用
