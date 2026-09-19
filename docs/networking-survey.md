# 网络包系统考察报告（networking-survey）

> 信息截至 2026-09-20 | 基线：分支 `1.21.1`（Minecraft 1.21.1 / NeoForge 21.1.x，`mod_version=2.1.1`）
> 参考面：`_refs/` 下 10 个模组（MineColonies / Create / TouhouLittleMaid / AE2 / Botania / FTB-Quests / Goety / IronSpells / Patchouli / Curios），均为其网络层架构的当日快照。
> **性质：现状体检 + 横向对比 + 改进选项分档，不是重构实施计划**。

- **【何时读】**：想动网络层、觉得「包太多/太啰嗦」、或评估合并同类包值不值时。
- **【不包含什么】**：分步实施步骤、逐包改造清单、里程碑排期——那是动手时的活。

---

## 一、结论速览（TL;DR）

- **先纠正一个认知**：「包多」不是问题。参考模组里一动作一包是绝对主流——MineColonies 132 个、Create 77、FTB-Quests 65、TLM 57、IronSpells 43、AE2 38。Wandscape 的 **86 个包**处在同一量级的中段，架构取向和整个生态一致，**不是异类**。
- **真正的问题有两个，且都不在「数量」上**：
  1. **每个包的样板成本偏高**——86 个包共 **10233 行，平均 119 行/包**，而 TLM 是 56 行/包、Create 52 行/包。差额里约 **1700–1900 行是纯样板**（`TYPE` + `type()` + `STREAM_CODEC` + `write/read` 签名），每个新包都要原样再抄一遍。
  2. **同步/发包层没有通用设施**——没有共享基类、没有按追踪范围的发送封装、没有统一的分块层、没有一个客户端 handler 注册表。于是每个包自己解决一遍这些问题，且**同类问题的解法在仓库里出现了好几份**。
- **「小内容单独设包」确实存在，且有明确的可合并族**：4 个 `NpcOpen*` 包（都是 `int entityId` + `openMenu`）、3 个生产站数据包（都是 `BlockPos + ListTag + String creator`，共 572 行）、14 个 `*OpenPacket`/`*EnterPacket`。但**合并包的收益远小于「抽掉每包样板」的收益**——合并一处省 60 行，抽基类一次省 1700 行。
- **一句话建议**：先做 §四·档 A（纯机械、零行为变更），档 B 挑着做，档 C 现在别做。
- **本次考察的真实初衷是「降低后续移植难度」**（见 `forge-1201-downgrade-survey.md` / `neoforge-26-upgrade-survey.md` / `fabric-port-survey.md` 三份报告）。按这个目标重算后的方案、以及对本报告 §四·A1 与 §五 优先级的修正，**集中在 §六**——若你只读一节，读 §六。

---

## 二、现状体检

### 2.1 规模硬数据

| 指标 | 数值 | 说明 |
|---|---|---|
| 包类文件数 | **86** | 全库 `*Packet.java`；82 个在 19 个 `*/network/` 目录，6 个散落在 `foundation/`、`compat/`、`content/warehouse/transport/` |
| 包类总行数 | **10233** | 平均 **119 行/包** |
| 注册条目 | **86** | `Wandscape.java:603-1012` 85 条 + `CuriosCompatImpl.java:76` 1 条（Curios 加载时才走） |
| `<80 行`的包 | **31 / 82** | 近四成是几乎纯样板的小包 |
| 网络目录总行数 | **11031** | 含 DTO / Tracker / Compressor 等非包类 |
| 占全库比例 | 11031 / 115221 ≈ **9.6%** | 全库 710 文件、115221 行 |

### 2.2 六个具体病灶

**P1 · 注册墙 410 行挤在一个类里**
`Wandscape.java:603-1012` 是 410 行连续 `.playToClient(...).playToServer(...)` 链式调用，占该文件（1398 行）的 **29%**。其中：
- **45 处**因 import 缺失而内联全限定名，如 `com.wsteam.wandscape.foundation.ui.panel.PanelStateTogglePacket.TYPE`（`Wandscape.java:829`）；
- **32 处**重复 `(ServerPlayer) ctx.player()` 强转；
- 大量 S2C 条目写 `(packet, ctx) -> X.handleClient(packet)`——`ctx` 收下即弃。

