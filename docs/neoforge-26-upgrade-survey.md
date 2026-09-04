# NeoForge 26.1 升级考察报告（neoforge-26-upgrade-survey）

> 信息截至 2026-09-04 | 基线：分支 `1.21.1`（Minecraft 1.21.1 / NeoForge 21.1.233）
> 目标：**Minecraft 26.1 / NeoForge `26.1.0.N`**（NeoForge 版本改为四段式对齐 MC 版本）
> **性质：长期可行性考察，不是升级实施计划**。契约差异由 NeoForge 官方 docs（1.21.1 vs 26.1）逐接缝 diff 得出，落盘在 `_refs/261/`（本文 + 下载材料，供后续升级复用）。

- **【何时读】**：评估升级到 26.1 的体量、划分「可脚本化的改名」与「需重写的深水区」、规划升级顺序时。
- **【不包含什么】**：分步步骤、分支/标签编排、里程碑排期——那是动手时的活。

> 阅读提示：本文大量术语变化来自 26.1 的官方契约。凡出现 `X→Y` 即「1.21.1 写法 → 26.1 写法」。

---

## 一、结论速览（TL;DR）

- **先澄清认知**：NeoForge 26.1 = **Minecraft 26.1**。Minecraft 已从 `1.21.x` 改为**年次版本线**（26.x），NeoForge 版本也因此改为四段式（`26.1.0.<N>`）。所以这不是「1.21.1 → 1.21.2」的小跳，而是**跨了整条版本线、约 8–11 个中间 MC 版本**的一次性深度迁移。
- **体量**：单 loader（NeoForge）不变，没有平台切换；但**契约变化是代码级的、渗透全库**。与「fabric 移植」不同——升级 26.1 不换 loader，而是**Vanilla + NeoForge API 的全面换血**。
- **两条工作带**：
  - **一级：可脚本化/机械改名**，量大但安全到位：`ResourceLocation→Identifier`、`BuiltInRegistries.get→getValue`、`DeferredRegister.create` 参数换序、`DeferredHolder→Supplier`、`register(name→registryName -> new ...)`、`MenuType` 的 `FeatureFlags.VANILLA_SET→DEFAULT_FLAGS`、`InteractionResult.sidedSuccess→SUCCESS`、`FMLEnvironment.dist→getDist`、`IFluidHandler/IEnergyStorage/IItemHandler→ResourceHandler/EnergyHandler` 命名族等。约占改动面的 **六成**。
  - **二级：需重写的深水区**，无法靠枚举编译错误顶过去，是**真实工作量主体**：
    1. **GUI 渲染层**——`GuiGraphics→GuiGraphicsExtractor` +「提交阶段/渲染阶段」双态渲染状态机（`GuiRenderState`/`GuiElementRenderState`/`GuiItemRenderState`/`GuiTextRenderState`/`PictureInPictureRenderState`），`Screen#render→#extractRenderState`、`renderBg→extractBackground`、`renderLabels→extractLabels`、`Renderable#render→#extractRenderState`、`RenderType→RenderPipeline/TextureSetup/RenderPipelines`、`PoseStack→Matrix3x2fStack`（GUI 侧 2D 矩阵）、`blit/fill/drawString/drawCenteredString/drawWordWrap/hLine/vLine/fillGradient` 一系列**改名 + 参数顺序/几何反转**。而本模组是 UI 重度：**30+ 屏/浮层**（每建筑屏、仓库、面板、任务浮层、道路工作室、gizmo、guide/指南书/markdown 框架、投影/扫描/俯瞰控制器）、大量自绘 `blit`/文本/工具提示。**这是最大的成本中心**。
    2. **实体/BER 渲染器**——`EntityRenderer<Entity>` → `EntityRenderer<Entity, EntityRenderState>`，`render()` 拆成 `createRenderState/extractRenderState/submit`；渲染层改 `RenderLayer<State,Model>` + `Model`/`EntityModel<State>` + `LayerDefinition`/`ModelLayerLocation` + `RegisterRenderStateModifiersEvent`；`EntityModel#setupAnim(RenderState)`；连 `Camera` 都被 `CameraRenderState` 取代。模组 2 个实体渲染器（WandscapeNpc / Tourist）+ 若干建筑幽灵投射渲染需按此重写；且 `MixinOverviewCamera/MixinSplineEditorCamera` 注入的 `Camera#setup` 目标可能不再存在。
    3. **物品/数据组件语义**——`Item`/`Block`/`Potion` 构造改收注册名（`registryName -> new ...`），`Item.Properties#delayedComponent`，数据组件原型从 ItemStack 侧移到 `Holder<Item>` 侧（`registryHolder().components()`），`ModifyDefaultComponentsEvent` 用置 `null` 表示移除。涉及 item 域（法杖/权杖/戒指/指南书/终端）与 `ModifyDefaultComponentsEvent`→`set(null)` 语义。
    4. **数据附件**——`INBTSerializable→ValueIOSerializable`、`.serialize(Codec)→map Codec`、**新增内置同步**（`AttachmentType#sync(...)`/`AttachmentHolder#getData/setData/removeData/syncData`，取代手动 `ChunkWatchEvent.Sent`）、level 也可挂附件、`copyOnDeath` 移入 builder。仓库/殖民地用 chunk/entity 附件、NPC 的 MagicState/EquippedMagicComponent 等全部需改。
    5. **网络**——服务端发包改 `ClientPacketDistributor.sendToServer`（231 处），**客户端收包 handler 迁移到独立 client-only 事件 `RegisterClientPayloadHandlersEvent`**（带 `HandlerThread`），`IPayloadContext` 收包上下文/签名可能变化。Wandscape 的 80 载荷、handler 两套并存将被推到收敛。
