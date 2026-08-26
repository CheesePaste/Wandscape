# compat/

第三方模组联动与兼容适配包。

## 职责

1. 封装对第三方模组（如 Iron's Spells 'n Spellbooks）的编译期与运行期依赖。
2. 通过 `ModList.get().isLoaded(...)` 门控提供软依赖隔离，确保在第三方模组未安装时零硬编码耦合、不发生类加载异常并优雅降级。
3. 桥接第三方物品、实体、法术状态与 Wandscape 的 ECS 组件和施法决策脑。

## 子包与类

### `ironspellbooks/`
- `IronSpellsCompat`：兼容总入口，检查加载状态并在模组加载时注册事件监听。
- `IronSpellsHelper`：识别铁魔法卷轴物品堆、提取/构建带等级的卷轴、生成动态 `MagicDef`。
- `IronSpellsCaster`：瞬发（`INSTANT`）、长蓄力（`LONG`）与持续引导（`CONTINUOUS`）法术的执行与生命周期维护。
- `IronSpellsDamageHandler`：订阅 `SpellDamageEvent`，将法师的 `SPELL_POWER` 与【魔力强化】倍率乘入铁魔法伤害输出。

## 依赖关系

- **上游依赖**：`core/`、`shared/`、`npc/`、`magic/`
- **下游依赖**：被 `Wandscape`（生命周期接线）、`NpcStrategyMenu`（卷轴槽位放置）、`CastBrain`（动态 `MagicDef` 解析）、`MagicSpellExecutors`（法术分发）调用
