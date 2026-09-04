# Fabric 1.21.1 官方移植考察报告（fabric-port-survey）

> 信息截至 2026-09-04 | 考察基线：分支 `1.21.1`（Minecraft 1.21.1 / NeoForge 21.1.233 / Mojmap）
> **性质：长期可行性考察，不是移植实施计划**。所有数字为当日代码快照，随开发会漂移。

- **【何时读】**：评估「要不要做双 loader 官方版」「仓库结构将来怎么演」、或日后真正动手前先读本报告定边界。
- **【不包含什么】**：分步移植步骤、里程碑排期、可勾选行动清单——那是动手时的活。

---

## 一、结论速览（TL;DR）

- **体量**：657 个 Java 文件、约 **10.4 万行**（content 85.5k / foundation 10.8k / compat 3.1k / api 1.5k / impl+mixin 0.6k）。单 loader（NeoForge 独占），**没有任何平台抽象层**，`net.neoforged.*` 直入业务类。
- **平台耦合量**：177 文件直接 `import net.neoforged.*`（≈27%），事件订阅 ~50 文件、约 78 个订阅耦合点。
- **整体定性：大型移植工程，不是机械平移**。粗估可「原样拷贝」约五成、「机械改造」约三成、「语义重写」约二成。硬骨头集中在一处结构性缺口：**客户端全局输入/相机控制**，和 **生物伤害/死亡/仇恨管线**——这两块 Fabric API 没有现成等价，需要 mixin vanilla 或改架构。
- **真实利好**：4 个 mixin 全打 vanilla 类、80 个网络载荷是 `record`+StreamCodec、数据与资产全 JSON 数据驱动（1353 个 data json 原样共享）、引擎内核纯逻辑零 MC、若干 NeoForge 语义在 Fabric 有近乎一一对应（ServerTick/ClientTick/WorldRender/HUD/区块加载/连接生命周期）。
- **Compat 是独立变量**：Curios/Iron's Spells/Goety 三件套在 Fabric 侧对象不同或缺失（详见 §五），决定「功能面 100% 平替」做不到还是降级做。
- **结构判断**：长期最稳是 monorepo 拆「common + 平台模块」并先收口 loader 触点；短期若只想试水则另开分支更省事（但同步会撕裂）。取舍见 §七。

---

## 二、规模与耦合总览（硬数据）

| 顶层包 | 文件数 | 行数 | 备注 |
|---|---|---|---|
| content（13 功能域） | 512 | 85,547 | 业务主体；loader 耦合散落各域 |
| foundation | 86 | 10,757 | 跨域基建（ui/networking/log/registry…） |
| compat | 24 | 3,103 | JEI/Curios/Iron's/Goety，compileOnly |
| api | 23 | 1,513 | 极薄契约，基本零耦合 |
| impl + mixin | 8 | 599 | 装配点 + 4 个 vanilla mixin |

**NeoForge import 分布**：`import net.neoforged.*` 共 177 文件。高频类：

| 类 | 用途 | import 文件数 |
|---|---|---|
| `neoforge.network.PacketDistributor` | 发包 | 65（≈231 处调用，无统一 util） |
| `neoforge.common.NeoForge`（总线） | 全局事件总线 | 42 |
| `neoforge.server.ServerLifecycleHooks` | 静态取当前 server | 35 |
| `bus.api.SubscribeEvent` | 事件订阅 | 29 |
| `neoforge.network.handling.IPayloadContext` | 收包上下文 | 12 |
| `neoforge.event.tick.ServerTickEvent` | 服务端 tick | 12 |
| `neoforge.client.event.RenderLevelStageEvent` | 世界渲染阶段 | 12 |
| `neoforge.client.event.InputEvent` / `ClientTickEvent` | 输入/客户端 tick | 8 / 8 |
| `neoforge.registries.DeferredRegister` / `DeferredHolder` | 注册 | 10 |
| `fml.ModList` / `FMLEnvironment` | 模组探测/判端 | 9 |
| 其余 event.*（伤害/死亡/交互/区块/睡眠…） | 各 1–5 | — |

---

## 三、六条平台接缝逐一考察

