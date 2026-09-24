# 迁移与重构活清单（checklists）

> 信息截至 2026-09-02 | Minecraft NeoForge 1.21.1

- **【何时读】**：大版本升级、重构推进、提交 PR 或发版前进行质量与规范核对时。
- **【不包含什么】**：琐碎日常提交 log。

---

## 一、大重构活清单（Refactor Checklist）

各阶段做完后直接在原地划 `~~` 留痕，不删不重写：

### Tier 0: 摸底与认知对齐
- [x] ~~全仓 29 个顶层包真实代码摸底，产出事实源 `newplan/packages.md`~~
- [x] ~~建立重构进度唯一定位器 `newplan/status.md` 与开发指南 `CLAUDE.md`~~

### Tier 1: 死代码与死字段清理
- [x] ~~物理删除 0 引用死类（`InterruptRecord`, `EquipmentPreset` 等）~~
- [x] ~~全仓清理 37 处未读私有死字段与 13 处真死私有方法~~
- [x] ~~物理删除全部 `src/test` 目录（拒绝低效测试灌注）~~

### Tier 2: 改名与去撞名
- [x] ~~核心撞名类重命名（`ecs/System` → `EcsSystem`, `AttributeModifier` → `NpcAttributeModifier`, `Inventory` → `NpcInventory`）~~
- [x] ~~生产级测试类改名（`GuideTestScreen` → `GuideScreen`, `GuideTestPacket` → `GuideDocOpenPacket`）~~
- [x] ~~删除历史残留死接口与死服务（`HouseApi`, `StatsService`）~~

### Tier 3: 样板与规则合并
- [x] ~~NPC 属性五处定义统一收敛至 `content/npc/attributes/NpcAttributes` 单类~~
- [x] ~~制作站合成动作与抄写动作统一至 `production:craft` 与 `CraftRecipeView`~~
- [x] ~~配方解析公共抽取 `ElementMaps.parse`~~

### Tier 4: 骨架迁移与桥层消解
- [x] ~~21 个旧顶层功能包迁入 `content/` 目标骨架~~
- [x] ~~消融 `shared/` 桥层（139 类分配至 content/foundation/api）~~
- [x] ~~消融 `core/` 与 `engine/` 桥层（88 类分配至 content/task/npc/colony/warehouse/foundation/impl）~~
- [x] ~~解散 `WandscapeEngine` 上帝定位器，收敛至 `TaskRuntime`~~
- [x] ~~API 瘦身与归口，内部调用废除搭桥直接引用~~

### 横切基建与功能深化
- [x] ~~日志 SLF4J 治理体系与 16 域分类降噪~~
- [x] ~~天平数值持久化 JSON 覆盖（`wandscape_balance.json`）~~
- [x] ~~新手引导与指南书概念拆分（`content/tutorial` 独立）~~
- [ ] UI 去堆框架（通用 Screen 数据驱动样板，替代每建筑一个独立 Screen）
- [ ] `newplan/api-ledger.md` 中待实现 API 落地（`NpcApi.spawnNpc`, `ColonyApi.setName` 等）
- [ ] 暂缓项归位（`content/command/` 清理、`mixin/` 按域划分）

---

## 二、开发与 PR 守门员清单（Code Review Checklist）

在提交代码前逐项核对：

- [ ] **直接调用**：域间协作是否直接调用业务类，未引入新的 API 中转或 EventBus 强制解耦。
- [ ] **纯逻辑解耦**：`content/task`、算法、公式、属性规则中是否绝对无 Minecraft / NeoForge import。
- [ ] **NBT 安全**：对外暴露复合标签时是否使用了 `tag.copy()`。
- [ ] **任务归属**：新任务发布时是否显式传递了 `colonyId`（未产生无主幽灵任务）。
- [ ] **统一日志**：是否使用 `Log.info/debug/warn`（严禁 `System.out.println`，严禁静默 catch 吞异常）。
- [ ] **文本规范**：玩家可见文本及源码注释中是否绝对无 emoji 与多余装饰符号。
- [ ] **构建编译**：`./gradlew compileJava` 与 `./gradlew build` 100% 通过。

---

## 三、发版与发布清单（Release Checklist）

版本号规范：**`<版本>-<游戏版本>`**，游戏版本去掉点号（1.21.1 → `-1211`，1.20.1 → `-1201`）。
例：`mod_version=2.1.2-1211` → jar `wandscape-2.1.2-1211.jar`、tag `v2.1.2-1211`。
双线并行后两版内容不同步，游戏版本必须焊进版本号，否则玩家在 CurseForge/Modrinth 上会装错线。

