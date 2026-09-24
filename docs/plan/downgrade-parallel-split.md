# 1.20.1 降级并行拆分方案（downgrade-parallel-split）

> 信息截至 2026-09-24 | 基线：分支 `1.20.1-forge`（工作区 `../wandscape-1201`），工具链已跑通
> 依据：`docs/forge-1201-downgrade-survey.md`（考察结论）、`docs/checklists.md` 四（活清单）
> **性质：分工与进度口径，不是逐处改造清单**。逐处的 before/after 写在 §五「食谱」，全部细节仍以考察报告为准。

- **【何时读】**：要给降级任务分工、估工期、核对某块该谁做、或想知道「我这一块还剩多少」时。
- **【不包含什么】**：具体某个类怎么改（报告里）、发版编排（`checklists.md`）。

---

## 一、结论速览

- **能并行，但不能无条件并行。** 全仓 716 个 java 文件里，绝大多数改动是**逐文件局部**的（改本文件的 import、本方法的签名、本处的枚举常量），这类天然可切；真正冲突的只有两类：**全局 find/replace** 与**跨域引用类型的签名**。
- **所以拆法是两层**：
  1. **地基（阶段 0，串行，约 1 人日）**——把「全局脚本」与「所有被跨域引用的类型」一次性定死，产出一个**其他人可以放心并行的基线**；
  2. **三份并行**——按**目录独占**切分，每人只碰自己目录树里的文件，互不重叠。
- **地基期间另外两人不是干等**：数据包目录与 JSON 字段改名是**纯资源改动（43 个 .json + 3 个 .mcmeta，零 `.java`）**，与地基零冲突，可以直接开工（见 §四·W0）。
- **工作量最大的不是网络栈了。** `Net` / `PayloadRegistry` 收口后，185 处发送点塌缩成 5 个方法体，86 个载荷的类型面是**同一套规则替换**（脚本可吸收），真正手写的只剩 3 个 codec + 2 个非载荷缓冲用户。现在最重的是 **建筑域（131 文件 / 24929 行，占全仓 21%）**。
- **三份工作量**（口径见 §三）：**A 104 点 / B 103 点 / C 100 点**，极差 ±4%。代价是**地基最重**——它吸收了全部「机械改名 + Java 21 回归 + 咽喉类型」，约占全仓触点四成；但这部分人力时间只有 1.5~2 人日，因为几乎全靠脚本，不是逐处手改（§四）。
- **一句话的取舍**：把四成的活压给一个人先做两天，换三个人之后各自独立不打架——**这比让三个人分别把全局改名跑一遍再互相收拾残局便宜**。

---

## 二、三条硬边界（决定了什么必须先做）

| # | 边界 | 为什么不能并行 | 处置 |
|---|---|---|---|
| 1 | **全局改名**：`net.neoforged.*` → `net.minecraftforge.*`（135 文件）、`NeoForge.EVENT_BUS`（93 处）、`ResourceLocation.fromNamespaceAndPath/.parse/.withDefaultNamespace`（154 处）、`DeferredHolder`/`DeferredItem` → `RegistryObject`（84 处）、`ModConfigSpec` → `ForgeConfigSpec`（61 处） | 这些是**全仓正则替换**。三人各跑一次 = 互相覆盖 + 三方冲突，且谁也不知道对方的替换是否跑过 | **地基一次性跑完并提交**，之后全员 rebase 到该基线 |
| 2 | **跨域引用类型的签名**：`ItemKey`（27 文件 / 136 处引用）、`Wandscape.java`（13 处 DeferredRegister 汇聚点）、`MedievalScreen`（18 个 Screen 的基类）、`Net` / `PayloadRegistry`、`Config` / `ClientConfig` | 这些类型**被三个域同时引用**。若 A 改了签名，B、C 会在自己文件里报错，且**无法靠各自修好** | 同样由**地基**一次性定死签名；各域只做「按食谱删实参 / 加 `@Override`」 |
| 3 | **构建红线是全局的**：`./gradlew compileJava` 只要还有一处 `net.neoforged` 就红，且报的是**全仓错误** | 三个人看同一个红色输出，无法判断「我的活干完没有」 | 给出**按路径过滤的自查命令**（§六），每份只看自己目录的错误数 |

> **边界 2 是单向的、不构成死锁**：地基把 `ItemKey` 的 `HolderLookup.Provider` 形参删掉后，各域只需在**自己的文件里删掉那个实参**。方向单一（地基 → 各域），不需要反向协调。这是全部跨域依赖的形态——**没有一个需要双向协商的点**。

