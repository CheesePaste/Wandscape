# 建筑数据调色板重构 + 分块网络同步

状态：已完成（2026-08-13，Phases 0–7 全部落地并提交）
日期：2026-08-13
作者：Wandscape 开发（AI 协作）

## 1. 背景与问题

报错（Netty Server IO）：
```
Failed encoding custom payload wandscape:building_config_sync:
String too big (was 519434 characters, max 262144)
```

`BuildingConfigSyncPacket` 在 `OnDatapackSyncEvent` 时把每个建筑的**完整 JSON 字符串**发给客户端，
每个字符串 `writeUtf(..., 262144)` 有 262144 字符上限。新增的 sea_store 紧凑 JSON 519449 字符，直接超限崩溃。

两个根因：
1. **体积大（磁盘 + 网络）**：`block_mapping` 是 `{"x,y,z": "完整方块状态字符串"}`，每个方块都重复写一遍方块 ID，
   占整份 JSON 的 66–79%。sea_store 8502 块里只有 462 种不同方块。
2. **结构上限**：即使调色板压缩，单字符串 262144 上限仍在，更大建筑仍会超。需要分块多包同步从根上解决。

## 2. 现状盘点

### 2.1 数据结构（BuildingConfig）

```jsonc
// 当前格式（buildings/<id>.json）
{
  "id": "sea_store",
  "pattern": [[x,y,z], ...],            // N 个占用位置（建造顺序 Y→X→Z）
  "block_mapping": {"x,y,z": "minecraft:xxx[...]", ...},  // N 条，每方块重复完整 ID
  "block_nbt":    {"x,y,z": "<base64 压缩 NBT>", ...},   // 仅 BE 方块
  "entities":     [{"offset":[...],"type":"...","facing":"...","nbt":"..."}],  // 装饰实体
  "boundary": {...}, "shop": {...}, ...
}
```

Java 模型：`BuildingConfig` record，字段 `List<BlockOffset> pattern` + `Map<String,String> blockMapping`（键 "x,y,z"）。

### 2.2 数据流

```
扫描器 ScannerExportPacket（写文件）
  → BuildingConfigLoader（反序列化 → BuildingConfig）
  → 服务端:
       EnqueueHelper.buildWorkItem → WorkItem(blueprint params)
         ├ offsets=pattern, blocks=block_mapping, blocks_nbt=block_nbt, entities
         ├ material_list/counts 由 pattern→block_mapping 自动统计
         └ 旋转: rotateBlockMappingJson（键+方块状态值一起转）
       OnDatapackSyncEvent → BuildingConfigSyncPacket(List<String> jsonConfigs) ← 崩溃点
  → 客户端:
       BuildingConfigSyncPacket handler → BuildingConfigLoader.registerFromJsonString → 同上模型
       渲染: ProjectionRenderer / ConstructionGhostRenderer / BuildingPreviewRenderer 读 pattern+blockMapping
```

### 2.3 尺寸实测（紧凑 JSON 字符数）

| 建筑 | blocks | 不同方块 | pattern | block_mapping | block_nbt | entities | 合计 |
|---|---|---|---|---|---|---|---|
| sea_store | 8502 | 462 | 86K (17%) | 352K (68%) | 49K (10%) | 30K (6%) | 519K |
| ancient_store | 7614 | 433 | 75K (17%) | 306K (68%) | 39K (9%) | 29K (6%) | 451K |
| creature_store | 8310 | 447 | 83K (17%) | 346K (69%) | 38K (8%) | 34K (7%) | 503K |
| autumn_wind_station | 4315 | 330 | 41K (14%) | 189K (66%) | 33K (12%) | 22K (8%) | 286K |

**block_mapping 是绝对大头。调色板把它的 N×重复 ID 换成 M×唯一 ID + N×小索引。**

## 3. 新数据格式设计（调色板）

```jsonc
// 新格式（buildings/<id>.json）
{
  "id": "sea_store",
  "pattern":        [[x,y,z], ...],          // N 个位置，不变
  "palette":        ["minecraft:stone_bricks", "minecraft:oak_log[axis=x]", ...],  // M 个唯一方块状态
  "block_indices":  [0, 1, 0, 2, ...],       // N 个索引，block_indices[i] ↔ pattern[i]
  "block_nbt":      {"x,y,z": "<base64>", ...},  // 保持 "x,y,z" 键（见决策 5.1）
  "entities":       [...],                   // 不变
  "boundary": {...}, "shop": {...}, ...
}
```

### 3.1 Java 模型

