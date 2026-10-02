# 超大建筑的渲染性能与数据体积方案（magic_academy 为样本）

> 日期：2026-10-01
> 状态：**A 档已实施**（A1 的非视觉部分 + A2 + A3 + A4，2026-10-01，口径见 §五）；**C9 / C10 已实施**（2026-10-02，口径见 §7.3），C11 已回退；C12 / C13（运行时堆内存）未做；A1 里会改变观感的部分与 A5 未做，B 档见 §六
> 适用版本：Minecraft NeoForge 1.21.1 / 分支 `1.21.1`
> 关联：[domain-notes.md](../domain-notes.md)（建筑域）、[data-formats.md](../data-formats.md)、[file-layout.md](../file-layout.md)、[adr.md](../adr.md)
> 外部参考：`_refs/litematica/`（LGPL-3.0，分支 `pre-rewrite/fabric/1.21.1-masa`，v0.19.50；已被 `.gitignore:116` 忽略，只读不抄，口径见 §八）

---

## 一、实测数字（现状）

样本是刚加入的 `src/main/resources/data/wandscape/buildings/magic_academy.json`：

| 指标 | 值 |
|---|---|
| 文件 | **29,490,718 字节 / 3,486,863 行**（pretty-print） |
| `pattern` 条数 | 580,814 |
| 其中 `minecraft:air` | **137,481（23.7%）** |
| `palette` 项数 | 460（全部用满 → 打包恰好需要 9 bit） |
| `boundary` | 154 × 234 × 197 = **7,099,092** 格体积 |
| 非空气方块 | 443,333 |
| 六面邻居都是「存在且遮挡」的 | 64,451 |
| **外壳（渲染时必须画的）** | **378,882，占非空气的 85.5%** |
| 占用的 16³ 段 | 803 个（中位 468 块/段，最大 2,322） |

**这栋楼是异常点，不是常态**：全目录 111 栋建筑里第二大的 `explorers_grocery` 是 15,747 块，magic_academy 是它的 **37 倍**。现有每条渲染/缓存预算都是按「一万块量级」调的，于是每一条都超了 37 倍。

玩家报的三个症状各对应下面四条独立爆点，**它们不是同一个 bug**，改一条不会让另外三条好转。

---

## 二、四条爆点（现状根因）

### 2.1 放置虚影 VBO —— 「点提交瞬间内存耗尽崩溃」的直接原因

`content/building/render/BuildingGhostVboCache.java:120`

```java
int capacity = Math.max(n * 24 * 32, 1024 * 1024);   // 580814 × 768 = 446 MB
```

`ByteBufferBuilder` 构造即 malloc（`com/mojang/blaze3d/vertex/ByteBufferBuilder.java:28`，`ALLOCATOR.malloc(capacity)`，失败抛 `OutOfMemoryError`）。也就是一进 `bake()` 就先吃 **446 MB 堆外内存**，之后 `resize()` 走 realloc 还有翻倍风险。

真正把量顶上去的是 `:153`：

```java
mc.getBlockRenderer().renderSingleBlock(state, pose, ghostSource, FULL_BRIGHT, OverlayTexture.NO_OVERLAY, ModelData.EMPTY, RenderType.translucent());
```

已核原版源码：`BlockRenderDispatcher.renderSingleBlock` 走的是 `modelRenderer.renderModel(...)`，**没有 `checkSides` 参数，不做任何相邻面剔除**。对比 `renderBatched(state, pos, level, pose, buf, checkSides, random, modelData, renderType)` 这条路径才会逐面判：

```java
// net/minecraft/client/renderer/block/ModelBlockRenderer.java:166 与 :236
if (!checkSides || Block.shouldRenderFace(state, level, pos, direction, blockPos$mutable))
```

于是：

| | 面数 | 顶点数 | 顶点数据 |
|---|---:|---:|---:|
| 现状（六面全发） | 2,659,998 | 10,639,992 | **324.7 MB** |
| 只做相邻面剔除 | 707,916 | 2,831,664 | **86.4 MB** |

（顶点按 `DefaultVertexFormat.BLOCK` 32 B 计）另外还有索引 `443333 × 6 × 4 ≈ 10.6 MB`。全部进一个 `VertexBuffer(STATIC)`，即显存。再叠加 `:35` 的 `Map<BuildingConfig, BakedGhostMesh[4]>` —— 四个旋转各一份，最坏 **4 倍**。

最后，烘焙是**在渲染线程同步跑完**的。触发链路：点提交 → 服务端登记工地 → `BuildingAreaSyncPacket` 下发 → `content/building/client/ConstructionGhostRenderer.java:74` 第一次 `renderGhostVboSkipped` → `getOrBake` → `bake`。

### 2.2 每帧全量重建 —— 「按 V 后严重卡顿」之一

