# Forge 1.20.1 降级考察报告（forge-1201-downgrade-survey）

> 信息截至 2026-09-19 | 基线：分支 `1.21.1`（Minecraft 1.21.1 / NeoForge 21.1.249 / Java 21 / ModDevGradle 2.0.141）
> 目标：**Minecraft 1.20.1 / Forge 47.x**（ForgeGradle 6 / Java 17 / 生产跑 SRG 名，mixin 必须有 refmap）
> **性质：长期可行性考察，不是降级实施计划**。Loader 侧结论由本地落盘的 Forge 1.20.1 源码**逐类比对**得出（`_refs/forge-1201-source/`，含官方 MDK 与 `net.minecraftforge.*` 全量源码）；第三方结论逐包比对各模组的 1.20.1 分支源码（清单见 §十三）。所有数字为当日代码快照，随开发会漂移。

- **【何时读】**：评估「要不要降到 1.20.1 Forge」「降下去到底要动多少」「先动哪一块」时。
- **【不包含什么】**：分步实施步骤、分支/发版编排、里程碑排期——那是动手时的活。

> 阅读提示：本文凡 `X → Y` 即「1.21.1 现状 → 1.20.1 目标」。

---

## 一、结论速览（TL;DR）

- **先澄清性质**：这不是「换 loader」的单层迁移，而是**同时降两个版本层**——loader（NeoForge → Forge）＋ Minecraft 本体（1.21.1 → 1.20.1）。两者成本结构完全不同，必须分开估，混在一起估会得出错误的结论。
- **Loader 层：意外地便宜。** 全仓只用到 **64 个** `net.neoforged` 类型，其中 **14 个是纯包名改名**（eventbus/fml/distmarker 外部构件）、**32 个在 Forge 1.20.1 有同名同形的对应类**，**只有 17 个真无对应**（见 §四）。事件面尤其顺：27 个事件类里 **21 个**在 Forge 1.20.1 同名存在。`ServerLifecycleHooks.getCurrentServer()`（40 文件 / 46 处）、`ItemStackHandler`、`ModelData`、`IClientItemExtensions`、AT 文件语法全部一致。
- **MC 本体层：才是真成本，且与 loader 无关。** 1.21.1 比 1.20.1 多出来的东西里，真正扎到本项目的是四处：**网络包体系**（`CustomPacketPayload`/`StreamCodec`/`RegistryFriendlyByteBuf`/`ByteBufCodecs` 全是 **1.20.5+ 的 vanilla API**，1.20.1 下任何 loader 都没有）、**物品数据组件**（1.20.5+；`ItemKey` 是本项唯一的结构性改造点）、**`SavedData`/`INBTSerializable` 去掉 provider 形参**（15 个 `SavedData` 子类 + 若干 NBT 序列化类）、**`appendHoverText` 的 `Item.TooltipContext`**（1.20.5+，9 文件）。
- **有几处看着像坑、其实不是**（已用 javap 对着真实 1.20.1 jar 逐条核实，列出以免过度预算）：`HolderLookup.Provider` **在 1.20.1 就存在**，且 `RegistryAccess` 在 1.20.1 同样继承它——**零改动**；`RangedAttribute` 的 min/max 两版一致；`Item.use(...)` 在 1.21.1 仍返回 `InteractionResultHolder<ItemStack>`；`SimpleJsonResourceReloadListener` 构造签名两版相同；`pack.mcmeta` 不必手写（见 §7.3）；`RecipeInput`/`SizedIngredient`/`PotionContents`/`EnchantmentHelper`/`CreativeModeTabs`/`MerchantOffer`/`StructureTemplate` 全仓 **0 处使用**。
- **最难的那块这次最轻。** GUI 渲染是「升级到 26.1」公认的成本中心，但在**降级**方向上 `GuiGraphics`、`blit` 全部重载、`drawString`（含 `dropShadow` 参数）、`RenderType`（含 `guiOverlay()`）、`PoseStack`、`enableScissor` 几乎原样可用；24 个 Screen 里只有 **4 处真断**。
- **两处会「不出声」的坑，必须先手处理**：
  1. 1.21 把数据包目录改成单数（`advancement`/`loot_table`/`recipe`/`tags/block`），1.20.1 是复数——**43 个文件会零报错、零日志地不加载**；
  2. `MixinMouseHandler` 注入的 `MouseHandler#turnPlayer` 在 1.20.1 是**无参**的，而 mixin 配置 `required: true` + `defaultRequire: 1`——不修就是**启动崩溃**。
- **工具链要换，且有两处隐性要求**：`net.neoforged.moddev` 不能产出 Forge 1.20.1，必须换 **ForgeGradle 6**（最新 `6.0.54`，仍在维护；且它不在 Gradle Plugin Portal，需单独加 Forge maven 仓库）；Java 21 → 17；Gradle wrapper **9.2.1 可能要降到 8.x**（FG6 是对 Gradle 8.1 API 编译的）；最要紧的是 **refmap 从「不需要」变成「必须有」**（NeoForge 1.20.2+ 用官方名跑，Forge 1.20.1 生产环境跑 SRG 名）。当前 jar 里没有 refmap，5 个 mixin 全部需要重映射。
- **第三方 compat 全部有 1.20.1 版，绝大多数 API 都对得上**：Curios `5.14.1+1.20.1`、Goety `2.5.58.3`、Patchouli `1.20.1-85-FORGE`（`PatchouliAPI` 两版字节级一致）、JEI `15.59.0.212`、Iron's Spells `1.20.1-3.16.3`、车万女仆 `1.5.3-forge`。**没有一件需要裁掉**；唯一需要真改的是 Curios 的 3 处成员级差异（`LazyOptional` 缺 `flatMap`/`ifPresentOrElse`、curio 注册方式、属性 `Holder` 包装），详见 §九。
- **难度评级：中。** 比 [fabric-port-survey.md](fabric-port-survey.md) 轻（那次 loader 全换且多处「无现成」需 mixin 硬啃，这次 loader 侧同名度极高）；比 [neoforge-26-upgrade-survey.md](neoforge-26-upgrade-survey.md) 轻（那次 MC 跨 8~11 个版本且 GUI 渲染整层重写成双态状态机）。真成本集中在**网络栈**与**物品数据**两处，其余是量大但安全的名字替换。

> **决策已落（2026-09-23）**——以下四条以此为准，本文其余小节仍是当日「考察」口径：
> 1. **形态**：`1.21.1` 为主分支，降级在独立分支 `1.20.1-forge` 上做（独立 worktree 并行），**只在主线稳定发布后跟进一次**，不跟 alpha/beta；预计维护一年以上，主流模组迁走后停更。跟进走「转换脚本 + 每次把新踩到的模式补进脚本」，数据/资产/文档先在主线改、靠 merge 流到副分支。
> 2. **配置屏**：入口两线一致裁掉（§6.4 已按此改写），配置只留 `config/*.toml` 与游戏内设置中心；面板未收录的 14 项也维持 TOML-only。
> 3. **车万女仆（TLM）**：1.20.1 不兼容——§6.3 的 attachment 缺口在 1.20.1 侧随之消失。**（2026-09-24 升级为两线一致）** 主分支亦已删除 `compat/tlm/`，该缺口现在两条线上都不存在，详见 `docs/adr.md`。
> 4. **compat 方针**：1.20.1 侧任何 compat 出问题就**关掉该模组**、不修；§九「没有一件需要裁掉」只是 API 层面的可用性结论，不等于承诺逐行修到能用。

---

## 二、先决：工具链与两类不同的差异

### 2.1 版本线