`BuildingConfig` record 字段由 `Map<String,String> blockMapping` 改为：
- `List<BlockOffset> pattern`（不变）
- `List<String> palette`（新）
- `List<Integer> blockIndices`（新）

新增派生方法（保留旧调用点，调用方零改动）：
- `String blockIdAt(int patternIndex)` = `palette.get(blockIndices.get(i))`
- `Map<String,String> blockMapping()`：O(N) 构建 `{"x,y,z": blockstate}`，供
  BuildCompleteListener / BuildingBreakHandler / ColonyCommand / EnqueueHelper / 渲染器 使用。
  ⚠️ 渲染端是每帧调用，必须缓存（见 5.4）。

### 3.2 向后兼容（必须）

`BuildingConfig.Deserializer` 同时解析两种：
- 新格式有 `palette` → 直接读。
- 旧格式有 `block_mapping` → 转换：遍历 pattern[i]，查 `block_mapping[pattern[i].toKey()]`，
  去重入 palette，填 block_indices[i]。旧格式的 `block_mapping` 与 pattern 完全对齐（扫描器保证），
  不对齐的孤儿键丢弃。

兼容保证：老世界 datapack 里导出的旧建筑文件（`wandscape_builds/`）在 /reload 后仍能加载；
且 git 仓库内现有 JSON 未迁移前也可运行。但**要拿网络体积收益，文件必须迁移成新格式**（服务器发的是原始 JSON 文件）。

## 4. 分块网络同步设计

### 4.1 目标

单字段 262144 上限从根上消除：建筑配置**压缩后分块**，每块远小于上限，未来任意大建筑都能同步。

### 4.2 协议

替换 `BuildingConfigSyncPacket`（List<String>），新增一个分块包：

```
BuildingConfigSyncChunkPacket {
  int configIndex;     // 第几个配置（服务端按稳定顺序发送）
  int chunkIndex;      // 第几块
  int totalChunks;     // 该配置总块数
  byte[] payload;      // zlib 压缩后的某一段（每块 ≤ 16KB）
}
```

编解码：configIndex/chunkIndex/totalChunks 用 `writeVarInt`；payload 用 `writeBytes`（带长度前缀，
上限 2MB，16KB 块远低于此，彻底避开 writeUtf 的 262144）。

### 4.3 服务端发送（onDatapackSync）

```
for (json : rawJsons.values()):
    bytes = zlib.compress(json.toString().getBytes(UTF_8))   // ~8-10x 压缩
    按 16KB 切成 chunk[0..total-1]
    for k: send BuildingConfigSyncChunkPacket(configIndex, k, total, chunk[k])
```
`rawJsons` 是 ConcurrentHashMap（迭代顺序稳定），configIndex 即迭代序号。

### 4.4 客户端重组

handler 维护 `Map<Integer, byte[]> buffers` + `Map<Integer, Integer> receivedChunks`：
- 收 chunk(c, k, T, payload)：写缓冲，计数 +1。
- 当 k 计数 == T：拼接 → zlib 解压 → String → `BuildingConfigLoader.registerFromJsonString` → 清空该条目。
- configIndex 递增连续 → 客户端可据此判定"全部收到"（或用首个包内的总配置数）。

### 4.5 体积推算（sea_store，新格式 + zlib）

| 项 | 当前 | 调色板后 |
|---|---|---|
| pattern | 86K | 86K |
| block_mapping | 352K | palette 18K + block_indices 27K |
| block_nbt | 49K | 49K |
| entities | 30K | 30K |
| 紧凑 JSON 合计 | 519K | **≈211K（−59%）** |
| zlib 后发送字节 | — | **≈50K → 4 个 16KB 块** |

## 5. 关键决策与理由

1. **block_nbt 保持 "x,y,z" 键，不改索引键**
   蓝图 DSL 用 `{"get": ["$blocks_nbt", {"keyof": "$off"}]}` 按位置键查 NBT。改键要动 DSL 引擎
   （`keyof` 函数），波及面大；block_nbt 只占 10%，不值。保持现键，DSL 零改动。

2. **蓝图 `blocks` 参数保持 map，不直接传 palette+indices**
   WorkItem 参数走**内存**，没有 262144 限制。让 EnqueueHelper 继续把派生 map 传给
   `build:clear_and_build`（`$block_mapping` bind），`place` 步骤的 `{"get": ["$blocks", keyof]}`
   完全不变。调色板只改变 JSON 文件/网络载荷，不动运行时蓝图契约。