### 3.1 入口与装配 ——「两点集中 + 大量自注册」混合，集中度中高

- `Wandscape` 是唯一 `@Mod` 入口：构造里统一挂 10+ 个 `DeferredRegister`、接 NeoForge 总线（40+ handler/系统 `.register()`）、配网络与配置，`FMLEnvironment.dist` 判端后显式调 `WandscapeClient.init`。
- `WandscapeClient` 无 `@Mod`，由主类按 dist 拉起；MOD 总线的**注册类事件**（RegisterMenuScreens/KeyMappings/EntityRenderers×2/ItemColors/Particles/ReloadListeners/FMLClientSetup）全部 `@SubscribeEvent` 收在类内（WandscapeClient.java:176-657），另收 ClientTick/连接事件，并注入 ~40 个 S2C 载荷的 `setClientHandler(...)`。
- 其余装配散在「各 handler 的静态 `register()`」里。
- **Fabric 判定**：无 `@Mod`/无事件总线，全部落成 `ModInitializer` / `ClientModInitializer` + 一组注册器回调（`EntityRendererRegistry`、`EntityModelLayerRegistry`、`ColorProviderRegistry.ITEM`、`ParticleFactoryRegistry`、`KeyBindingHelper`、`HandledScreens`…）。改造机械但条目多，主装配本身是清晰 choke point。
- `impl/` 里 `CoreBootstrap`、`TemplateResolver` **零 MC 依赖**（纯 ECS），可整块原样搬。

### 3.2 网络 —— 注册单点、发送分散、handler 两套并存（最散的一条）

- **载荷 80 个**，各 `record implements CustomPacketPayload`，StreamCodec 可原样搬；ID 散在各类的 `TYPE` 字段，无集中 ID 表。
- **注册收敛一处**：`Wandscape.onRegisterPayloads`（Wandscape.java:577-957），38 次 playToClient + 41 次 playToServer，`versioned("1.0")`。
- **发送无统一 util**：`PacketDistributor` 直调散落 **72 文件、231 处**。
- **handler 两套并存**：C→S 多数 `handleServer(packet, IPayloadContext)`（`ctx.player()` 强转）；S→C 走静态 `handleClient` 只 dispatch 到静态 `Consumer`，真逻辑由 `WandscapeClient` 的 `setClientHandler` 注入 —— 与客户端状态强耦合。
- **Fabric 判定**：发送/收包上下文 API 差异大（无 `IPayloadContext`、需线程切主线程），这两点会推着引入统一 send util + 统一 handler 签名——是**重构动作最大的一条缝**，也是日后单 loader 自身就该收口的地方。

### 3.3 GUI/菜单/容器 —— 真容器少，海量「直开屏」，中等

- 真容器菜单仅 **3 个 NeoForge 注册 + 1 个 Curios 条件注册**（WarehouseMenu/NpcMenu/NpcStrategyMenu/NpcCuriosMenu），都 `extends AbstractContainerMenu`。
- MenuType：vanilla `new MenuType<>(…)` 为主；Curios 用 NeoForge `IMenuTypeExtension.create`（buffer 版）。
- **容器数据同步不走 ContainerData/DataSlot（全库 0 处）**：槽数据由服务端权威，打开后经自订载荷（WarehouseMenu.sendRefresh → WarehouseDataPacket）推送，客户端 `bindSlots` 指向显示列表；NPC 装备槽走真 vanilla Slot 自动同步。
- 大量「屏」是**无 MenuType 的纯客户端 Screen**，S2C 载荷客户端回调直接 `Minecraft.setScreen(...)` 打开（Shop/Tavern/Hotel/Altar/TownHall/Tourist/MageHut/Node/ConstructionSite…，集中在 WandscapeClient.java:259-316）——完全绕过 menu 流。
- **Fabric 判定**：菜单类/Screen 类是 vanilla 两侧同款；`RegisterMenuScreensEvent → HandledScreens`、`IMenuTypeExtension → ScreenHandlerType.Extended` 语义相同；`setScreen` 直开屏则零改动。改造主要落在 4 个菜单注册 + Curios 槽实现。

### 3.4 配置 —— NeoForge ModConfigSpec，面窄但需另写

