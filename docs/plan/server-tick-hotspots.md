# 服务端 tick 卡点排查（提交施工 / 打开工地 UI）

> 日期：2026-10-01
> 状态：**A / B / C1 已实施（2026-10-01）**；C0 被 C1 取代，C2 已弃（见 §四）。
> 待实测复核：见 §五 —— 尤其 C1 的「清场后世界状态逐格一致」
> 证据：spark 采样 `run/config/spark/profile-2026-10-01_13.47.14.sparkprofile`（1139 ticks / 102 s，聚焦 Server thread）
> 关联：[large-building-render-perf.md](large-building-render-perf.md)（渲染侧与 §7.1 的整箱清空量化）、[domain-notes.md](../domain-notes.md)、[data-formats.md](../data-formats.md)

---

## 一、现象

```
[13:38:05] [Render thread/INFO]  [OverviewFlightController] [Overview] Interacting with building at BlockPos{x=-27, y=56, z=119}
[13:38:19] [Server thread/WARN]  [minecraft/MinecraftServer] Can't keep up! Is the server overloaded? Running 6563ms or 131 ticks behind
```

三个要点：

1. 卡的是 **Server thread**，不是渲染线程 —— 所以这是服务端计算，不是客户端画虚影。
2. **131 ticks = 6.5 秒**，一次卡顿。
3. 触发路径是**总览模式交互**与**提交施工**，两条最终分别汇进
   `BuildingInteractHandler.handleInteraction` 与 `BuildingApiImpl.placeBuilding`。

## 二、spark 实测

`buildWorkItem` 显示 5.97% = 6016 ms，据此换算 1% ≈ 1007 ms（下表的「折算」列由百分比推出，
**不是** spark 直接给出的绝对值）。

### 2.1 入口：两个包处理器吃掉服务端线程一半时间

服务端线程自身的总占比是 52.51%，其中 **47.50% 都花在 c2s 包处理器上**
（`MainThreadPayloadHandler` → `PayloadRegistry.lambda$c2s$1`），而它只分成两条：

```
PayloadRegistry c2s                                    47.50%
├─ OverviewInteractPacket.handleServer()                27.90%      ← 总览模式交互
│  └─ BuildingInteractHandler.handleInteraction()       27.90%
│     ├─ ConstructionSiteDataPacket.from()              27.85%
│     │  └─ EnqueueHelper.computeMaterialCounts()       27.80%   ★
│     └─ BuildingDebugRequestPacket.buildResponse()      0.06%
│        └─ BuildCompleteListener.findDamagedBlocks()     0.04%   ← 已证明不是瓶颈
└─ ProjectionPlacePacket.handleServer()                 19.53%      ← 提交施工
   └─ BuildingApiImpl.placeBuilding()                   19.53%
      ├─ EnqueueHelper.findDisabledBlock()              13.44%   ★
      │  └─ ElementApiImpl.isDisabled()                 13.34%
      ├─ EnqueueHelper.buildWorkItem()                   5.97%
      │  └─ EnqueueHelper.fillBoundaryAsAir()            5.34%   ★
      └─ EnqueueHelper.registerIfAbsent()                0.12%
```

`EventHooks.fireServerTickPost` 3.19%、`fireEntityTickPost` 0.61% 等 NeoForge 帧是普通 tick
开销，`io.redspace.ironsspellbooks` 等第三方帧都是 0.00% —— **没有外部模组在抢时间**。

### 2.2 三个大项

| spark 节点 | 占比 | 折算 | 性质 |
|---|---:|---:|---|
| ★ `computeMaterialCounts` | 27.80% | 约 28 s | 累计（每次交互/提交都跑） |
| └ `hasElementMapping` → `findConfigByItemId` | 27.43% | 约 27.6 s | 见 §3.1 |
| └└ `SimpleDataRegistry.getAll()` → `Map.copyOf()` | 23.65% | 约 23.8 s | 每次调用整份复制 |
| └└└ `Map.ofEntries` / `AbstractCollection.toArray` | 13.58% / 10.05% | | 副本构造的两半 |
| ★ `findDisabledBlock` | 13.44% | 约 13.5 s | 同一条链，见 §3.1 |
| ★ `fillBoundaryAsAir` | 5.34% | 6016 ms | 单次提交的一次性开销，见 §3.2 |
| `String.replaceAll` | 0.27% / 0.10% | | **已证明不是瓶颈**（见 §3.3） |
| `findDamagedBlocks` | 0.04% | | **已证明不是瓶颈**（见 §3.3） |