对比：TLM 同样是单点注册但只有 ~60 行（`NetworkHandler.java:22-83`），Create 用枚举常量表压到 `AllPackets.java:106,199` 两段。**这条本身不是架构问题，是排布问题**。

**P2 · 手写 codec 是默认，组合式 codec 几乎不用**
86 个包里 **83 个**用 `StreamCodec.of(write, read)` 各写一遍 `write`/`read`；只有 **2 个**用 `StreamCodec.composite`（`GuideBookOpenPacket`、`ScreenFeedbackPacket`）。`composite` 正是 NeoForge 1.21 提供给「省掉手写」的 API，本项目基本没用。

**P3 · 16 处把 raw `CompoundTag` 直接当负载**
`buf.writeNbt(tag)` / `readNbt()` 出现在 16 个文件里，其中 15 个是包：

```
content/building/network/AltarCastRequestPacket, AltarOpenPacket, BuildingInfoPacket,
                            HotelOpenPacket, MageHutDataPacket, ShopMaxStockPacket,
                            ShopOpenPacket, TavernOpenPacket
content/production/network/CraftingStationPacket, MagicStationPacket, WorkstationDataPacket
content/warehouse/network/WarehouseActionPacket, WarehouseDataPacket
content/building/scanner/network/ScannerSyncPacket
content/warehouse/transport/TransportStartPacket
```

典型如 `ShopOpenPacket`（88 行）：负载逻辑上是「pos + 2 个 UUID + creator + 2 个 `Map<String,Integer>`」，实现上是把整条数据塞进一个 `CompoundTag` 再 `writeNbt`——**字段名当 key 上线、客户端读回时全靠字符串匹配、没有类型约束**（`ShopOpenPacket.java:52-88`）。代价是每字段多几字节、丢编译期检查、版本漂移只能靠运行时。

**P4 · S2C handler 靠 31 个静态字段注入，与 C2S 是两套机制**
- C2S：`static handleServer(pkt, ctx)` 写在包类里，直接在注册处引用（`Wandscape.java:641`）。
- S2C：包类里放 `private static Consumer<X> clientHandler` + `setClientHandler` + `handleClient` 空判（`GuideBookOpenPacket.java:38-46`），由 `WandscapeClient.java:205-472` 逐个注入 —— **31 处**。

根因是合理的（1.21.1 的 `onRegisterPayloads` 两侧都跑，不能直接引用客户端类），但**每包一个可变静态字段**不是唯一解：同样目的用一个「按 payload type 查表」的客户端派发表即可，`WandscapeClient` 的绑定代码一行不减，却能删掉 31 个静态字段与 31 处 null 空判。NeoForge 26.1 会用 `RegisterClientPayloadHandlersEvent` 推着收口（见 `docs/neoforge-26-upgrade-survey.md:94`），届时这 31 处仍要改一次——现在收成一张表，那次迁移会便宜很多。

**P5 · 同类动作各设一包（可合并族）**

| 族 | 包 | 形态 |
|---|---|---|
| NPC 开屏 | `NpcOpenStrategyPacket` / `NpcOpenInventoryPacket` / `NpcOpenEquipPacket` / `NpcOpenCuriosPacket` | **四个包字段完全一样**：`record X(int entityId)`，`write`/`read` 只有 `writeInt`/`readInt`，服务端都做「解析实体 → 归属校验 → `openMenu`」 |
| 生产站数据 | `WorkstationDataPacket`(214) / `CraftingStationPacket`(206) / `MagicStationPacket`(152) | **三包首字段同形**：`BlockPos stationPos, ListTag ..., String creator`，都把配方塞 `ListTag`，共 **572 行** |
| 开屏/进入 | 14 个 `*OpenPacket`/`*EnterPacket` + `OpenWarehousePacket` | 见 §2.1 清单 |

注意 NPC 那四个**不能简单并成一个**：`NpcOpenInventoryPacket` 走 vanil​la `openMenu` 带额外 buf（`NpcOpenInventoryPacket.java:59-63`），`NpcOpenCuriosPacket` 在 `compat/` 且只在 Curios 加载时注册。合并要保「菜单种类」这个维度，不是无损替换。

**P6 · 缺通用设施，导致同一件事被实现多遍**