---

## 三、工作量统计口径（可复跑）

### 3.1 为什么按「触点」而不按「行数」

行数与工作量**不成比例**：`content/building/projection` 23 文件 3266 行几乎全是纯逻辑（零 MC 依赖，一行不改）；而 `content/building/network` 23 文件要逐处换类型。按行数分配会把活全压给写得多的人。

### 3.2 加权公式

```
工作量点数 = 机械改名点 + 判断点 + 载荷点 + 重写点

机械改名点 = NEOIMP×0.3 + RRL×0.1      # NEOIMP=含 net.neoforged 的文件数；RRL=ResourceLocation 工厂调用数
判断点     = TICKocc×2 + SDATA×3 + HOVER×2 + ATTR×2 + MOBEF×2
载荷点     = NET×0.5                    # 载荷类型面走脚本，残留的是 3 个 codec + 2 个非载荷缓冲用户
重写点     = 真重写行数 ÷ 50            # VBO 963 行、foundation/ui 断点、compat Curios 成员级改写
```

> **Java 21 语法（`Math.clamp` 57 处等）不进本公式**——它由地基做一次全仓替换，不构成任何一份的独立工作量。同理，`ItemKey` / `Net` / `PayloadRegistry` / `Wandscape` 这些咽喉类型的语义重写也归地基，不计入 A/B/C。

权重含义是**「每分钟判断量」的量级**，不是精确值——它只用来判断三份是否均衡，**不用于考核**。谁觉得权重不对，改公式重算即可，触点数的采集命令在 §3.4。

### 3.3 三份划分

| | **A · 建筑与生产域** | **B · NPC 与殖民地域** | **C · 基建与物品域** |
|---|---|---|---|
| **目录（独占）** | `content/building/**`（含 `network`/`scanner`/`projection`/`render`/`preview`/`client`）<br>`content/production/**`<br>`content/element/**`<br>`content/warehouse/**` | `content/npc/**`<br>`content/tourist/**`<br>`content/road/**`<br>`content/colony/**`<br>`content/command/**`<br>`content/task/**` | `foundation/**`<br>`content/items/**`<br>`content/magic/**`<br>`content/tutorial/**`<br>`compat/**`<br>`mixin/**`<br>`api/**`<br>`impl/**`<br>`src/main/resources/**` |
| java 文件 | 178 | 310 | 224 |
| 行数 | 32,782 | 53,697 | 27,029 |
| 载荷文件 NET | 42 | 38 | 18 |
| `net.neoforged` 文件 | 38 | 64 | 34 |
| `ResourceLocation` 工厂 | 45 | 52 | 56 |
| 判断触点 | tick 11 / SavedData 8 / hover 1 | tick 14 / SavedData 8 / 属性 2 / 药效 2 | tick 6 / SavedData 5 / hover 8 / 属性 5 / 药效 1 |
| 真重写 | VBO 963 行 | （无，全是局部改） | `foundation/ui` 4 处 GUI 断点、compat Curios 3 处 |
| **加权点数** | **104** | **103** | **100** |

> 上表**不含 Java 21 语法触点**（全仓 57 处 / 约 30 文件）——那部分由地基统一全仓替换，不落到各份，故不进加权。
> `Wandscape.java` / `WandscapeClient.java` / `Config.java` / `ClientConfig.java` 四个顶层散文件（2,046 行）同样归地基，不在上表。
> 校验：文件数 178 + 310 + 224 + 4 = **716**，行数 32,782 + 53,697 + 27,029 + 2,046 = **115,554**，与全仓实测一致——**没有未分配的目录**。

**为什么这样切**（按目录语义而非按工作量硬凑，避免"我的活改到一半发现归属不明"）：

- **A 拿到 `warehouse`**：`ItemKey` 的语义簇（`ColonyItemBank` / `WarehouseManager` / `WarehouseMenu` / 运输包）全在这，与 `ItemData` 收口同一片区域，一个人做完整。
- **B 拿到 `task`**：任务派发链（`TaskRequest → GlobalTaskPool → SchedulerSystem`）服务于 NPC 与殖民地，与 B 的域语义相邻。B 的文件数最多（310）但多为小文件。
- **C 拿到 `foundation` + `items` + `compat`**：地基类（`foundation/ui` 的 4 处 GUI 断点会波及 18 个 Screen）、物品类（`appendHoverText` 9 文件全在这附近）、以及唯一需要真改的第三方（Curios 3 处）。