| 项 | 1.21.1 现状 | 1.20.1 目标 | 影响 |
|---|---|---|---|
| Minecraft | 1.21.1 | **1.20.1** | 数据包目录单→复（零报错）；运行时生成的 `pack_format` 自动 48→15 |
| Loader | NeoForge **21.1.249** | **Forge 47.4.x**（1.20.1 线最新 `47.4.23`，2026-08-19 发布；官方 recommended 仍为 `47.4.10`）——**该版本线 2026 年仍在收更新的** | 包根 `net.neoforged.neoforge.*` → `net.minecraftforge.*` |
| 构建插件 | **ModDevGradle 2.0.141**（`net.neoforged.moddev`） | **ForgeGradle 6**（`net.minecraftforge.gradle`，最新 `6.0.54`，2026-06；仓库里有 `FG_7.0` 分支，未废弃）。**Forge 1.20.1 没有第二条路**——ModDevGradle 与 NeoGradle 都是 NeoForge 专用 | 配置 DSL 全改；`neoForge {}` → `minecraft {}` |
| 插件仓库 | `gradlePluginPortal()` 即可 | FG6 **不在** Plugin Portal，`settings.gradle` 的 `pluginManagement.repositories` 必须加 `https://maven.minecraftforge.net/` | 新增一段配置 |
| Gradle | **wrapper 9.2.1** | **需降到 8.x**：FG6 是对 Gradle 8.1 API 编译的，9.x 支持未确立 | **要改 wrapper**（待实测确认） |
| JDK | 21 | **17** | 源码级回归见 §6.7 |
| 混淆 / 映射 | Mojang official + Parchment `2024.11.17` | official + **Parchment `2023.09.03`**（1.20.1 有，2023-10 之后冻结）；走插件 `org.parchmentmc.librarian.forgegradle` + 仓库 `maven.parchmentmc.org` | 参数名保留；**生产环境跑 SRG 名** → refmap 必需 |
| mod 元数据 | `src/main/templates/META-INF/neoforge.mods.toml` | `src/main/resources/META-INF/mods.toml` | schema 不同 |
| mixin 声明 | `[[mixins]]` 块写在 mods.toml（**NeoForge 专有**） | **JAR manifest `MixinConfigs` 属性** | 已在 Forge 源码与 4 个真实 1.20.1 模组上确认 |
| AT 声明 | 自动探测 `META-INF/accesstransformer.cfg` | `minecraft { accessTransformer = file(...) }` | 文件内容本身格式一致 |
| 第三方依赖 | `compileOnly "group:artifact:ver"` | `compileOnly fg.deobf("group:artifact:ver")` | **Forge 1.20.1 的 mod 依赖必须 `fg.deobf()` 包裹** |
| 产物 | 直接出 jar | `finalizedBy 'reobfJar'` | 多一步重混淆 |
| CI | JDK 21（`.github/workflows/build.yml`） | JDK 17 | 改一行 |

### 2.2 两类差异必须分开看

| | **Loader 差异**（NeoForge → Forge） | **MC 版本差异**（1.21.1 → 1.20.1） |
|---|---|---|
| 形状 | 包名/类名搬家，语义基本不变 | 语义真变（数据模型、网络协议、注册机制） |
| 占比 | 约六成改动面，但绝大多数是安全的 | 约三成改动面，**成本主体** |
| 能否脚本化 | 大部分能（find/replace） | 只能逐处判断 |
| 有无对应物 | 46/64 有同名对应（另 1 项改注解） | 无「对应物」概念，只能重写 |

**这是本次考察最重要的判断**：直觉会以为「从 NeoForge 降到 Forge」是 loader 灾难，实际上 loader 侧近乎平替；真正要命的是跟着一起降的 MC 本体版本差。反过来，直觉会以为「1.21.1 降到 1.20.1 只差一个版本应该没事」——但 1.21.1 与 1.20.1 之间隔着 **1.20.2 / 1.20.4 / 1.20.5 / 1.20.6 / 1.21** 五个版本，其中 **1.20.5 是数据组件与全新网络包体系的分界线**，恰好切在本项目的两个要害上。

---

## 三、规模与耦合（硬数据）

### 3.1 体量

| 顶层包 | 文件数 | 行数 | 备注 |
|---|---|---|---|
| `content`（13 功能域） | 542 | 92,263 | 业务主体 |
| `foundation` | 97 | 14,146 | 跨域基建；`foundation/ui` 独占 70 文件 / 11,700 行 |
| `compat` | 33 | 3,869 | JEI/Curios/Iron's/Goety/TLM，全 compileOnly |
| `api` | 25 | 1,811 | 公开契约，**零 loader 耦合** |
| `impl` | 4 | 452 | 装配层，仅 1 文件碰 loader |
| `mixin` | 5 | 202 | 5 个纯 vanilla mixin |
| **合计** | **710** | **115,221** | |

`content` 子域（按行数）：building 130/24,753、npc 69/12,333、task 84/11,180、tourist 39/10,393、road 43/9,299、colony 52/6,587、warehouse 18/4,214、command 23/3,902、magic 20/3,337、production 21/2,692、items 30/2,316、element 8/976、tutorial 5/281。

### 3.2 耦合

| 指标 | 数值 | 说明 |
|---|---|---|
| 直接 `import net.neoforged.*` 的文件 | **201 / 710（28%）** | 541 / 54,991 行（**48% 的代码行**）落在这些文件里 |
| 去重后用到的 NeoForge 类型 | **64 个** | 见 §四 |
| NeoForge import 行 | 376 行 | 其中 15 个类型属外部构件（eventbus/fml/distmarker），纯改名 |
| `import net.minecraft.*` 的文件 | 462 / 710 | 真「零 MC 依赖」的纯逻辑岛：**225 文件** |
| 自定义数据包类 | **86**（10,233 行） | 网络是本项目最大单一子系统 |
| 数据包发送点 | **185** | `sendToServer` 93 / `sendToPlayer` 68 / `TrackingEntityAndSelf` 17 / `TrackingChunk` 3 / `AllPlayers` 3 / `TrackingEntity` 1 |
| `StreamCodec` 引用 | 290 处 / 86 文件 | 与载荷一一对应 |
| `RegistryFriendlyByteBuf` 引用 | 359 处 / 93 文件 | **该类型 1.20.1 不存在** |
| 事件监听注册点 | 165 | 69 个 `@SubscribeEvent` + 68 `addListener` + 28 `EVENT_BUS.register` |
| 自定义事件类 | 17 | 其中 1 个用 `ICancellableEvent`（Forge 无此接口） |
| 数据包 JSON | 1,348 | 其中 `element_mappings` 1,188 是自定义 schema，**与版本无关** |
| 资源文件 | json 283 / png 186 / ogg 7 / mcmeta 3 | 另有 126 篇 guidebook markdown |
| `Screen` 子类 | 24 | 1 个抽象基类 `MedievalScreen` + 18 继承 + 5 `AbstractContainerScreen` |

---

## 四、Loader 契约比对：64 个 NeoForge 导入逐类判定

方法：抽取全仓 `import net.neoforged.*` 去重得 64 个类型，逐个到 `_refs/forge-1201-source/src/main/java/` 里找同名类（按类名而非路径，避免 `common/` 层级差异造成误判）。

### 4.1 汇总

| 分类 | 数量 | 处理 |
|---|---|---|
| 纯包名改名（外部构件：eventbus / fml / distmarker） | 14 | find/replace |
| 同形同名，仅改包根 | 32 | find/replace |
| **真无对应，需改造** | **17** | 见 4.3 |

### 4.2 同形同名（32 个，改名即可）

事件类 21 个：`BlockEvent`、`ExplosionEvent`、`ChunkEvent`、`SleepFinishedTimeEvent`、`PlayerEvent`、`PlayerInteractEvent`、`LivingDeathEvent`、`LivingChangeTargetEvent`、`MobSpawnEvent`、`EntityJoinLevelEvent`、`EntityAttributeCreationEvent`、`RegisterCommandsEvent`、`AddReloadListenerEvent`、`ServerStartingEvent`、`ServerStoppingEvent`、`ServerStoppedEvent`、`RenderLevelStageEvent`、`InputEvent`、`RenderGuiEvent`、`ScreenEvent`、`MovementInputUpdateEvent`（`ClientPlayerNetworkEvent` 亦同名）。

非事件 11 个：`DeferredRegister`、`PacketDistributor`、`ServerLifecycleHooks`、`RegisterCapabilitiesEvent`、`IItemHandlerModifiable`、`ItemStackHandler`、`SlotItemHandler`、`IClientItemExtensions`、`ModelData`、`INBTSerializable`、`IMenuTypeExtension` 的替代 `IForgeMenuType`。

其中 `RegisterCapabilitiesEvent` 与 `IClientItemExtensions` 只是从 `net.neoforged.neoforge.common.capabilities` / `...client.extensions.common` 挪到 `net.minecraftforge.common.capabilities` / `net.minecraftforge.client.extensions.common`。

### 4.3 真无对应（17 个，需改造）

