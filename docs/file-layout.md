# 文件落位总览（file-layout）

> 信息截至 2026-09-23 | mod_version 2.1.1 | 分支 1.21.1
>
> **【何时读】**：想知道"某个 JSON / 建筑 / 配置到底躺在哪个目录"，或准备搬动某个落盘位置（尤其是扫描器导出的建筑包）。
> **【不包含什么】**：每种 JSON 的字段含义与迁移纪律——那是 [data-formats.md](data-formats.md)；建筑 JSON 怎么建——那是 [domain-notes.md](domain-notes.md) 的建筑小节。

---

## 〇、先分清三层

找文件前先问"它在哪一层"，三层的读写权限和生命周期完全不同：

| 层 | 根路径 | 读写 | 生命周期 | 谁能改 |
|---|---|---|---|---|
| **A. 模组 jar 内** | `src/main/resources/`（构建后进 jar） | 只读 | 跟模组版本 | 只有改模组源码/重打包 |
| **B. 数据包** | `<world>/datapacks/<pack>/data/…` 或其它数据包 | 只读（游戏加载） | 跟存档／整合包 | 玩家、整合包、模组自己导出 |
| **C. 运行时目录** | `<gameDir>/config/…`、`<world>/…` | 读写 | 客户端／存档 | 模组运行时写 |

A 和 B 用的是**同一套加载器**：B 里放同 id 的文件就能覆盖 A（覆盖规则见 §二）。C 里的东西模组**不当作内容读**，是缓存、状态与导出产物。

---

## 一、A 层：模组 jar 内（只读）

### 1.1 `data/wandscape/` — 游戏数据（服务端+客户端都读）

| 路径 | 文件数 | 内容 | 登记在 |
|---|---|---|---|
| `data/wandscape/buildings/` | 56 | 内置建筑（`default` 包 53 个）+ `building_pack.json` + `deprecated/`（2） | `BuildingConfigLoader` |
| `data/wandscape/buildings/deprecated/` | 2 | 旧档兼容载荷，**不可删**（见 §四） | 同上 |
| `data/wandscape/element_mappings/` | 1188 | 单物品/方块元素定价，`minecraft_<id>.json` | `WandscapeDataLoader` |
| `data/wandscape/element_mappings/disabled/` | 1 | 停用映射的停放处（示例文件） | 不加载 |
| `data/wandscape/advancement/` | 33 | 成就树（含 `recipes/` 子目录） | 原版 advancement 加载 |
| `data/wandscape/craft_recipes/` | 32 | 合成站配方（法杖/权杖/戒指/卷轴/终端…） | `WandscapeDataLoader` |
| `data/wandscape/magic_spells/` | 10 | 魔法定义 | `SpellbookLoader` |
| `data/wandscape/magic_circles/` | 10 | 法阵视觉 spec | `MagicCircleLoader` |
| `data/wandscape/loot_table/blocks/` | 2 | 扫描器方块的掉落表 | 原版 loot 加载 |
| `data/wandscape/recipe/` | 2 | 原版工作台配方（扫描器、指南书） | 原版 recipe 加载 |
| `data/wandscape/damage_type/` | 2 | 自造伤害类型（`beam`/`melee`，不用原版 magic） | 原版 |
| `data/wandscape/tags/block/` | 1 | `custom_roads.json`——道路方块判定走标签 | `WandscapeTags` |
| `data/wandscape/tags/item/` | 1 | `mage_main_hand_tools.json` | 原版 |
| `data/wandscape/element_seeds.json` | 1 | **权威种子库**（元素定价的源头） | 审计/生成命令读 classpath |
| `data/wandscape/wandscape_balance.json` | 1 | 天平数值覆盖（可调常量的持久化入口） | `WandscapeBalanceLoader` |
| `data/wandscape/patchouli_books/guide/book.json` | 1 | 帕秋莉书本定义 | 帕秋莉 |

### 1.2 `assets/wandscape/` — 客户端资源