- `BuildingGhostVboCache.java:190 rebuildMaskedIndex`：**每帧**遍历全部 580,814 格，每格一次 `mc.level.getBlockState(anchor.offset(...))` —— 58 万次世界方块查询/帧。
- `content/building/render/BuildingGhostRenderer.java:78 renderGhostAnimated`：**每帧**遍历全部 580,814 条 `config.pattern()`，每条一次 `resolved.get(off)` HashMap 查找 + `BuildingRotation.rotateOffset`，**只为挑出那几十个箱子/告示牌**。

### 2.3 每 tick 30 MB 分配 —— 「按 V 后严重卡顿」之二

`content/building/projection/client/ProjectionFlightController.java:169` 每 tick 调 `currentSelectionConflicts(ghostPos)`；`BuildGizmoController.java:213` 每拖一帧也调。往下到 `content/building/internal/BuildingVoxels.java:69-87`：

```java
Set<BlockPos> world = new HashSet<>(offsets.size());   // 443,333 个 BlockPos
for (BlockOffset off : offsets) { ... world.add(new BlockPos(x, y, z)); ... }
```

BlockPos（3 int + 对象头 ≈ 32 B）+ `HashMap.Node`（32 B）+ table 槽位 ≈ **约 30 MB / 次**，20 次/秒就是 **~600 MB/s 垃圾**，GC 直接被压死。

### 2.4 GIF 烘焙 —— 「生成 gif 严重卡顿」

`content/building/preview/BuildingPreviewGifCache.java`：

- `:308 bakeFrame` 每帧渲染 378,882 块（`:468 isEnclosed` 只砍掉 14.5%，因为这楼本来就是薄壳），× `FRAME_COUNT = 120` 帧。
- `:143` 的 8 ms 预算检查在 `materializeFrame` **之后** —— 只在帧之间限流，**单帧本身就是几百毫秒到数秒**。所以现象是「每渲染帧卡一次，连卡 120 次」，而不是慢慢填。
- `WandscapeClient.java:595`（登录）与 `:684`（reload）调 `warmAll()`（`BuildingPreviewGifCache.java:155`），把**全目录**排进队列 —— 玩家从没打开面板，这栋楼也在后台烤。
- `:291 stableName` 每次都重新拼一个 **约 15 MB 的字符串**（58 万个 blockId + 58 万个坐标）再 `hashCode()`；120 帧就重拼 120 次。
- `content/building/preview/BuildingPreviewRenderer.java:110-119 buildBlockStates` 造出的 `HashMap<BlockOffset, BlockState>`（58 万条）+ `fullEntries`（58 万条）由 `:49 META_CACHE` 常驻，约 **40 MB**。

---

## 三、对照：投影模组为什么渲染这么大也不会崩

`_refs/litematica` 的六条，条条我们没有：

| # | Litematica 做法 | 位置 | 我们的情况 |
|---|---|---|---|
| 1 | **逐面剔除**：按邻居判 `Block.shouldDrawSide`，不透明就丢面 | `render/schematic/BlockModelRendererSchematic.java:147 shouldRenderModelSide` | `renderSingleBlock` 六面全发，多 73% |
| 2 | **分列 + 静态 VBO + 脏区重建**：每 16×16×全高一列、每种 RenderLayer 一个 `VertexBuffer(STATIC)`，dirty 才重烤 | `render/schematic/ChunkRendererSchematicVbo.java:109` | 一整栋一个 buffer，首次同步烤完 |
| 3 | **视锥 + 距离剔除**：只把 `frustum.isVisible(bbox)` 且在 `renderDistance + 2` 内的列放进 draw list | `render/schematic/WorldRendererSchematic.java:301 setupTerrain` | 一次 draw 全楼，无剔除 |
| 4 | **逐帧时间预算**：重建每帧只花半帧 | `render/LitematicaRenderer.java:71-84` | 同步一次跑完 |
| 5 | **透明用全局 `shaderColor` alpha**，不因此关掉剔除 | `render/schematic/WorldRendererSchematic.java:466` | 逐顶点 alpha 转 translucent，266 万半透明面 → 巨大 overdraw |
| 6 | **非整方块**（箱子/告示牌）走 `BlockEntityRenderDispatcher` 单独画 | `render/schematic/WorldRendererSchematic.java:751 renderEntities` | 我们也是这么画的，但**每帧扫 58 万条把它们找出来** |

值得一提：Litematica 自己也有一个会炸的开关组合 —— 同时打开 `renderBlocksAsTranslucent` + `renderTranslucentBlockInnerSides` 就会绕过 `shouldDrawSide`，产出约 400 MB。**那正好是我们现在的形态**。

一句话概括差别：**它在构建期用邻居剔除 + 视锥剔除把「要画什么」压到很小，把「什么时候重算」交给脏标记；我们从构建到绘制全程都是「全量、同步、每帧」。**

---