3. **旋转改为调色板级（M 次）而非方块级（N 次）**
   旋转建筑 = 旋转 pattern（位置）+ 旋转每个 palette 条目（方块状态属性如 facing/axis），
   block_indices 不变。M≈462 vs N≈8502，旋转成本降 18 倍。`BuildingRotation.rotateBlockMapping`
   保留给派生 map 的旧调用（BuildComplete/Break 完整性检查），或改为 palette 旋转后重映射。

4. **渲染端缓存解析结果**
   `resolveBlockStates` 每帧调用，且对每方块做字符串→BlockState 解析（本就昂贵）。改造为
   按 config 缓存 `Map<BlockOffset, BlockState>`，config 不可变，缓存安全。顺带解决
   `blockMapping()` O(N) 派生 map 的每帧重建问题。

5. **迁移所有现有 JSON 文件（脚本）**
   服务器发的是原始 JSON，要拿体积收益必须把 git 内的 buildings/*.json 全部转成 palette 格式。
   用一次性脚本（python）转换 + 校验。转换后仍需能跑旧格式的解析器兜底（老世界导出）。

## 6. 实施步骤（分阶段，每步可回滚）

### Phase 0 — 前置
- [ ] 提交上一任务的 7 处商店物品 ID 修复（独立 fix，先清空工作树）
- [ ] `./gradlew build` 确认基线绿

### Phase 1 — 数据模型 + 反序列化
- [ ] `BuildingConfig`：字段换 palette/blockIndices；`blockMapping()` 改为派生；`blockIdAt(i)`
- [ ] `Deserializer`：支持新格式 + 旧 block_mapping → palette 转换
- [ ] 单测：`BuildingConfigTest` 扩展（新格式解析、旧格式转换、派生 map 一致性）

### Phase 2 — 扫描器导出格式
- [ ] `ScannerExportPacket`：写出 palette + block_indices（替代 block_mapping）

### Phase 3 — EnqueueHelper + 旋转
- [ ] `computeMaterialCounts` 走 palette 快路径（`blockIdAt(i)`，省字符串键构建）
- [ ] `resolveField("block_mapping")` / `blockMappingToJson` 用派生 map（行为不变）
- [ ] 旋转：`BuildingRotation` 增加 palette 旋转；EnqueueHelper 旋转路径接入

### Phase 4 — JSON 数据迁移
- [ ] 写转换脚本，全部 buildings/*.json → palette 格式
- [ ] 校验：全量重解析 + 尺寸对比 + pattern/block_indices 对齐断言

### Phase 5 — 分块网络同步
- [ ] 新增 `BuildingConfigSyncChunkPacket`（varint + byte[]，zlib 压缩，16KB 分块）
- [ ] `Wandscape.java`：替换包注册 + `onDatapackSync` 发送逻辑
- [ ] `WandscapeClient.java`：重组 handler + 注册
- [ ] 删除旧 `BuildingConfigSyncPacket`

### Phase 6 — 渲染端缓存
- [ ] `BuildingGhostRenderer` / `BuildingPreviewRenderer`：按 config 缓存 BlockState map

### Phase 7 — 文档 + 验证
- [ ] `docs/data/buildings.md` 更新新格式；`docs/decisions.md` 记决策
- [ ] `./gradlew test` 全绿；`./gradlew build`
- [ ] runClient 实测：三个大店建造/旋转/完整性检查/游客交互/幽灵渲染/同步不崩
- [ ] 触发 `OnDatapackSync`（进服）验证分块重组日志

## 7. 体积与性能预期

- 磁盘：三个大店 JSON 各减 ~59%（519K→211K / 503K→205K / 451K→183K）
- 网络：压缩后单店 ~50K，分 4 块，永不超过 262144
- 内存：palette+indices 比 map 更省（方块 ID 字符串只存 M 份）
- 旋转/材料统计：由 N 次字符串操作降为 M 次

## 8. 风险与回滚

| 风险 | 缓解 |
|---|---|
| 老世界 datapack 旧格式建筑无法加载 | 解析器保留旧 block_mapping 分支 |
| 迁移脚本出错破坏 JSON | 脚本先 dry-run 校验 + git 可回滚 |
| 分块重组丢包/乱序 | TCP 有序；chunkIndex 幂等；configIndex 校验 |
| blockMapping() O(N) 派生拖慢每帧渲染 | 渲染端缓存（Phase 6），事件型调用不计 |
| 旋转改 palette 引入状态错乱 | 保留 rotateBlockMapping 旧路径，palette 旋转单测覆盖全方向 |
