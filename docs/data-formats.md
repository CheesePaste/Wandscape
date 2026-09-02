# 数据格式与迁移纪律（data-formats）

> 信息截至 2026-09-02 | Minecraft NeoForge 1.21.1

- **【何时读】**：新增或修改 `data/wandscape/` 下的数据 JSON（建筑/配方/元素/魔法/天平配置/叙事/标签）或处理存档序列化时。
- **【不包含什么】**：代码实现细节、游戏内具体数值平衡脑洞。

---

## 一、数据兼容与版本纪律

1. **开发期断档权利**：开发迭代期不承诺旧存档兼容；修改 NBT / JSON 结构时，要么显式编写基于版本号的迁移链，要么直接断档。
2. **禁止无版本号兜底**：严禁在代码中编写"缺少某 Key 自动猜默认值"的内联兼容特判代码。
3. **彻底删除废弃字段**：废弃字段从数据结构中真删，禁止保留读取别名或兼容用空构造器。
4. **SavedData 迁移规范**：持久化数据升级统一在 SavedData 根标签存储 `version` 整数，依据版本号走单一确定性迁移链。

---

## 二、天平数值持久化 (`data/wandscape/wandscape_balance.json`)

用于整合包作者通过直接编辑 JSON 文件覆盖全模组运行平衡参数。

```json
{
  "_comment": "Wandscape Balance Overrides",
  "guard.flee_hp_threshold": 0.3,
  "scepter.hostile_range": 128.0,
  "tourist.vision_radius": 48.0,
  "tourist.max_energy": 100
}
```

- 由 `WandscapeBalanceLoader` 在服务器重载数据包（`/reload`）时先重置全部覆盖项，再读取本文件重新注入。
- `_` 开头的键为注释，自动忽略；未知键将被记录警告并跳过。

---

## 三、建筑 JSON (`data/wandscape/buildings/<id>.json`)

位置：`src/main/resources/data/wandscape/buildings/<id>.json`

```json
{
  "id": "townhall1",
  "display_name": "Town Hall",
  "creator": "CheesePaste",
  "category": "government",
  "first_free": true,
  "deprecated": false,
  "pattern": [[0,0,0], [1,0,0]],
  "palette": [
    "minecraft:oak_planks",
    "minecraft:glass"
  ],
  "block_indices": [0, 1],
  "block_nbt": {
    "0,0,0": "<base64_nbt>"
  },
  "comfort": 10,
  "magic": 10,
  "wonder": 10,
  "unlock_requirement": { "min_colony_level": 1 },
  "interact_spots": [
    { "pos": [2, 1, 3], "action": "browse", "facing": "south" }
  ],
  "shop": {
    "goods": [{ "item_id": "minecraft:bread", "comfort": 6, "magic": 0, "wonder": 0 }],
    "profit_rate": 0.3,
    "interaction_duration_ticks": 2400
  },
  "service": {
    "energy_per_use": 10,
    "element_output": { "water": 4 },
    "max_occupancy": 4,
    "interaction_duration_ticks": 600
  },
  "relax": {
    "energy_restore": 40,
    "interaction_duration_ticks": 1200
  },
  "atm": {
    "interaction_duration_ticks": 1200
  }
}
```

### 关键字段说明
- `block_nbt`：仅由创造扫描器导出时包含；生存扫描器导出时剔除。
- `interact_spots`：交互位列表，坐标相对建筑 anchor。`action` 支持 `browse/eat/bathe/view/pay/read/take/rest/withdraw`；`facing` 为朝向（`north/east/south/west`）。
- **四类游客模式预设块**：`shop`（购物）、`service`（服务/住宿）、`relax`（歇脚恢复精力）、`atm`（取现补充随身钱包）。

---

## 四、合成配方 JSON (`data/wandscape/craft_recipes/<id>.json`)

位置：`src/main/resources/data/wandscape/craft_recipes/<id>.json`

```json
{
  "type": "wand",
  "craft_station": "crafting_station",
  "id": "apprentice_wand",
  "display_name": "学徒法杖",
  "slot": "wand",
  "wand_color": "#7FB8D0",
  "attributes": [
    { "type": "spell_power", "operation": "addition", "amount": 0.25 },
    { "type": "max_mana", "operation": "addition", "amount": 30.0 }
  ],
  "output": { "item": "wandscape:wand" },
  "cost": { "fire": 800, "water": 800, "wood": 800 },
  "unlock_requirement": { "min_colony_level": 1 }
}
```

- `type: "wand"`：NPC 建造法杖，携带预设属性加成与颜色。
- `type: "spell"`：魔法工坊卷轴合成（`output.magic_id` 绑定法术 ID）。
- `type: "misc"`：功能性右键物品（权杖、戒指、指南针、仓库终端等）。

---

## 五、元素映射与种子 (`element_mappings/` 与 `element_seeds.json`)

### 1. 单方块/物品映射 (`element_mappings/minecraft_<id>.json`)
```json
{
  "block": "minecraft:acacia_log",
  "build_cost": { "wood": 8 }
}
```
或指定物品：`"item": "minecraft:diamond"`。

### 2. 权威种子库 (`element_seeds.json`)
包含约 370 条基准物品价值，作为元素生成命令（`/wandscape generate_element_mappings`）的权威基准源。

### 3. 7 大元素类型
`earth`（土）、`wood`（木）、`water`（水）、`fire`（火）、`metal`（金）、`wind`（风）、`dark`（暗）。

---

## 六、魔法定义与法阵 JSON

### 1. 魔法定义 (`magic_spells/<id>.json`)
```json
{
  "id": "beam",
  "category": "normal",
  "default_group": "single_target",
  "mana_cost": 50,
  "base_cooldown": 400,
  "range": 32,
  "target_mode": "hostile_nearest",
  "altar_only": false,
  "conditions": {
    "self_hp_max": 0.6,
    "no_effect": "minecraft:absorption"
  },
  "effect": {
    "circle_id": "arcane_hexagram",
    "damage": 12.0
  }
}
```

### 2. 魔法阵视觉 (`magic_circles/<id>.json`)
定义法阵几何图层与粒子动画。包含 5 种几何图元：`ring`（环）、`arc`（弧）、`polygon`（多边形）、`star`（星形）、`glyph`（符文）。

---

## 七、叙事模板 JSON (`data/wandscape/narratives/`)

- `buildings/<type>.json`：特定建筑专属的叙事访问模板。
- `zh_cn.json` / `en_us.json`：全局类别的到达、离场、住宿及满意度里程碑叙事模板。
- 支持占位符：`{name}`（游客名）、`{building}`（建筑名）、`{item}`（商品名）、`{emotion_adj}`（心情形容词）。

---

## 八、道路标签 (`data/wandscape/tags/block/custom_roads.json`)

```json
{
  "replace": false,
  "values": [
    "minecraft:dirt_path",
    "minecraft:stone_bricks",
    "minecraft:purpur_block"
  ]
}
```
标记路面方块，供游客寻路速度增益与物品运输沿路判定使用。
