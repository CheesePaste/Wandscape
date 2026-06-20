# 03 — 法杖系统 (`wand/`)

法杖物品 + NBT 行为标签 + 能力并集 + JSON 预设加载 + 3D 模型 + 使用射线粒子。

## 源文件 (5 文件)

| 文件 | 作用 |
|------|------|
| `item/WandItem.java` | 法杖物品：隐藏耐久条，不可损坏，右键发射彩色射线粒子（`CastBoltParticle`）。纯 `Item` 子类 |
| `internal/WandApiImpl.java` | WandApi 实现：从 ItemStack NBT 读 `wand_color` / `behaviors` / `range` / `mana_cost_multiplier`，`computeAbilities()` 取所有法杖行为等级最大值并集 |
| `internal/WandBehaviorDataImpl.java` | WandBehaviorData 实现 record：color + behavior map + range + mana cost multiplier |
| `internal/WandDataValidator.java` | NBT 校验器：颜色 hex 格式 / 行为类型有效性 / range [1-5] / mana_multiplier [0.3-1.0] |
| `internal/WandPresetLoader.java` | 从 `data/wandscape/wands/*.json` 加载法杖预设 → WandPreset（含预制 NBT CompoundTag） |

## 资源文件

| 文件 | 作用 |
|------|------|
| `models/item/wand.json` | 3D 法杖模型：木杆（`[7,0,7]`→`[9,10,9]`，无 tintindex）+ 浮空宝石（`[6.5,12,6.5]`→`[9.5,15,9.5]`，tintindex:0） |
| `textures/item/wand_3d.png` | 16×16 贴图：左半木纹（杆），右半宝石色块 |
| `textures/item/wand.png` | 1.0 版本 2D 贴图（保留备用） |

## 注册项

| 注册 ID | 类型 | 所在 |
|---------|------|------|
| `wandscape:wand` | WandItem | `Wandscape.WAND` |

注册在创造标签 `wandscape_tab` 中。

## JSON 格式 (`data/wandscape/wands/`)

```json
{
  "id": "builder_wand",
  "display_name": "建筑法杖",
  "default_color": "#FFD700",
  "behaviors": { "building": 1 },
  "default_range": 1,
  "default_mana_cost_multiplier": 1.0,
  "unlock_magic_value": 0
}
```

已有 4 个预设：`builder_wand` / `gatherer_wand` / `crafter_wand` / `ritual_wand`

## 依赖

- `shared/api/WandApi` — 实现的接口
- `shared/data/AbilitySet` / `BehaviorType` / `WandBehaviorData` — 数据类型
- `shared/registry/WandscapeApis` — 注册 API 实现
- `dataconfig/WandscapeDataLoader` — 加载 JSON 预设
