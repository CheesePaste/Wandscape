# integration/ — 第三方集成兼容

`src/main/java/com/wsteam/wandscape/integration/`

## 职责

承载与第三方模组/系统的可选集成。**统一收口**：未来每兼容一个第三方（REI/EMI/Curios/JourneyMap…）新增一个子包，避免往顶层堆包。

- 全部 **compileOnly** 依赖、**不打包**（mod jar 不内嵌）。无对应第三方时本包不加载、其余功能完全不受影响。
- 纯客户端显示，服务端零改动。
- 纯模型与收集逻辑设计为零第三方引用、可单测。

## 子包

| 子包 | 依赖 | 职责 |
|---|---|---|
| `jei/` | `mezz.jei`（compileOnly，flatDir `libs/`，19.27.0.336） | 在 JEI 物品配方视图顶部加模组标签页，展示元素合成/分解/法杖/药剂配方 |

## jei/ — 元素配方标签页

### 数据模型（纯逻辑，零 mezz 引用，可单测）

- `ElementRecipe` — record：`kind`（SYNTHESIZE/DECOMPOSE）、`stationKey`（workstation/crafting_station/magic_station）、`itemId`（存字符串，JEI 层再解析成 ItemStack）、`elements`（Map<ElementType,Long>：合成=成本，分解=完整价值）、`extraInputs`（药剂额外原料如玻璃瓶）、`value`（分解总价值）。
- `ElementRecipeKind` — SYNTHESIZE / DECOMPOSE。
- `ElementRecipeCollector` — 纯收集，四个来源：
  - `fromElementMappings`：元素映射 buildCost → 工作站「合成」+「分解」两条；跳过 disabled / 空 buildCost。
  - `fromCraftWandRecipes`：法杖配方 → 合成站「合成」（法杖不可分解）。
  - `fromBrewPotionRecipes`：药剂配方 → 合成站「合成」（带额外原料，随配方 craft_station=crafting_station，原酿造站归并）。
  - `fromCraftSpellRecipes`：魔法卷轴配方 → 魔法工坊「合成」。
  - `itemIdEquals`：忽略 `minecraft:` 前缀比较（与 `ProductionRecipeLoader.findSynthesizeRecipe` 一致）。

### JEI 层

- `WandscapeJeiPlugin` — `@JeiPlugin` + `IModPlugin`；注册单个 category + `addTypedRecipeManagerPlugin`（懒查询，随 `/reload` 刷新）。
- `ElementRecipeCategory` — 图标=法杖；布局：
  - 合成：左侧元素物品（带数量）→ 中间箭头（上方站名标签）→ 右侧物品。
  - 分解：左侧物品 → 中间箭头（上方站名标签，下方 ÷N）→ 右侧元素物品。
  - `draw()` 渲染站名标签与 `÷N`（配方本地坐标），元素槽显示实际数量。
- `ElementRecipeManagerPlugin` — `ISimpleRecipeManagerPlugin`，实时从数据加载器收集；聚焦物品列出其输入（被消耗）/输出（被产出）的全部配方；元素物品额外匹配消耗/产出它的配方。**全部查询 try-catch 兜底**，数据异常（如配方引用未注册物品 `mana_potion` 等）只记 `Log.warn` 并返回空，不崩客户端。

### 数据入口

| 数据 | 来源 |
|---|---|
| 元素合成/分解 | `Wandscape.ELEMENT_MAPPING_LOADER.getAllConfigs()` |
| 法杖合成 | `Wandscape.PRODUCTION_RECIPE_LOADER.getCraftWandRecipes()` |
| 药剂合成 | `Wandscape.PRODUCTION_RECIPE_LOADER.getPotionRecipes()` |
| 元素物品 | `Wandscape.ELEMENT_ITEMS` |
| 分解除数 | `Config.ELEMENT_DECOMPOSE_DIVISOR`（默认 5） |

### 依赖配置

- `build.gradle`：`compileOnly "mezz.jei:jei-1.21.1-neoforge:19.27.0.336"`（flatDir `libs/`）。**不得**放运行时 classpath（其 `jei`/`jei.neoforge` 双模块会 split-package 冲突）；本地调试把 jar 放 `run/mods/`。
- `neoforge.mods.toml`：`jei` 列为 optional 依赖，`side=CLIENT`。
- lang：`gui.wandscape.jei.*`（zh/en）。