- **整体难度评级：高（体量大、且 GUI/渲染是重写而非平移）**。工具链与全局改名是「先苦后甜」的第一关；真正磨人的是 GUI 渲染层与实体渲染状态机的重构。相对而言**比 fabric 移植的工作量更大**（fab 那次是「loader 映射 + 少数深缝」，这次是「同 loader 但 API 全换血」），尤其对本 UI 重度项目尤甚——详见 §十 对比。

---

## 二、先决：版本线、工具链与升级路径

| 项 | 1.21.1 现状 | 26.1 目标 | 影响 |
|---|---|---|---|
| Minecraft | 1.21.1 | **26.1**（年次版本线） | 版本号、asset/data 命名空间不变，但语义按新线 |
| NeoForge 版本 | 21.1.233 | **`26.1.0.<N>`**（四段式对齐 MC） | `neo_version` / `neoform_version` / `minecraft_version` |
| 混淆 | 有（Mojang runtime 混淆） | **移除混淆**，官方参数名可用，NeoForm 简化（fuzzy patches） | `Parchment` 可移除（可留 javadoc）；`gradle.properties` 简化 |
| JDK | 21 | **25** | GC、工具链、CI 镜像全换 |
| Gradle | 项目当前版 | **9.1.0+** | wrapper、插件兼容 |
| 构建插件 | ModDevGradle（或 NeoGradle） | **MDG ≥2.0.141 / NG ≥7.1.21** | 配置 DSL |
| Loader | FancyModLoader 2.x 时代 | **FancyModLoader 7.0.x** | mods.toml / `@Mod` / 事件总线注册等元层变化（见 `_refs/261/loader-7.0.4-changelog.txt`） |

**升级路径**：NeoForge 官方 `PORTING.md` 面向的是「NeoForge 本体」移植（NeoForm + patches + rejects-*），对**mod** 的启发是：没有「1.21.1→26.1」的分步迁移文档，只能**一次性跳到 26.1，靠编译错误枚举 + 逐缝对照**。由于本模组已删 datagen / GameTest 运行配置（`CLAUDE.md`），datagen 变化不咬人；数据资产（1353 个 data json、193 纹理、lang/指南书）**零 loader 依赖，原样可用**（JSON 里的 id 仍是字符串，不受 `ResourceLocation→Identifier` 影响）。

---

## 三、一级改动：可脚本化的改名与签名（量大但安全，约六成工作量）

