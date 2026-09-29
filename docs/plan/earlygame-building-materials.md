# 1-10 级建筑建材 + 1-5 级商店商品前期可得性审计（配方解锁引入后的堵点）

> 日期：2026-09-27（§3.4 商店商品审计补于 2026-09-29）
> 状态：建材已实施（方案 A，2026-09-29，口径见 §六）；§3.4 的 1-5 级商品待决策——下一步动 5 级
> 适用版本：Minecraft NeoForge 1.21.1 / 分支 `1.21.1`
> 关联：[domain-notes.md](../domain-notes.md)（建筑 / 生产域）、[data-formats.md](../data-formats.md)、[balance-baseline.md](../balance-baseline.md)

---

## 一、为什么要做这次审计（机制链）

配方解锁（`77948e0c`、`d0a4dbcf`、`3a539acc`）落地后，建材的获取链路变成一条闭环，任何一环缺失都会让建筑永久停在等料态：

1. **建材 = 仓库实物**。`EnqueueHelper.computeMaterialCounts` 按建筑 palette 计数，**只统计有元素映射的方块**——没有映射的方块（见 §五）既不是建材、也不能解锁，直接免费放置。
2. **缺料自动补**。`ResourceSupplySystem.enqueueSynthesize` 在仓库缺料时自动向工作站排合成任务，但**尊重配方门控**：`ProductionRecipeManager.isSynthesizeUnlocked` 为假时直接 `return false`，不排任务。
3. **合成的解锁途径只有三条**（另加 OP 指令，见 `RecipeCommand`）：物品**入过一次仓库**（`ProductionRecipeManager.checkAndUnlockOnWarehouseAdd`）、**法杖右键鉴定**（`WandModeService:182`）、**图纸自选**（`UnlockRecipeByBlueprintPacket:87`）。三者都要求玩家**先亲手拿到过该物品**（图纸除外，它可任选，但来源随机）。
4. **于是堵点成立**：某方块早期拿不到 → 配方永远锁着 → 自动合成不启动 → 建筑永远停在"等待材料"。`ProjectionPlacePacket:108` 已专门检测这种组合（`BuildingApiImpl.findMissingLockedMaterials`）并给玩家提示"缺少 N 种未解锁配方的材料，施工将等待材料入库"——**说明这个洞已被预见，只是建材表没跟上**。
5. **商店是这条闭环的第二个消费者**。`ShopStockManager.restock` 先看**仓库有没有现货**，没有才走 `ResourceSupplySystem.enqueueSynthesize` 排合成——同一道 `isSynthesizeUnlocked` 门。货架初始为空（`getOrCreateShopStock` 的空表 + `DEFAULT_MAX_STOCK`），所以**配方锁着 = 这个货位永远空着**，游客想买也买不到。审计范围因此不止建材，还包括 1-5 级商店的 `shop.goods`（见 §3.4）。

**首免不解决问题**：`first_free: true` 只免该类型在本镇的第一栋（`BuildingApiImpl:960`），第二栋起照价收料，所以首免建筑的堵点材料仍是隐患，只是延后一栋爆发。

## 二、判定口径

- **硬堵点**：该方块在对应等级下，玩家**没有合理路径拿到第一个**（原版门控：下界 / 末地 / 海底神殿 / 深暗 / 繁茂洞穴 / 滴水石 / 紫水晶洞 / 蜜蜂 / 精准采集 / 铜自然氧化 / 丛林）。
- **商店商品的"第一个"同理**，只是主体从方块换成了物品：先亲手拿到过一次实物（入库解锁）或花图纸。**法杖鉴定对纯物品无效**——鉴定的入参是方块（`WandModeService:158` 读 `level.getBlockState(pos)`），所以 `quartz` / `spectral_arrow` / `spyglass` / `honey_bottle` 这类没有同名方块的物品只剩"入库 / 图纸"两条路。有同名方块的（`end_rod` / `soul_lantern` / `comparator` 等）多一条鉴定，但前提是世界里先有那块方块，早期同样没有。
- **商品本身都能合成**：50 种 1-5 级商品（9 家商店）在 `element_mappings` 里**全部有条目**（无 `disabled`），且由映射生成的 `SynthesizeRecipe` 带的是 `RecipeUnlockRequirement.NONE`（minColonyLevel = 1，`SynthesizeRecipe:33`），没有等级闸。所以商品堵点**只可能是解锁**这一环，不是"没配方"或"等级不够"。
- **重成本**：拿得到但要么量大（染料、羊毛、陶瓦、砂岩），要么要专门跑一趟（铁、钟），不算堵但值得记一笔。
- 造价口径：Σ 方块数 × `element_mappings` 的 `build_cost`，1 元素 = 1 通用值。**开局每元素 3000（七元素合计 21000）**，可作为 L1 建筑造价的直观标尺。