前两项合计 **41.5 s**，是元素映射查表那一件事被放在 58 万次循环里的结果；第三项
6016 ms 几乎正好等于那次「131 ticks = 6.5 秒」的卡顿。

## 三、根因

### 3.1 元素映射查表：每次调用先整份复制，再全表线性扫描

```java
// foundation/registry/dataconfig/internal/SimpleDataRegistry.java:29
@Override
public Map<String, T> getAll() {
    return Map.copyOf(entries);          // ← 每次调用都整份复制
}

// content/element/internal/ElementMappingLoader.java:100
private ElementMappingConfig findConfigByItemId(String itemId) {
    ElementMappingConfig rc = runtimeConfig(itemId);
    if (rc != null) return rc;
    for (ElementMappingConfig config : registry.getAll().values()) {   // ← 复制 + 全表线性扫描
        if (itemId.equals(config.itemId()) || itemId.equals(config.blockId())) return config;
    }
    return null;
}
```

`findConfig`（按 BlockState 查）与 `getAllConfigs` 走同一条链（`ElementMappingLoader.java:87`、`:163`）。

**而它是逐块调用的**：`EnqueueHelper.computeMaterialCounts:393` 与 `findDisabledBlock:412`
都在 `for (int i = 0; i < config.pattern().size(); i++)` 里调它 —— 那栋超大建筑 580,814 条，
**每个方块复制一次整张映射表再做一次全表扫描**。映射表约一千两百条 ⇒ 单次 58 万 × 1200 次比较。

调用点（修一处全线受益）：`EnqueueHelper.computeMaterialCounts:393`、`findDisabledBlock:412`、
`BuildingApiImpl.materialCountsForMissingOffsets:481`、`BuildingRepairHandler:123`、
`ConstructionSupply:71`、`ConstructionSiteDataPacket:146`。

### 3.2 `fillBoundaryAsAir`：贵在往 Gson 的 `JsonObject` 里逐条插

5.34%（**6016 ms**）的细分：

```
EnqueueHelper.fillBoundaryAsAir()                     5.34%
├─ com.google.gson.JsonObject.addProperty()           4.19%
├─ com.google.gson.JsonObject.add()                   4.18%
├─ com.google.gson.internal.LinkedTreeMap.put()       4.18%
├─ com.google.gson.internal.LinkedTreeMap.find()      4.18%
├─ com.google.gson.internal.LinkedTreeMap.rebalance() 0.31%
├─ java.util.HashSet.add() / contains()               0.38% / 0.19%
├─ BuildingConfig$BoundaryBox.allPositions()          0.06%   ← 注意这里
├─ com.google.gson.JsonArray.add()                    0.03%   ← 注意这里
└─ EnqueueHelper.offsetToJson()                       0.00%
```

**这修正了我在 [large-building-render-perf.md](large-building-render-perf.md) §7.1 的量化归因。**
当时我按对象大小推算，认为大头是 `airs` 那个装了 666 万个三元组的 `JsonArray`（估 1.7 GB）。
实测相反：

- `JsonArray.add` 只占 **0.03%** —— Gson 的 `JsonArray` 底层是 `ArrayList`，追加很便宜；
- `BoundaryBox.allPositions()` 只占 **0.06%** —— 造 710 万个 `BlockOffset` 也不是大头；
- 时间几乎全部在 **`blocks` 那个 `JsonObject`** 上 —— Gson 的 `JsonObject` 底层是
  **`LinkedTreeMap`**，每次 `addProperty` 都要 `find` 一遍再可能 `rebalance`，是哈希表插入
  而非数组追加。

规模（§7.1）：`boundary 体积 7,099,092 − pattern 条目 580,814 = 6,655,759 条 air`，
也就是 **666 万次 `LinkedTreeMap` 插入**（外加同样数量的 `JsonPrimitive` 分配）→ 实测 6016 ms。

**教训**：估「往容器里塞 N 条」的代价时，先看容器底层是数组还是哈希表 —— 两者差一个数量级。

### 3.3 已排除的两个怀疑（记录以免下次重查）

- **`String.replaceAll` 逐块编译正则**：spark 里只占 0.27% / 0.10%。我上一轮的判断是错的
  ——它是真实的浪费（`Pattern.compile` 无缓存），但**不是**这次卡顿的原因。