### 3.4 采集命令（可复跑，用来重算任一时刻的口径）

```bash
# 在某份自己的目录树下统计触点（把 $D 换成 §3.3 的目录列表）
D="content/building content/production content/element content/warehouse"
printf "%-20s %6s %6s %6s %6s %6s %6s\n" DIR NEOIMP RRL J21 TICK SDATA NET
for d in $D; do
  n=$(grep -rl 'net\.neoforged' $d --include=*.java | wc -l)
  r=$(grep -rho 'ResourceLocation\.\(fromNamespaceAndPath\|parse\|withDefaultNamespace\)' $d --include=*.java | wc -l)
  j=$(grep -rho 'Math\.clamp(\|\.getFirst()' $d --include=*.java | wc -l)
  t=$(grep -rho 'ServerTickEvent\.\(Pre\|Post\)\|ClientTickEvent\.Post' $d --include=*.java | wc -l)
  s=$(grep -rl 'extends SavedData\|HolderLookup\.Provider' $d --include=*.java | wc -l)
  e=$(grep -rl 'RegistryFriendlyByteBuf\|StreamCodec\|CustomPacketPayload' $d --include=*.java | wc -l)
  printf "%-20s %6s %6s %6s %6s %6s %6s\n" "$d" "$n" "$r" "$j" "$t" "$s" "$e"
done
```

**进度口径**：每份的进度 = `该份目录下的编译错误数`（§六命令）。剩余错误从 2000 降到 0 就是做完。**不要用"改了几个文件"报进度**——那是过程量，和完成度无关。

---

## 四、阶段 0：地基（串行，先做完，约 1 人日）

**产出**：一个提交（或一小组提交）落在 `1.20.1-forge` 上，之后全员从这里开分支。

### W0-A · 全局改名脚本（治机械成本，约 4 小时）

按 `docs/networking-survey.md` §6.3 的思路：**脚本跑一次即弃，不入常驻架构**。规则替换、无需判断：

| 替换 | 规模 | 备注 |
|---|---|---|
| `net.neoforged.neoforge.*` → `net.minecraftforge.*` | 135 文件 | 注意 `neoforged.bus.api` / `fml` / `api.distmarker` 三个外部构件**包根不同**，别一把梭 |
| `NeoForge.EVENT_BUS` → `MinecraftForge.EVENT_BUS` | 93 处 | |
| `ResourceLocation.fromNamespaceAndPath(a,b)` → `new ResourceLocation(a,b)` | 142 处 | `.parse(s)` → `new ResourceLocation(s)`；`.withDefaultNamespace(s)` → `new ResourceLocation("minecraft", s)` |
| `DeferredHolder<A,B>` → `RegistryObject<B>` | 55 处 | `DeferredItem<T>` → `RegistryObject<Item>` |
| `ModConfigSpec` → `ForgeConfigSpec` | 61 处 | 仅 3 文件 |
| `NeoForgeRegistries` → `ForgeRegistries` | 5 处 | |
| `DeferredRegister.createItems/createBlocks(MODID)` + `.registerItem/.registerBlock` → `create(ForgeRegistries.ITEMS/BLOCKS, MODID)` + `.register` | 各 1 处 | `Wandscape.java` |
| `IMenuTypeExtension.create` → `IForgeMenuType.create` | 5 处 | |
| `LivingIncomingDamageEvent` → `LivingHurtEvent` | 13 处 | 访问器 1:1 |
| **载荷类型面**：`CustomPacketPayload` → `IMessage`、`RegistryFriendlyByteBuf` → `FriendlyByteBuf`、删 `@Override type()` 与 `TYPE` 字段 | 86 文件 / 364 处 | **这一步是网络栈成本的主体，脚本吸收后只剩 3 个 codec 要手改** |

> **不要把 `ADDITION` / `MULTIPLY_BASE` 写进脚本**——它们在仓里指的是本模组自己的 `ModifierOperation` 枚举，不是 `AttributeModifier.Operation`。属性枚举那 7 处必须逐处判断（归 C 以外的域各自处理，见 §五）。

### W0-B · Java 21 → 17 回归（约 2 小时）