| 类别 | 1.21.1 | 26.1 | 影响面 |
|---|---|---|---|
| 资源定位 | `ResourceLocation` / `ResourceLocation.fromNamespaceAndPath` | **`Identifier`** / `Identifier.fromNamespaceAndPath` | **全库改名**（所有 packet TYPE、注册名、纹理/模型/音效 id、key binding 分类、`ResourceKey.createRegistryKey` 等；`ResourceLocation::toString→Identifier::toString`） |
| 注册表查找 | `BuiltInRegistries.X.get(id)` | **`BuiltInRegistries.X.getValue(id)`** | 全局 |
| DeferredRegister 创建 | `DeferredRegister.create("modid", KEY)` | **`DeferredRegister.create(KEY, "modid")`** | 13 处 |
| 注册返回值 | `DeferredHolder<R,T>` | **`Supplier<T>`** | 全部字段类型声明 |
| 注册重载 | `register(name, () -> new X(...))` | 新增重载 `register(name, registryName -> new X(...))`（对象构造收注册名，见 §四） | 物件构造 |
| MenuType | `FeatureFlags.VANILLA_SET` | **`FeatureFlags.DEFAULT_FLAGS`** | 3+1 菜单 |
| 交互返回 | `InteractionResult.sidedSuccess(...)` | **`InteractionResult.SUCCESS`**（`useWithoutItem`/`use`） | scepter/building 交互 handler |
| 判端 | `FMLEnvironment.dist` | **`FMLEnvironment.getDist()`** | 散落判端 |
| 容器/能力 | `Container`、`IItemHandler`、`ItemStackHandler`、`IFluidHandler`、`IEnergyStorage` | **`ItemStacksResourceHandler` / `ResourceHandler<ItemResource>` / `ResourceHandler<FluidResource>` / `EnergyHandler`**；`Capabilities.ItemHandler.BLOCK→Capabilities.Item.BLOCK`，`registerItem(...)→Capabilities.Fluid.ITEM＋ItemAccess`，bucket→`BucketResourceHandler` | 仓库/compat/槽位 |
| 玩家背包 | `armor`/`offhand` 两个 `NonNullList` | **`items`/`equipment`＋`Inventory#EQUIPMENT_SLOT_MAPPING`** | NPC 装备读取、`hurtArmor` |
| 菜单同步 | `SlotItemHandler` | **`ResourceHandlerSlot`**（写回需 `dataInventory::set`） | Npc/Warehouse 菜单 |
| 数据组件默认 | `Item` 上 `Properties#component` | `Item`/`Holder` 上 `#delayedComponent`，`registryHolder().components()` | item 域 |
| 附件序列化 | `INBTSerializable` / `serialize(Codec)` | **`ValueIOSerializable`** / `serialize(map Codec)`（`Codec.INT.fieldOf("mana")`） | 附件 |
| `ModifyDefaultComponentsEvent` | `builder.remove(...)` | **`builder.set(..., null)`** | 移除组件语义 |

---

## 四、二级深水区：需重写（真实成本主体）

### 4.1 GUI 渲染层（成本：**最高**）
26.1 把 GUI 从「即时画」改成「**提交阶段（收集）→ 渲染阶段（回放）**」的渲染状态机：

- `GuiGraphics` → **`GuiGraphicsExtractor`**；绘图从「直接画」变为「往 `GuiRenderState` 提交元素」（`GuiElementRenderState` / `GuiItemRenderState` / `GuiTextRenderState` / `PictureInPictureRenderState`）。
- `Screen#render` → **`Screen#extractRenderState`**（还有 `extractBackground`/`extractContents`/`extractLabels`/`extractTooltip`，按 strata 提交）；`Renderable#render` → **`#extractRenderState`**。
- `AbstractContainerScreen` 构造从 5 参（含背景宽高）改为 **3 参**（menu/inventory/title，背景默认 176×166），`imageWidth`/`imageHeight`/`leftPos`/`topPos` 在 `init` 里设；`renderBg→extractBackground`、`renderLabels→extractLabels`、tooltip 归 hoverable stratum。
- 绘图方法改名/换参：`drawString→text(+boolean 阴影)`、`drawCenteredString→centeredText`、`drawWordWrap→textWithWordWrap`、`hLine/vLine→horizontalLine/verticalLine`、`fill->fill(可带 RenderType→RenderPipeline/TextureSetup)`、`fillGradient` **参数顺序反转**、`blit` 签名大幅扩展（含 tint、png 尺寸），`blitSprite` 分散细节变化。
- GUI 用 `PoseStack`（3D） → **`Matrix3x2fStack`**：`graphics.pose().pushMatrix()/translate/rotate/scale/popMatrix()`；`peekScissorStack`/`enableScissor/disableScissor` + `ScreenArea#bounds` 决定节点归属与裁剪。
- `RenderType` → **`RenderPipeline`**（`RenderPipelines.GUI/GUI_TEXTURED/...`）+ `TextureSetup`；顶点 `addVertexWith2DPose(...)`。