- `Config`（COMMON，~24 键）+ `ClientConfig`（CLIENT，4 键）两个文件，`ModConfigSpec`；`Wandscape` 里 `modContainer.registerConfig`。
- 客户端配置屏由 NeoForge **自动生成**（`IConfigScreenFactory` → `ConfigurationScreen`），无手写 UI。
- **Fabric 判定**：需换自写 JSON 配置 + 配置屏（或依赖 Cloth/ModMenu 类库），键的读写值模型要迁一遍。面窄、价值中，属「重写」清单项。

### 3.5 注册 —— 全 DeferredRegister（13 处），低危但含两个 loader 专有点

- 全部 `DeferredRegister`，无一处 vanilla `Registry.register`：3+ 方块、28+ 物品、EntityType×5、BlockEntityType×2、ParticleType×2、SoundEvent ~14、vanilla Attribute×6（好迹象）、MobEffect×6、MenuType 3+1、CreativeTab×1。
- **loader 专有点 A**：自定义 `EntityDataSerializer` 注册进 `NeoForgeRegistries.ENTITY_DATA_SERIALIZERS`（Wandscape.java:183-187，`beam_target`）——代码注释明说「NeoForge 限制 vanilla 表」，Fabric 无同级注册表，需按 Fabric 惯用做法（通常 mixin vanilla `EntityDataSerializers`）单独处理，触点 1 处。
- **loader 专有点 B**：`META-INF/accesstransformer.cfg` 把 `NearestAttackableTargetGoal#targetType` 提为 public（HostileTargetingHandler 直读）。Fabric 需转成同语义的 **access widener**（作用等同、文件格式不同），触点 1 处。
- 数据驱动注册（BuildingConfig/Element/Spellbook/Road 经 JSON loader 内存注册）非游戏注册表，**零耦合可搬**。
- **Fabric 判定**：DeferredRegister → vanilla `Registry.register` 静态化，结构相近、低危。

### 3.6 Mixin 与访问权限 —— 全部 vanilla 目标，最顺的一条

- 4 个 mixin：`LevelTicks#schedule`（建造守卫取消排队 tick）、`ServerLevel#isVillage`（市政厅视同村庄让袭击跑满波次）、`Camera#setup` ×2（俯瞰/样条编辑器注入相机位姿）。全 @Inject、无 handler 注入、无 NeoForge 目标。
- **Fabric 判定**：fabric-loader mixin 同语义，仅需改 mixin 配置（且需按 client/server 拆两份 json）、补 access widener、加 fabric banner。≈原样搬。

---

## 四、事件订阅面 → Fabric 对应物

共 ~50 文件订阅至少一个 NeoForge 事件（不含注册类生命周期，见 3.1）。按订阅文件数降序：

| NeoForge 事件类 | 订阅文件 | 代表域 | 1.21.1 fabric-api 判定 |
|---|---|---|---|
| RenderLevelStageEvent | 13 | ui/building/tourist/colony/road | 直接：`WorldRenderEvents`；需逐点替换 + stage→phase 映射（量大、风险低） |
| ServerTickEvent (Post) | 12 | 各域 | 直接：`ServerTickEvents.END_SERVER_TICK`，机械替换 |
| ClientTickEvent | 9 | foundation/ui、colony、road、building | 直接：`ClientTickEvents.END_CLIENT_TICK` |
| InputEvent（鼠键全局） | 8 | 相机/面板控制器 | **无现成**：fabric-api 无全局鼠标/键盘事件，需 mixin `MouseHandler`/`KeyboardHandler` 或重写输入消费 |
| RenderGuiEvent (Post) | 6 | panel/overlay/preview | 直接：HUD 渲染回调（`HudRenderCallback`） |
| PlayerEvent（登录登出/换维） | 5 | main/ui/task/items | 部分：登录登出→连接事件；**换维度无现成** |
| LivingIncomingDamageEvent | 3 | npc/guard | **无现成**：需 mixin 伤害入口或改写攻击逻辑 |
| PlayerInteractEvent | 3 | scepter/building/scanner | 直接：`UseBlock/UseItem/AttackBlockCallback` |
| MovementInputUpdateEvent | 3 | 相机/面板控制器 | **无现成**：需 mixin `LocalPlayer` 或 tick 内覆盖输入 |
| LivingDeathEvent | 2 | scepter/npc | 存疑：稳妥走 mixin `LivingEntity#die` |
| ScreenEvent.Opening | 2 | foundation/ui(panel/ReplayGuard) | 半直：`ScreenEvents.BEFORE_INIT` 不可取消，需取消语义则 mixin |
| BlockEvent（放置/破坏） | 2 | main/tourist | 部分：破坏有 `PlayerBlockBreakEvents`；放置后无现成 |
| LivingDamageEvent.Pre | 1 | magic（石化/护甲削减） | **无现成**：需 mixin 伤害管线 |
| LivingChangeTargetEvent | 1 | npc/guard | **无现成**：需 mixin `Mob#setTarget` |
| MobSpawnEvent.SpawnPlacementCheck | 1 | building（防刷怪区） | **无现成**：需 mixin 刷怪逻辑 |
| ChunkEvent / EntityJoinLevel / ServerStarting…Stopped / AddReloadListener / RegisterCommands / OnDatapackSync / ClientPlayerNetwork | 各 1 | main 等 | 直接：fabric-api 均有对应回调/注册器 |