| 设施 | 现状 | 参考模组同类 |
|---|---|---|
| 分块/分包 | **有两份各写一遍**：`BuildingConfigSyncChunkPacket`（zlib + 16KB 分块）、`DatapackDataSyncChunkPacket`（逐字重复同一协议） | MineColonies 在通用层做一次（`NetworkChannel.java:434-479`），任何包自动切 |
| 按追踪范围发送 | **无封装**，各处直接调 `PacketDistributor` | TLM `NetworkHandler.sendToNearby`、AE2 `sendToAllNearExcept`、Create `sendToClientsTrackingEntity` |
| 服务端权限/归属前置 | **每个 C2S 包各写一遍**（四个 `NpcOpen*` 里是逐字重复的同一段 `ColonyOwnership.isOwn` + `deny`） | MineColonies `AbstractColonyServerMessage.java:111-147` 基类统一做 |
| 表单式（key-value）同步 | 无通用通道；同类需求各建专包 | Create `ISyncPersistentData.PersistentDataPacket`、MineColonies 全量快照重发 |

### 2.3 已经做对的地方（别在重构里弄丢）

- **脏标记 + 节流是有的**：`TaskPanelSyncTracker.java:81-82` 每 10 tick 或脏时推一次，且有订阅者集合，不是每 tick 全量广播——这正是 MineColonies `ColonyPackageManager` 的同款思路。
- **大包分块 + zlib 是有的**：`BuildingConfigSyncChunkPacket` 注释记录了事故根因（旧包单次 `writeUtf` 超 262144 上限崩溃，`sea_store` 207K→40K）。**这个坑已经踩过并修好了**。
- **归属门禁是有的**：多人完全平行隔离的 C2S 入口校验（`ColonyOwnership`），参考模组里只有 MineColonies 有等价物。
- **开屏部分已走原版**：`NpcOpenInventoryPacket`、`NpcOpenStrategyPacket`、`NpcOpenEquipPacket`、`WarehouseTerminalItem.java:56` 都用 `SimpleMenuProvider` + `openMenu`，没有重造菜单轮子。

---

## 三、参考模组横评

### 3.1 总表

| 模组 | MC/loader | 包类数 | 行/类 | 统一信封 | 注册 | 开 GUI | 自定义包做同步？ |
|---|---|---|---|---|---|---|---|
| **Wandscape** | 1.21.1 NeoForge | **86** | **119** | 无 | 单点 410 行 | 混合（5 vanilla / 14 自定义） | 是，主力 |
| MineColonies | 1.20.1 Forge | 132 | 103 | `IMessage` + 2 服务端基类 | 单点 138 行 `++idx` | 混合 | 是，脏标记全量快照 |
| Create | 1.21.1 NeoForge | 77 | **52** | `BasePacketPayload` + 分向派生（Catnip 库） | 枚举常量 114 个 | **全原版 openMenu** | 否，BE 走 `sendData()` |
| TouhouLittleMaid | 1.21.1 NeoForge | 57 | **56** | 无 | 单点 ~60 行 | 混合 | 部分（附件/区域） |
| AE2 | 1.21.1 NeoForge | 38 | — | `CustomAppEngPayload` + `Clientbound/ServerboundPacket` | 单点显式 | **全原版 openMenu** | 是，但带增量+分包 |
| IronSpells | 1.21.1 NeoForge | 43 | — | 无（仅 2 个 Vec3 复用基类） | 单点按域注释 | 混合 | 部分 |
| FTB-Quests | 26.1 NeoForge | 65 | — | 无 | 单点 65 行 | 自定义包 | 是 |
| Botania | 1.20.1 Forge | 14 | — | `BotaniaPacket` | 单点 `registerMessage(i++)` | **全原版** | 否，优先原版 BE 同步 |
| Goety | 1.21.1 NeoForge | ~58 动作 | — | **有**：2 个 Type + `packetId` 分派 | 只注册 2 个类型 | 混合 | 通用 `EntityUpdatePacket` |
| Patchouli | 1.21.1 双 loader | 2 | — | 无 | 单点 2 行 | 自定义包 | 否（内容本地读） |

### 3.2 四条被反复验证的行业取向

**① 一动作一包是主流，不是反模式。** 上述模组除 Goety 外全部如此。Wandscape 的 86 个包**不需要为「多」而羞耻**；要改的是每包的成本，不是包的数量。