| NeoForge 类型 | Forge 1.20.1 | 触点 | 性质 |
|---|---|---|---|
| `network.PacketDistributor` 的静态发送族 | `PacketDistributor.PLAYER/SERVER/ALL/NEAR/TRACKING_*` 目标对象 + `SimpleChannel.send(...)` | **185 处 / 73 文件** | 重写 |
| `network.handling.IPayloadContext` | `NetworkEvent.Context` | 14 文件 / 28 处 | 改造 |
| `network.registration.PayloadRegistrar` | 无 | 2 处 | 重写 |
| `network.event.RegisterPayloadHandlersEvent` | 无（改在 `FMLCommonSetupEvent` 里建 channel） | 3 处 | 重写 |
| `attachment.AttachmentType` | **无** | 1 个类型 / 4 文件 | 重做（Capability） |
| `registries.DeferredHolder` | `RegistryObject` | 55 处 / 7 文件 | 改名 |
| `registries.DeferredItem` | `RegistryObject<Item>` | 29 处 / 2 文件 | 改名 |
| `registries.NeoForgeRegistries` | `ForgeRegistries` | 2 处 | 改名 |
| `common.ModConfigSpec` | `common.ForgeConfigSpec` | **3 文件** / 61 处 | 改名 |
| `common.NeoForge`（总线持有者） | `common.MinecraftForge` | 53 文件 / 94 处 | 改名 |
| `common.extensions.IMenuTypeExtension` | `common.extensions.IForgeMenuType` | 2 处 | 改名 |
| `common.DeferredSpawnEggItem` | `common.ForgeSpawnEggItem`（**构造签名不同**） | 3 处 | 改造 |
| `event.tick.ServerTickEvent`（Pre/Post） | `event.TickEvent.ServerTickEvent` + `Phase` | 12 文件 / 13 处 | 改造 |
| `client.event.ClientTickEvent.Post` | `event.TickEvent.ClientTickEvent` + `Phase.END` | 8 文件 / 10 处 | 改造 |
| `event.entity.living.LivingIncomingDamageEvent` | `LivingHurtEvent`（访问器 1:1） | 4 文件 / 6 处 | 改名 |
| `client.event.RegisterMenuScreensEvent` | 无（改在 `FMLClientSetupEvent` 里 `MenuScreens.register`） | 3 文件 / 4 处 | 改造 |
| `client.gui.IConfigScreenFactory` + `ConfigurationScreen` | 有 `ConfigScreenHandler.ConfigScreenFactory`，但 **Forge 不自带配置屏** | 1 处 | **已裁掉**（2026-09-23 决策，见 §6.4） |

**另有 1 个语义陷阱**：`bus.api.ICancellableEvent`（1 文件）在 Forge 的事件总线上不存在，须改用 `@net.minecraftforge.eventbus.api.Cancelable` 注解 + `isCanceled()/setCanceled()`。

**读法**：17 项里 9 项其实也只是改名（`DeferredHolder`/`DeferredItem`/`NeoForgeRegistries`/`ModConfigSpec`/`NeoForge`/`IMenuTypeExtension`/`LivingIncomingDamageEvent`…），真正的「改造」只有网络 4 项 + tick 2 项 + 菜单屏/配置屏 + spawn egg + attachment，**共 10 项**。

---

## 五、一级改动：机械改名（量大但安全，约六成改动面）

| 类别 | 1.21.1 | 1.20.1 | 规模 |
|---|---|---|---|
| NeoForge 包根 | `net.neoforged.neoforge.*` | `net.minecraftforge.*` | 376 import 行 / 201 文件 |
| 事件总线 | `net.neoforged.bus.api.*` | `net.minecraftforge.eventbus.api.*` | — |
| 总线实例 | `NeoForge.EVENT_BUS` | `MinecraftForge.EVENT_BUS` | 94 处 / 53 文件 |
| fml / distmarker | `net.neoforged.fml.*`、`net.neoforged.api.distmarker.*` | `net.minecraftforge.fml.*`、`net.minecraftforge.api.distmarker.*` | 15 类型 |
| 资源定位构造 | `ResourceLocation.fromNamespaceAndPath(a,b)` / `.parse(s)` / `.withDefaultNamespace(s)` | `new ResourceLocation(a,b)` / `new ResourceLocation(s)` | **142 处 / 114 文件（全仓 16% 的文件）** |
| 注册返回类型 | `DeferredHolder<R,T>`、`DeferredItem<T>` | `RegistryObject<T>` | 84 处 / 9 文件 |
| 注册器工厂 | `DeferredRegister.createItems(MODID)` / `createBlocks(MODID)` / `.registerBlock` / `.registerItem` | `DeferredRegister.create(ForgeRegistries.ITEMS/BLOCKS, MODID)` / `.register` | 8 处 / 3 文件 |
| 配置规格 | `ModConfigSpec` | `ForgeConfigSpec` | 61 处 / **3 文件** |
| 当前服务器 | `ServerLifecycleHooks.getCurrentServer()` | 同名（仅包根） | 46 处 / 40 文件 |
| 注册表常量 | `NeoForgeRegistries.ENTITY_DATA_SERIALIZERS` | `ForgeRegistries.ENTITY_DATA_SERIALIZERS` | 1 处 |
| 菜单类型工厂 | `IMenuTypeExtension.create(...)` | `IForgeMenuType.create(...)` | 2 处 |
| 伤害事件 | `LivingIncomingDamageEvent` | `LivingHurtEvent` | 6 处 / 4 文件 |
| 刷怪判定枚举 | `MobSpawnEvent.SpawnPlacementCheck.Result.FAIL` | `Result.DENY` | 1 处 |
| 物品渲染扩展 | `IClientItemExtensions`（neoforge 包） | 同名（minecraftforge 包） | 2 处 / 1 文件 |
| 序列化接口 | `INBTSerializable`（neoforge 包） | 同名（minecraftforge 包） | 1 处 |
| 物品同一性判定 | `isSameItemSameComponents` | `isSameItemSameTags` | 3 处 / 3 文件 |
| 属性修饰符枚举 | `ADD_VALUE` / `ADD_MULTIPLIED_BASE` / `ADD_MULTIPLIED_TOTAL` | `ADDITION` / `MULTIPLY_BASE` / `MULTIPLY_TOTAL` | 15 处 / 5 文件（**现状混用**） |
| 药水效果构造 | `MobEffectInstance(Holder<MobEffect>, ...)` | `(MobEffect, ...)` | 39 处 / 3 文件（方向上是简化） |

**本层的判断**：单点最多的是 `ResourceLocation`（114 文件），但它是**逐字正则替换**、零语义风险；`ModConfigSpec` 只有 3 个文件，比预想小一个数量级（`Config.X` 的静态字段访问散在 48 个文件里，但那是本模组自己的 API，**完全不用动**）。

---

## 六、二级深水区：需重写（真实成本主体）

### 6.1 网络栈（成本：**最高**）

**为什么它最重**：这里叠了三层变化，任何一层单独都能应付，叠在一起就变成整层重写。

1. **NeoForge 的载荷注册体系整个没有**：`PayloadRegistrar`、`RegisterPayloadHandlersEvent`、`IPayloadContext` 在 Forge 1.20.1 都不存在。
2. **承载它的 vanilla API 也不存在**：`CustomPacketPayload`、`StreamCodec`、`ByteBufCodecs`、`RegistryFriendlyByteBuf` 全是 **1.20.5+** 的 vanilla 类型。也就是说这**不是 NeoForge 与 Forge 的差别，而是 1.21 与 1.20.1 的差别**——换成任何 loader 都一样。
3. **Forge 1.20.1 只有一条路**：`NetworkRegistry.newSimpleChannel(...)` + `SimpleChannel.registerMessage(index, class, encoder, decoder, consumer)`，编码缓冲是 `FriendlyByteBuf`，收包上下文是 `Supplier<NetworkEvent.Context>`。

**规模**：86 个载荷类 / 10,233 行；`RegistryFriendlyByteBuf` 359 处；`StreamCodec` 290 处；发送点 185 处；注册点 3 处。

**三个真实利好**（决定了这是「L 级体量、M 级智力」而不是灾难）：

- **注册是单点的**：全部 86 个载荷只在 `Wandscape.java` 的 `onRegisterPayloads` 一处注册（38 次 `playToClient` + 41 次 `playToServer`，`versioned("1.0")`），另有 `CuriosCompat`/`CuriosCompatImpl` 各 1 处挂接。**只有 3 处需要重写注册逻辑**。
- **编解码函数可以整体复用**：81/86 个载荷已经写成 `static void write(RegistryFriendlyByteBuf, X)` + `static X read(RegistryFriendlyByteBuf)`，用 `StreamCodec.of(X::write, X::read)` 串起来（83 处）——这个形状与 `registerMessage(index, class, encoder, decoder, consumer)` **一一对应**，方法体原样保留，只换形参类型。只有 3 个载荷用了非机械写法（2 个 `StreamCodec.composite`、1 个 `StreamCodec.unit`）。
- **handler 已经与框架解耦**：每个载荷暴露静态 `handleClient(X)` / `handleServer(X)`，真逻辑由 `WandscapeClient` 在 `FMLClientSetupEvent` 里通过 `setClientHandler(Consumer)` 注入，注册 lambda 本身是空转。只有 14 个文件用到 `IPayloadContext`，其中仅 13 处 `ctx.player()` + 1 处 `ctx.enqueueWork()` 需要真判断。

**建议的落点**：把 185 个发送点收敛到一个 `WandscapeNetwork` 门面（`sendToServer` / `sendToPlayer` / `sendTracking` / `sendToAll` 四个方法），call site 保持不动，改造只发生在门面内部。这既是降级的最短路径，本身也是本项目「网络发送无统一 util（原 fabric 考察已记过同一笔账）」该做的收口。**具体设计（方法清单、`PayloadRegistry` 划分、以及「别包装缓冲区 / 别加 `WandPayload` 标记接口」的取舍）见 [networking-survey.md](networking-survey.md) §六。**