- 新建 `foundation/util/MathUtil`（`clamp` 三态重载），全仓 `Math.clamp(` → `MathUtil.clamp(`（23 文件 / 53 处）
- switch 类型模式 → `instanceof` 链（2 文件 / 9 处）；record 解构 → 逐字段取（`MarkdownRenderWidget` 11 处）
- `List.getFirst()` → `.get(0)`（3 处；**注意 `LinkedList` 上的 `getFirst()` 是 Java 8 的 `Deque` 方法，不要一起改**）
- `wandscape.mixins.json` 的 `compatibilityLevel: JAVA_21` → `JAVA_17`

### W0-C · 跨域引用类型（咽喉，签名一次定死）

| 类型 | 为什么归地基 |
|---|---|
| `foundation/util/ItemKey.java` + `ItemData.java` | 主改点：`DataComponentPatch.CODEC` + `RegistryOps` 整层塌缩成 `getTag().copy()` / `setTag(...)`，`HolderLookup.Provider` 形参消失。**它被三个域 27 文件引用，签名不能由 A 单方面决定再让 B/C 追着改** |
| `Wandscape.java` / `WandscapeClient.java` | 13 处 `DeferredRegister` 汇聚点 + 客户端派发表，全仓唯一 |
| `Config.java` / `ClientConfig.java` | `ForgeConfigSpec.ConfigValue` 无 `getSpec()`，`SettingItem.declaredRange` 改走 `SPEC.getSpec().get(path)` |
| `foundation/networking/Net.java`（5 个方法体）、`PayloadRegistry.java`（`s2c`/`c2s` 两个方法体 + channel 创建） | 185 处发送点的唯一落点；改完即对全仓透明 |
| `foundation/ui/MedievalScreen.java` | 18 个 Screen 的基类，`renderBackground` 退回单参会波及所有子类的 `@Override` |

> 地基做完后，**全仓应只剩「需要判断」的错误**，不再有「因为别人没改所以我也红」的错误。这是阶段 0 的验收线。

### W0-D · 数据包与资源（**可与地基并行**，约 3 小时）

纯资源、零 `.java`，与地基的任何改动都不冲突，**资历最浅的人也能独立完成**：

- 目录单→复：`advancement/`(33) `loot_table/`(2) `recipe/`(2) `tags/block/`(1) `tags/item/`(1) `data/curios/tags/item/`(4)。**`damage_type/` 两版都是单数，不要"顺手改对"**
- JSON 字段：`"icon":{"id":...}` → `{"item":...}`(31)、进度谓词 `"items"` 标量→数组(2)、配方 `"result":{"id":...}` → `{"item":...}`(2)、`neoforge:mod_loaded` → `forge:mod_loaded`(2)
- 3 个 `assets/**/panel_9slice_*.png.mcmeta` 的 `gui_sprite_scaling` 在 1.20.1 被忽略 → 面板退化，归 C 处理

---

## 五、三份并行的食谱（每份都用同一套规则）

各份在**自己的目录树内**独立应用下列规则。规则是通用的，差别只在目录。

| 类别 | 规则 | 详细 |
|---|---|---|
| SavedData 签名 | `save(CompoundTag, HolderLookup.Provider)` → `save(CompoundTag)`；`INBTSerializable` 同理；删调用处的实参 | 15 个 SavedData 子类 |
| 方块实体 | `saveAdditional/loadAdditional/getUpdateTag/handleUpdateTag` 去 provider（`CreativeScannerBlockEntity`） | |
| Tooltip | `appendHoverText(ItemStack, Item.TooltipContext, ...)` → `(ItemStack, Level, ...)`；`Item.TooltipContext` 类型不存在 | 9 文件 / 10 处 |
| 属性枚举 | `ADD_VALUE`→`ADDITION`、`ADD_MULTIPLIED_BASE`→`MULTIPLY_BASE`、`ADD_MULTIPLIED_TOTAL`→`MULTIPLY_TOTAL` | **只改 `AttributeModifier.Operation` 上的，别碰 `ModifierOperation`** |
| 属性 Holder | `Holder<Attribute>` → `Attribute`（映射单点 `WandscapeAttributes.toVanilla`） | 6 文件 / 18 处 |
| 药效构造 | `new MobEffectInstance(Holder<MobEffect>, ...)` → `(MobEffect, ...)` | 21 处 / 3 文件 |
| tick 相位 | `ServerTickEvent.Pre/Post` → `TickEvent.ServerTickEvent` + `phase == Phase.START/END`；`ClientTickEvent.Post` → `TickEvent.ClientTickEvent` + `Phase.END` | 20 文件 / 23 处；**注意事件是 `@SubscribeEvent` 形参式注册，无类字面量，不必改注册方式** |
| 物品同一性 | `isSameItemSameComponents` → `isSameItemSameTags` | 3 处 |
| 刷怪结果 | `SpawnPlacementCheck.Result.FAIL` → `Result.DENY` | 1 处 |
| 菜单屏 | `RegisterMenuScreensEvent` → `FMLClientSetupEvent` 里 `MenuScreens.register` | 3 文件 |
| Java 17 | 见 W0-B（若地基已全仓做过，这里只是兜底复查） | |