## 四、文件体积：457 KB vs 29.5 MB

`.litematic` 的存法（`schematic/LitematicaSchematic.java:1083 writeSubRegionsToNBT`）：

- 每个 region 一条 `BlockStates` **LongArray**，palette 索引按 `bits = max(2, 32 - numberOfLeadingZeros(paletteSize - 1))` 打包（`schematic/container/LitematicaBlockStateContainer.java:149`）——460 项即 9 bit ≈ 1.125 B/格；
- 索引序为 y → z → x（`LitematicaBlockStateContainer.java:82 getIndex`），每 64 位长整型内 LSB-first 紧凑排列（`schematic/container/LitematicaBitArray.java:42-77`）；
- **覆盖整个 region 体积，空气也是 ID 0**（`LitematicaBlockStateContainer.java:103`）—— 空气长游程经 deflate 后几乎不占体积；
- 整体 `NbtIo.writeCompressed`（gzip，`:2233`），并带 `Version` / `MinecraftDataVersion` 迁移链。

拿本项目的 magic_academy 按同样规则实测了一遍：

| 存法 | 大小 |
|---|---:|
| 现状 pretty JSON | **29.5 MB** |
| 只压成 compact JSON（去缩进换行） | 8.56 MB（3.4×） |
| compact + gzip | 1.63 MB |
| **Litematica 式 9 bit 打包（全域 154×234×197）+ gzip** | **288 KB** |

（另测：同样内容按 16 bit 字节对齐再 gzip 只有 199 KB，因为对齐对 deflate 更友好。）所以 **29.5 MB → 约 0.3 MB 是约 100 倍**，与观察到的 457 KB 同一个量级。

我们的开销是四件事叠加：pretty-print（348 万行的缩进与换行本身占大头）、每个方块重复写绝对坐标、每个方块重复写 palette 索引、**air 也当普通方块逐条写**、全程零打包。

---

## 五、A 档：止血（已实施 2026-10-01）

范围口径：**只做不改变观感的部分**。任何会改变虚影外观的做法（降级预览、LOD、部分渲染、
分段烘焙）一律推到 B 档；「只渲染一部分方块」这种会让建筑支离破碎的方案已明确否决。

### 5.1 崩溃主因：`ByteBufferBuilder` 堆外内存泄漏（A1 已修）

排查过程中发现的**真问题**，也是「提交瞬间内存耗尽崩溃」的主因 —— 不是「预留太多」，是**从来不释放**：

- `ByteBufferBuilder` 整个类 156 行，**没有 `Cleaner` 也没有 finalizer**，唯一释放点是 `close()` 里的
  `ALLOCATOR.free(pointer)`。
- `ByteBufferBuilder.Result.close()`（`MeshData.close()` 走的就是它）只调 `freeResult()` →
  `discardResults()`，那只是把 `writeOffset` 归零，**不 free 指针**。
- `BuildingGhostVboCache.bake()` 里的 `vertBbb` 是局部变量，**全项目没有任何一处 close 它**。

于是每烘焙一次就永久漏掉整个 capacity。按 `n * 24 * 32` 算，这栋楼是 **446 MB**；
`CACHE` 按旋转分四个桶（`BuildingGhostVboCache.java:35`），转一圈就是 1.78 GB；
`closeAll()` 只关 GL 的 `VertexBuffer`，native 那笔照样收不回。堆外配额（默认等于最大堆）
吃光后 `malloc` 返回 0，`ByteBufferBuilder` 构造/`resize` 抛 `OutOfMemoryError` —— 崩在烘焙瞬间。

修法（`BuildingGhostVboCache.java:131-144`）：把构建体拆成 `bake()` + `buildMesh()`，
`bake()` 用 `try/finally` 保证 `vertBbb.close()`。时机是安全的 —— 已核
`VertexBuffer.uploadVertexBuffer` 是同步 `RenderSystem.glBufferData`，`upload()` 返回时
顶点数据已经拷进 GPU。

同时把 capacity 从 `n * 24 * 32` 改成 `vertexCapacityFor(config)`：非空气格数 × 6 面 ×
4 顶点 × 32 字节。空气在 pattern 里占约 24%（137,481 / 580,814），而空气质量为 0
（`AirBlock#getRenderShape` 返回 `INVISIBLE`，已核源码），把它们算进去是平白多占上百 MB。
顺带把原来 `n * 24 * 32` 的 int 溢出隐患（n 超过约 280 万就翻负）改成 long 运算。

### 5.2 A2：遮罩索引重建节流（已实施）

`drawGhostSkipped` 原来每帧重建一次遮罩索引：遍历整份 pattern 写退化四边形索引，再
`glBufferData` 整块上传。这栋楼的索引是约 266 万面 × 6 × 4 B（顶点数超 65535，索引类型是
INT）≈ **64 MB，每帧写一遍再传一遍**。