**② 「打开 GUI」行业默认交还原版 `openMenu`。** AE2、Botania、Create 三家**完全不用自定义包开屏**；MineColonies 用自定义包只传「打开哪一个」，真正开屏仍走 `NetworkHooks.openScreen`。Wandscape 是混合的：5 处已走 vanilla，14 处自定义——**取向对但没做彻底**，剩下的是历史包袱而非设计。

**③ 状态同步行业默认交还原版。** `getUpdatePacket`/`getUpdateTag`/`SynchedEntityData`/`ContainerData` 是绝大多数模组的默认通道；自定义包只用于原版表达不了的东西（能力、冷却、进度、法术数据）。Botania 的代码注释甚至量化了这个取舍：走原版 BE 同步约 14B，自定义包 26B+（`clientbound/BotaniaEffectPacket.java:38-39`），并明确推荐优先原版。
→ Wandscape 把大量**方块实体状态**（仓库、生产站、节点、法师小屋）都做成了自定义包。这在「面板需要跨方块聚合殖民地级数据」时是合理的（原版 BE 同步只传单方块），但**单个方块自己就能表达的字段**（如站点的位置与自己槽位）没必要占用一条独立包。

**④ 通用机制只有两种成熟解法，且都不流行。**
- **Goety 式 opcode 信封**（2 个 Type + `packetId` 分派 62 个动作，`network/ModNetwork.java:38,263-268`）——把注册线从 62 条压到 2 条。**代价**是每类仍手写 encode/decode，且全部动作集中列在 `init()`。**这是「小内容单独设包」问题的正面解法，但整个生态只有 Goety 一家这么做**。
- **Botania 式单包多子类型**（`BotaniaEffectPacket(EffectType type, ..., int... args)`，一个包 + 枚举 + 变长参数复用 15 种特效，`network/EffectType.java:3-27`）——**只适用于表现层事件**，是投入产出比最高的一个。

### 3.3 三个「真有用」的具体招式

1. **AE2 的增量 + 分包**：`MEInventoryUpdatePacket` 给每个 key 分配 `serial`，首包全量、后续只发 `serial + 数量`；超 512KB 自动切包，链中仅首包 `fullUpdate=true`（`MEInventoryUpdatePacket.java:57,134-199`）。**只在数据量大到需要增量时才值得**。
2. **MineColonies 的三个基础设施**：通用分块层、服务端权限基类、脏标记订阅广播。
3. **Create 的 `BlockEntityConfigurationPacket`**：把「位置校验 + 距离上限 + 旁观/冒险拦截 + 回写 `sendData()`」收进一个泛型基类（`BlockEntityConfigurationPacket.java:23-43`），业务包只写 `applySettings`。

### 3.4 明确**不值得**借鉴

- MineColonies 的 **138 行 `++idx` 注册墙 + 25 个通配 import**（`NetworkChannel.java:107-280`）——删/插包即错位。
- MineColonies 的**极端粒度**：连「切换旗帜集结守卫」都单独成类，服务端入包无限速。
- MineColonies 的**殖民地全量快照重发（含 NBT）**——代价随殖民地规模线性膨胀。
- FTB-Quests 的**请求/响应成对命名**（`XxxMessage` / `XxxResponseMessage`）造成的 65 包爆炸。

---

## 四、改进选项（分档）

> 分档依据：只按「下次改动更省力」判断，不按「是否企业级干净」。

### 档 A · 该动：纯机械、零行为变更、省下的是每次新增包的成本

| # | 动作 | 收益 | 风险 |
|---|---|---|---|
| **A1** | **抽共享 codec 工具 + 两个方向基类**（`ClientboundPayload` / `ServerboundPayload`，仿 AE2 `CustomAppEngPayload`），把 `TYPE`/`type()`/`StreamCodec.of` 样板收掉 | 86 包 × ~20 行 ≈ **省 1700–1900 行**，且新包只写「字段 + 怎么读怎么写」 | 低。逐包替换可编译验证；`./gradlew build` 即门槛。**注意：按「降移植成本」这个目标衡量，本条收益被 §六 调降——见 §6.4** |
| **A2** | **注册墙搬家**：`Wandscape.java:603-1012` 的 410 行移到 `foundation/networking/PayloadRegistry`，顺手清掉 45 处内联全限定名与 32 处重复强转 | `Wandscape.java` 瘦 29%；`network` 相关改动不再碰主类 | 低 |
| **A3** | **客户端 handler 收成一张表**：31 个 `setClientHandler` → 一个 `ClientPayloadDispatcher`（`Map<Type, Consumer>` 或直接按类型分派） | 删 31 个可变静态字段 + 31 处 null 空判；**26.1 迁移时省一次全量返工** | 低 |
| **A4** | **共享编解码器**：`UUID 列表`、`BlockPos+UUID` 组合、`String→Int` 映射等在手写包里重复出现的模式抽成 `WandscapeCodecs` | 消掉 §2.2 P2 里大量重复的 `for` 循环 | 低 |