**撞上的类**（全是本模组 UI 骨架，`foundation/ui/` 全树 + 各域 client 屏）：WarehouseScreen、TownHallScreen、TavernScreen、ShopScreen、NodeScreen、MageHutScreen、AltarScreen、ConstructionSiteScreen、TownHallCreateScreen、NpcScreen、NpcStrategyScreen、NpcCuriosScreen、CreativeScannerScreen、WorkstationScreen、MagicStationScreen、CraftingStationScreen，以及 PanelController/Overlay、TaskManagementOverlay、RoadStudioOverlay、BuildingDebugOverlay、guide/guidebook/markdown/panel/theme/skin/vanilla/guidance/tutorial（foundation/ui 子包太多）+ 各 gizmo/controller/渲染器（SplineEditorRenderer、RoadPlacementRenderer、RoadConstructionGhost、BuildGizmoRenderer、ScannerGizmoRenderer、ProjectionRenderer、BuildingGhostRenderer、BuildingAreaRenderer、ConstructionGhostRenderer、OverviewRenderer、TouristDebugRenderer、BuildingPreviewGifCache、WandscapeHighlightRenderer）。**约 30–40 个客户端文件**，且是「重写表达方式」而非逐个 `blit` 改名——是 26.1 升级的**最大单一成本**。

### 4.2 实体 / 世界渲染器（成本：高）
- `EntityRenderer<Entity>` → **`EntityRenderer<Entity, EntityRenderState>`**；`render()` 拆为 `createRenderState()` + `extractRenderState(entity, state, partialTick)` + `submit(state, PoseStack, SubmitNodeCollector, CameraRenderState)`；`getTextureLocation(EntityRenderState)→Identifier`。
- 渲染层：`RenderLayer<State, Model>`（`submit`）+ `Model` 类；`EntityModel<State>`（`setupAnim(RenderState)`）；`LayerDefinition`/`ModelLayerLocation`；`EntityRenderersEvent.RegisterRenderers/RegisterLayerDefinitions`，新增 `RegisterRenderStateModifiersEvent`（`ContextKey<T>` 改状态）；`RenderLayerParent`；NeoForge 加 JSON 动画系统（`assets/<ns>/neoforge/animations/entity/<path>.json`、`AnimationHolder`/`KeyframeAnimation`、`RegisterJsonAnimationTypesEvent`）。
- **相机**被 `CameraRenderState` 取代 → `MixinOverviewCamera/MixinSplineEditorCamera` 注入的 `Camera#setup` 目标可能失效。
- 撞上：`WandscapeNpcRenderer`、`TouristRenderer`（若 extends `MobRenderer`/`LivingEntityRenderer` 则泛型与父类全变）；任何 `getTextureLocation`/`setupAnim`/layer 叠加逻辑。BEWLR 侧 `renderByItem` 基本保留，但 `IClientItemExtensions` 改经 `RegisterClientExtensionsEvent` 注册（`IClientItemExtensions` 单例）。

### 4.3 物品 / 数据组件语义（成本：中高）
`Item`/`Block` 构造改收注册名（`registryName -> new ...`，`new Item()`→`(ResourceKey id)-> new MyItem(id)`），`Item.Properties#delayedComponent`；数据组件原型迁移到 `Holder<Item>`（`stack.get(comp)`→`item.builtInRegistryHolder().components().get(comp)`）；`ModifyDefaultComponentsEvent` 移除用 `set(..., null)`。涉及 `items` 域：法杖/权杖/盟誓戒指/指南书/终端/罗盘，及其组件原型与 `.setId(ResourceKey)`。

### 4.4 数据附件与容器（成本：中）
`ValueIO` 序列化、map Codec、内置 `sync(...)` 取代手动 `ChunkWatchEvent.Sent`、level 可挂附件、`copyOnDeath` 进 builder。仓库/殖民地 chunk·entity 附件、NPC MagicState/EquippedMagicComponent/GuardState 等全部迁移；同时 `Container→ItemStacksResourceHandler` 波及槽位与 `StackCopySlot`（数据组件不可变性要求菜单改 ItemStack 时 `#copy()`）。

### 4.5 网络（成本：中，需收敛）
`PacketDistributor.sendToServer→ClientPacketDistributor.sendToServer`（**231 处直发**）；客户端收包 handler 从「common 的 `onRegisterPayloads` 一处」迁到 **`RegisterClientPayloadHandlersEvent`**（仅物理客户端，带 `HandlerThread`）；`IPayloadContext` 收包上下文签名可能变化。Wandscape 现在「S2C 靠 `setClientHandler` 注入、C2S 靠 `IPayloadContext`」的两套并存会被推着收口——这反过来也是单 loader 就该做的。