现在走 `needsMaskRebuild()`（`BuildingGhostVboCache.java`）：

- 当前上传的若不是遮罩索引（`drawGhost` 恢复过完整索引）→ 必须重建；
- anchor 变了 → 立即重建（不重建会拿错位置的遮罩）；
- 否则每 `MASK_REBUILD_INTERVAL_TICKS`（= 1）tick 至多一次。

即把「每渲染帧一次」降到「每 tick 一次」。`maskAnchor` / `maskTick` 挂在 `BakedGhostMesh` 上。

### 5.3 A3：动画方块候选表预计算（已实施）

`renderGhostAnimated` 原来每帧遍历整份 pattern（这栋楼 58 万条），每条做一次
`HashMap<BlockOffset, BlockState>` 查找加一次 `rotateOffset`（`steps > 0` 时每次 `new BlockOffset`），
只为挑出通常不到一百个箱子 / 告示牌。

现在按 (config, rotation) 预计算一张 `List<AnimatedCell>`（`offset` 已旋转、`Block` 与
`ItemStack` 预建），弱键缓存，与 `BuildingPreviewRenderer.META_CACHE` 同一口径。

### 5.4 A4：客户端冲突检测去装箱（已实施）

`BuildingVoxels.compute` 每 tick 建一个 `HashSet<BlockPos>`：这栋楼 44 万格 = 每格一个
`BlockPos` 对象加一个 `HashMap` 节点，约 **32 MB / 次**，再加 44 万次哈希插入。

- `BuildingVoxels` 新增 `PackedOccupancy`（`LongSet` + AABB）与 `computePacked` /
  `computePackedFromOffsets` / `overlaps(PackedOccupancy, PackedOccupancy)`，规则与装箱版逐字一致
  （同样先 AABB 粗筛、再迭代较小一侧逐格相交）。
- **服务端一条没动**：`Occupancy.positions()` 要持久化进 SavedData 并拿来建 posIndex，
  所以 `BuildingSavedData` 那条路继续用装箱版。两条路并存是刻意的，不是遗漏。
- `BuildingAreaSyncPacket` 切到打包版，并给候选占用加一条缓存（`candidateOccupancy`）：
  准心不动时同一栋建筑同一个 anchor 会被反复问，缓存命中就不再重建。

### 5.5 未做 / 已知残留

| 项 | 状态 | 说明 |
|---|---|---|
| A1 的降级预览闸门 | **未做**（判断为破坏性） | 会改变观感，按决策推到 B 档 |
| A5 `warmAll()` / `stableName()` | **未做**（按决策） | 理由：超大建筑优化到位后不再是问题。注：`stableName()` 每次重建约 15 MB 字符串这件事与建筑大小无关，B8 做实了顺手收掉 |
| 同 config + 旋转的多个工地共享一份网格 | 残留 | `ConstructionGhostRenderer` 逐个工地调 `drawGhostSkipped`，共享同一个 `BakedGhostMesh`，anchor 交替变化会让节流失效 —— 但这**不比改之前差**（改之前本来就每帧重建）。彻底解法是每个工地独立索引缓冲，属 B7 |
| 选中建筑同时也在施工时的索引抖动 | 残留（改动前就有） | `ProjectionRenderer` 走 `drawGhost`（恢复完整索引），`ConstructionGhostRenderer` 走 `drawGhostSkipped`（重建遮罩索引），同一份网格每帧来回 64 MB。只有「选中的那栋正好也在施工」才触发 |
| GPU 侧 266 万半透明面 | 残留 | A 档一点没动，是 B6 的活 |
| GIF 烘焙 120 帧 × 37.9 万块 | 残留 | A5 + B8 的活 |


---

## 六、B 档：结构性改造（对齐 Litematica，收益最大）

| # | 改动 | 预期 | 备注 |
|---|---|---|---|
| B6 | **用原版面剔除**：造一个 `BlockAndTintGetter` 视图包住 pattern，改用 `BlockRenderDispatcher.renderBatched(state, pos, ghostLevel, pose, buf, checkSides = true, random, ModelData.EMPTY, RenderType.translucent())` | 顶点 −73%（324.7 MB → 86.4 MB），并顺带白拿 AO | 投入产出最高的一条 |
| B7 | **按 16³ 段切静态 VBO + 视锥剔除**：803 个非空段、中位 468 块/段，每段 mesh 很小；绘制只画视锥内的段，anchor 变化才重烤 | 常驻显存与每帧 draw 都降到可控 | 现有 `BuildingGhostVboCache` 骨架可复用 |
| B8 | **GIF 用 LOD**：见 §6.2（已实施） | 那栋楼每帧绘制次数 378,882 → 2,925（**152×**） | — |

LOD 各档实测（每格画一个立方体，32 B/顶点）：