发版时按顺序执行：

1. [ ] 更新 `gradle.properties` 中的 `mod_version` 为新版本号（含游戏版本后缀）。
2. [ ] 清理 `build/libs/` 下旧构建 jar。
3. [ ] 运行 `./gradlew build` 确认全量编译通过。
4. [ ] 提交发版 commit（格式：`chore: mod_version <版本>-<游戏版本> — 发布说明`）。
5. [ ] 打 Git 标签 `git tag v<版本>-<游戏版本>`。
6. [ ] 推送分支与标签（分支名 = 游戏版本：主线 `1.21.1`、副分支 `1.20.1-forge`）：
   ```bash
   git push origin 1.21.1 --tags
   ```
7. [ ] 使用 GitHub CLI 发布 Release 并上传构建产物：
   ```bash
   gh release create "v<版本>-<游戏版本>" "build/libs/wandscape-<版本>-<游戏版本>.jar" \
     --title "Wandscape <版本>-<游戏版本>" --notes-file RELEASE_NOTES.md
   ```
8. [ ] CurseForge / Modrinth 的 Game Version 标签按线选（1.21.1 或 1.20.1），文件名保持同一格式。

---

## 四、1.20.1 降级活清单（Downgrade to 1.20.1 / Forge 47.x）

主线 `1.21.1` 不受影响；降级全部落在分支 `1.20.1-forge`（独立 worktree，与主仓平级）。依据 `docs/forge-1201-downgrade-survey.md`，§12 给出建议顺序。各阶段做完后原地划 `~~` 留痕，不删不重写。

> 触点数字取自 2026-09-24 实测。原报告的 §3.2 / §四 / §五 写于网络包与 ItemData 两次重构**之前**，触点已缩水三成左右（loader import 201 文件 → 135），**动手每一步前按当次实测重新数**。

### 里程碑 1：分支与工具链
- [x] ~~建 `1.20.1-forge` 分支与并列 worktree `../wandscape-1201`~~
- [x] ~~wrapper 9.2.1 → **8.9**（FG6 对外编译目标是 Gradle 8.1 API，9.x 不受支持）~~
- [x] ~~`settings.gradle` 补三个插件仓库（MinecraftForge / Sponge / ParchmentMC）+ mixingradle 的 `resolutionStrategy`；foojay 1.0.0 → 0.7.0~~
- [x] ~~`gradle.properties` 换 1.20.1 数值、删 `configuration-cache`、JVM 内存 1G → 3G、第三方按 1.20.1 线重写、删 TLM~~
- [x] ~~`build.gradle` 重写为 ForgeGradle 6 + MixinGradle（`minecraft{}` / `mixin{}` / `fg.deobf` / `reobfJar` / manifest `MixinConfigs`）~~
- [x] ~~`neoforge.mods.toml` → `src/main/resources/META-INF/mods.toml`，改 Forge schema，去掉 `[[mixins]]` / `[[accessTransformers]]`~~
- [x] ~~CI JDK 21 → 17~~
- [ ] 工具链跑通门槛：`./gradlew build` 能走到 `compileJava`，且**不出现 Gradle 配置错误**（源码报 `package net.neoforged does not exist` 属预期）

### 里程碑 2：机械改名波 + Java 17 回归
- [ ] 包根 `net.neoforged.*` → `net.minecraftforge.*`（135 文件 / 289 import）；`NeoForge.EVENT_BUS` → `MinecraftForge.EVENT_BUS`（53 文件 / 93 处）
- [ ] `ResourceLocation.fromNamespaceAndPath` / `.parse` / `.withDefaultNamespace` → `new ResourceLocation(...)`（126 文件 / 154 处）
- [ ] 注册返回类型 `DeferredHolder` / `DeferredItem` → `RegistryObject`（9 文件 / 84 处）；`ModConfigSpec` → `ForgeConfigSpec`（3 文件 / 61 处）；`NeoForgeRegistries` → `ForgeRegistries`
- [ ] `DeferredRegister.createItems/createBlocks` → `create(ForgeRegistries.ITEMS/BLOCKS, MODID)`；`DeferredSpawnEggItem` → `ForgeSpawnEggItem`（构造签名不同）；`IMenuTypeExtension` → `IForgeMenuType`
- [ ] `LivingIncomingDamageEvent` → `LivingHurtEvent`（6 文件 / 13 处）
- [ ] Java 21 语法回归：`Math.clamp(`（23 文件 / 53 处）、switch 类型模式（2 文件 / 9 处）、record 解构（`MarkdownRenderWidget`，11 处）、`List.getFirst()`（3 处）
- [ ] `wandscape.mixins.json` 的 `compatibilityLevel: JAVA_21` → `JAVA_17`