## 三、硬堵点清单

> §3.1–§3.3 是 2026-09-27 审计当时的原始建材清单，保留作记录；其中已经处理掉的部分见 §6.1，别再照着这几张表判断现状。§3.4 是后补的商店商品审计。

### 3.1 一级（最要紧：这是教学阶段）

| 建筑 | 堵点材料 | 说明 |
|---|---|---|
| **craftstation1** | 氧化铜族 ×14（格栅 9 + 切割板 3 + 切割梯 2）、`waxed_copper_bulb` ×1、`end_rod` ×1、`pearlescent_froglight` ×1、`beehive` ×1 | 一栋集齐四种门控 |
| **mage_hut1** | 氧化铜族 ×14、`waxed_copper_bulb` ×1、`beehive` ×1 | 另有 `enchanting_table` ×1（黑曜石需钻石镐 + 钻石 + 书，本身不算门控但很重） |
| **potionstation1** | 氧化铜族 ×14、`waxed_copper_bulb` ×1、`brewing_stand` ×1、`soul_campfire` ×1 | 酿药台要烈焰棒 |
| **potion_store** | `brewing_stand` ×4、`nether_wart` ×3、`soul_sand` ×3、`redstone_lamp` ×1、`bamboo_door` ×4 | 红石灯要荧石 |
| **bamboo_hall** | `polished_blackstone_button` ×13 | 黑石=下界 |
| **altar1** | `polished_blackstone_bricks` ×1、`amethyst_block` ×1 | 紫水晶需找到晶洞。注：`aa35e04b` 当初特意把祭坛降到 1 级并写明"材料成本即门槛"，紫水晶可能是**有意**保留的成本门槛，改前先确认 |
| **long_chair** | `bamboo_trapdoor` ×3、`bamboo_slab` ×3、`bamboo_wall_sign` ×2 | 整栋竹子 |
| **signboard** | `bamboo_hanging_sign` ×9、`bamboo_fence` ×6、`bamboo_slab` ×2 | 整栋竹子 |
| book_shop / flower_shop / bakery（首免）/ potion_store | `bamboo_door` ×4（门）、`scaffolding`（仅 book_shop ×4）、`redstone_lamp` ×1 | 竹子=丛林；红石灯=荧石 |
| flower_shop / youth_hostel（首免）/ bakery（首免）/ hotel / beekeepers_house | `beehive` ×1~2 | 需蜜蜂 + 营火 + 剪刀（或精准采集搬天然蜂箱） |
| **workstation1**（首免） | 氧化铜族 ×14、`waxed_copper_bulb` ×1 | 首栋免费，第二栋起堵 |
| tavern1（首免）/ townhall1（首免） | `glowstone` ×7 / ×6、`sea_pickle` ×4 | 荧石=下界 |
| warehouse1（首免） | `flowering_azalea_leaves` ×4 | 繁茂洞穴 |

一级非首免建筑共 14 栋，其中 **craftstation1、mage_hut1、potionstation1、potion_store、bamboo_hall、long_chair、signboard、altar1** 八栋存在"现在就是堵的"材料；其余（atm1、arrow_store、flower_shop、book_shop、street_light、bakery）只在第二次建造或个别材料上踩坑。

### 3.2 五级

| 建筑 | 堵点材料 |
|---|---|
| redstone_shop | `calibrated_sculk_sensor` ×1（深暗+紫水晶）、`redstone_ore` ×1 + `deepslate_redstone_ore` ×3（**精准采集**）、`comparator` / `daylight_detector` / `observer` 各 ×1（**下界石英**） |
| watch_tower | `magma_block` ×12（下界）、`scaffolding` ×17（竹子）、`dried_kelp_block` ×3（海带+烧炼） |
| hotel | `redstone_lamp` ×4（荧石）、`soul_sand` ×2（下界） |
| cellar | `quartz_block` ×2（下界石英） |
| latern_shop | `glowstone` ×1、`soul_lantern` ×1、`waxed_copper_bulb` ×1、`end_rod` ×1 |
| smithy | 氧化铜格栅 ×2（其余为铁重，见 §四） |
| windmill1 | `waxed_copper_bulb` ×3 |