### 4.6 事件与输入（成本：中）
`@EventBusSubscriber` 在 26.1 的 docs 中被移除（仅 `<21.1.180` 旧版仍有）；事件类/层级调整（如 `LivingJumpEvent→LivingEvent.LivingJumpEvent`、`RenderNameTagEvent→RenderNameTagEvent.CanRender`）；输入签名变化：`mouseClicked(double,double,int)→mouseClicked(MouseButtonEvent, boolean doubleClick)`、`keyPressed(int,int,int)→keyPressed(KeyEvent)`、`isActiveAndMatches(InputConstants.getKey(...))`；`KeyMapping` 分类改 `KeyMapping.Category`（`registerCategory`，`Identifier` 命名）。影响 `WandscapePanelController`、各 flight/相机控制器、指南/面板输入、`#keyPressed/#mouseClicked` 覆写（约 10+ 文件）。

### 4.7 注册与对象构造（成本：中）
`DeferredRegister.create` 参数换序、注册返回值 `Supplier`、`register(name, registryName -> new ...)`、`MenuType` 常量换名、`MobEffect`/`Potion` 构造（`new Potion()`→带 name；`applyEffectTick(ServerLevel, LivingEntity, int)` 加 level 参）、`registerItem/registerBlock/registerEntity` 新重载。涉及 13 个 DeferredRegister、28+ 物品、3+ 方块、EntityType×5、BlockEntityType、Particle、SoundEvent、Attribute×6、MobEffect×6、MenuType、`EntityDataSerializer`（`NeoForgeRegistries.ENTITY_DATA_SERIALIZERS`——26.1 各注册表形态需复核）。

### 4.8 Misc
- **Config**：`ModConfigSpec` 仍在；新增 `ModConfigEvent.Unloading`；配置屏仍自动生成。**低**。
- **Mixin（4）**：全部注入 vanilla 目标——`LevelTicks#schedule`、`ServerLevel#isVillage`、`Camera#setup`×2。26.1 这些类/方法可能已变/已搬家（尤其相机），**需逐个重审**；@Inject 语义仍在，但目标签名字节码要对上。**中**（每个都可能断）。
- **Compat**：Curios/Iron's Spells/Goety 需各自出 26.1 版且 API 调整（Curios 槽、IronSpells 属性桥、Goety volley）。**中，依赖外部进度**。

---

## 五、不动的部分（正向清单）

- 数据/资源：1353 个 data json、193 纹理、模型、lang、指南书、元素/建筑/配方 JSON——**零 loader 依赖，id 是字符串，原样可用**。
- `api/` 极薄契约、`impl/CoreBootstrap`/`TemplateResolver` 等纯逻辑零 MC 岛。
- 大部分 ServerTick/ClientTick/WorldRender/HUD 事件与注册类事件（仍在 NeoForge 事件总线，只是事件类/命名微调）。
- 自研 `SimpleEventBus`（content/task）与自定义 mod 事件——它们是私有实现，不受 MC 改名影响。

---

## 六、分项难度表（按「触点规模 × 是否重写」）：

| 面 | 触点规模 | 类型 | 面级 |
|---|---|---|---|
| GUI 渲染层 | 30–40 client 文件 | **重写**（双态渲染状态机 + 方法改名/换参） | **最高（成本主体）** |
| 实体/BER/相机渲染 | 2 渲染器 + 相机 mixin×2 + 建筑幽灵 | **重写**（render state / RenderLayer / CameraRenderState） | 高 |
| ResourceLocation→Identifier 等全局改名 | 全库（657 文件绝大部分） | 脚本化 | 高（量大但安全） |
| 物品/数据组件语义 | items 域 + Holder 原型 | 语义重写 | 中高 |
| 数据附件 + Container→ResourceHandler | NPC/仓库/殖民地块/entity attach + 槽位 | 语义迁移 | 中高 |
| 网络 | 80 载荷 / 231 发送点 | 收口 + 构件替换 | 中 |
| 事件与输入 | ~50 订阅 + 输入覆写 / KeyMapping | 改名 + 签名 | 中 |
| 注册与对象构造 | 13 DeferredRegister / 28+ 物件 | 机械 + 构造 | 中 |
| Mixin 重审 | 4 | 逐审 | 中（每项可能断） |
| Config | 2 文件 | 微改 | 低 |
| Compat | 4 模组 | 依赖外部 | 外部主导 |