### 档 B · 挑着做：改结构，收益中等，按痛点排序

| # | 动作 | 说明 |
|---|---|---|
| **B1** | **合并 `NpcOpen*` 四包**为「实体 id + 菜单种类」一包 | 先确认 `compat/` 的 Curios 包能否接受被 common 侧引用；若不能，至少合并 common 侧那三个（`Strategy`/`Inventory`/`Equip`），并把重复的归属校验抽成一处 |
| **B2** | **生产站三包显式 codec 化**：`WorkstationDataPacket` / `CraftingStationPacket` / `MagicStationPacket` 的 `ListTag` 改 `StreamCodec` 组合 | 572 行里至少一半是 NBT 手工装拆。这三个是热路径（每次开站发一次），收益实在 |
| **B3** | **抽通用分块层** | `BuildingConfigSyncChunkPacket` 与 `DatapackDataSyncChunkPacket` 是同一协议的两份实现；抽一份，第三个大包（如 `BuildingAreaSyncPacket`）将来超限时直接复用 |
| **B4** | **`BuildingAreaSyncPacket` 的发送时机**：目前每次面板打开全量重发所有建筑的旋转后包围盒（`BuildingAreaSyncPacket.java:271-299`），单条约 70–90 字节 | 几百座建筑约数十 KB/次。加一个「版本号/脏标记」即可只在变更后重发。**先测真实殖民地规模再决定要不要做** |
| **B5** | **表现层事件合成一个包**（Botania 式） | `ParticleBurstPacket` + `ScreenFeedbackPacket` + `MagicCircleCastPacket` + `TouristBubblePacket` + `ColonyAmbientPacket` 这类「短小、无状态、纯视觉/提示」的可考虑合一个带子类型的包。**投入产出比高但可选** |

### 档 C · 现在别做

| # | 动作 | 为什么不做 |
|---|---|---|
| **C1** | Goety 式 opcode 信封（2 个 Type 收所有包） | 整个生态只此一家；会把 86 个自说明的类名换成一个 `int packetId` + 一处巨型 switch，**新增/删除包变成改中心表**，与 CLAUDE.md「改动只落该域自己的包」相冲。收益是注册线从 86 条降到 2 条——而 A2 已经把这 86 条挪出主类了 |
| **C2** | AE2 式序列号增量同步 | 只在数据量大到「重复传输成为可测的带宽问题」时才值得。Wandscape 当前最大的重复传输是 `BuildingAreaSyncPacket`（见 B4），**先做 B4 的脏标记，不够再谈增量** |
| **C3** | 给每个方块实体补 `getUpdatePacket` 以「省包」 | 只在「单方块自身状态」场景成立。面板类数据是殖民地级聚合，原版通道表达不了，改了反而更绕 |

---

## 五、给下一步的最小建议

**只挑一件事做的话：A1 + A2。** 这两条是纯机械的，零行为变更，`./gradlew build` 就能验证，且合起来覆盖了 §2.2 里 P1、P2 两个最占行数的病灶——86 个包未来每次新增都要付的样板成本，一次性降到 TLM/Create 的水平。

**A3 值得紧跟**：它本身收益一般（删 31 个静态字段），但 NeoForge 26.1 已经确定会用 `RegisterClientPayloadHandlersEvent` 推着收口（`docs/neoforge-26-upgrade-survey.md:94`），现在收成一张表，等于把那次迁移的一道工序提前做掉。

**B5（表现层合包）** 是「小内容单独设包」这个直觉最对得上的一条，但它是可选优化而非缺陷修复——**如果只是想回复「我们的包是不是太碎」，答案是：不碎，和生态一致；但每个包的写法和配套设施确实比同行笨重。**