这一档的红石商店最集中：**深暗 + 精准采集 + 下界石英**三种门控同时压在一栋建筑上，而它叫"红石商店"、主题又确实需要这些东西——属于"主题自洽但前置过高"，是这档最需要单独决策的一栋。

### 3.3 十级

| 建筑 | 堵点材料 |
|---|---|
| **beekeepers_house** | `end_stone` ×165（**末地主材**）、`end_rod` ×12、`honeycomb_block` ×76 + `honey_block` ×3 + `beehive` ×6（蜜蜂量很大，与"养蜂人小屋"主题自洽） |
| **luxury_hotel_cyan** | `stripped_warped_hyphae` ×341（下界诡异森林）+ 诡异木系列、`crimson_slab` ×14 |
| **luxury_hotel_green** | `dark_prismarine` 族 12（**海底神殿专属**）、`crimson_slab` ×14 |
| 三款 advanced_street_light | 蛙明灯 ×9 每栋、`chiseled_quartz_block` ×1、`amethyst_cluster` ×1（精准采集）、`moss_carpet` ×8 + `flowering_azalea_leaves` ×3（繁茂洞穴） |
| fountain_plaza | 蛙明灯 ×28、`end_rod` ×12、`moss_carpet` ×28 |
| bamboo_house | `bamboo` ×396（丛林）、`bell` ×1（**原版无配方**，只能从村庄取得或与盔甲匠交易 36 绿宝石）、`moss_block` ×51 + `rooted_dirt` ×10 + `hanging_roots` ×3 |
| 七座元素节点 | **每栋 `end_rod` ×4**；另 nodedark 用 `purpur_block` ×12（末地）、nodefire 用 `nether_bricks` ×12（下界）、nodemetal 用 `deepslate_copper_ore` ×10 + `deepslate_iron_ore` ×2（精准采集）、nodewater 用 `ice` ×12（精准采集） |

十级的门控密度显著高于前两档，但玩家此时通常已开下界、跑过洞穴；**真正要单独决策的是三处**：`end_stone` ×165（必须去末地）、`dark_prismarine`（必须打海底神殿）、以及 7 座节点建筑共 28 根 `end_rod`（烈焰棒 + 末地紫颂果，等于每座节点都要末地下界各跑一趟）。

### 3.4 商店商品（1-5 级）

判定口径见 §二：货架初始为空，配方锁着就是**死货位**；商品全都有合成配方且无等级闸，堵的只有解锁那一环。原版配方逐条核过（§八），`门控` 一栏即"玩家怎么拿到第一个实物"。

| 等级 | 建筑 | 商品 | 门控 | 造价 |
|---|---|---|---|---|
| L1 | **potion_store** | `quartz` | 下界石英矿（下界，冶炼得） | 8 |
| L1 | **potion_store** | `nether_wart` | **原版无配方**，只在下界要塞 / 猪灵交易 | 16 |
| L1 | **book_shop** | `spyglass` | 紫水晶碎片（紫水晶洞） | 80 |
| L1 | **arrow_store** | `spectral_arrow` | 荧石粉（下界） | 36 |
| L1 | bakery | `honey_bottle` | 蜜蜂（软：拿瓶子对满蜜的蜂巢右键，比蜂箱方块省掉剪刀/精准采集） | 33 |
| L5 | **latern_shop** | `end_rod` | 烈焰棒（下界）+ 爆裂紫颂果（末地） | 17 |
| L5 | **latern_shop** | `soul_lantern` | 灵魂火基底方块（下界） | 61 |
| L5 | **latern_shop** | `copper_bulb` | 烈焰棒（下界） | 73 |
| L5 | **redstone_shop** | `comparator` / `observer` / `daylight_detector` | 下界石英（三件都要） | 53 / 38 / 30 |
| L5 | **redstone_shop** | `calibrated_sculk_sensor` | 深暗之域 + 紫水晶碎片 | 224 |

