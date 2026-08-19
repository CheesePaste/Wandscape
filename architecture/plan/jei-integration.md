# 1.9 — JEI 兼容：元素合成/分解配方标签页 计划

> 状态：待实施（2026-08-16）
> JEI 版本：`jei-1.21.1-neoforge-19.27.0.336`（已下载，jar 在 `tmp/JEI/`，解压版在 `tmp/JEI/unpacked/`）
> 目标版本：并入 1.9（与移除 ImGui 同属 1.9 改进）

## 背景与目标

在 JEI 的物品配方视图（点击物品弹出的合成配方 UI）顶部新增一个**印有法杖图标的模组标签页**，点开显示该物品的**元素合成 / 元素分解**配方，让玩家不离开 JEI 就能查"做这个要哪些元素、拆这个能得哪些元素"。

## 决策记录（2026-08-16）

- **JEI 是可选运行时依赖，不打包**：用 `compileOnly`（flatDir 指向本地 jar），mod jar 不内嵌 JEI。玩家自行装 JEI。JEI 相关代码全部隔离在新建 `jei/` 包，`Wandscape.java` 等核心类零 JEI 引用 → 无 JEI 时其余功能不受影响、不崩溃。
- **一个标签页、两种配方**：注册**单个** recipe category「Wandscape 元素」，图标 = 法杖（`Wandscape.WAND`）。同一 category 里 `ElementRecipe` 用 `kind` 区分 `SYNTHESIZE`（合成）/ `DECOMPOSE`（分解）。聚焦某物品时列表恰好显示它的 合成+分解 两条。
- **元素用元素物品（`ElementItem`）当 JEI 原料**：`Wandscape.ELEMENT_ITEMS` 已注册 7 种元素物品（代码注释本就写"供 JEI/配方展示"）。合成/分解配方直接以这些物品为 input/output → 点元素物品也能看"它被哪些配方用/产生"，无需自定义 JEI 原料类型。
- **配方动态生成 + 随 `/reload` 刷新**：元素映射是数据驱动 JSON。用 `IAdvancedRegistration.addTypedRecipeManagerPlugin` + `ISimpleRecipeManagerPlugin`（懒查询）而非一次性 `registerRecipes`，保证 `/reload` 后配方同步。
- **分解产出 = 元素价值 ÷ `ELEMENT_DECOMPOSE_DIVISOR`（默认 5）**：与工作站分解、商店售卖的取数完全一致（`getItemElementValue` = `build_cost`）。JEI 槽位需要整数数量，分解产出为分数 → 展示形式见「开放决策」。

## 现状盘点：数据入口（均已存在，直接复用）

| 数据 | 来源 | 说明 |
|---|---|---|
| 合成配方 | `Wandscape.PRODUCTION_RECIPE_LOADER.getSynthesizeRecipe(id)` / `getAllSynthesizeRecipes()` | `SynthesizeRecipe(id, outputItem, cost: Map<ElementType,Long>, unlock)`，来自 `ElementMappingConfig.buildCost` |
| 分解价值 | `Wandscape.ELEMENT_MAPPING_LOADER.getItemElementValue(itemId)` | = `build_cost`；分解产出 = value ÷ divisor（`Config.ELEMENT_DECOMPOSE_DIVISOR`） |
| 元素物品 | `Wandscape.ELEMENT_ITEMS`（`Map<ElementType, DeferredItem<Item>>`） | 7 元素，JEI 原料用 `new ItemStack(item)` |
| 法杖图标 | `Wandscape.WAND` | `IGuiHelper.createDrawableItemStack(new ItemStack(WAND.get()))` → 标签页图标 |
| 语言键 | lang `gui.wandscape.*` / `element.wandscape.*` | 新增 `gui.wandscape.jei.*`（category 标题/工具提示） |

## 实施步骤

### 1. build.gradle：JEI 作为 compileOnly（离线 flatDir）

1. 复制 jar 到 `libs/jei-1.21.1-neoforge-19.27.0.336.jar`（原文件名含中文方括号 `[JEI物品管理器]…`，Gradle flatDir 无法按坐标匹配，必须重命名）。
2. `repositories { flatDir { dirs 'libs' } }`（或指向 `tmp/JEI`）。
3. `compileOnly "mezz.jei:jei-1.21.1-neoforge:19.27.0.336"`。
4. **不**加 jarJar / additionalRuntimeClasspath（不内嵌）。
5. `mods.toml`：`jei` 列为可选依赖（仅展示，不强制）。
   - 备选（需联网）：用 JEI Maven 的 `jei-1.21.1-neoforge-api` 构件，更干净；离线场景用 flatDir。

### 2. 新建 `com.wsteam.wandscape.jei` 包（全部 JEI 相关代码收口于此）

- `ElementRecipe.java` — **纯模型（零 mezz 引用，可单测）**：
  ```java
  public enum ElementRecipeKind { SYNTHESIZE, DECOMPOSE }
  public record ElementRecipe(String id, ElementRecipeKind kind,
                              String itemId, Map<ElementType, Long> elements) {}
  ```
  `itemId` 存字符串，JEI 层再解析成 `ItemStack`，保持模型纯逻辑可测。