| 路径 | 文件数 | 内容 |
|---|---|---|
| `assets/wandscape/blockstates/` | 4 | 方块状态（含 `$name.json` 模板） |
| `assets/wandscape/models/` | 130 | 方块/物品模型 + 三个罗盘共 96 帧 `compass_frame/` |
| `assets/wandscape/particles/` | 2 | 粒子定义 |
| `assets/wandscape/textures/` | 189 | 贴图（含元素图标 `textures/gui/icons/element_*.png`） |
| `assets/wandscape/sounds.json` | 1 | 音效映射（逻辑 id → 文件） |
| `assets/wandscape/lang/` | 2 | `zh_cn.json` + `en_us.json`——**生成物**，改 `lang_src/` 后跑 `gen_lang.py`（见 §五） |
| `assets/wandscape/guidebook/en/` `zh_cn/` | 各 62 | 手册正文 **md 单源**，兜底屏直接读它 |
| `assets/wandscape/guidebook/runtime/` | 2 | 手册结构清单（分类/条目/《…》指向），`gen_patchouli.py` 生成，两侧渲染共读 |
| `assets/wandscape/patchouli_books/guide/` | 142 | 帕秋莉条目与分类 JSON（`<locale>/{categories,entries}/`），**生成物** |

### 1.3 根与杂项

| 路径 | 内容 |
|---|---|
| `wandscape.mixins.json` | mixin 配置（相机/存档/袭击） |
| `META-INF/accesstransformer.cfg` | AT |
| `logo.png` | 模组图标 |

---

## 二、B 层：数据包（可覆盖 A 层同 id 文件）

内容类 JSON 由 `WandscapeDataLoader` 统一扫描 **`data/<namespace>/<category>/*.json`**，每个 category 一个注册表。`/reload` 触发重扫。

已注册的 category：

| category | 解析器 | 备注 |
|---|---|---|
| `buildings` | `BuildingConfigLoader::loadFromDataPath` | 支持子目录 = 建筑包，见 §四 |
| `road_presets` | `RoadPresetLoader` | jar 内**没有**文件，默认路型在代码里（`RoadPreset.DEFAULT_PRESETS`） |
| `exploration_regions` | `ExplorationRegionConfig::fromJson` | jar 内**没有**文件，是给数据包写的"声明层"，压过世界里的生成层 |

另外还有一个不走 DataLoader 的 category 路径：`data/<ns>/blueprints/*.json`（旧的蓝图 DSL，已被 `BlueprintDefaults` 的 Java lambda 取代，仅注释里提到）。

**覆盖规则**（`WandscapeDataLoader.apply`）：id 取自文件名（剥掉命名空间）。跨命名空间撞 id 时 **`wandscape` 命名空间优先**，其余按 key 排序。想覆盖模组内置内容，需要把文件放进**同优先级或更高优先级**的数据包里。

---

## 三、C 层：运行时目录（模组写盘的地方）

### 3.1 跟存档走（`<world>/…`）

| 路径 | 内容 | 写入者 |
|---|---|---|
| `<world>/datapacks/wandscape_builds/` | 建筑导出包：`pack.mcmeta` + `data/wandscape/buildings/custom/<id>.json` | `ScannerExportPacket` 导出；`ScannerExportDirs.ensureSkeleton` 建骨架 |
| `<world>/datapacks/wandscape_roads/` | 道路导出包：`pack.mcmeta` + `data/wandscape/road_presets/*.json` | 同上 |
| `<world>/wandscape/generated_regions/*.json` | 一次性生成的探索区域定价（当前世界实测约 177 个） | `ExplorationRegionGenerator` |
| `<world>/data/wandscape_*.dat` | 15 个 `SavedData`（见下表） | 各域 |
| `<world>/data/wandscape_*.dat` | 15 个 `SavedData`（见下表） | 各域 |

`SavedData` 全清单（都在 `<world>/data/` 下，压缩 NBT）：