**映射占比（按订阅耦合点粗算 ~78 个）**：≈7 成可直接落到 fabric-api 现成回调/注册器；≈3 成需 mixin 或替代路径。按文件粗估：~50 个订阅文件里 ~18–20 个至少触及一处「无现成」事件，即约 6 成文件可纯回调直改、约 4 成需至少一处 mixin/重写。

**隐形耦合（不属 net.neoforged 事件类但骑 NeoForge 总线）**：本项目把 NeoForge 全局总线当进程内总线，投递了一批自定义 mod 事件（投递 ~12 文件：WarehouseManager、BuildingApiImpl、DailySettlementSystem、RaidTriggerScanner、ColonyItemBank、ShopStockManager、ColonyRaidTracker、ColonyCommand、TouristApiImpl 等；订阅 ~5 文件：StatisticsCollector、WonderEffectApplier、AchievementService、ChunkLoadManager 等）。改挂已存在的自研 `SimpleEventBus`（content/task/event，无 NeoForge 依赖）即可去耦合，改造量小。

### 最痛三簇

1. **客户端输入/相机控制簇（8 文件，最硬）**：`InputEvent` + `MovementInputUpdateEvent`，集中在 flight/相机控制器（OverviewFlightController、SplineEditorController、ProjectionFlightController、WandscapePanelController、BuildGizmoController、ScannerGizmoController、RoadStudioOverlay、BuildingDebugOverlay）。这些控制器靠**取消/吞掉 vanilla 输入**做自定义相机与面板交互——而 Fabric 无全局输入钩子，通常要 mixin `MouseHandler`/`KeyboardHandler`/`LocalPlayer` 或把「输入消费」改造成 tick 内轮询，行为等价最难保。
2. **生物伤害/死亡/仇恨簇（7 文件）**：`LivingIncomingDamageEvent`/`LivingDamageEvent.Pre`/`LivingDeathEvent`/`LivingChangeTargetEvent`（npc/guard×4、MagicEventHandler、NpcDeathHandler、ScepterDeathHandler）。NPC 守卫「替伤害」、魔法感化/石化减伤全挂伤害管线；Fabric 无此管线事件，需 mixin 伤害入口或改由攻击方逻辑改写。
3. **RenderLevelStageEvent（13 文件，量最大、风险最低）**：直接对应 `WorldRenderEvents`，但 13 个渲染点 + 相机相位映射是纯劳动量。

其余零散缺口各 1–2 文件（睡眠结算、放置后回调、换维度、Screen 取消、刷怪检查）。

---

## 五、Compat 层与第三方模组在 Fabric 的可用性