- `ElementRecipeCollector.java` — **纯收集（零 mezz 引用，可单测）**：输入 `Collection<ElementMappingConfig>` → `List<ElementRecipe>`。
  - SYNTHESIZE：`buildCost` 非空 → `(itemId, SYNTHESIZE, cost)`。
  - DECOMPOSE：`buildCost` 非空 → `(itemId, DECOMPOSE, value/divisor 各元素)`；跳过 `disabled`、跳过 `buildCost` 为空。
  - 与 `ProductionRecipeLoader.findSynthesizeRecipe` 的去 `minecraft:` 前缀匹配逻辑保持一致。
- `WandscapeJeiPlugin.java` — `@JeiPlugin` + `IModPlugin`：
  - `getPluginUid()` → `ResourceLocation.fromNamespaceAndPath(MODID, "jei_plugin")`。
  - `registerCategories(IRecipeCategoryRegistration)` → `addRecipeCategories(new ElementRecipeCategory(helpers.getGuiHelper()))`。
  - `registerAdvanced(IAdvancedRegistration)` → `addTypedRecipeManagerPlugin(RecipeType, collector 实现的 ISimpleRecipeManagerPlugin)`（懒查询，支持 `/reload`）。
  - `registerRecipes` 不注册（配方全走懒查询）。
- `ElementRecipeCategory.java` — `IRecipeCategory<ElementRecipe>`：
  - `getRecipeType()` → `RecipeType.create("wandscape", "element", ElementRecipe.class)`。
  - `getTitle()` → `I18n.name("gui.wandscape.jei.title", "Wandscape 元素")`。
  - `getIcon()` → `guiHelper.createDrawableItemStack(new ItemStack(Wandscape.WAND.get()))`（法杖标签页图标）。
  - `getBackground()` / `getWidth()/getHeight()` → 固定尺寸（如 116×56）。
  - `setRecipe(IRecipeLayoutBuilder builder, ElementRecipe recipe, IFocusGroup focuses)`：
    - SYNTHESIZE：左 = 元素 input 槽（`addInputSlot`，元素物品 × count），中 = 箭头，右 = `addOutputSlot(itemStack)`，上方文字「元素合成」。
    - DECOMPOSE：左 = `addInputSlot(itemStack)`，箭头标签「分解 ÷N」，右 = 元素 output 槽，上方文字「元素分解」。
    - 槽位 `setStandardSlotBackground()` / `setOutputSlotBackground()` + `addTooltipCallback`（工具提示写各元素数量与分解公式）。

### 3. 动态刷新（`/reload` 正确性）

`ISimpleRecipeManagerPlugin<ElementRecipe>` 的 5 个方法直接实时从 `Wandscape.PRODUCTION_RECIPE_LOADER` / `ELEMENT_MAPPING_LOADER` 收集：
- `isHandledInput/Output` → 判定原料是元素物品或本模组物品。
- `getRecipesForInput/Output` → 聚焦物品时返回它的 合成/分解 配方（懒收集，天然支持 datapack reload）。
- `getAllRecipes` → 全部配方（JEI 配方总览用）。

### 4. 文档 + 版本

- `docs/architecture.md` 包地图 + `architecture/packages/` 新增 `jei/` 条目。
- `docs/decisions.md` 新增 2026-08-16 决策（JEI 可选集成、懒查询配方、分解÷N 展示）。
- lang：新增 `gui.wandscape.jei.*`（en/zh）。
- 版本：并入 1.9，最终发布时 `mod_version` → 1.9.0（与 ImGui 移除合并）。

## 测试计划

- **纯逻辑单测**（无 JEI/MC 运行时）：
  - `ElementRecipeCollectorTest`：给定 `ElementMappingConfig` 列表 → 断言 SYNTHESIZE/DECOMPOSE 生成、`disabled`/空 `buildCost` 跳过、`minecraft:` 前缀匹配、分解 ÷divisor 数值正确。
- `./gradlew build` 编译通过（JEI 在 compileOnly 上）。
- `./gradlew test` 全绿。
- **手动验证**（用户运行，装 JEI）：
  1. JEI 点物品 → 顶部出现法杖标签页 → 点开看到 合成+分解 两条。
  2. 合成条：显示元素 input → 物品 output，数量正确。
  3. 分解条：显示物品 input → 元素 output，数量 = 价值 ÷5。
  4. JEI 点元素物品 → 看到"用它合成的物品"与"分解出它的物品"。
  5. `/reload` 后改 element_mappings.json → 配方同步刷新。
  6. 不装 JEI 启动 → 模组其余功能正常、无崩溃/报错。

## 风险与注意

- **分解产出分数 → JEI 整数槽位**：见「开放决策」，展示形式需用户拍板。
- **懒查询插件与 JEI 版本兼容**：`addTypedRecipeManagerPlugin` 是 19.x 新 API；实现时以解压 jar 的 javap 签名为准（已完成核对）。
- **`libs/` flatDir 坐标匹配**：jar 必须重命名（去中文方括号），否则 Gradle 解析失败。
- **不打包 JEI**：mod jar 体积与 CurseForge 审核不受影响（纯 class，无 native）。
- 服务端零改动（纯客户端显示）。

## 已确认决策（2026-08-16）

1. **分解产出展示 = 显示价值 + ÷5 标注（用户选定方案 A）**：output 槽数量 = 完整元素价值（整数），箭头/工具提示标注「分解产出 = 价值 ÷ 5」，每个槽 tooltip 写精确分数产出（与工作站 UI 的 `xY.Z` 一致）。
2. **元素物品出现在 JEI 原料区**：配方用元素物品做 input/output 后天然出现，无需额外处理。
