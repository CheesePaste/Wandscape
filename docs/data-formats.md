# 数据格式与迁移纪律（data-formats）

> 信息截至 2026-09-02 | Minecraft NeoForge 1.21.1

- **【何时读】**：新增或修改 `data/wandscape/` 下的数据 JSON（建筑/配方/元素/魔法/天平配置/标签）或处理存档序列化时。
- **【不包含什么】**：代码实现细节、游戏内具体数值平衡脑洞。

---

## 一、数据兼容与版本纪律

1. **断档权利**：修改 NBT / JSON 结构时，要么显式编写基于版本号的迁移链，要么直接断档 ——
   **不维护新旧两套格式并存的兼容层**。断档不是免责：这类改动**必须在 release 正文里给出
   不兼容提示**（说清哪种世界受影响、要不要开新档），清单见 [checklists.md](checklists.md) §三。
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
  "scepter.hostile_range": 32.0,
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
  "pattern": {
    "format": 1,
    "origin": [0, 0, 0],
    "size": [2, 1, 1],
    "palette": ["minecraft:oak_planks", "minecraft:glass"],
    "cells": "<base64>",
    "values": "<base64>"
  },
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

### 3.1 `pattern` 打包格式（`PatternCodec`，版本 1）

`pattern` 是**对象**，不是数组。方块网格按 sparse 方式打包，编解码唯一实现是
`content/building/data/PatternCodec`（纯逻辑、不 import MC），扫描器导出与加载器解析共用它。

- `origin` / `size`：网格包围盒的最小角与尺寸（`origin` 可为负；扫描器导出的偏移是
  `boundaryMin + 相对坐标`）。**自带范围，不复用 `boundary`** —— 后者是可选的、且语义是
  「整箱清空的范围」，两者不是一回事。
- `palette`：该建筑用到的方块状态，首次出现序。
- `cells`：`base64(gzip(varint 流))`。每格线性下标 `((y-oy)*sx + (x-ox))*sz + (z-oz)` 升序排列，
  存与前一个的差分（首个对 0 差分）。
- `values`：`base64(gzip(varint 流))`，与 `cells` **逐位对齐**的 palette 下标。
- 解码结果按 `(y, x, z)` 序产出，`pattern[i]` 的方块是 `palette[blockIndices[i]]`。

实测（magic_academy，44.3 万格 / 453 种方块）：29.5 MB 的 pretty JSON → **361 KB**，约 82 倍。
全仓 56 栋合计 36.4 MB → 1.5 MB。

**旧格式已断档，不提供兼容读取**：`pattern` 数组 + 顶层 `palette` + `block_indices` 三件套
（v1.11.1 及更早版本的扫描器导出）见到即抛 `JsonParseException`。`format` 不认识同样抛错。
玩家在此版本之前导出的建筑需要重新扫描导出。

**空气不是特殊方块**：扫描器从不导出空气，所以数据里出现 `minecraft:air` 一律是**人为把
导出的周边山体/树木改写成 air 想踢出建筑**留下的痕迹 —— 它的本意就是删除。这类格子不进打包
数据，直接成为「缺失」。放置、修复、物料、占用与重叠都只按 `pattern` 的格子算，没有任何
针对空气的特判。

### 关键字段说明
- `block_nbt`：仅由创造扫描器导出时包含；生存扫描器导出时剔除。键是 `x,y,z`，值是**逐条
  gzip 后的 base64 NBT** —— 一栋大建筑的这一项可能比 pattern 本身还大（magic_academy 是
  159 KB vs 载荷 210 KB），是下一步的压缩目标。
- `interact_spots`：交互位列表，坐标相对建筑 anchor。`action` 支持 `browse/eat/bathe/view/pay/read/take/rest/withdraw`；`facing` 为朝向（`north/east/south/west`）。
- **四类游客模式预设块**：`shop`（购物）、`service`（服务/住宿）、`relax`（歇脚恢复精力）、`atm`（取现补充随身钱包）。
- **`shop.profit_rate` / `service.element_output` 是基准值，不是最终入账**。入账结算顺序是「JSON 原始产出 → 创始人离线的 `colony.offlineIncomeMultiplier` 折减 → 全局产出阀门」，阀门分别是 `Config.shop.elementMultiplier` 与 `Config.service.elementMultiplier`（默认 1.0 = 不缩放，可在设置中心「经营」页调）。想整体缩放游客经济的元素产出就动这两个 config，别逐个改 JSON。
  - 商店入账 = `商品元素估价 × (1 + profit_rate)`；`profit_rate` 是**加价率**不是产出倍率，把入账砍半需要 `(profit_rate − 1) / 2`（负数），所以「减半」只能按利润率减半来配。

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
- `type: "misc"`：功能性右键物品（戒指、指南针、仓库终端等）。

---

## 五、默认解锁配方 (`data/wandscape/default_recipes.json`)