现状：`ModList.get().isLoaded(id)` 装入静态 `loaded` 作守卫 + 门面/实现分离（Impl 类只在 loaded 为真时才被触达，避免 NoClassDefFoundError）；依赖全 compileOnly（无 jarJar/打包），铁魔法/诡厄只做「纯标签判定」注册进 NpcMainHandApi，不引外部类。JEI 走自身 `@JeiPlugin` 扫描，不经 loader 入口。→ 判定机制迁到 `FabricLoader.isModLoaded` 是直译，**低危**。

| 第三方 | 现状接入 | 1.21.1 Fabric 侧现状（2026-09 检索） | 对移植的影响 |
|---|---|---|---|
| Curios | NPC Curios 槽数据包声明 + IMenuTypeExtension | Curios 本体仅 Forge/NeoForge；Fabric 生态对应为 **Accessories**，另有 Accessories/Curios 兼容层（Curios Compat Layer for Accessories）可让按 Curios API 写的模组跑在 Accessories 上 | 二选一：改接 Accessories API，或保留 Curios API 写法靠兼容层在 Fabric 落地——需动手时实证，属重写面 |
| Iron's Spells 'n Spellbooks | 铁魔法标签判定/属性桥/门控 | 官方仅 NeoForge（无官方 Fabric 版） | Fabric 侧无对等物 → 该功能面要么裁、要么等未来、要么降级为无 Iron's 的体验 |
| Goety | 聚晶/volley 化/随从友军 | 官方仅 NeoForge | 同上 |
| JEI | @JeiPlugin 插件 | JEI 双 loader 同有 | 低危 |

**另注（可行捷径）**：Kilt（NeoForge-on-Fabric 兼容层，21.1.x 已有）理论上可让现 NeoForge 版直接跑在 Fabric loader 上，属「运行层兼容」而非「官方移植」——品质/体验/审核角度均不满足"官方移植版"的意涵，仅作背景参考。

**与理念自洽**：本项目本就奉行「高兼容、无感降级」（ModList 守卫即为此设）。Fabric 版把无对等物的 compat 功能自然休眠，符合既有降级哲学，不算退步——所以 compat 面可接受的裁剪空间其实很大。

---

## 六、难度判断（综合）

按「触点规模 × 语义复杂度」给各面定级（工作量相对比例，非日历）：

| 平台面 | 触点规模 | 难点 | 面级 |
|---|---|---|---|
| 网络发送/收包 | 80 载荷 + 72 文件/231 处 | 无 util、handler 两套、fabric 上下文差异 → 需先收口再迁 | **高（重构量最大）** |
| 客户端输入/相机 | 8 控制器文件 | fabric 无全局输入钩子 → mixin/重写，行为等价最难保 | **高（最硬）** |
| 伤害/死亡/仇恨管线 | 7 文件 | fabric 无事件 → mixin vanilla 或改入口 | **中高** |
| 事件订阅映射（其余） | ~50 文件 | ≈7 成机械替换；零散缺口各想办法 | 中 |
| RenderLevelStage / HUD | 13+6 文件 | 量大、低风险 | 中（纯劳动量） |
| GUI/菜单/容器 | 3+1 菜单，大量直开屏 | vanilla 同款，改注册面 | 中 |
| 注册（含 serializer/AT） | 13 DeferredRegister + 2 专有点 | 低危，serializer 需单独解法 | 低-中 |
| Mixin | 4（全 vanilla） | 顺 | 低 |
| 配置 | 2 文件 | 需另写 json+屏 | 低-中 |
| Compat | 4 模组 | 裁/换/降级三选一，决策面大 | 决策主导 |
| 纯逻辑岛 / 数据资产 | CoreBootstrap、1353 data json、193 纹理 | 零改动 | 0 |

**总体**：体量属「一个大型移植项目」（10 万行回归面 + 上述重写点），绝非一个周末或一次 clone 平移能完成；但只要走「先收口网络发送与自研事件总线、再逐缝替换」的路径，每一缝都有明确解法，**没有技术死路**。真正的不确定项集中在三处需要拍板的事：① Fabric 侧功能面是否接受 compat 裁剪（§五）；② 输入/伤害两簇愿意为行为等价投多少 mixin；③ 仓库结构选型（§七）。