| LOD | 格数 | 可见格 | 顶点 | 顶点数据 |
|---|---:|---:|---:|---:|
| 1（现状） | 443,333 | 378,882 | 9,093,168 | 277.5 MB |
| 2 | 99,234 | 87,487 | 2,099,688 | 64.1 MB |
| **4** | **4,392** | **2,925** | **70,200** | **2.14 MB** |

### 6.1 已实施口径（B6 逐面剔除，2026-10-01）

**视图语义（已敲定）**：空气格、pattern 里没有的坐标、跨边界的邻居查询 —— 三者一律
返回空气，含义是「这格没有方块」。**不等于把该格替换成空气**（虚影只渲染，从不在世界里
放东西）。空气不遮挡，所以贴着空气的那一面照常画出来，正是我们要的。

**新增** `content/building/render/BuildingGhostBlockView.java`：一个只读的
`BlockAndTintGetter`，内部是覆盖 pattern 包围盒的定长索引表（`short[]` + 一张小状态表），
空气一律落在 0 号槽。不用 `Long2ObjectMap` 是因为一次烘焙的邻格查询在「每块 × 每面」
量级（那栋楼约三千万次），哈希每次几十纳秒，稠密表是 3 ns 量级。代价是固定内存：
154×234×197 ≈ 710 万格 → 约 14 MB。

**走 `tesselateWithoutAO` 而不是 `renderBatched`/`tesselateBlock`**：三者都带 `checkSides`，
但后两者会按客户端设置挂上 AO，凭空多出转角明暗；而原路径 `renderSingleBlock` 是平光。
选不带 AO 的那版，配上视图把 `getBrightness` 恒返 15、`getShade` 恒返 1.0，才和改造前一致。
（对照：`renderSingleBlock` → `renderModel`，**没有 `checkSides`，六面全发**。）

**着色不丢**：AO 那版走 `putQuadData` → `blockColors.getColor(state, level, pos, tintIndex)`，
用的是我们传进去的 level 与 pos，所以视图把 `getBlockTint` 转发到真实世界
（`anchor.offset(pos)`）即可保住群系染色。

**唯一有意为之的观感差异**：改造前 `renderSingleBlock` 用的是
`blockColors.getColor(state, null, null, 0)`，也就是**固定的默认草绿**；现在换成目的地
群系的真实染色。只影响草/树叶/藤蔓这类带 tint 的方块（那栋楼里 46 格 / 443,333）。
另外随机模型（`state.getSeed(pos)`）从一个共享随机序列变成了按位置确定，观感更稳定。

**兜底**（硬规则 4）：包围盒超过 1600 万格、状态种类超过 `Short.MAX_VALUE`、
或客户端没有世界时，`create` 返回 null，烘焙退回不做剔除的老路径 —— 宁可不优化，也不赌内存。

**实测预期**（样本那栋楼）：可见面 2,659,998 → 707,916（−73.4%），顶点数据
324.7 MB → 86.4 MB，索引 64 MB → 约 17 MB。**这很可能让全细节渲染直接可行，
连降级预览都不需要** —— 这也是把它排在 B7 前面的原因。

### 6.2 已实施口径（B8 缩略图 LOD，2026-10-01）

**口径**（用户定）：缩略图可以粗，但**建筑看上去必须完整** —— 所以用缩放式归并，
不是抽稀。每 k³ 格只画一个代表方块并把它**放大 k 倍**填满整格，建筑看着仍是连续实心的；
抽稀那种画法会变成漂浮的点阵，直接违背这条。

**改动**（`BuildingPreviewGifCache`）：

- 原本每帧要画 `visibleEntries` 全部可见方块（那栋楼 378,882 次 `renderSingleBlock`），
  现在先按 `LOD_CELL_BUDGET = 12_000` 选归并系数：从 k=1 起逐级翻倍，直到不同格子数落回预算。
- **代表方块优先取能遮挡的整方块**：一格里第一个碰到的可能是火把、告示牌或树叶，
  放大 k 倍会画成一片漂浮的碎片 —— 宁可取它的石墙。
- 外壳剔除也改在归并后的格子上做（六面邻格都在且都遮挡才丢），比按方块做便宜一个数量级。
- `CACHE_VERSION` 4 → 5，旧帧自动重烤。

**实测量级**（按当前数据）：

| 建筑 | factor | 每帧绘制次数 | 降幅 |
|---|---:|---:|---:|
| magic_academy | 8 | 2,925 | **152×** |
| explorers_grocery | 2 | 2,183 | 7× |
| adventures_union | 1 | 9,197 | 1×（在预算内，行为与改造前一致） |
| 其余 53 栋 | 1 | ≤ 7,300 | 1× |

全仓只有 **2 栋**触发 LOD。最坏单帧约 12 ms（adventures_union 的 9,197 次），
对比改造前那栋楼约 570 ms/帧 —— 累积烘焙时间从几十秒降到一两秒。