- **`findDamagedBlocks` 的 58 万次 `level.getBlockState`**：只占 0.04%。之前为它做的
  `87eae8a2` 优化本身没错，但不是这里的瓶颈。

## 四、方案（A / B / C1 已实施 2026-10-01；C0 被 C1 取代，C2 已弃）

### A · 元素映射查表改成 O(1)（已实施）

1. **`SimpleDataRegistry.getAll()` 缓存不可变快照**（`loadEntry` / `clear` 置脏），单次零拷贝；
   契约写进 `WandscapeDataRegistry#getAll` 的 javadoc —— 快照在重载前是同一实例，下游可据此
   判失效。其它调用点（JEI、建筑目录、配方等）一起受益。
2. **`ElementMappingLoader` 建 `id → config` 索引**（`itemId` / `blockId` 双键），按快照实例
   身份失效；`findConfig` / `findConfigByItemId` / `getBuildCostByItemId` 全走它。
   **索引必须建在原始 registry 上（含 disabled 条目）**，否则 `isDisabled` 查不到。

行为变更：重复键的胜者从「每次调用随机」（取决于不可变 map 每次不同的迭代序）变为「固定」，
属修掉的不确定性。

### B · 逐块循环改按 palette 预解析（已实施）

`computeMaterialCounts` / `findDisabledBlock` 先按 palette（magic_academy 460 项）解析
`pureId` / `mapped` / `disabled` 三个并行数组，逐块循环退化成数组下标：58 万次查表 → 几百次。
顺带新增 `foundation/util/BlockIds.stripBlockState`（手写截断）取代逐块 `replaceAll` 的
正则重编译，用在三处逐块循环。**注意这是纯粹的第二刀**：第一刀（A）才是 41.5 s 的大头。

### C1 · `fillBoundaryAsAir` 不再展开，改传包围盒给蓝图（已实施）

原方案（C0：只换容器不碰 op）**已废弃** —— 它只拿掉 §3.2 里 `blocks` 那一半，而 §3.4 的实测
显示这笔开销还有另外两笔同样量级、C0 一笔都碰不到。实施的是彻底版：

- **参数侧**（`EnqueueHelper`）：`fillBoundaryAsAir` 删除，只在 params 里写
  `boundary_min` / `boundary_max`（旋转后的世界包围盒，与 `registerIfAbsent` 的占地/区块租约
  共用 `worldBoundary` 一个口径）。offsets/blocks 回到只含 pattern。
- **蓝图侧**（`BlueprintDefaults.clearAndBuild`）：见到 boundary 参数就编出**一个**
  `AtomicOp.ClearBoxOp(min, max, excludedSorted)`；排除集 = 编译期的 pattern 全集，编成盒内
  一维排名（`ClearBoxOp.index`，z 最快、x 次之、y 最外）后排序，580k 条占约 4.6 MB。
- **执行侧**（新增 `content/task/boundary/ClearBoxExecutor`）：多 tick 游标，每 tick 16384 格
  （标定见常量注释），按同一排名序推进；**跳过本来就是空气的格**（`BlockOps#isAir`，比
  `getBlock()` 便宜——不查方块注册表、不造 `BlockType`），并用「游标只前进」的双指针跳过
  pattern 格。非 pattern 且非空气的格才 `BlockSalvage.salvage` + `setBlock(air)`，与改前同序。
  `BlockSalvage` 是从 `AsyncTransformExecutor` 里原样抽出的共用实现（逻辑一字未改）。
- **顺序不变**：`addMaterialRequest` → 清场 → 放置。缺料仍先 park，不清场。

**必须守住的不变量**：排除集不能省。清场一旦碰 pattern 格，会把已经放好的方块回收进仓库、
再由放置 op 从 NPC 背包重放一遍；仓库满时 `dropSalvageOnGround` 那一份就是一次物品复制。

**参数形态是自描述的，所以不需要版本迁移**：老档/在途任务的老参数（offsets 里自带 air、
没有 boundary_*）不产生 ClearBoxOp，那些 air 条目照旧逐格放掉，与改前完全一致。

**可见的行为变化**：清场从「每个非空气格占一个 tick」变成「后台按预算扫完（大盒子约 22 秒）」，
小建筑上表现为法师一口气清场完毕；最终世界状态与掉落回收完全一致。

### 3.4 A / B 修不到的两笔（2026-10-01 复核 sparkprofile 时补记）