**残留风险**：`ComponentSerialization.STREAM_CODEC`（1.20.5+）须换成 `buf.writeComponent()/readComponent()`；`ByteBufCodecs.BOOL` 须换成 `buf.writeBoolean()`；`RegistryFriendlyByteBuf` 之所以是「registry 版」是因为 1.20.5 起包同步要带注册表上下文，1.20.1 无此概念，直接降级为 `FriendlyByteBuf` 即可。**「依赖注册表上下文的读写」已实测收敛为 3 个载荷**（`NpcDataPacket` / `ExplorationRewardPacket` / `ScreenFeedbackPacket`），非全部 86 个——见本文「主要行为等价风险」第 1 条订正。

### 6.2 物品数据组件 → NBT（成本：中）

1.20.5 引入数据组件后，`ItemStack` 不再挂裸 `CompoundTag`，改用 `DataComponents`/`CustomData`/`DataComponentPatch`。1.20.1 只有裸 NBT。

| 项 | 触点 |
|---|---|
| `DataComponents` | 13 文件 / 34 处 |
| `CustomData` | 12 文件 / 28 处 |
| `DataComponentPatch` | 1 文件 / 7 处 |
| 自定义 `DataComponentType` | **0 个** |

**只碰了 3 个 vanilla 常量**（`CUSTOM_DATA`、`CONTAINER`、`ENCHANTMENTS`），自定义数据全塞在 `CUSTOM_DATA` 里，只有 4 个键：`magic_id`、`mode`、`wand_color`、`preset_id`。规模比预想小，且 `"components"` 在数据包 JSON 里 **0 处**。

**唯一的结构性改造点是 `foundation/util/ItemKey.java`**（被 18 文件 / 34 处引用，是物品身份判定的咽喉）：

- 它用 **`DataComponentPatch.CODEC` + `RegistryOps.create(NbtOps.INSTANCE, registries)`** 把「所有非默认组件」编码成一个 `CompoundTag` 存进 `ItemKey(String itemId, CompoundTag nbt)`，并把 `HolderLookup.Provider` 一路穿到 `fromStack/toStack/matches/fromLegacy`。
- **这套东西在 1.20.1 没有对应物，但也不需要**：1.20.1 的 `stack.getTag()` **本身就是全量 tag**，所以整个 `DataComponentPatch`/`RegistryOps` 层直接塌缩成 `getTag().copy()` / `setTag(...)`，`HolderLookup.Provider` 形参从约 25 个调用点消失。
- 换句话说：**改造量集中在 1 个类，收益是删掉一整层间接**。

**另一个可用的现成接缝**：`foundation/ui/util/ItemStackUtil.withCustomNbt(ItemStack, CompoundTag)` 是只写不读的辅助方法，**除自身 `fromIdWithNbt` 外没有调用者**——是天然的 NBT shim 落点。

**顺带清理（已做，2026-09-23）**：`wand_color` 曾在 4 个不相关文件里各自内联字面量 + 各自写一遍十六进制解析（`WandApiImpl`、`MagicCaster`、`WandscapeClient`、`WandscapeNpcRenderer`），现统一为 `WandItem.COLOR_KEY` / `PRESET_KEY` 两个常量 + `WandItem.colorHex/colorArgb` 两个取值口（默认色仍由各调用方定，因为默认色本来就各不相同）。`magic_id` 走 `SpellItem.MAGIC_ID_KEY`，`mode` 走 `OmniScepterItem.MODE_KEY`；连数据侧的写入点（`WandPresetLoader`、`CraftWandRecipe`、`ElementRecipeCollector`、`MagicStationPacket`）也改为引用同一批常量。

**已收口（2026-09-23）**：`foundation/util/ItemData` 成为全仓**唯一**读写物品自定义数据的地方。实测收口前后：读写的**组件 API**（`DataComponents.CUSTOM_DATA` / `CustomData.of`）此前散在 **13 个文件**，现只剩 `ItemData` 一个文件（另 `compat/goety/GoetyHelper` 用的是 `CONTAINER`/`ENCHANTMENTS` 两个**别的**组件，不属本项；顺带清掉 `WarehouseMenu` 一条死 import）。降级时这项改动面 = 1 个文件。

### 6.3 数据附件 → Capability（成本：种类的 L，范围的 S）

**全仓只有 1 个 `AttachmentType`**（**2026-09-24 已随 TLM 兼容删除，现全仓 0 个**；以下为当日考察原文）：`compat/tlm/MaidColonyAttachments.MAID_COLONY_STATE`，挂在车万女仆的 `EntityMaid` 上，存 `MaidColonyState`（本身是 `INBTSerializable<CompoundTag>`），**读取点只有 1 处**（`MaidColonyWorker.java:83`）。

1.20.1 的替代路径有两条：① 老式 capability（`Capability` + `CapabilityToken` + `AttachCapabilitiesEvent<Entity>` + `ICapabilityProvider` + 失效回调）；② 直接写进 maid 实体自己的 NBT。

**判断**：这是**唯一一处「无对应物」的真缺口**，但它是可选软依赖（TLM）里的一小块，爆炸半径 4 个文件。工作量在「capability 样板本身啰嗦」，不在业务逻辑。（2026-09-24：该缺口随 TLM 兼容从两条线上一起消失。）

> **决策已落（2026-09-23）**：1.20.1 侧**直接不兼容 TLM**，本项在降级中不再存在——删 `compat/tlm/` 整包（7 文件）+ `Wandscape` 两处注册调用 + gradle 依赖 + mods.toml 可选依赖条目即可，外部触点仅此。
>
> **（2026-09-24 更新）**：该删除已在 1.21.1 主分支执行完毕，1.20.1 侧因此不再需要单独处理，两线在这一点上无差异。两处修正：① 实际爆炸半径比当时估的大——除 7 个类，还要删手册「联动与兼容」页（md + 生成物）、3 条 `task.wandscape.colony_worker*` lang 键与 `gen_patchouli.py` 的条目登记；② 元数据模板 `src/main/templates/META-INF/neoforge.mods.toml`（不在 `src/main/resources/META-INF/` 下，只有 `accesstransformer.cfg` 在那儿）**从未声明过 TLM 可选依赖**——它只声明了 neoforge / minecraft / jei / curios / patchouli，当时清单里「删 mods.toml 可选依赖条目」那条并不存在。

### 6.4 配置屏（成本：0，入口已裁）

**决策（2026-09-23，两线一致）**：**不注册 `IConfigScreenFactory`**——1.21.1 与 1.20.1 都不再提供「模组列表 → 配置」那个自动生成的配置屏。配置只留两个落点：`config/*.toml`，或游戏内设置中心（V 面板 → 设置）。判据是那个屏本来也不是给人改的（一条翻译键都没有，覆盖的多是整合包作者的曲线旋钮），多一个入口就多一处要维护的 UI。

**已落地**（1.21.1 主线，先于分叉做掉）：`WandscapeClient` 删掉注册调用与随之空掉的 `ModContainer` 形参；`lang_src/content/wandscape.json` 里 3 条 `wandscape.configuration.*` 键一并删除并重新生成 lang。Forge 侧因此既不需要手写配置屏，也不需要 `ConfigScreenHandler.ConfigScreenFactory`。

**剩下的一处真改动**（与配置屏无关，是设置中心自己要用的）：`SettingItem.declaredRange` 读 `defineInRange` 的区间做加减夹取。**这是直接 API 调用、不是反射**（本节原写「反射读取配置规格」有误），但 `ForgeConfigSpec.ConfigValue` 上**没有** `getSpec()`；等价信息在 `SPEC.getSpec().get(configValue.getPath())`（`ForgeConfigSpec.java:110` 返回 ValueSpec 树、`:311` 写入），故为一行改动 + `SettingsRegistry` 里 22 个构造点各补一个 `Config.SPEC` / `ClientConfig.SPEC` 形参。零反射、零新机制。

### 6.5 原版 API 版本差（非 loader，成本：中高）

逐条对着真实 1.20.1 jar 用 `javap` 核过，**含若干「看着像坑其实不是」的项**，一并列出以免过度预算。

**真差异：**