| 文件 | 存什么 |
|---|---|
| `wandscape_colonies.dat` | 殖民地主体（另有强制落盘逻辑 `ColonySavedData.saveNow`） |
| `wandscape_colony_levels.dat` | 殖民等级/经验 |
| `wandscape_colony_items.dat` | 殖民地物品银行 |
| `wandscape_buildings.dat` | 已建建筑实例 |
| `wandscape_chunk_leases.dat` | 区块租约 |
| `wandscape_altar_casts.dat` | 祭坛施法状态 |
| `wandscape_roads.dat` | 路网 |
| `wandscape_tasks.dat` | 任务池 |
| `wandscape_statistics.dat` | 统计 |
| `wandscape_npc_deaths.dat` | NPC 死亡登记 |
| `wandscape_oath_rings.dat` | 誓约之戒 |
| `wandscape_scepter_marks.dat` | 权杖标记 |
| `wandscape_tavern_recruits.dat` | 旅店招募 |
| `wandscape_tourist_sim.dat` | 游客模拟 |
| `wandscape_tutorial_progress.dat` | 新手引导进度 |

### 3.2 跟客户端/gameDir 走（`<gameDir>/…`）

| 路径 | 内容 | 写入者 |
|---|---|---|
| `config/wandscape/previews/` | 建筑预览的 PNG 帧缓存（按 `名字_hash_帧号.png`） | `BuildingPreviewGifCache` |
| `config/wandscape/scanner_presets/` | 扫描器客户端预设，每个一个 `.nbt` | `ScannerPresetStore` |
| `config/wandscape/splines/` | 道路样条模板 JSON（道路工作室导出） | `SplineEditorClientState` |
| `config/wandscape-common.toml` | 通用配置（含建筑包启停，见 §四） | NeoForge |
| `config/wandscape-client.toml` | 客户端配置 | NeoForge |
| `config/wandscape-logging.properties` | 日志级别配置 | `LogConfig`（可手工编辑） |

### 3.3 遗留目录（**当前代码不再写入**，见到可删）

`run/wandscape/`（`previews/` + `scanner_presets/`）是统一进 `config/wandscape/` 之前的旧落点，见提交 `07887bfe`。`run/wandscape_buildings/` 同理，是扫描器还写游戏根目录那个年代的残留。

---

## 四、建筑包专章（"建筑包跟存档走"的那一份）

### 4.1 一个"建筑包"是什么

`BuildingPackage` = **`buildings/` 下的一个子目录**，特征是目录里有 `building_pack.json`：

```
data/wandscape/buildings/
├── building_pack.json        <- 根包（default）的元数据
├── tavern1.json              <- 根包里的建筑
├── deprecated/               <- 特殊：旧档兼容，不算建筑包
│   ├── README.md
│   └── service_hall.json
└── <你的包名>/
    ├── building_pack.json    <- 包元数据：id/name/description/author/version/icon/priority
    └── <建筑id>.json
```

`building_pack.json` 字段含义见 [data-formats.md](data-formats.md) §三。文件名收在 `BuildingPackage.FILE_NAME` 一处，读写两处都引用它。`name`/`description` 里允许放 lang key（内置的 `default`、`custom` 都这么干），界面走 `I18n` 解析，中英各显示各的。

### 4.2 三个来源，一套加载

| 来源 | 物理位置 | 出现时机 |
|---|---|---|
| 内置 `default` 包 | jar 内 `data/wandscape/buildings/`（53 个） | 永远 |
| 数据包加的包 | 任意数据包 `data/<ns>/buildings/<pkg>/` | 加载数据包时 |
| **扫描器导出** | **`<world>/datapacks/wandscape_builds/data/wandscape/buildings/custom/`** | 玩家在游戏里点导出时，即时写盘并**同时**注册进内存（不必 `/reload` 就能用） |

导出默认落进 **`custom`（自定义）包**——`default` 是随 jar 发布的核心包，玩家不该往里写。扫描器界面上的「包」输入框与下拉循环按钮仍可改成别的包（整合包作者要往自建包里导出），只是默认值不再是 `default`。