单文件，列出**开局即解锁**的合成配方（`DefaultRecipeUnlocks` 读取，`/reload` 生效）：

```json
{
  "recipes": [
    "minecraft:oak_log",
    "minecraft:oak_planks",
    "minecraft:stone",
    "minecraft:tuff"
  ]
}
```

- **写的是配方 id，不是文件名**：id 即 `SynthesizeRecipe.id()`——`element_mappings` 里该条目的 `item`（若有）否则 `block`，带不带 `minecraft:` 前缀都收。清单里写了但 `element_mappings` 里没有对应条目的 id 无副作用（它本来就没有配方）。
- **语义是"基准"，不是存档记录**：清单内容不写进 `ColonyRecipeSavedData`，因此对每个殖民地恒定算已解锁，`/wandscape recipe lock` 也锁不掉；想收回就把 id 从文件里删掉。存档里存的仍只是玩家靠存货/图纸/鉴定挣来的那部分额外解锁。
- **它同时是合成树的种子**：清单当作"开局就已知的材料"，沿原版配方树推出下游（`VanillaRecipeTree`，见 §五.1），推出的东西同样对所有殖民地恒定已解锁、同样不进存档。所以往这里加一条原木/矿石会连带放出整条产业链，加之前先确认那正是想要的效果。
- **多命名空间取并集**：整合包/数据包可在自己的命名空间再放一份同名 `data/<ns>/default_recipes.json` 扩充，不必改模组文件。
- 当前自带清单放**主世界基础建材**（八种原木/木头、八种木板、八种树苗、蘑菇与蘑菇柄、竹子/竹块/竹板，加草方块/石头/圆石/闪长岩/安山岩/深板岩/深板岩圆石/凝灰岩），另破例收一项 `minecraft:glowstone`——1 级酒馆/市政厅拿它当灯，算早期建材而不是"下界探索奖励"，不收就会让首免建筑卡在等料；其余下界物资照旧。去皮原木、竹马赛克与下界木料**故意不收**——留给玩家自己解锁。清单刻意用显式 id 而非 `#minecraft:logs` 这类标签，正是为了不把别的模组的高级木材（或下界木料）顺带提前放出。
- 自带清单（49 条）沿树能推出 187 条下游（木头族、石材族、木炭、火把、木/石工具等），合计 236 条恒定已解锁，占图鉴 1186 条约两成；这个数字随清单改动而变，改完重开服看日志里的 `Recipe tree built: ... default-derived` 一行即可。草方块自身没有任何原版配方、也不是任何配方的材料，加它只放出它自己。

### 五.1 沿合成树推导（`VanillaRecipeTree`）

**规则**：解锁不是一件一件来的。任何一样东西解锁（存货入库、图纸自选、法杖鉴定、指令 / API），它的下游都会跟着解锁——凡是**每个材料槽都有已解锁选项**的原版配方，其产出也算已知，递归下去。材料槽按标签展开（`#minecraft:planks` 只要有任意一种木板已知就满足）。例子：镇上开局就知道橡木木板，玩家再存入一次白色羊毛，白色床随即解锁。

- **覆盖的配方类型**与 `ElementValueGenerator` 完全一致（合成 / 熔炼 / 烟熏 / 营火 / 切石 / 锻造），所以"能推出来的"与"`element_mappings` 里有价的"是同一个域，图鉴里不会出现有价却推不出来的条目。特殊配方（染色 / 烟花 / 地图复制等 `isSpecial()`）没有稳定材料表，一律跳过；产出没有元素映射的配方直接丢掉（既合不出来也永远不会被解锁）。
- **推导结果的去向**：和玩家直接解锁的条目一样写进 `ColonyRecipeSavedData`，来源标 `recipe_tree`（`RecipeUnlockedEvent` 的 source），永久生效；`/wandscape recipe lock_all` 能一并清掉。
- **缓存与重扫**：整张图挂在一个 `volatile` 快照上，键是**配方管理器实例本身**——`/reload` 会新建一份，实例换了就重建，因此不订阅重载事件。每次构建另算一个**内容指纹**，按殖民地存进 `ColonyRecipeSavedData`（`tree_fingerprint`，v2 起）；开局 / `/reload` 只对指纹不符的镇重推一遍，配方表没变时每次启动只花几次哈希查找。旧档没有指纹字段，会自愈重扫一次（增量，已有结果不重写）。
- **规模化数据**（1.21.1 原版 + 本模组，实测）：1260 条配方里 1240 条产出有元素映射；索引 508 个材料 → 7261 条"材料-配方"边；一次全量闭包 BFS 在 Python 里约 1 ms（Java 更低一个量级），整镇重扫最坏 20 万次集合查找。

---

## 六、元素映射与种子 (`element_mappings/` 与 `element_seeds.json`)

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

## 七、魔法定义与法阵 JSON

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