> **本节优先级已被 §六 修正。** 若目标是「降低移植难度」而非「日常可维护性」，A1 应降级、A2 应升格为第一优先，且必须先做 §6.2 的 `Net` 发送门面——理由见 §六。

---

## 六、面向移植的收口方案

> 本节回答的是「让网络包不拖累移植难度」。此前 §二~§五 按「臃肿/粒度」体检，得出的优先级在移植视角下需要重排。

### 6.1 先算清一笔账：移植成本是两类性质不同的东西

实测的网络层**平台接触面**：

| 事实 | 数 |
|---|---|
| 直接 import 平台网络 API 的文件 | **155 个** |
| 但不同 API 符号只有 | **~12 个**（6 个发送方法 + 3 个类型声明 + buffer + ctx + registrar） |
| 包已写成 `static write(Buf,X)` + `static X read(Buf)` 的 | **81 / 86** |
| 真正依赖注册表上下文（`ItemStack.STREAM_CODEC` / `ComponentSerialization`）的包 | **3 个** |

第一行与第二行的反差就是全部故事：**移植成本是「155 份机械编辑」，不是「12 个难题」。**

逐符号接触点：

| 平台符号 | 文件数 | 说明 |
|---|---|---|
| `CustomPacketPayload` / `StreamCodec` | 86 / 86 | 每包一次的类型声明 |
| `RegistryFriendlyByteBuf` | 92 | codec 读写形参 |
| `PacketDistributor` | **80** | **185 处调用，但只有 6 个方法** |
| `IPayloadContext` | **14** | 且多数只取 `player()`；`getSource()` 那 111 处属命令 `CommandContext`，与网络无关 |
| `PayloadRegistrar` | 2 | 注册 |

由此得到治法的分野：

- **机械成本**（换类型名、换形参类型）→ 155 文件，但**规则统一、零判断** → 这是**脚本的一次性活，不该变成常驻抽象层**。
- **结构成本**（发送拓扑、注册范式、ctx 语义）→ 185 处发送点，但**只有 6 个方法** → 这是**该收成常驻门面的**。

### 6.2 该做之一：`Net` 发送门面 + `PayloadRegistry`（治结构成本）

```java
// foundation/networking/Net.java —— 覆盖 185 处 / 80 文件
Net.toServer(payload);                //  93 处
Net.toPlayer(player, payload);        //  68 处
Net.toNear(pos, radius, payload);     //   sendToPlayersNear
Net.toTracking(entity, payload);      //  17 处（sendToPlayersTrackingEntityAndSelf）
Net.toTrackingChunk(pos, payload);    //   3 处
Net.toAll(payload);                   //   3 处
```

外加 `foundation/networking/PayloadRegistry.java`，把 `Wandscape.java:603-1012` 那 410 行墙整体搬出。

三个平台符号（`PacketDistributor` / `PayloadRegistrar` / `RegisterPayloadHandlersEvent`）从此只出现在 1–2 个文件里。

**这条不移植也立刻回本**：410 行墙消失，「谁给谁发什么」从 80 个文件散落变成一处可读。也正是 `docs/README.md:79` 已写、但代码里并不存在的「网络包基类收 foundation」。

### 6.3 该做之二：一次性的移植脚本（治机械成本）

移植当天跑，**不入常驻架构**。纯规则替换、无需判断：

```
CustomPacketPayload               → IMessage                    (1.20.1 Forge)
RegistryFriendlyByteBuf           → FriendlyByteBuf
StreamCodec.of(X::write, X::read) → registerMessage(idx, X.class, X::write, X::read, ...)
@Override type() { ... }          → 删
```

**为什么用脚本而不是抽象层**：抽象层要每个读代码的人一辈子穿过一层间接；脚本只在移植那天跑一次。而这批替换恰好全是无判断的规则替换——正是脚本的强项。仓库已有同类先例（`gen_patchouli.py` 入库）。

### 6.4 明确别做（为移植付出的常驻代价，不划算）

1. **别包装缓冲区**（自造 `WBuf`）。`FriendlyByteBuf` / `RegistryFriendlyByteBuf` / `PacketByteBuf` 的方法面几乎重合，包一层只换来 ~30 个转发方法 + 每字段一次间接；而它要解决的问题（注册表上下文）实测**只涉及 3 个文件**（`NpcDataPacket` / `ExplorationRewardPacket` / `ScreenFeedbackPacket`），手改这三个即可。
2. **别给包加 `WandPayload` 标记接口**。只要签名里还露平台类型，省的是打字不是移植；AE2 的 `CustomAppEngPayload` 正是如此——好设计，但**不是移植资产**。这条即 §四·A1 的调降依据。
3. **别上 opcode 信封**（Goety 式，2 个 `Type` 收所有包）。移植时那个中心文件会变成全仓最难改的文件——把「155 份机械编辑」换成「1 份需要动脑的编辑」，方向反了。