### 里程碑 3：静默失效与启动崩溃（失败无声，专项清）
- [ ] 数据包目录单→复：`advancement`(33) / `loot_table`(2) / `recipe`(2) / `tags/block`(1) / `tags/item`(1) / `curios/tags/item`(4)。**`damage_type/` 两版都是单数，不要「顺手改对」**
- [ ] JSON 字段：进度图标 `{"id":}` → `{"item":}`(31)、进度谓词 `items` 标量→数组(2)、配方 `result`(2)、`neoforge:mod_loaded` → `forge:mod_loaded`(2)
- [ ] `MixinMouseHandler#turnPlayer(double)` → 1.20.1 是**无参** `turnPlayer()`（配置 `required:true`，不修即启动崩溃）
- [ ] `ICancellableEvent`（`ExplorationChestRewardEvent`）→ `@Cancelable` + `isCanceled()`/`setCanceled()`
- [ ] AT 三条字段放宽实测生效（AT 失败不编译报错、只在运行时炸）

### 里程碑 4：原版 API 版本差
- [ ] `SavedData#save` / `INBTSerializable` 去掉 `HolderLookup.Provider` 形参（15 个 SavedData 子类，共 22 文件出现该形参）
- [ ] `appendHoverText` 第 2 参 `Item.TooltipContext` → `Level`（9 文件 / 10 处）
- [ ] `AttributeModifier.Operation` 统一到 1.20.1 拼写（7 处 `ADD_VALUE` / 5 处 `ADD_MULTIPLIED_BASE` / 1 处 `ADD_MULTIPLIED_TOTAL`）。**`ADDITION` / `MULTIPLY_BASE` 另有 5 处是本模组自己的 `ModifierOperation` 枚举，别一起改**
- [ ] `Holder<Attribute>` → `Attribute`（6 文件 / 18 处，映射单点 `WandscapeAttributes.toVanilla`）
- [ ] `MobEffectInstance` 收裸 `MobEffect`（21 处）；`MobEffects.RAID_OMEN` → `BAD_OMEN`
- [ ] `saveAdditional` / `loadAdditional` / `getUpdateTag` / `handleUpdateTag` 去 provider（`CreativeScannerBlockEntity`）
- [ ] 杂项：`isSameItemSameComponents` → `isSameItemSameTags`(3)、`MobSpawnEvent` 的 `Result.FAIL` → `DENY`、tick 相位改造（20 文件 / 23 处）、`RegisterMenuScreensEvent` → `FMLClientSetupEvent` 里注册

### 里程碑 5：网络栈重写（最大单点）
- [ ] `Net` 的 5 个方法体：`PacketDistributor` 目标对象 + `SimpleChannel.send`
- [ ] `PayloadRegistry`：`NetworkRegistry.newSimpleChannel` + 85 条 `registerMessage`；改写 `s2c` / `c2s` 两个方法体
- [ ] 86 个载荷的类型面：`CustomPacketPayload` → `IMessage`、`RegistryFriendlyByteBuf` → `FriendlyByteBuf`、删 `type()`
- [ ] 3 个注册表依赖 codec 手工改：`NpcDataPacket`（`ItemStack.OPTIONAL_STREAM_CODEC`）、`ExplorationRewardPacket` 与 `ScreenFeedbackPacket`（`ComponentSerialization.STREAM_CODEC`）
- [ ] 两个**非载荷**的缓冲用户易漏：`BuildingPackage`、`MagicBeamEntity`（`ByteBufCodecs.VECTOR3F`）
- [ ] 3 个非机械 codec：`GuideBookOpenPacket` / `ScreenFeedbackPacket`（`composite`）、`WarehouseTerminalKeyPacket`（`unit`）

### 里程碑 6：物品数据与局部重写
- [ ] `ItemKey` 塌缩掉 `DataComponentPatch` / `RegistryOps` → `getTag().copy()` / `setTag(...)`；`HolderLookup.Provider` 从 22 文件移除
- [ ] VBO 幽灵渲染重建（963 行；1.20.1 无公开的 `uploadIndexBuffer` 等价路径）
- [ ] GUI 4 处断点：`renderBackground` 退回单参、`renderTransparentBackground`、`blitSprite`、`WidgetSprites`
- [ ] compat 收尾：Curios 3 处成员级改写 + 依赖 range 重写；TLM 整包删除（7 文件 + 2 处注册调用）
- [ ] 资源：3 个九宫格 `.png.mcmeta` 在 1.20.1 被忽略 → 面板退化，需换实现