**如果嫌 magic_academy 缩略图太粗**：`LOD_CELL_BUDGET` 调到 `25_000` 就会用 factor 4
（横向 38 格，每格约 2.6 px），代价是那栋楼单帧约 23 ms。这是一个常量的事。

### 6.3 已实施口径（B7 分段 VBO + 视锥剔除，2026-10-01）

`BuildingGhostVboCache` 的几何改成按 **16³ 段**切分，每段一个独立的 `VertexBuffer` 与索引，
`drawGhost` / `drawGhostSkipped` 多接一个 `Frustum`（调用方 `ProjectionRenderer` 与
`ConstructionGhostRenderer` 都传 `event.getFrustum()`）。

| 项 | 改造前 | 现在 |
|---|---:|---:|
| 单次遮罩索引重写 | 整栋 16.2 MB（写 + `glBufferData`） | **单段**中位 **18 KB**、最大 87 KB |
| 构建期顶点缓冲峰值 | 整栋预留（那栋楼上百 MB 级） | **最大一段**（中位 468 格） |
| 每帧绘制 | 全楼 803 段一次 draw | 只画视锥内的段 |
| `INDEX_BBB` 共享暂存 | 会被撑到 16 MB | 只需容纳单段 |

那栋超大建筑有 **803 个非空段**（中位 468 块/段、均值 552、最大 2322）。逐段构建、建完
立刻 `close()` 掉该段的 native 顶点缓冲，所以构建期峰值只受最大一段约束 —— 这也是 A1
那个「按整栋楼预留」的彻底解法。

**残留（先记着，不必现在做）**：`drawGhostSkipped` 每 tick 会把**所有可见段**重建一遍。
段都可见时，一趟的总写入量与改造前相同，只是分段后每次是小块、且能被视锥剔掉一部分。
要再压一档可以给重建加「每 tick 时间预算 + 轮转」，代价是遮罩最多落后几个 tick；
anchor 变化时必须整轮重建（否则会用错位置的遮罩）。等实测确认还有感知再上。

---

## 七、C 档：数据格式与对象模型

| # | 改动 | 收益 | 风险 |
|---|---|---|---|
| C9 | **去 pretty-print**：落盘一律紧凑 JSON | 缩进不再占体积，零逻辑改动 | 无 |
| C10 | 自定义打包格式 `PatternCodec` v1：**稀疏**（排序线性下标差分 varint + 值 varint 两条流，各自 gzip + base64），**做我们自己的格式，不要求兼容 `.litematic`** | 全仓 36.4 MB → **1.5 MB**（23.6×）；magic_academy 29.5 MB → **361 KB** | 需带版本号断档（[data-formats.md](../data-formats.md) 与硬规则 7） |
| C11 | ~~air 语义定为「这格不存在」，收口进 `BuildingConfig.NON_CELL_BLOCK_ID`~~ → **已回退**：不给 air 任何特殊待遇，迁移时把存量 air 格直接删成缺失 | —— | **见 §7.3** |
| C12 | 对象模型去装箱：`List<BlockOffset>` → `int[]`、`List<Integer>` → `short[]`；`blockMapping()`（`BuildingConfig.java:209`，58 万条 String 键 HashMap，被 `BuildCompleteListener.java:189` / `BuildingRepairHandler.java:43` / `EnqueueHelper.java:448` 调用）要么删、要么改 int 键 | 常驻堆 −30 MB 级，且消掉一个约 100 MB 瞬时的定时炸弹 | 触及多个调用点 |
| C13 | `rawJsons` 不再常驻 Gson 的 `JsonElement` 树（`BuildingConfigLoader.java:289`）；网络同步直接从序列化字节走（`Wandscape.java:905` 现在是先 `json.toString()` 再压） | 光这一栋楼的树按量级估算就是 **150–200 MB** 常驻 | 影响 `getRawJsons()` 的全部消费者 |

C13 的量级说明：Gson 把 `[0,0,1]` 存成 `JsonArray` + `ArrayList` + 3 个 `JsonPrimitive`（每个内部包一个 `String`），单个三元组约 270 B；58 万个三元组即约 150 MB，加上 `block_indices` 数组与之同量级 ⇒ 200 MB 量级。这是估算，不是实测。

### 7.1 「删掉 pattern 里的 air」专项排查（2026-10-01）

**先说分布**：全仓 56 栋建筑 pattern 共 698,554 条，其中 air 137,509 条。但这 137,509 条里
**137,481 条全在 magic_academy 一栋**；其余 27 栋合计只有 28 条，且那 28 条是上一轮建材改造
故意留的「把这个方块删掉」标记（palette 写成 `minecraft:air`、索引不动，见
[earlygame-building-materials.md](earlygame-building-materials.md) §6.1）。