**五级这一档最集中**：`latern_shop` 六个货位里三个死（`end_rod` / `soul_lantern` / `copper_bulb`，`glowstone` 那个已由 §6.1 顺手解决），`redstone_shop` 六个里四个死（三件石英件 + 校准幽匿感测体）——两家正好是"灯饰店"和"红石商店"，主题自洽，但按门控算等于开张就有一半货架空着。对照之下 **`cellar` 全绿**（六件全是农田 / 牧场 / 熔炉产物），`smithy` 六件铁装全可挖，只是量大（`iron_chestplate` 512 metal）。

非堵但重：

- **smithy** 六件铁装：铁可挖，属于贵不属于堵，但 L5 就摆 `iron_chestplate` 512 / `iron_leggings` 448 这种量。
- **redstone_shop** 的 `sticky_piston`（黏液球，造价 150）与 `redstone_block`（红石 ×9，造价 108）——都要专门下一趟矿 / 蹲一趟沼泽。

修法可选项（留给定 5 级档时挑）：

| 做法 | 说明 | 代价 |
|---|---|---|
| **A 加进 `default_recipes.json`** | `glowstone` 的现成先例（§6.1），一行一个 id，不动商品表不动外观，货位立刻活 | 等于把该商品的门控整个拿掉——货架能自给自足，玩家不必先去下界/末地/晶洞 |
| **B 换商品** | 同主题换早期可得：`latern_shop` 的 `end_rod` → `torch` / `campfire`，`redstone_shop` 的三件石英件 → `redstone_lamp` / `note_block` 之类 | 商店主题变淡，且要重新配 comfort/magic/wonder 与造价 |
| **C 删商品** | 最省事 | 货位变少，商店价值下降；`redstone_shop` 六个删四个就只剩 `sticky_piston` / `redstone_block` |

A 与建材那条不同：建材删/换之后玩家照旧要自己攒料，商品走 A 则连料都不用挖，是**直接发**。所以若只想让货架"能补上"而不想削弱门控，选 B/C 更贴。

## 四、非堵但成本 / 获取重（可保留，改动前先权衡）

- **铁重**：`anvil` ×1（31 铁，映射 metal 1984）在 L1 **arrow_store**——开局 metal 3000，单这一项就吃掉约 2/3 金属预算；同档还有 `cauldron`（7 铁）、`chain` ×15（youth_hostel，首免）、`blast_furnace`、`smithing_table`。铁可挖，属于贵不属于堵，但 L1 的 arrow_store 值得单独看。
- **大批量染料 / 羊毛 / 石材**：luxury_hotel_cyan 的 `cyan_wool` ×442 + `cyan_concrete` ×359（青色 = 仙人掌绿 + 青金石，需沙漠与青金石矿）、luxury_hotel_green 的绿 / 黄绿陶瓦（同源仙人掌）、`smooth_sandstone` ×2670（海量沙子 + 烧炼）、`terracotta` 系（黏土）、`packed_mud` ×206。
- **地下洞穴系**：`moss_block` / `moss_carpet` / `flowering_azalea_leaves` / `rooted_dirt` / `hanging_roots` / `dripstone_block` / `amethyst_cluster`——都找得到，只是要碰运气。
- **海洋系**：`sea_pickle`、`dried_kelp_block`、`scaffolding`（竹子）。

## 五、附带发现（不属于本次修改范围，但影响修法选择）

1. **无元素映射 = 免费，且不参与解锁门控**。当前这类方块有一批：`wall_torch`、各类 `*_wall_banner`、`*_wall_sign`、全部 `potted_*`、`water`、`lava`、`bubble_column`、`tripwire`。同一栋建筑里 `torch` 要钱而 `wall_torch` 不要钱、`oak_sign` 要钱而 `oak_wall_sign` 不要钱，属于 `element_mappings` 的覆盖缺口。它同时意味着：**"删掉映射"是让某个方块免费的最短路径**——不建议用，但要知道这条路存在。
2. **氧化铜族与普通铜族元素价完全相同**（格栅 72 / 切割板 36 / 切割梯 108 metal）。所以"氧化铜 → 普通铜"的同形替换对造价零影响，唯一变化是颜色（青绿 → 铜橙）。
3. **`bell` 有映射（metal 2048）但原版没有配方**，首解锁只能靠村庄——"映射表里有价、游戏里没门路"的典型样本。
4. **`ice` 需精准采集**、`amethyst_cluster` 需精准采集、深板岩矿石需精准采集，这类"精准采集"门控在 L5 的 redstone_shop 与 L10 的 nodemetal / nodewater 上都有出现。