原方案把「提交施工」算作一笔（`fillBoundaryAsAir` 5.34%），**实际是三笔**，A/B 一笔都碰不到：

| 笔 | 位置 | 实测 | 说明 |
|---|---|---:|---|
| ① 提交当帧造 JSON | `EnqueueHelper.fillBoundaryAsAir` | 6016 ms | §3.2 已记 |
| ② 提交后约 1 秒的蓝图编译 | `BuildingTaskSource.poll → BuildingTaskPool.enqueue → GlobalTaskPool.addTaskWithId → BlueprintRegistry.compile → BlueprintDefaults.clearAndBuild` | **2536 ms** | 710 万条 offsets 解析成 710 万个 `GridPos` + 710 万个 `TransformOp`，在服务端线程上跑 |
| ③ 第一次动工的批量跳过 | `TaskExecutionSystem` 4a（目标已是目标方块就 `advanceStep(); continue;`） | 未实测（外推 2–7 s 单帧） | 每次跳过都要走 `blockOps.getBlock()` = `getBlockState` + `BuiltInRegistries.BLOCK.getKey()` + `toString()` + 造 `BlockType`，666 万次全压在一个 tick 里 |

①②的数值是从 `run/config/spark/profile-2026-10-01_13.47.14.sparkprofile` 里直接读出来的
（protobuf，Server thread 单线程树；与 §2 的百分比换算一致：6016/100.7 s = 5.97%、
2536/100.7 s = 2.5%）。①在 §2.1 的树里，②③不在 —— ②挂在 tick 的 source poll 下、
③挂在 `TaskExecutionSystem.processNpc` 下，上一轮看树时漏了。

**顺带发现（另一条独立的账）**：`BuildingSavedData:674` 会把建筑任务队列里每个 WorkItem 的
params 用 `PARAMS_GSON.toJson` 序列化进 NBT。而 `BuildingTaskSource.poll` 在
`hasEligibleWork` 为假时**不弹队列**（缺料时就是这样），于是一个「已提交但仓库没料」的建造
任务会长期滞留队列 —— 此后每次自动存档都要把 710 万条三元组序列化成几十 MB 的字符串。
C1 把这条一起从 340 MB 级降到 25 MB 级；要彻底消掉得让 params 不再携带 pattern
（蓝图按 building_type 自己查配置），那违反「蓝图不 import MC」的纯逻辑边界，暂不做。

## 五、复现与复核

```bash
# 服务端（卡的是 Server thread）
/spark profiler start --only-ticks-over 100 --timeout 120
# 期间：总览模式交互一次 + 提交一次施工
/spark profiler stop
```

A / B / C1 全部修完后重跑，预期这几项从榜上消失或降到 1% 以下：

- `SimpleDataRegistry.getAll` / `java.util.Map.copyOf` / `LinkedTreeMap` 相关的占比（A）
- `ElementMappingLoader.findConfigByItemId` / `EnqueueHelper.computeMaterialCounts` /
  `findDisabledBlock`（A + B）
- `EnqueueHelper.fillBoundaryAsAir`（C1，整段方法已删除）、`BlueprintDefaults.clearAndBuild`
  与 `posList`（C1，710 万 → 58 万条，2.5 s → 约 0.2 s）
- `TaskExecutionSystem` 4a 的 `WandscapeBlockOps.getBlock` / `getKey` 尖峰（C1，不再存在）

新增应该出现的两项（正常表现，不是问题）：`tick.clear_box` 有恒定的每 tick 开销
（16384 格约 1.7 ms），以及 `ClearBoxExecutor` 的一次 start/done 两条 info 日志。

**还没验过的**：C1 的最终世界是否与改前逐格一致（清场后的方块集合 + 仓库回收量）。建议在
一座小建筑上做 A/B 对照：提交 → 比较「清场后盒内非 pattern 格是否全为空气」与
「回收进仓库的物品数量」，再拿大建筑复跑 spark 看单帧尖峰是否消失。

## 六、一句话

这次是**拿 spark 定的案，不是推算**。前一轮我从静态代码推出「逐块 `String.replaceAll`
重编译正则是元凶」，被实测否掉了（0.27%）；真正的元凶是一条**每次调用都整份复制再全表扫描**
的查表路径，被放在了一个 58 万次的循环里。教训记在这里：**逐块循环里的任何「查表」都要先问
一次调用成本**。