**再看来源**：当前扫描器**永不导出空气** —— `ScannerExportPacket.java:138` 就是
`if (state.isAir()) continue;`，没有任何开关。所以 magic_academy 那 137k 条是旧版扫描器或
后处理工具留下的，当前管线不会复现。

**air 条目承重的三处（删掉会弱化）**：

| 行为 | 位置 | 删掉之后 |
|---|---|---|
| **修复断言** | `BuildCompleteListener.java:183-206 findDamagedBlocks` 把 `"minecraft:air"` 当「此处应为空」，`BuildingRepairHandler.java:157` 据此点亮修复按钮、`:38-79` 真的把被填的格清空 | 不再认为这些格「该空」，被塞了东西也不报损坏、不清理 |
| **拆除语义** | `BuildingApiImpl.java:314-326` 拆除 offsets 直接取自 `config.pattern()` | 这些格上的方块不再被清除，也不再被 `AsyncTransformExecutor.performSalvage` 回收进仓库 |
| **占用/重叠** | `BuildingVoxels.java:90-108 computeFromOffsets` 把含空气在内的**全部**偏移塞进占用集，`BuildingSavedData.java:549-573` 据此建 posIndex 与重叠门禁 | 这些体素不再独占，两栋建筑可在原 air 格上重叠 |

**不承重的（确认无影响）**：物料统计（`EnqueueHelper.java:387` 显式跳过 air）、仓库扣料
（`BuildingRepairHandler.java:121`、`BuildingApiImpl.java:480` 同样跳过）、**建造完成判定**
（`BuildCompleteListener.java:87` 无条件 `setStructureIntact(true)`，压根没有 pattern 比对 gate）、
渲染几何（空气 `INVISIBLE`，0 个四边形）、x/z 居中（`BuildingCentering.java:29-35` 只看 min/max x/z）。

> **勘误（2026-10-02）**：上面这份清单当时把「x/z 居中」也列了进来，**那条是错的**。
> `BuildingCentering.rotatedCenterOffsets` 用 `config.pattern()` 的 min/max x/z 算中心，而 C11 只在
> 三处过滤、**没动 `pattern()`**，所以 C11 当时确实没影响它 —— 但 §7.3 的迁移把空气格从
> `pattern()` 里**真删了**，于是它承重。实测全仓 56 栋只有 1 栋受影响：magic_academy 的 z=0、z=1
> 两层整层是空气，删掉后 `minZ` 0 → 2，居中偏移 `floor((0+196)/2)=98` → `floor((2+196)/2)=99`，
> **放置锚点沿 Z 平移 1 格**。判断：按「真正属于建筑的格子」居中比按两整层幻影空气居中更正确，
> 故接受该偏移、不做补偿。`boundary` 未动（仍是 `z∈[0,196]`），清场范围、注册占地与虚影几何
> 都不受影响。

**默认建造路径下它本来就冗余**：`clearBox` 默认 true，`EnqueueHelper.fillBoundaryAsAir`
（`:313-345`）会给 boundary 里**每一个**体素补写 air。经核对，含 air 的 10 栋建筑的 air 偏移
**全部落在自己的 boundary 内**，且 pattern 偏移无重复 —— 也就是说这些 air 对最终世界状态
**没有增量贡献**（boundary 填充已经覆盖了它们）。唯一 `clearBox=false` 的生产路径是测试命令
`/wandscape spawnall`（`SpawnAllBuildingsCommand.java:267-268`），那条路下 pattern 的 air
才是唯一的清格来源。

**已定口径与实施（2026-10-01）**：这些 air 是**手工标注**出来的 —— 导出把建筑周围的山体、
树木一并收进了 pattern，标注者把那些不属于建筑的方块改写成 air 以把它们踢出建筑。而当前
扫描器（`ScannerExportPacket.java:138`）从不导出空气，所以数据里的 air 一律是这个意思。

**这一节的口径已被推翻，见 §7.3。** 上面那段分析（air 从哪来、曾经怎么承重）仍然有效，
但结论——「把 air 定义成『这格不存在』的特殊标记、并在代码里三处跳过它」——是**错的**：
它给空气开了一个本不该有的特例。正确做法是**不给 air 任何特殊待遇**，把这些手动标注的格子
在迁移时直接删成缺失。

**仍然有效的两条边界**（改这块前先看）：

1. `EnqueueHelper.fillBoundaryAsAir` 里的 `"minecraft:air"` 是**另一回事** —— 那是
   `clearBox` 整箱清空的真实放置操作（一个动作），不是「缺席」标记。
2. 物料统计里不该向仓库要空气。这一条不靠特判，靠「空气没有元素映射」自然成立
   （`computeMaterialCounts` 只统计 `mapped` 的 palette 项）。

---

### 7.3 C11 回退 + C9/C10 实施口径（2026-10-02）

**回退 C11**（6 文件 9 处，全部删掉 air 特判）：