| 项 | 1.21.1 现状 | 1.20.1 | 触点 |
|---|---|---|---|
| `SavedData.save` / `INBTSerializable.serializeNBT` | 形参带 `HolderLookup.Provider` | **形参整个没有**（`save(CompoundTag)` / `serializeNBT()`） | **15 个 `SavedData` 子类** + 若干 NBT 序列化类（共 22 文件出现该形参） |
| `appendHoverText` | `(ItemStack, Item.TooltipContext, List, TooltipFlag)` | `(ItemStack, Level, List, TooltipFlag)`；`Item.TooltipContext` **不存在** | 9 文件 / 10 处覆写 |
| `AttributeModifier.Operation` 枚举常量 | `ADD_VALUE` / `ADD_MULTIPLIED_BASE` / `ADD_MULTIPLIED_TOTAL` | `ADDITION` / `MULTIPLY_BASE` / `MULTIPLY_TOTAL` | 15 处 / 5 文件（**且现状是混用的，见下**） |
| `AttributeInstance.addOrUpdateTransientModifier` | 有 | 只有 `addTransientModifier` / `addPermanentModifier` | 1 处 |
| `Holder<Attribute>` + `getAttribute(Holder<Attribute>)` | 属性用 Holder 包 | 直接 `Attribute` | 6 文件 / 18 处（映射单点 `WandscapeAttributes.toVanilla`） |
| `MobEffectInstance` / `MobEffects` | 构造收 `Holder<MobEffect>` | 收 `MobEffect` | 3 文件 / 39 处（**方向上是简化**） |
| `isSameItemSameComponents` | 1.21 名 | `isSameItemSameTags` | 3 文件 / 3 处 |
| `ItemStack.OPTIONAL_STREAM_CODEC` | 有 | 无 | 1 文件 / 4 处 |
| `ComponentSerialization.STREAM_CODEC` | 有 | 无（用 `buf.writeComponent`/`readComponent`） | 2 文件 / 3 处 |
| `ByteBufCodecs` | 有 | 无 | 3 文件 / 7 处 |
| `RecipeManager.getAllRecipesFor` | 返回 `List<RecipeHolder<T>>` | 返回 `List<T>` | 1 文件（去掉一层 cast） |
| `ItemEnchantments` | 有 | 无 | 1 文件 / 3 处（`compat/goety/GoetyHelper`） |
| `finalizeSpawn` | 4 参 | **5 参**（多 `CompoundTag`） | 1 文件 / 3 处 |
| `BlockEntity.saveAdditional` / `loadAdditional` | 多一个 provider 形参 | 无 | 2 文件 / 7 处 |

**不是差异（已核实，零改动）：**

| 项 | 核实结果 |
|---|---|
| `HolderLookup.Provider` | **1.20.1 存在该类型**，且 `RegistryAccess extends HolderLookup.Provider` 同样成立 → 所有以它为形参**但不受接口约束**的地方不用动 |
| `RangedAttribute` | 两版都是 4 参构造 + `sanitizeValue`，min/max 钳制**没有被移除** |
| `Item.use(...)` 返回类型 | 1.21.1 仍是 `InteractionResultHolder<ItemStack>`（改成 `InteractionResult` 是 1.21.2 的事） |
| `SimpleJsonResourceReloadListener` | 构造签名与 `prepare`/`apply` 钩子两版一致 |
| `ResourceLocation(String)` / `(String,String)` 构造 | 1.20.1 **存在**（1.21 才删）——但本项目 **0 处**在用，反倒要反向加回 |
| `Ingredient` / `MobSpawnType` / `SpawnGroupData` / `PathNavigation` / `ChunkPos` / `LevelChunkSection` | 全部存在且签名一致 |

**一个值得单独点出的隐患**：`AttributeModifier.Operation` 的常量在项目里**现在是混用的**——已有 10 处 `MULTIPLY_BASE` + 6 处 `ADDITION`（1.20.1 写法），同时有 7 处 `ADD_VALUE` + 7 处 `ADD_MULTIPLIED_BASE` + 1 处 `ADD_MULTIPLIED_TOTAL`（1.21 写法）。降级时要统一到 1.20.1 拼写，**但混用本身说明现在就有不一致**，值得顺手清理。

### 6.6 原始 VBO 建筑幽灵渲染（成本：中高，963 行）

`BuildingGhostVboCache`（330 行）、`BuildingPreviewGifCache`（481 行）、`BuildingGhostRenderer`（152 行）依赖 **1.20.1 不存在的底层顶点 API**：

| 1.21.1 | 1.20.1 |
|---|---|
| `ByteBufferBuilder` | 无 |
| `MeshData` / `mesh.drawState().indexType()` | 无 |
| `VertexBuffer.uploadIndexBuffer(ByteBufferBuilder.Result)` | 无（1.20.1 只有 `VertexBuffer.upload(BufferBuilder.RenderedBuffer)`，且无公开的索引缓冲上传路径） |

1.20.1 没有等价的公开索引上传 API，`BuildingGhostVboCache` 里手工打包索引的那段（`:216-219`）必须重建在 `BufferBuilder` / `RenderedBuffer` 上。

**这是「GUI 侧最轻」之外的例外**：一个自研的底层渲染缓存确实踩在了版本线的断层上。

### 6.7 Java 21 → 17 源码回归（成本：低，但会编译失败）

Forge 1.20.1 是 Java 17，下面这些 Java 21 语法/API 必须替换：

| 项 | 触点 |
|---|---|
| `Math.clamp(...)`（Java 21 新增） | **23 文件** |
| switch 类型模式（`case Type x ->`） | 2 文件 / 5 处 |
| record 解构（`instanceof Foo(...)`） | 1 文件 / 11 处（`MarkdownRenderWidget`） |
| `List.getFirst()`（Java 21 `SequencedCollection`） | 4 处 |
| `wandscape.mixins.json` 的 `compatibilityLevel: JAVA_21` | 1 处 |

合计约 30 个文件，需要一个小工具类（如 `MathUtil.clamp`）加逐处替换。**注意 `instanceof` 类型模式（260 处 / 116 文件）是 Java 16+，在 17 上合法，不用动**。

### 6.8 屏幕与 GUI 基类（成本：低-中）

24 个 `Screen`（`foundation/ui` 70 文件 / 11,700 行，`GuiGraphics` 326 处引用 / 64 文件）里，只有 4 处真断：

| 断点 | 触点 | 处理 |
|---|---|---|
| `Screen#renderBackground(GuiGraphics,int,int,float)` 是 1.20.2+ | `MedievalScreen`（调用 + `@Override`）、`CreativeScannerScreen` | 退回单参 `renderBackground(GuiGraphics)` |
| `renderTransparentBackground(GuiGraphics)` 1.20.1 无 | `MedievalScreen` | 用 `fillGradient` 自己铺一层压暗 |
| `GuiGraphics.blitSprite(...)` 1.20.2+ | `OverviewRenderer` 1 处 | 换成 `blit` 贴 sprite 矩形 |
| `WidgetSprites` 1.20.1 无 | `NpcCuriosButton` | 手写状态→贴图查找 |

**其余全部可用**（已逐个核对）：`blit` 的全部重载形状、`drawString`（含 `dropShadow` 参数——1.20.1 也有，不是差异）、`drawCenteredString`、`renderTooltip`、`renderItem`、`enableScissor`、`fill`/`fillGradient`、`RenderType.guiOverlay()`、`RenderType.create(...)` 8 参构造、`AbstractContainerScreen` 三段渲染、`EditBox`。

### 6.9 事件：tick 的相位改造（成本：低-中）

除 tick 外事件面近乎全平。tick 需要真改造：

- `event.tick.ServerTickEvent.Pre/Post`（12 文件 / 13 处）→ `TickEvent.ServerTickEvent` + `event.phase == Phase.START/END`
- `client.event.ClientTickEvent.Post`（8 文件 / 10 处）→ `TickEvent.ClientTickEvent` + `Phase.END`

注意 `ClientTickEvent.Post.class` 这种**类字面量注册**在 1.20.1 不存在，必须改成 `TickEvent.ClientTickEvent.class` 再在回调里判相位。

**顺带辟一个流传很广的说法**：「`@EventBusSubscriber` 的默认总线在 NeoForge 是 GAME、在 Forge 是 MOD，所以要补 `bus =`」——**这是错的**。已在 `_refs/forge-1201-source/minecraftforge/fml/common/Mod.java:68` 核实，Forge 1.20.1 是 `Bus bus() default Bus.FORGE;`（即游戏总线），与 NeoForge 的默认值语义一致。项目里那 2 个没写 `bus =` 的 `@EventBusSubscriber` **不用动**。（这条被两个独立信源同时报错，故单独记一笔——照着改反而会引入 bug。）

### 6.10 Mixin 与 AT（成本：低-中，但含一处启动崩溃）

5 个 mixin（202 行）全部打 vanilla 类，目标方法在 1.20.1 **除一处外全部存在**：

| Mixin | 目标 | 1.20.1 |
|---|---|---|
| `MixinLevelTicks` | `LevelTicks#schedule` | 存在 |
| `MixinServerLevel` | `ServerLevel#isVillage` | 存在 |
| `MixinOverviewCamera` / `MixinSplineEditorCamera` | `Camera#setup` + `@Shadow setPosition/setRotation` | 存在 |
| `MixinMouseHandler` | `MouseHandler#onPress`（存在）；`MouseHandler#turnPlayer` | **不存在该签名**：1.21 是 `turnPlayer(double)`，1.20.1 是**无参** `turnPlayer()` |

因配置为 `required: true` + `defaultRequire: 1`，`turnPlayer` 不匹配 = **启动崩溃**（不是静默跳过）。修法简单（去掉 `double` 形参），但必须先找到。