---

## 七、可量化的相对工作量分层

- **机械改名**（ResourceLocation/Builder/寄存器/命名族）：约占**六成**改动面，可大幅脚本化 + 编译枚举兜底。
- **语义改写**（数据组件原型、附件 ValueIO+同步、Container→ResourceHandler、网络收口）：约占**两成余**，逐个域过。
- **深水重写**（GUI 渲染状态机、实体渲染状态机、相机）：约占**两成**，但**是真实的时间黑洞**——尤其 GUI 那 30–40 个文件。
- 一句定性：**这是「同 loader、全 API 换血」的大型迁移，比不换 loader 的常规小版本升级重得多；其核心难点不在改名而在 GUI/渲染层的表达方式重写。**

---

## 八、与 fabric 移植对比（横评）

| 维度 | fabric 1.21.1 移植 | neoforge 26.1 升级 |
|---|---|---|
| loader | 换 | 不换 |
| 难度主因 | loader 语义映射 + 输入/伤害两个深缝 | **GUI/渲染重写 + 全库 API 改名** |
| 硬骨头 | 客户端全局输入/相机（fabric 无钩子）、伤害管线 | GUI 双态渲染状态机、实体渲染状态机、相机被 CameraRenderState 取代 |
| 可脚本化占比 | 高（~7 成事件直接映射） | 中高（~6 成机械改名，但 GUI/渲染非脚本） |
| 数据资产 | 原样 | 原样 |
| 相对总量 | 中 | **更大**（尤其本 UI 重度项目） |

（fab 报告见 `docs/fabric-port-survey.md`；本文与其互为两条独立演进线。）

---

## 九、考察级建议顺序（非计划，供立项时参考）

1. **先定工具链**：`gradle.properties` 三版本号 + MDG/NG 插件版 + JDK25 + Gradle9.1 + 去混淆（去 Parchment）。此步先让 `./gradlew build` 能稳定失败的基线。
2. **全局改名先做**：ResourceLocation→Identifier、get→getValue、DeferredRegister.create 换序、Supplier、FMLEnvironment.getDist、FeatureFlags.DEFAULT_FLAGS、InteractionResult.SUCCESS 等——脚本化 + 编译枚举，先把「能编过」铺满。
3. **再碰语义域**：数据组件原型、附件 ValueIO+sync、Container→ResourceHandler、网络收口（顺带收敛发送 util）、事件/输入改名——逐域过，每域 `build` 一把。
4. **最后啃 GUI/渲染**：先抽象一个本模组自己的 `GuiRenderState` 提交包装（对标 vanilla 双态），把 30 个屏逐个迁移；实体渲染器同法封装。
5. **重审 mixin**（相机×2、LevelTicks、ServerLevel）与 compat（等三模组 26.1），再决定是否保留。
- **陷阱**：GUI 绘图方法参数顺序/几何反转（fillGradient、blit tint 等）不会报编译错误，是行为回归高发区；`RenderType→RenderPipeline` 语义差异；相机会失效；`stack.get(comp)`→`registryHolder().components()` 引出的运行时差异。

---

## 十、需升级时点复核的外部事实

- NeoForge 26.1 各注册表（含 `ENTITY_DATA_SERIALIZERS`）最终形态；`Identifier` 是否彻底替换 `ResourceLocation`（有无 shim/别名）。
- 事件层级最终命名（`LivingEvent.LivingJumpEvent` 等）与 `@EventBusSubscriber` 去留的实錘；`Camera#setup`/`LevelTicks#schedule`/`ServerLevel#isVillage` 在 26.1 的真实签名。
- `RegisterClientPayloadHandlersEvent` / `ClientPacketDistributor` / `HandlerThread` 的确切收包上下文与线程约定。
- Curios / Iron's Spells 'n Spellbooks / Goety 的 26.1 发布状态与 API 变化。
- 全部依据的原始材料在 `_refs/261/`（`neoforge-PORTING.md`、`loader-7.0.4-changelog.txt`、`neoforge-docs/{v1.21.1,v26.1}/` 逐接缝文档、`deltas-seams.txt`、`diffs-seams.txt`、`docs-tree.json`）。