路径本身收在 `ScannerExportDirs`（`content/building/scanner/`）一处，导出与建骨架共用，不再散在导出包里。选"世界数据包"这条路的理由是：世界数据包每次启动都会自动加载，退出重进后导出的建筑还在；写源码目录在开发环境会被 `build/resources/main` 盖掉。

### 4.3 启动时自动建的空骨架

服务器每次启动（`Wandscape.onServerStarting` → `ScannerExportDirs.ensureSkeleton`）都会把两个导出包的骨架建出来，**哪怕一条内容都没有**：

```
<world>/datapacks/
├── wandscape_builds/
│   ├── pack.mcmeta
│   └── data/wandscape/buildings/
│       └── custom/
│           └── building_pack.json  <- 自定义包元数据，可直接照抄当模板
└── wandscape_roads/
    ├── pack.mcmeta
    └── data/wandscape/road_presets/
```

这样玩家进存档就能看见建筑/道路该往哪儿放，不必猜路径。**幂等**：已存在的文件一律不碰，玩家改过的 `building_pack.json` 不会被冲掉；任何失败只 `Log.warn`，不影响服务器启动。

`custom` 包在内存里还有一层兜底（`BuildingConfigLoader.ensureCorePackages`）——即使本次启动刚建的 `building_pack.json` 要等下次重载才被数据包读进来，「自定义」也始终出现在建筑包列表里。

### 4.4 旧档兼容：`deprecated/`

`data/wandscape/buildings/deprecated/` 里的建筑按**内层 `id`** 解析（不按文件名/包名），旧存档里引用的建筑靠它加载。**目录被删过多次，删即断旧档**。

### 4.5 停用某个包

`config/wandscape-common.toml` 的 `building.disabledPackages`（默认空 = 全开）。停用的包**不在建造栏显示**，但世界里已建成的建筑不受影响。同一个开关也有 UI 入口（设置面板）。

---

## 五、生成器脚本 → 产物（仓库根）

| 脚本 | 输入 | 输出 |
|---|---|---|
| `gen_lang.py` | `lang_src/` | `assets/wandscape/lang/zh_cn.json` + `en_us.json`（编译期校验中英齐全/占位符对齐） |
| `gen_patchouli.py` | `assets/wandscape/guidebook/<locale>/*.md` | `assets/wandscape/patchouli_books/guide/**` + `assets/wandscape/guidebook/runtime/<locale>.json` |
| `paginate_patchouli_json.py` | 帕秋莉条目 JSON | 分页后写回 |
| `strip_building_ground.py` / `gen_icons.py` / `fix_icons.py` | 美术/建筑素材 | 贴图与建筑 JSON |

游戏内命令 `/wandscape generate_element_mappings` 是**开发期命令**：它从 classpath 读 `element_seeds.json`，推算元素定价，**直接写回** `../src/main/resources/data/wandscape/element_mappings/`（靠 `serverDir/..` 反推仓库根）。生产环境的服务目录没有 `src/`，所以这条命令只在开发环境有意义。

---

## 六、快查表：我要找的东西在哪

| 我想找… | 去这里 |
|---|---|
| 建筑定义 | jar `data/wandscape/buildings/*.json`；导出过的在 `<world>/datapacks/wandscape_builds/…` |
| 物品元素定价 | `data/wandscape/element_mappings/minecraft_*.json`，种子在 `element_seeds.json` |
| 数值常量 | 先查 [balance-baseline.md](balance-baseline.md)，覆盖入口是 `data/wandscape/wandscape_balance.json` |
| 手册正文 | `assets/wandscape/guidebook/<locale>/*.md`（改 md，别直接改帕秋莉 JSON） |
| 上屏文案 | `lang_src/`（改这里，别直接改 `lang/*.json`） |
| 玩家设置 | `<gameDir>/config/wandscape-common.toml`、`wandscape-client.toml` |
| 存档状态 | `<world>/data/wandscape_*.dat` |
| 建筑预览缓存 | `<gameDir>/config/wandscape/previews/`（删了会自动重建） |