**AT 文件**（3 条，全部是字段放宽）：`NearestAttackableTargetGoal#targetType`、`LootTable#pools`、`LootPool#entries`。三个目标字段在 1.20.1 都存在且保护级别相同，cfg 语法两版一致，**只需改声明方式**。AT 失败不会编译报错、只会运行时炸，需实测。

---

## 七、数据与资源包：静默失效清单（成本：低，但**必须专项检查**）

这一层的危险不在工作量，而在**失败无声**。

### 7.1 数据包目录单数 → 复数（43 文件 / 6 个目录，零报错）

1.21 把数据包目录改成单数，1.20.1 是复数。**改不对不会报任何错，内容直接不加载。**

| 现状 | 1.20.1 | 文件数 |
|---|---|---|
| `data/wandscape/advancement/` | `advancements/` | 33 |
| `data/wandscape/loot_table/` | `loot_tables/` | 2 |
| `data/wandscape/recipe/` | `recipes/` | 2 |
| `data/wandscape/tags/block/` | `tags/blocks/` | 1 |
| `data/wandscape/tags/item/` | `tags/items/` | 1 |
| `data/curios/tags/item/` | `tags/items/` | 4 |
| **合计** | **6 个目录** | **43** |

**注意 `damage_type/` 两版都是单数，不要「顺手改对」**。`craft_recipes/`、`buildings/`、`magic_circles/`、`magic_spells/`、`element_mappings/` 是本模组自定义 schema，与版本无关。

### 7.2 JSON 字段格式（1.20.5+ 形制）

| 项 | 现状 | 1.20.1 | 文件数 |
|---|---|---|---|
| 进度图标 | `"icon": {"id": "minecraft:x"}` | `{"item": "minecraft:x"}` | **31** |
| 进度谓词 | `"items": "minecraft:x"`（标量） | 需为数组 | 2 |
| 配方产物 | `"result": {"id": ...}` | `{"item": ...}` | 2 |
| 加载条件 | `"type": "neoforge:mod_loaded"` | `"type": "forge:mod_loaded"` | 2 |

进度 JSON 格式错误**可能中断整包 reload**，属高严重度但低工作量。

### 7.3 资源包

| 项 | 情况 |
|---|---|
| `pack.mcmeta` | **当前不存在，且不必新建**：Forge 1.20.1 官方 MDK 也不带（`_refs/forge-1201-source/mdk/src/main/resources/` 只有 `META-INF/mods.toml`），FML 会自行合成。全仓唯一的 `pack_format` 是运行时生成的——`ScannerExportPacket` 用 `SharedConstants.getCurrentVersion().getPackVersion(PackType.SERVER_DATA)`，在 1.20.1 上**自动产出 15**，零代码改动。（`run/saves/*/datapacks/` 里已有的导出档带 48，属旧档垃圾，可删） |
| 9-slice 面板 | 3 个 `.png.mcmeta` 用 `gui_sprite_scaling`/`nine_slice`，是 **1.20.2+** 特性，1.20.1 会忽略 → 三张九宫格面板退化（与 §6.8 的 `blitSprite` 是同一处问题的两面） |
| 模型 | 130 个纯 JSON，**零自定义 model loader**，无 `ItemOverrides`/`BakedModel`/`ItemStackRenderState`，无 `render_type` 字段 → 原样可用 |
| 音效 / 粒子 | 7 个 ogg + `sounds.json`、2 个粒子 JSON → 原样可用 |
| lang | 中英各 2,265 键、键完全对齐 → 原样可用 |
| 数据生成 | **不存在**（无 `GatherDataEvent`、无 `data` run 配置、无 `src/generated/`）→ 不咬人 |
| 死文件 | `models/block`、`models/item`、`blockstates` 下有字面量 `$name.json` 模板残留，可顺手清 |

**数据包加载器不受影响**：两个 `SimpleJsonResourceReloadListener` 子类的构造签名与钩子在两版一致，且**全部自定义 JSON 解析都是手写 GSON**（`BiFunction<String, JsonElement, T>` + `X::fromJson`），与版本无关。无 `RegistrySetBuilder`、无 datagen。

### 7.4 Patchouli 手册：**零改动**

143 个文件（1 个 `book.json` + 16 分类 + 126 条目）。`book.json` 的 8 个字段（`name`/`landing_text`/`subtitle`/`book_texture`/`nameplate_color`/`use_resource_pack`/`show_progress`/`text_overflow_mode`）在 Patchouli 1.20.1 全部支持；`use_resource_pack: true` 的现代布局在两版一致。页面类型只有 `patchouli:text` 一种（585 页），未用任何 1.21 专有页型。`gen_patchouli.py` 里也没有任何版本条件分支。

---

## 八、不动的部分（正向清单）

- **225 个纯逻辑文件**（无 `net.minecraft` 也无 `net.neoforged` import）——引擎内核、蓝图解析、任务评分、路由算法，整块原样搬。
- **`api/` 25 文件 / 1,811 行**：零 loader 耦合。
- **`impl/` 4 文件**：只有 `EngineBootstrap` 一处 import `ServerStartingEvent`。
- **1,188 个 `element_mappings` JSON + 全部自定义 schema 数据**（buildings/craft_recipes/magic_*）：1,348 个数据文件里 1,305 个与版本无关。
- **Patchouli 手册全线**（§7.4）。
- **130 个模型 JSON、189 张纹理、7 个音效、2,265×2 语言键**。
- **GUI 渲染的绝大部分**（§6.8）：`GuiGraphics`/`blit`/`drawString`/`RenderType`/`PoseStack`/裁剪/`AbstractContainerScreen` 三段式。
- **实体/BER/粒子/`ModelData`/`IClientItemExtensions`**：签名已是 1.20.1 形状。
- **13 处 `DeferredRegister`（14 处挂到总线）与全部注册调用点**：`ITEMS.register(...)` 这类调用语法本身不变，只换字段类型与构造入口。
- **vanilla `Registries.*` 常量**（`CREATIVE_MODE_TAB`/`ENTITY_TYPE`/`MOB_EFFECT`/`MENU`/`ATTRIBUTE`/`SOUND_EVENT`…）：两版一致。
- **无自定义 Forge 注册表**（`NewRegistryEvent`/`RegisterEvent`/`RegistryBuilder` 全仓 0 处）：`foundation/registry/dataconfig` 是数据包 JSON 加载器，零 loader 耦合。
- **无 `IFluidHandler`/`IEnergyStorage`/`LazyOptional`**：能力面极小。
- **不使用 `IConfigScreenFactory`**（自动配置屏入口已裁，见 §6.4）；JEI 走自身 `@JeiPlugin` 扫描。

---

## 九、Compat 与第三方模组在 Forge 1.20.1 的可用性

逐包比对：把我们 `compat/` 里对每个第三方 API 的 import 拿去在其 **1.20.1 分支源码**里找同名类；坐标与版本另经 maven 元数据 / 实际 jar 核对。

| 第三方 | 1.20.1 坐标（已验证） | 类名命中 | 结论 |
|---|---|---|---|
| **Curios** | `top.theillusivec4.curios:curios-forge:5.14.1+1.20.1`（含 `:api` classifier） | 10/10 | **类名全存活，但有 3 处成员级断裂**，见下 |
| **Goety** | `curse.maven:goety-586095:8894243`（= `goety-2.5.58.3`，2026-09-16 上传） | 11/11 | 存活，签名近乎相同 |
| **Patchouli** | `vazkii.patchouli:Patchouli:1.20.1-85-FORGE`（含 `:api`） | 1/1 | 存活；`PatchouliAPI` 在 1.20.1-85-FORGE 与 1.21.1-93-NEOFORGE 之间**字节级一致** |
| **JEI** | `mezz.jei:jei-1.20.1-forge:15.59.0.212`（2026-09-16 发布） | 18/18 | 存活；仅版本线 19.x → 15.x |
| **Iron's Spells** | `io.redspace:irons_spellbooks:1.20.1-3.16.3`（含 `:api`） | **12/12** | 存活（**修正见下**） |
| **车万女仆 (TLM)** | `maven.modrinth:touhou-little-maid:g1SKoGQJ`（= `1.5.3-forge+mc1.20.1`） | 6/6 | 存活；包名零改名，仅 `MaidTickEvent` 从 `ICancellableEvent` 变 Forge `@Cancelable`。**但 1.20.1 侧决定不做（2026-09-23）**——数据附件无对应物（§6.3），关掉整包即可 |

**Curios 是唯一需要真改的 compat**（这也修正了「导入命中即可用」的粗判）：

1. **`getCuriosInventory` 的返回类型变了**：1.21.1 的 Curios 9.x 返回 `Optional<ICuriosItemHandler>`，1.20.1 的 5.x 返回 **`LazyOptional<ICuriosItemHandler>`**。而 Forge 的 `LazyOptional`（已在 `_refs/forge-1201-source/.../common/util/LazyOptional.java` 核实）**只有 `ifPresent`/`map`/`filter`/`resolve`/`orElse`/`lazyMap`，没有 `flatMap`、没有 `ifPresentOrElse`**。
   → 项目里恰好有两处踩中：`CuriosCommand.java:78` 用了 `ifPresentOrElse`，`CuriosCompatImpl.java:104` 用了 `flatMap`。**都要改写**。