| 文件 | 回退内容 |
|---|---|
| `BuildingConfig` | 删 `NON_CELL_BLOCK_ID` / `isNonCellAt()` / `solidPattern()`；`blockMapping()` 不再跳过任何格 |
| `BuildCompleteListener` | 删 `parseExpected` 里的 air 早退 |
| `BuildingApiImpl` | 拆除 offsets 改回 `config.pattern()` |
| `BuildingVoxels` | 占用/重叠改回 `config.pattern()` |
| `EnqueueHelper` | `patternToJson` 改回 `pattern()`、`blocksFromPalette` 删跳过；顺带删掉 `PaletteScan` 里已成死重量的 `air[]` 数组 |

回退后 `docs/domain-notes.md` 里「建筑自注册起拥有其 pattern 所占每个格」重新成立
（C11 之后那句话其实已经和代码不符了）。

**C10 格式**（`content/building/data/PatternCodec`，纯逻辑不 import MC，扫描器编码 + 加载器解码
共用一份实现）：

```json
"pattern": { "format": 1, "origin": [...], "size": [...],
             "palette": [...], "cells": "<base64>", "values": "<base64>" }
```

- **稀疏**：只存有方块的格子。线性下标升序，存差分 varint；palette 下标另开一条流逐位对齐。
- 两条流各自 gzip(BEST_COMPRESSION) + base64。自带 `origin`/`size`，不复用 `boundary`。
- 顶层 `palette` / `block_indices` **删除**；`pattern` 从数组变对象。

**为什么不是 litematica 那种全域定长位域**：同一栋楼实测，全域 9 bit 铺满 + gzip = **414 KB**，
稀疏流 = **189 KB 载荷**（+ 信封 172 KB = 文件 361 KB）。稀疏把「没有方块的地方」压到几乎为零，
且是完全自成一体的编码（有序流 + 变长整数 + 双流对齐），与投影模组的「定长位域 + LSB-first
紧凑排列」不是同一套东西 —— 只借鉴了「palette + 位/字节打包 + gzip + 带版本号」这个大方向。

**实测**：全仓 56 栋 36.4 MB → 1.5 MB（23.6×）；magic_academy 29.5 MB → **361 KB**。
注意 **jar 只小 12 万字节**（14.94 MB → 14.81 MB）—— pretty JSON 本来就被 zip 压掉了大半，
所以这次的收益在**源码/仓库体积与加载解析开销**，不在模组下载体积。
`block_nbt` 现在是最大的单项（magic_academy 159 KB，1087 条逐条 gzip 的 base64 NBT），是下一步
的压缩目标。

**迁移与断档**：存量文件用一次性脚本转换（**脚本用完即删，不入库**），转换前后逐格无损 ——
只丢那 137,481 条人为标注的 air（magic_academy 一栋占 137,481 条）。**运行期没有迁移代码**：

- 旧格式（`pattern` 数组 + `block_indices`）见到即抛 `JsonParseException`，玩家此前导出的建筑
  **直接失效**，需重新扫描；
- 因此这是**版本断档**，需在 release 正文给出不兼容提示（见 `docs/checklists.md` §三）。
  另注：`block_indices.size() != pattern.size()` 那类运行时校验随旧格式一起消失，
  尺寸一致性由 `PatternCodec` 在解码期保证（cells 与 values 两条流长度必须相等）。

**验证方式**：`./gradlew build` 只保证编译。格式一致性用一次性的对账做了——编译仓库里**真实的
`PatternCodec.java`**（只依赖 gson），解码全部 56 个迁移后文件算出逐格 SHA-256，与「迁移前
git HEAD 原始文件滤掉空气后」的同名摘要比对，56 条全部相同。

**附带发现（与删不删 air 无关，但更值得看）**：`EnqueueHelper.fillBoundaryAsAir:330` 会给
boundary 里每个体素补一条 op。magic_academy 的 boundary 体积是 **7,099,092**，pattern 只有
580,814 —— 也就是建造时会被膨胀成约 **710 万条 offset/op**（其中约 650 万条是空气）。
**这才是那栋楼建造耗时的真正大头**，而且删掉 pattern 里的 13.7 万条 air 对它毫无改善
（那些格本来就在 boundary 内，删了也会被 boundary 填充原样补回）。要动应该动这里。

### 7.2 服务端卡点

提交施工与打开工地 UI 的秒级卡顿**不在渲染侧**，是服务端的两条独立开销；已用 spark 定案，
结论与方案移到 [server-tick-hotspots.md](server-tick-hotspots.md)。

留一句教训在这里：那一轮我从静态代码推出「逐块 `String.replaceAll` 重编译正则是元凶」，
被 spark 否掉了（0.27%）—— **逐块循环里的任何「查表」都要先问一次调用成本**，
这也正是 §7.1 那类「按包围盒体积展开」的写法真正贵在哪里的原因。