## 六、修法候选（三选一，待决策）

| 方案 | 做法 | 优点 | 代价 |
|---|---|---|---|
| **A 换方块** | 同形状换早期同类：氧化铜 → `copper_grate` / `cut_copper_slab` / `cut_copper_stairs`；`waxed_copper_bulb` / 蛙明灯 / `end_rod` → `lantern`（铁系，模组通用）；`polished_blackstone_button` → `stone_button`；竹子系 → 橡木同形（门 / 告示牌 / 活板门 / 栅栏）；`end_stone` → `smooth_sandstone` / `bone_block`；`dark_prismarine` → 同色石材；`deepslate_*_ore` → `raw_copper_block` / `raw_iron_block` | 直接消除堵点，改动全落在建筑 JSON 的 palette（替换 palette 项即整栋生效，`block_indices` 不动）；氧化铜一档造价零变化 | 少量配色变化（青绿→铜橙等）；约 15 栋建筑需要逐栋过 |
| **B 开合成配方** | 外观完全不动，给这批方块在 `craft_recipes/*.json` 加合成站配方（`min_colony_level` 定档、按元素付费） | 美术零改动；复用已有配方系统 | 缺料**自动**补料走的是工作站合成（`ResourceSupplySystem`），与此路不通，玩家仍需手动合成后入库；等于多一条并行通路 |
| **C 上调解锁等级** | 不换材质，把受堵建筑挪到有该材料的等级（mage_hut1 → 10、redstone_shop → 15 等） | 零美术改动 | L1 可选建筑变少，教学阶段内容被抽薄 |

**倾向**：以 A 为主（堵点实实在在卡在 1 级，而 1 级是教学阶段，不该靠"等铜氧化"或"翻图纸"过关）；`end_stone` / `dark_prismarine` 这类**主题自洽**的 L10 门控可以不动，或只保留 1~2 个作"去过末地/海底神殿"的纪念性建材；`redstone_shop` 建议单独决策（见 §3.2）。若舍不得氧化铜的青绿观感，可只对氧化铜一档改用 B。

### 6.1 已实施口径（2026-09-29，方案 A）

- **氧化铜**：全部建筑、全部氧化阶段（`exposed` / `weathered` / `oxidized`，含 `waxed_` 变体）一律剥掉氧化前缀 → 未氧化铜（`copper_grate` / `cut_copper_slab` / `cut_copper_stairs` / `copper_bulb`…）。L15/20 建筑原有的深浅铜色阶一并抹平。造价零变化（见 §五.2）。
- **`end_rod` / `*_froglight` / `beehive`**：只从 **1-5 级**建筑删除——craftstation1、bakery、flower_shop、mage_hut1、youth_hostel、hotel、latern_shop。L10 的七座节点建筑、三款蛙明灯路灯、养蜂人小屋、喷泉广场**原样保留**。
- **同形替换**：`polished_blackstone_bricks` → `deepslate_tiles`（祭坛）、`polished_blackstone_button` → `polished_deepslate_button`（竹厅）、`flowering_azalea_leaves` → `oak_leaves`（仓库）。
- **直接删除**：酒馆（tavern1）的 `sea_pickle` ×4；potion_store 的 `brewing_stand` ×4 / `soul_sand` ×3 / `nether_wart` ×3；potionstation1 的 `brewing_stand` ×1 / `soul_campfire` ×1。市政厅本就没有海泡菜（§3.1 表把两栋并作一行，`sea_pickle` ×4 全在酒馆）。
- **`glowstone` 加进 `default_recipes.json`**：酒馆 / 市政厅的荧石改为开局即可合成（§3.1），仍是唯一一项破例放出的下界物资。
- **本次未动**：hotel 的 `soul_sand` ×2、redstone_shop 整栋（§3.2 单独决策）、L10 的 `end_stone` ×165 / `dark_prismarine` / 节点 `end_rod`。

实现口径：只改 palette 条目文本，**`block_indices` 一律不动**——删掉的方块把 palette 项写成 `minecraft:air`，索引保持不变（物料统计 `computeMaterialCounts` 与放置本就跳过 air，`clear_and_build` 的整箱清空也用同一套 air 映射）；同时删掉落空位置残留的 `block_nbt`。