**各份独有**：

- **A**：VBO 幽灵渲染重建（`BuildingGhostVboCache` 330 行 + `BuildingPreviewGifCache` 481 行，1.20.1 无公开的 `uploadIndexBuffer`，索引上传要重建在 `BufferBuilder`/`RenderedBuffer` 上）；`warehouse` 的 `ItemKey` 调用点删实参
- **B**：无真重写，全是上表规则的批量应用；`tourist` 的 22 处 Java 21 语法是主要体力活
- **C**：`foundation/ui` 4 处 GUI 断点（`renderBackground` 退单参、`renderTransparentBackground` 自铺压暗、`blitSprite` → `blit`、`WidgetSprites` 手写查找）；`compat/curios` 3 处成员级改写（`LazyOptional` 无 `flatMap`/`ifPresentOrElse`、`registerCurio` 注册方式、属性 `Holder`）；`compat/tlm` 整包删除（7 文件 + 2 处注册调用）；`MixinMouseHandler#turnPlayer(double)` → 无参

---

## 六、分支、验证与合并

### 6.1 分支模型

`CLAUDE.md` 规定「同一分支同一时刻只允许一个 AI 提交」——多人的话这条会直接失效。因此：

```
1.20.1-forge        ← 地基（W0）落在这里，作为唯一基线
   ├── 1201/building      (A)
   ├── 1201/citizen       (B)
   └── 1201/infra         (C)     ← 各自独立 worktree
```

- 每人从**地基提交**开一条分支（不要从别人的分支开），做完合回 `1.20.1-forge`
- **合并没有顺序要求**：目录独占保证文件级不相交，git 三方合并不会冲突。唯一例外是 `W0-C` 里那些咽喉类型——**它们只在地基改，各域不改**，所以也不冲突
- 合并后若发现某处漏改，**在 `1.20.1-forge` 上直接修**，不要回各自分支

### 6.2 怎么在"仓库还是红的"时验证自己那块

这是并行最大的痛点：`compileJava` 报的是**全仓**错误，你无法从整体变绿判断自己做完没有。因此每份都用**按路径过滤**：

```bash
# 把 $D 换成本份的目录（A/B/C 的目录列表见 §3.3）
D="content/building|content/production|content/element|content/warehouse"

cd ../wandscape-1201
./gradlew compileJava --console=plain 2>&1 \
  | grep -E "\.java:[0-9]+: error:" \
  | grep -E "$D" | wc -l
```

- 这个数字**降到 0**，就是你这一块做完了（剩下的是别人的）
- 数字降不下去的常见原因：错误在**你引用的别人的类型**上——但地基已经把所有跨域类型的签名定死，所以正常情况下不该出现；真出现了就是地基漏了，回报地基

### 6.3 三份都绿之后

1. 合并 `1.20.1-forge`，跑一次**完整** `./gradlew build`（这次要真的绿）
2. 跑运行时验证：mixin 是否真的注入（`MixinMouseHandler` 等 5 个，配置是 `required: true`，不匹配即启动崩溃）、AT 三条是否生效、数据包是否真的加载（**静默失败清单见 `checklists.md` 四·里程碑 3**）
3. 已知的「不报错但不对」项：3 个九宫格面板退化、`pack_format` 由运行时自动产 15、`dist` 侧 `onlyIn` 差异

---

## 七、一句话总结

**先把「所有人都会碰的东西」一个人碰完，再让三个人各碰自己那棵树。** 前者是脚本和五个咽喉类，一天；后者是 800 个文件的局部改动，三份各约 130~150 点，互不重叠。

真正需要小心的不是"改得多"，而是**静默失效的那 43 个数据文件**和**`required: true` 的 5 个 mixin**——它们错了不会编译报错，只会让你以为做完了。