### 6.5 真正的大杠杆：删包，不是改包

最好移植的代码是没有的代码。参考模组的行业默认（AE2 / Botania / Create 三家）是**开屏走原版 `openMenu`、状态同步走原版 `getUpdatePacket`**。

但成本要诚实说：Wandscape 的 14 个 `*OpenPacket`/`*EnterPacket` 多数存在，是因为面板是自定义 `Screen` 而非原版 `Menu`。把 `ShopScreen` / `TavernScreen` / `TownHallScreen` 改成真 `Menu` 是大工程（要碰槽位与容器同步）——**不建议为移植而做**，只在将来正好重做某个面板时顺手走 `MenuProvider`，白捡一个开屏通道。

### 6.6 门面收益在三个移植目标上的分布（不必均等对待）

| 目标 | 门面收益 | 为什么 |
|---|---|---|
| 降级 1.20.1 | **最大** | `CustomPacketPayload` / `StreamCodec` / `RegistryFriendlyByteBuf` 整套在 1.20.1 不存在（属 1.20.5+ 的 vanilla API，换任何 loader 都一样），范式全换 |
| Fabric 移植 | 中等 | 注册与 buffer 不同，但现代 Fabric API 有对应接口 |
| 升 26.1 | **容易被低估** | 表面是 231 处 `sendToServer → ClientPacketDistributor.sendToServer` 改名；实质是**发送端按物理侧拆开**——门面正是唯一该做这次判断的地方。且 `RegisterClientPayloadHandlersEvent` 的落点也是注册表，顺手解决 31 个 `setClientHandler` 静态字段 |

**结论**：`Net` + `PayloadRegistry` 这条**不亏**（不移植也回本）；但它的**移植溢价**主要在 1.20.1 那条线上兑现。若降级不是真目标，它从「紧急」降为「该做，不急」。

### 6.7 关于强制手段

曾考虑用 gradle task 把「`platform/` 之外禁 import 平台网络包」变成构建门槛（贴合 CLAUDE.md「build 即验证」）。**已决定不加**——业余项目，规则守得太严反而拖累开发。边界作为**约定**写在本文即可，不作为门槛。

---

## 附：数据采集口径

- 包类数 = 全库 `*Packet.java`（含 `foundation/`、`compat/`、`content/warehouse/transport/` 下的散落包），非仅 `*/network/` 目录（后者为 82）。
- 行数含 javadoc 与 import，未去空行；`行/类` 因此偏保守（对 Wandscape 与参考模组同口径）。
- 参考模组数据来自 `_refs/` 下当日源码快照，四组子代理分头采集，每项结论均附 `文件路径:行号`，未逐条复验。
- Wandscape 侧 `119 行/包` **不等于全是样板**：其中含真实业务逻辑（`from()` 组装工厂、`ColonyOwnership` 归属校验）。纯样板估算按每包 ~20 行（`TYPE`2 + `STREAM_CODEC`2 + `type()`4 + `write/read`签名与壳）计。
- §六的平台接触面数据：按符号名 `\b符号\b` 全库 `grep -rl` 计文件数（**文件数，非出现次数**）；`PacketDistributor` 的 185 处为出现次数，6 个方法名为 `sendToServer` / `sendToPlayer` / `sendToPlayersNear` / `sendToPlayersTrackingEntityAndSelf` / `sendToPlayersTrackingChunk` / `sendToAllPlayers`。
- §六的「注册表依赖」判定：在 `*Packet.java` 内检索具名 codec（`ItemStack.OPTIONAL_STREAM_CODEC` ×4、`ComponentSerialization.STREAM_CODEC` ×3、`GlobalPos.STREAM_CODEC` ×2、`ByteBufCodecs.STRING_UTF8/BOOL` 各 1），命中的包共 3 个。这是在**类型签名层面**的判定，未逐个核验 codec 方法体内是否另有注册表访问。