## 七、附录：L1-10 逐栋元素造价

单位为元素值，`[首免]` 表示该类型本镇第一栋免料。开局每元素 3000（合计 21000）可作 L1 标尺。

**本表是 2026-09-27 审计当天的快照，此后两条互不相干的线各自让它过时**：(1) §6.1 的建材替换 / 删除让相关建筑变便宜（craftstation1 1845→1464、potion_store 2681→2356、flower_shop 1683→1467、smithy 12539→11551 等）；(2) 当天的 `element_mappings` 重做（`be8509d5`）改了一批方块单价，与本次改动无关——七座节点一律 -520、signboard -8 即属此类。要最新数字就重跑 `python balance/extract_buildings.py`，本表不逐次回填。

| L1 | 造价 | L5 | 造价 | L10 | 造价 |
|---|---:|---|---:|---|---:|
| townhall1 [首免] | 20650 | smithy | 12539 | luxury_hotel_cyan | 37827 |
| youth_hostel [首免] | 8473 | hotel | 11817 | luxury_hotel_green | 30684 |
| bamboo_hall | 6725 | watch_tower | 11072 | beekeepers_house | 19832 |
| arrow_store | 4166 | windmill1 | 5490 | bamboo_house | 11161 |
| mage_hut1 | 4092 | redstone_shop | 4030 | fountain_plaza | 10370 |
| workstation1 [首免] | 3875 | latern_shop | 2773 | fishing_point | 3832 |
| book_shop | 3498 | cellar | 2001 | advanced_street_light1/2/3 | 约 2486 各 |
| tavern1 [首免] | 3080 | farm | 1954 | nodemetal | 1702 |
| warehouse1 [首免] | 2714 | | | nodewood | 1510 |
| potion_store | 2681 | | | nodefire / nodewind | 1486 |
| potionstation1 | 2237 | | | nodewater | 1483 |
| craftstation1 | 1845 | | | nodedark | 1438 |
| bakery [首免] | 1713 | | | nodeearth | 1402 |
| flower_shop | 1683 | | | | |
| signboard | 566 | | | | |
| altar1 | 299 | | | | |
| atm1 | 165 | | | | |
| street_light | 82 | | | | |
| long_chair | 21 | | | | |

L1 前五栋里有三栋含金属大户（workstation1 metal 3438、mage_hut1 metal 3076、arrow_store metal 2382）。开局 metal 3000 时 arrow_store 已能自行造出，workstation1 与 mage_hut1 仍差数百，需靠挖铁——这属于合理的早期行为，但可与 §四 一起看。

## 八、复现方式

```bash
# 造价（走仓库内已有的提取器，产 buildings_full.json / buildings.csv）
python balance/extract_buildings.py

# 堵点清单：join 建筑 palette × element_mappings
# 1) element_mappings/*.json 建 { 方块id: build_cost }，无映射即"免费方块"
# 2) 逐栋读 buildings/*.json，按 block_indices 聚合 palette（去 [state]）计数
# 3) 按 block_indices → palette → 方块 id 累加币值，并按 unlock_requirement.min_colony_level 过滤

# 商店商品（§3.4）：同一个 join，只是取 shop.goods[].item_id 而不是 palette
# 逐栋读 buildings/*.json，筛 unlock_requirement.min_colony_level <= 5 且有 shop 的，
# 列出 item_id → element_mappings 查 build_cost（全部有条目，所以只需再判解锁门控）
```

**原版配方别猜，直接读客户端 jar**：1.21.1 的原版数据都在
`~/.gradle/caches/neoformruntime/artifacts/minecraft_1.21.1_client.jar` 里，
`data/minecraft/recipe/*.json`（1290 条）就是逐条配方原文——`spectral_arrow` 要荧石粉、
`soul_lantern` 要灵魂火基底、`copper_bulb` 要烈焰棒、三件红石件要下界石英、
`calibrated_sculk_sensor` 要紫水晶+幽匿感测体、`nether_wart` **一条配方都没有**，都是这么核的
（一行 `python -c` 读 zip 即可，不必开游戏）。类行为仍走 [minecraft-source](../../.claude/skills/minecraft-source)。
本次已复核的关键事实：铜灯配方需烈焰棒、钟无配方只能村庄/交易取得、`wall_torch` 等在映射表中缺项。