2. **curio 注册方式不同**：我们用 `RegisterCapabilitiesEvent.registerItem(CuriosCapability.ITEM, ...)`（`CuriosCompatImpl.java:110`，NeoForge 的注册风格）；5.x 侧对应的是 `CuriosApi.registerCurio(Item, ICurioItem)`（已在 `curios-1201` 源码中确认存在）。
3. **属性映射的 Holder 包装**：Curios 5.x 的 `Multimap<Attribute, ...>` vs 9.x 的 `Multimap<Holder<Attribute>, ...>`，与 §6.5 的 `Holder<Attribute>` → `Attribute` 是同一处改动。

**Iron's Spells 修正**：1.20.1 的正确版本线是 **`1.20.1-3.16.3`**，不是 `1.20.1-legacy`（3.4.0.11）——后者是过期分支，在那里接口叫 `MagicSummon` 而非 `IMagicSummon`，会造成「缺一个类」的误判。**按 3.16.3 计，12/12 全部命中。**

**一个新增的传递依赖约束**：Iron's Spells 1.20.1 把 `curios ≥5.14.1+1.20.1`、`geckolib ≥4.8.2`、`playeranimator`、`irons_lib` 列为**强制依赖**；Goety 1.20.1 也强制 `curios ≥5.3.4`。也就是说 1.20.1 侧的 Curios 5.x 是被第三方钉死的，没有选 9.x 的余地。

**结论**：**没有一件 compat 需要裁掉**。这与 fabric 移植时「Iron's/Goety 官方仅 NeoForge」的处境完全不同——降级方向上，这些模组的**首发平台本来就是 Forge**，1.20.1 版本比 1.21.1 版本更成熟（Goety/Goety-2 两条线并行维护，TLM 是同一天发的双 loader 版）。

代价只有两处：① 各依赖的 `versionRange` 按 1.20.1 版本线重写（JEI `[19,)` → `[15,)`、Curios `[9.4,)` → `[5.14,)`、Patchouli `[1.21,)` → `[1.20.1-85,)`）；② Curios 那 3 处成员级改写。

---

## 十、难度判断（综合）

按「触点规模 × 语义复杂度」定级（工作量相对比例，非日历）：

| 平台面 | 触点规模 | 难点 | 面级 |
|---|---|---|---|
| **网络栈** | 86 载荷 / 10,233 行 / 185 发送点 / 359 缓冲引用 | 承载它的 vanilla API 在 1.20.1 不存在，注册/发送/上下文三套全换 | **高（最大单点）** |
| 物品数据组件 → NBT | 62 处 / 25 文件，结构改造集中 `ItemKey` | 数据模型语义变更，非语法 | 中 |
| 原版 API 版本差 | `SavedData` 15 子类、`ResourceLocation` 114 文件、属性枚举 15 处、`appendHoverText` 9 文件、`MobEffectInstance` 39 处 | 逐处判断，无对应物 | 中高 |
| 原始 VBO 渲染 | 963 行 | 1.20.1 无等价公开 API，索引上传要重建 | 中高 |
| 工具链 + refmap | 整个 `build.gradle`、wrapper 版本、5 个 mixin | MDG 不能产 Forge 1.20.1；可能要把 Gradle 9.2.1 降到 8.x；refmap 从「不需要」变「必须」 | 中高 |
| 数据包静默失效 | 43 文件 / 6 目录 + 37 处字段 | 工作量小，**失败无声**，是发布阻断项 | 中（风险高） |
| tick 事件相位 | 20 文件 / 23 处 | 形状变化，逐个判断 | 中 |
| 配置屏 | 0（决策已落：入口裁掉） | 两线都不给自动配置屏入口，配置只有 TOML 与游戏内设置中心 | **0** |
| GUI 基类 4 处断点 | 4 处（含基类，全屏继承） | 在基类上，改动影响面大 | 中低 |
| Java 21 → 17 | ~30 文件 | 机械，但会编译失败 | 低 |
| mixin / AT | 5 + 3 | 一处启动崩溃；refmap 重映射 | 低-中 |
| 改名波（RRL/注册/总线/配置规格） | 114 + 84 + 94 + 61 处 | 正则级，零语义 | 低（量大） |
| 数据附件 | 0（TLM 兼容已决定不做） | 唯一无对应物，随 TLM 兼容一起裁掉 | **0** |
| Patchouli / 资产 / 模型 / lang | 603 + 1,348 JSON | 零改动 | 0 |

### 相对工作量分层

| 层 | 内容 | 占比 |
|---|---|---|
| **A. 机械改名（可脚本化，安全）** | 包根改名 376 行、`ResourceLocation` 142 处、注册返回类型 74 处、`EVENT_BUS` 94 处、`ModConfigSpec` 61 处、`ServerLifecycleHooks` 46 处、AT/依赖范围/CI | **约 45%** |
| **B. 需逐处判断的次机械改动** | tick 相位 23 处、`SavedData`/`appendHoverText`/属性枚举等原版版本差、Java 21→17 30 文件、GUI 4 断点、mixin 1 处、数据目录与字段 | **约 25%** |
| **C. 真重写** | 网络栈（最大）、物品数据、VBO 963 行、配置屏、attachment capability | **约 30%** |

### 主要行为等价风险

1. **网络协议一致性**：`RegistryFriendlyByteBuf` 降级为 `FriendlyByteBuf` 时，若有载荷依赖了注册表上下文（1.20.5 起才有），会写出读不回来的字节流。
   > **订正（2026-09-20，据 `networking-survey.md` §6.1 实测）**：本条原写「须逐个核对 86 个载荷的读写」，**高估了核对量**。按类型签名检索，真正用到注册表依赖 codec 的载荷只有 **3 个**——`NpcDataPacket`（`ItemStack.OPTIONAL_STREAM_CODEC` ×4）、`ExplorationRewardPacket` 与 `ScreenFeedbackPacket`（均 `ComponentSerialization.STREAM_CODEC`）。其余 83 个只写基本类型 / `CompoundTag` / 字符串。核对范围从 86 个缩到 3 个。
   > 口径边界：此为**签名层面**判定，未逐行核验 codec 方法体内是否另有注册表访问；另 `ScreenFeedbackPacket` 属 foundation 通用反馈包，是全库使用面最广的一个，优先核它。
2. **静默失效**：数据包目录与 JSON 字段错误**不报错**，可能一路带到发布。需要一份「加载后自检」清单。
3. **AT 与 mixin**：两者失败都只在运行时暴露，且当前 mixin 配置是「必需」级别。
4. **设置中心读配置规格**：`SettingItem.declaredRange` 用的是直接 API 调用（**不是反射**，原表述有误）。`ForgeConfigSpec.ConfigValue` 没有 `getSpec()`，但 `SPEC.getSpec().get(path)` 一行可等价替代——已确证，不再是风险项（见 §6.4）。
5. **`ClientTickEvent.Post.class` 类字面量注册**：漏改会是编译错误（好事），但相位判断漏写会是静默的行为差异（坏事）。
6. **compat 的「类名命中」不等于「能用」**：Curios 就是反例——10 个导入全部命中，但 `LazyOptional` 少两个方法、curio 注册方式不同。其余 5 个 compat 只做了类名级比对，**成员级形制未逐行核对**。

---

## 十一、与 fabric 移植 / 26.1 升级横评

| 维度 | **本报告：降级 1.20.1 Forge** | [fabric 移植](fabric-port-survey.md) | [26.1 升级](neoforge-26-upgrade-survey.md) |
|---|---|---|---|
| 版本层 | loader + MC **各退一步** | loader 换，MC 不变 | MC 跨 8~11 个版本，loader 不变 |
| 平台抽象层 | 无需新建 | 需新建 | 无需 |
| 「无对应物」缺口 | **10 项**，集中在网络与 1 个 attachment | 输入/相机、伤害死亡/仇恨、刷怪检查等多簇 | GUI 渲染状态机、实体渲染状态、数据附件序列化 |
| 事件面 | **27 个里 21 个同名** | 约 3 成需 mixin 或替代路径 | 大量改名 + 深度重写 |
| GUI 渲染 | **几乎全平**（4 处断点） | 同款（vanilla 未变） | **整层重写成双态状态机（成本最高）** |
| 数据 / 资产 | 需 6 处目录改名 + 37 处字段 | 原样共享 1,353 个 JSON | 原样可用 |
| 第三方 compat | **全部有 1.20.1 版**（1.20.1 反倒是这些模组的主场）；仅 Curios 需 3 处成员级改写 | Iron's/Goety 无 Fabric 版，需裁或降级 | 需逐一等各模组跟版 |
| 工具链 | MDG → ForgeGradle 6（+ refmap） | MDG → Fabric Loom | MDG 升版 + JDK 21→25 |
| 相对体量 | **中**（本轮三者最小） | 大 | 大 |