**主要行为等价风险**：输入吞吃语义、RenderLevelStage 的 stage→phase 映射、tick 时序/多 tick 事件先后、`ServerLifecycleHooks` 静态取 server 的时机（35 处）、客户端 S2C handler 注入（~40 回调）与主线程排队。

---

## 七、仓库结构的选择面（考察意见，非方案）

### 现有约束（决定选型的前提）

- 单一事实源、归属看语义、禁搭桥/壳层；文档与代码同 commit；`./gradlew build` 即验证；开发期不承诺存档兼容（所以不需要跨 loader 共享存档——Fabric 版可自由重来）。
- 10 万行单仓库已很重，双 loader 同步若走「分支分叉」会立刻把「同一逻辑两处存在」的裂缝拉大——**这与本项目文档纪律（一个概念一个唯一命名类）正面冲突**。

### 三种路线与各自卡点

1. **monorepo 拆 common + 平台模块**（先重构后移植）
   - 形态：把纯逻辑 + 纯 vanilla 逻辑 + 数据资产沉入 loader 无关 core；NeoForge/Fabric 各自薄模块只放接缝（入口、事件映射、注册、网络收发封装、配置）。与既有「纯逻辑零 MC」边界同构，最贴本项目气质。
   - 卡点：**前置一次收口重构**——把现在散在业务类里的 231 处直发、5+12 个自定义事件总线骑挂、Dist 判端等收成 seam。收口本身不依赖 Fabric，随时可做、随时可停，是「低风险、高回报」的演进而非一次性大爆炸。
   - 长期收益：一次移植永久获益，双 loader 同一批业务代码，README/数据/指南全共享。
2. **独立 fork / 分叉分支**
   - 形态：现仓库照旧，另起 fabric 分支/仓库平行移植。
   - 卡点：先爽后痛——每次功能更新都要双写，文档/ADR/数据格式变更两头同步；本项目重构刚完结、功能迭代仍在快车道，此路线半年后同步成本会吃掉全部早期省下的力。
   - 适用：只想验证「Fabric 有没有发行价值」的试水阶段。
3. **第三方双 loader 脚手架**（Architectury / Stonecutter 类）
   - 形态：工具链替你维护 common+loader 样板与版本切换。
   - 卡点：引入元层依赖与 DSL，与「禁桥层、自研极简、文档单事实源」的仓库文化互斥；且本仓对 build 的依赖极简（就一个 `./gradlew build`），上脚手架会把验证命令复杂化。**长期看与 1 冲突**，不推荐作为落点。

### 现在仓库对移植「友好 / 不友好」的观察（不列行动项）

- 友好：mixin 全 vanilla；80 载荷 record+StreamCodec；1353 data json + 资产零 loader 依赖；无 DataSlot 而走自订载荷（同步语义 loader 无关）；vanilla Attribute 收编 NPC 属性；4+2 个纯逻辑零 MC 岛；API 层 23 文件近零耦合；JEI 走自身扫描不经 loader 入口。
- 不友好：网络发送无 util（72 文件/231 处）；自定义 mod 事件骑 NeoForge 总线；S2C handler 集中注入且与客户端状态耦；AT 与 EntityDataSerializer 两个 loader 专有点虽各 1 处但 Fabric 无同款；`RenderLevelStageEvent` 13 点与相机/输入控制器把大量客户端逻辑钉在 NeoForge 事件形状上；配置屏自动化省了事但没留 UI。

### 倾向

若日后真做，走**路线 1**，但把它当「先收口接缝」的长期演进而非一朝移植：接缝收口是双 loader 之前的独立价值。本报告不展开步骤（非本报告目的），只记录这个判断供日后立项时复用。

---

## 八、需在动手时点复核的外部事实

- Iron's Spells 'n Spellbooks / Goety 官方是否仍仅 NeoForge、是否出现 Fabric 版（本报告据 2026-09 检索判为无官方版）。
- Curios for Fabric 的最终形态（Accessories API 直接 vs 兼容层间接）。
- fabric-api 各事件在目标版本的确切存在性（如 `ServerLivingEntityEvents` 的去留、`SYNC_DATA_PACK_CONTENTS` 回调版本）。
- Kilt 若被采用需验证品质；本报告仅记背景。
