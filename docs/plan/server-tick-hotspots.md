# 服务端 tick 卡点排查（提交施工 / 打开工地 UI）

> 日期：2026-10-01
> 状态：**A / B 已实施（2026-10-01）**，C 档待定（C0 已被 C1 取代，见 §四）
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

## 四、方案（按性价比排序，均未实施）

### A · 元素映射查表改成 O(1)（最高优先）

两处一起改才有效，缺一不可：

1. **`SimpleDataRegistry.getAll()` 不再每次复制**：维护一份不可变快照，`loadEntry` / `clear`
   时置脏，下次 `getAll` 只重建一次。这样它也顺带给了「按快照缓存」一个稳定的身份可比较。
   注意它有很多其它调用点（JEI、建筑目录、配方等），全部一起受益。
2. **`ElementMappingLoader` 建 `id → config` 索引**：同时登记 `itemId` 与 `blockId` 两个键，
   按 `getAll()` 返回的快照实例做身份比较来失效；`findConfig` / `findConfigByItemId` /
   `hasMapping` / `isDisabled` 全部改走它。`runtimeOverrides` 本来就是 O(1)，保持先行。

预期：41.5 s → 亚秒级。

### B · 逐块循环改按 palette 预解析

`computeMaterialCounts` / `findDisabledBlock` 的循环里只需要「这个方块 id 有没有映射 / 是否
disabled」。先按 **palette**（几百项）解析一次纯 id 与判据，再逐块查表：

- 58 万次查表 → 几百次；
- 即使 A 做了，这条也能把剩下的 58 万次 HashMap 查找压到忽略不计，且是纯局部改动；
- 顺手把 `String.replaceAll("\\[.*?\\]", "")` 换成手写截断（白给的，虽然只占 0.27%）。

### C · `fillBoundaryAsAir` 不再展开（需单独决策，三个档位）

这笔开销是**单次 6 秒**、只在提交那一瞬间，不修的话「提交卡一下」会一直留着。

**C0 · 只换容器，不碰 op（新增，优先推荐）** —— 由 §3.2 的实测直接推出：

既然贵的是 `blocks` 那个 **`LinkedTreeMap` 的逐条插入**（4.18%），而 `offsets` 那个
**`JsonArray` 追加几乎免费**（0.03%），那就**只往 `offsets` 里加边界格、不再往 `blocks` 里塞
6.6 万个 air 映射**，让 `BlueprintDefaults.clearAndBuild` 把「在 offsets 里但 blocks 里查不到」
当作 `minecraft:air` 来处理（现在这种 key 是 `continue` 跳过）。

- 效果：把 666 万次 O(log n) 的树插入换成 666 万次数组追加，预计 5.34% → 接近 0；
- 不改 op、不改 executor、不改执行语义（清空盒内这件事照旧发生）；
- 需要动的地方只有两处：`EnqueueHelper.fillBoundaryAsAir`（不发 air 映射）与
  `BlueprintDefaults.clearAndBuild`（缺失 key 视为 air）。
- **风险**：`blocks.get(key) == null` 的语义从「跳过」变成「放空气」。当前所有 offsets 都来自
  pattern（必有映射）或边界补格，所以实际不会误伤；但这是个语义耦合，改之前要确认没有别的
  调用方依赖「缺 key = 跳过」。

**C1 · 传包围盒 + 排除集给蓝图**（彻底版，见 [large-building-render-perf.md](large-building-render-perf.md) §7.1）：
执行期（区块已加载）枚举、跳过 pattern 格与本来就是空气的格。要动 op / executor / 蓝图，
用户此前明确说不想碰。

## 五、复现与复核

```bash
# 服务端（卡的是 Server thread）
/spark profiler start --only-ticks-over 100 --timeout 120
# 期间：总览模式交互一次 + 提交一次施工
/spark profiler stop
```

修完 A / B 后重跑，预期这几项从榜上消失或降到 1% 以下：

- `SimpleDataRegistry.getAll` / `java.util.Map.copyOf` / `LinkedTreeMap` 相关的占比
- `ElementMappingLoader.findConfigByItemId`
- `EnqueueHelper.computeMaterialCounts` / `findDisabledBlock`

`fillBoundaryAsAir` 只有在做 C 之后才会掉下去；若未做 C，它仍应是榜上唯一的大项。

## 六、一句话

这次是**拿 spark 定的案，不是推算**。前一轮我从静态代码推出「逐块 `String.replaceAll`
重编译正则是元凶」，被实测否掉了（0.27%）；真正的元凶是一条**每次调用都整份复制再全表扫描**
的查表路径，被放在了一个 58 万次的循环里。教训记在这里：**逐块循环里的任何「查表」都要先问
一次调用成本**。