**一句话**：**降级到 1.20.1 Forge 是三次迁移里最轻的一次**——因为「退版本」在这个项目的技术栈里，丢掉的主要是 1.20.5 之后那批新 API，而项目恰好只重度使用了其中两个（网络包体系、物品数据组件），其余新特性都没怎么用；而生态侧（Curios/Goety/Iron's/TLM/Patchouli）**在 1.20.1 上反而是主场**。

---

## 十二、建议顺序（考察级，供立项时参考）

不是实施计划，只是给出「先动哪一块风险最低、收益最高」的判断。

1. **先做「不依赖 loader」的两件事**（可随时单独做、随时可停，对现版本零风险）：
   - 抽门面收口 185 个发送点（`foundation/networking/Net`，原 fabric 考察已记过同一笔账，属独立价值）——**已完成并合入 1.21.1**；
   - 抽 `ItemData` 工具类收口散落的 `DataComponents`/`CustomData` 读写——**已完成并合入 1.21.1**（实测 13 文件 → 1 文件，见 §6.2）。
   这两步做完，降级时最重的两块就都缩到单个类里；在「1.21.1 主、1.20.1 副」的双线模型下，它们同时也是**每次跟进**的主要成本削减器（要手工降级的热点从 73 个文件 / 13 个文件缩到各 1 个）。
2. **专项清数据包静默失效**（§七）：6 处目录改名 + 37 处字段改名。**注意：改名是 1.20.1 分支专属工作**——1.21.1 用单数目录，在主线改成复数反而会失效；主线这边只做「加载自查」（确认现版本没有被静默忽略的文件），因为失败是无声的，越早暴露越好。
3. **处理三处「会炸但不显然」的点**：`MixinMouseHandler#turnPlayer`、Java 21 语法回归、`ICancellableEvent`。
4. **换工具链**（ForgeGradle 6 + mods.toml + manifest `MixinConfigs` + refmap + Java 17），先把 build 打通再谈逐缝替换。
5. **按 §四表格逐类替换 loader 契约**（17 项，其中 9 项只是改名），同时按 §6.5 表处理原版 API 版本差（`SavedData` 签名、`appendHoverText`、属性枚举、`MobEffectInstance` 等）。
6. **重写网络栈与物品数据**（两块真成本）。
7. **最后处理 VBO 渲染与 attachment capability**两处局部重写（配置屏已裁，不再列出；入口裁撤本身属于分叉前就该在 1.21.1 做掉的前置，见 §6.4）。

### 一条需要知道、但不建议走的捷径

调研中发现 **MesdagPortLib**：一个声称把 NeoForge 式 API 前向移植回 **Forge 1.20.1** 的库，覆盖面恰好命中本文两大成本项——数据组件（编解码持久化 + StreamCodec 同步 + patch 更新）、数据附件（实体/BE/level）、一套自带包与富发送目标的网络层、注册表感知的 `FriendlyByteBuf` 扩展、data map、220+ 个移植事件、以及对 45+ 个 vanilla 类的接口注入。

**看起来能省掉 §6.1 与 §6.2 两块最大成本，但不建议作为方案前提**，理由三条：① **成熟度未知**——未找到任何生产采用或维护节奏的证据；② 它会把本项目「compat 全 compileOnly、无硬依赖、无感降级」的设计哲学，换成**一个硬运行时依赖**，与 CLAUDE.md 的「高兼容、不硬编码」倾向相冲；③ 降级本身的动机若是「跑在更普及的环境上」，再加一个冷门前置库是反效果。**留作背景信息，不作为备选方案。**

---

## 十三、参考源码清单（`_refs/`）

`_refs/` 已被 `.gitignore` 忽略，以下为本次考察落盘的参考源，抓取脚本 `_refs/fetch-1201-refs.sh` 可重跑（浅克隆、仅目标分支；本机代理常挂，脚本内统一 `-c http.proxy=` 直连）。

| 目录 | 来源 | 版本 | 用途 |
|---|---|---|---|
| `forge-1201-source/` | `MinecraftForge/MinecraftForge` 分支 `1.20.1`（sparse：`mdk/` + `src/main/java/net/minecraftforge/` + `javafmllanguage/` + `fmlloader/`） | MC 1.20.1 | **本文 loader 结论的唯一依据**：MDK 的 `build.gradle`/`gradle.properties`/`mods.toml` 与 `net.minecraftforge.*` 全量源码 |
| `curios-1201/` | `TheIllusiveC4/Curios` 分支 `1.20.x` | 5.14.1+1.20.1 | Curios API 逐包比对 |
| `goety-2-1201/` | `Polarice3/Goety-2`（master） | 2.5.58.3 / Forge 47.3.22 | Goety API 逐包比对（**注意不是 `Polarice3/Goety`**，那个仓库的 master 是 1.16.5 线） |
| `patchouli-1201/` | `VazkiiMods/Patchouli` 分支 `1.20.1` | 1.20.1 | `book.json` 字段与页面类型核对 |
| `jei-1201/` | `mezz/JustEnoughItems` 分支 `1.20.1` | 1.20.1（15.x 线） | JEI 插件 API 逐包比对 |
| `irons-spells-1201/` | `iron431/Irons-Spells-n-Spellbooks` 分支 `1.20.1-legacy` | **过期分支，3.4.0.11** | 只作参考——**该分支不是正确的 1.20.1 线**（正确的是 `1.20.1-3.16.3`），按它判断会得出「缺 `IMagicSummon`」的错误结论 |
| `tlm-120/` | `TartaricAcid/TouhouLittleMaid` 分支 `1.20` | 1.5.3-forge+mc1.20.1 | 车万女仆 API 逐包比对 |
| （原有）`Botania/` | 分支 `1.20.x` | MC 1.20.1 | 现成的 Forge 1.20.1 真实代码库参照 |

---

## 十四、需在动手时点复核的外部事实

以下为当日**未完全确证**或**会随时间变化**的项，动手前须重新核对：

1. **Gradle 版本**：FG6 是对 Gradle 8.1 API 编译的，而本仓 wrapper 是 **9.2.1**——「必须降到 8.x」是本文的判断而非官方明示，**动手第一件事就该拿一个空壳项目实测 6.0.54 + Gradle 9.2.1 能不能跑**，能跑就省一次 wrapper 变更。
2. **Forge 47.4.23 与 Parchment `2023.09.03` 的实际组合可用性**：两者都已确认存在（Parchment 自 2023-10 起冻结于这一个 1.20.1 版本），但未在本机构建验证过。
3. **Curios 5.x 成员级差异是否已穷尽**：本文只抓到了 `LazyOptional` 两处 + `registerCurio` 一处 + 属性 `Holder` 一处；`compat/curios` 共 8 个文件，未逐行重读。
4. **配方 / 附魔面的残余**：`RecipeInput`/`SizedIngredient`/`EnchantmentHelper` 全仓 0 处使用，`RecipeManager.getAllRecipesFor` 的返回类型差已确证（1 文件）；但 `compat/goety/GoetyHelper` 里 22 处 `Enchantment` 相关的**实际调用形制**未逐行核对（仅 1 文件，风险有限）。
5. **Forge 1.20.1 的 mixin 加载细节**：本文已确认「`[[mixins]]` 不适用于 Forge、须走 manifest `MixinConfigs`」，但 ForgeGradle 6 + MixinGradle 在该版本上是否需要额外 `refMap` 配置，需实测。
6. **Forge 1.20.1 的维护态势**：本文核实的最新构建是 `47.4.23`（2026-08-19 发布），说明该线**当前仍在收更新**；但这是一条长尾线，后续节奏需在立项时再确认。另注意 FG6 已有 `FG_7.0` 分支在开发。
7. ~~**`SettingItem` 的反射读配置规格**在 `ForgeConfigSpec` 上是否真的可用~~ —— **已确证（2026-09-23）**：不是反射，且替代写法唯一（`SPEC.getSpec().get(path)` → `ValueSpec.getRange()`），详见 §6.4。
8. **`RegistryFriendlyByteBuf` → `FriendlyByteBuf`** 降级后，86 个载荷的读写是否逐一对齐（本文判断为「可整体复用」，但未逐字节核对）。
9. **`irons-spells-1201/` 参考源是过期分支**：它落在 `1.20.1-legacy`（3.4.0.11），而正确版本线是 `1.20.1-3.16.3`。若要重做该模块的逐包比对，需改抓 3.16.3 的源码或直接对 jar 做 `javap`。
10. **MesdagPortLib 的成熟度**（见 §十二 末段）：它号称把 NeoForge 式 API 前向移植回 Forge 1.20.1，若能成立可省掉网络与数据组件两块最大成本；但未找到任何生产采用或维护节奏的证据，**不建议作为方案前提**。
