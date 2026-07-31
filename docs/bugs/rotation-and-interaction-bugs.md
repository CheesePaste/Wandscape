# 旋转系统与交互 Bug 分析

## 修复状态

| Bug | 描述 | 状态 |
|-----|------|------|
| Bug 1 | townhall1 不显示 boundary + 右键无法交互 | ✅ 间接修复（Bug 3 修复后不再误判 BROKEN） |
| Bug 2 | V 面板侧边栏图标不变色 | ✅ 已修复 |
| Bug 3a | findDamagedBlocks 使用未旋转 pattern | ✅ 已修复 |
| Bug 3b | 维修用未旋转 blockMapping | ✅ 已修复 |
| Bug 3c | tourist_interact_aabb / door_offset 未旋转 | ✅ 已修复 |
| Bug 3d | demolishBuilding 未旋转 offsets | ✅ 已修复 |
| Bug 4 | 楼梯 SHAPE left/right 未交换 | ✅ 已修复 |
| Bug 5 | Warehouse UI 显示 0 | ✅ 间接修复（殖民地 fallback 恢复） |
| 新 Bug | 重进游戏殖民地消失 | ✅ 已修复（rebuildFromSavedData fallback 增强） |

### 修复涉及的 12 个文件

- `BuildingState.java` — 新增 `rotationSteps` 字段 + getter/setter
- `BuildingSavedData.java` — NBT 持久化 + `register()`/`rebuildPosIndex()` 旋转 pattern + `getTouristInteractPoint()`/`getEntryPoint()` 旋转交互框
- `BuildCompleteListener.java` — `findDamagedBlocks()` 接受 rotationSteps 参数
- `BuildingBreakHandler.java` — `enqueueRepairForOffsets/Positions` 使用旋转后 blockMapping
- `BuildingApiImpl.java` — `demolishBuilding()` 旋转 offsets
- `EnqueueHelper.java` — `registerIfAbsent` 设置 rotationSteps + `buildWorkItem` 旋转 `tourist_interact_aabb`/`door_offset`
- `BuildingRotation.java` — 新增 `swapShapeLeftRight()` 修复楼梯 SHAPE
- `BuildingAreaRenderer.java` — 渲染时旋转 boundary 和 tourist_interact_aabb
- `BuildingAreaSyncPacket.java` — BuildingEntry 新增 rotationSteps
- `PanelStateTogglePacket.java` — 同步时携带 rotationSteps
- `BuildingData.java` — 接口新增 `getRotationSteps()` 默认方法
- `WandscapePanelOverlay.java` — Bug 2: 图标根据 activeMode 着色
- `ColonyApiImpl.java` — 殖民地恢复 fallback 不要求 isStructureIntact + 全建筑兜底扫描

---

## 总览

旋转系统（`BuildingRotation` + `EnqueueHelper.buildWorkItem`）目前有**结构性缺陷**：所有 pipeline 中只有 blueprint param 层面的
`offsets`/`blocks`/`blocks_nbt`/`clear_offsets` 做了旋转，但**验证**、**渲染**、**交互寻路**、**维修** 等子系统全部使用了 config 中的原始（未旋转）数据。
这导致旋转后的建筑在多个方面表现异常，并且 bug 之间会相互级联放大。

---

## Bug 1: townhall1 不显示白框 boundary + 右键无法交互

### 现象

- V 面板打开，准心碰到建筑不显示白色边界线框
- 右键点击建筑无法打开市政厅 GUI
这里是0旋转，状态完好的建筑，和旋转无关，其他建筑正常
## Bug 2: V 面板侧边栏模式图标不会变色（不显示当前激活模式）

### 现象

点击侧边栏的 Build/Road/Stats 图标切换模式后，图标颜色不变（应该变绿）。

### 根因

**文件**: `WandscapePanelOverlay.java:130-134`

```java
for (int i = 0; i < 3; i++) {
    ...
    WandscapeTheme.drawIcon(g, tabIcons[i], ix, iy, SIDEBAR_ICON_S, SIDEBAR_ICON_S,
            WandscapeTheme.COLOR_TEXT_NORMAL);  // ← 永远用灰色
}
```

所有三个 tab 图标都硬编码使用 `COLOR_TEXT_NORMAL`（0xFFCCCCCC）。虽然 `isTabActive()` 方法存在（line 506），但**从未被调用**。
需要根据 `activeMode` 判断当前 tab，对激活的 tab 使用不同的颜色（如绿色或 `COLOR_TEXT_ACTIVE` 金色）。

同样，hover 效果也没有实现（`hoveredIcon` 变量已定义但未使用）。

---

## Bug 3: 建筑旋转不旋转交互箱 → 建筑永久 Broken → Destroy 破坏错误区域

### 现象

- 建筑建好后 NPC 报告完成，但立即变成 Broken 状态
- 点 Destroy 破坏的是未旋转区域的内容

### 根因

#### 3a. findDamagedBlocks 使用未旋转的 config.pattern()

**文件**: `BuildCompleteListener.java:170-184`（这是最关键的 bug）

```java
static List<BlockOffset> findDamagedBlocks(Level level, BlockPos anchor, BuildingConfig config) {
    for (BlockOffset offset : config.pattern()) {  // ← 永远用 JSON 中的原始偏移！
        BlockPos target = anchor.offset(offset.x(), offset.y(), offset.z());  // ← 查的是未旋转位置
        String expectedSpec = config.blockMapping().get(expectedKey);          // ← 未旋转的 blockstate
        BlockState actual = level.getBlockState(target);
        if (!blockMatchesSpec(actual, expectedSpec)) {
            damaged.add(offset);
        }
    }
}
```

建筑实际由 NPC 在**旋转后**的位置建造。但 `findDamagedBlocks` 在**未旋转**的位置验证。结果：
- 所有方块都被判定为"受损"
- `isBroken(damaged.size(), pattern.size())` → true（因为 `damaged * 3 >= total`）
- 建筑被标记为 BROKEN

这个 `findDamagedBlocks` 在**三个地方**被调用：
1. `BuildCompleteListener` — 建筑建成后验证
2. `BuildingBreakHandler.onBlockBreak` — 方块被破坏时验证
3. `BuildingBreakHandler.onExplosion` — 爆炸后验证
4. `BuildingBreakHandler.triggerRepair` — 玩家手动修复时验证

全部都会因为使用未旋转的 pattern 而误判。

#### 3b. 维修也用未旋转数据

**文件**: `BuildingBreakHandler.java:186-207`

```java
static void enqueueRepairForOffsets(BuildingState state, BuildingConfig config,
                                    List<BlockOffset> damagedOffsets) {
    for (BlockOffset offset : damagedOffsets) {       // ← 从 findDamagedBlocks 来的未旋转偏移
        String key = offset.toKey();
        String blockSpec = config.blockMapping().get(key);  // ← 未旋转的 blockstate
        ...
    }
}
```

维修 WorkItem 使用了 findDamagedBlocks 报告的未旋转 offsets 和未旋转 blockMapping → NPC 试图在**未旋转位置**放置方块 → 进一步破坏建筑。

#### 3c. 交互箱和门偏移未旋转

**文件**: `EnqueueHelper.java:224-246`

`buildWorkItem()` 的旋转代码：
```
✅ offsets   → rotatePatternJson()
✅ blocks    → rotateBlockMappingJson()
✅ blocks_nbt → rotateBlockNbtJson()
✅ clear_offsets → rotateOffsetsJson()
❌ tourist_interact_aabb → 未处理！
❌ door_offset   → 未处理！
```

`tourist_interact_aabb` 和 `door_offset` 完全未参与旋转，导致：
- `BuildingSavedData.getTouristInteractPoint()` (line 251) 用 `config.touristInteractAabb()` 原始值 → NPC 交互点计算在错误的世界坐标
- `BuildingSavedData.getEntryPoint()` (line 290) 用 `config.doorOffset()` 原始值 → 游客入口在错误位置
- `BuildingAreaRenderer` (line 99) 用 `config.touristInteractAabb()` 原始值 → 橙色交互框画在错误位置

#### 3d. demolishBuilding 未旋转 offsets

**文件**: `BuildingApiImpl.java:307-319`

```java
for (var offset : config.pattern()) {  // ← 未旋转
    JsonArray arr = new JsonArray();
    arr.add(offset.x());
    arr.add(offset.y());
    arr.add(offset.z());
    offsets.add(arr);
}
```

破坏（Destroy）WorkItem 使用了未旋转的 pattern → NPC 在未旋转位置填空气 → 原位置的旋转后方块没被清除。

---

## Bug 4: 旋转不更新方块朝向属性（楼梯 facing/shape 等）

### 现象

旋转放置后，楼梯的 `facing` 和 `shape` 等朝向属性不正确。

### 分析

**文件**: `EnqueueHelper.java:232-235`

`buildWorkItem` 确实调用了 `rotateBlockMappingJson()` → `BuildingRotation.rotateBlockStateString()`。

`rotateBlockStateString` 使用 MC 自带的 `BlockState.rotate(Rotation.COUNTERCLOCKWISE_90)`（line 89-91），
对于楼梯的 FACING 属性处理是正确的（`west` → `north` → `east` → `south` 循环）。

**但 SHAPE 属性（outer_left/inner_left/outer_right/inner_right）的旋转与楼梯的朝向有关**。MC 的 BlockState.rotate()
对 StairBlock 的 SHAPE 处理是否完整需要验证（查阅 Minecraft 源码确认）。

### StairBlock.rotate() 检查

需要运行 `minecraft-source` skill 查看 StairBlock 的 rotate 方法，确认 90° 逆时针旋转时 SHAPE 的转换是否正确。
初步分析：`outer_left` 楼梯逆时针旋转 90° 应该变为 `outer_right`（因为 stair 的 footprint 绕 Y 轴转了 90°，
外角从西北变为西南）。

**但即使 rotateBlockStateString 本身正确**，由于 Bug 3 的 `findDamagedBlocks` 验证失败和维修覆盖，旋转后的 blockstate
在修复过程中会被未旋转的 blockMapping 覆盖掉。这是 Bug 4 真正表现出来时的**触发路径**：

1. NPC 以旋转后的 blockstate 放置了楼梯（facing/shape 正确）
2. `build_complete` → `findDamagedBlocks` 误判所有方块损坏 → 建筑 BROKEN
3. 维修 WorkItem 用未旋转的 `config.blockMapping()` 覆盖了正确的旋转状态
4. 最终建筑中的楼梯朝向变回未旋转状态

---

## Bug 5: Warehouse UI 显示为 0（殖民地从市政厅迁移到独立保存后）

### 现象

- 点击仓库建筑，UI 显示元素为 0、没有物品
- 建筑建造运行正常
- 顶栏元素显示正常
- 其他建筑可能也有类似问题

### 分析

这个问题可能由多个因素叠加造成：

#### 5a. ColonySavedData 独立持久化后的加载时序

`ColonyApiImpl.rebuildFromSavedData()` 在重启时重建殖民地图索引：
1. 优先读取 `ColonySavedData`（独立的 colony 持久化）
2. 如果 `ColonySavedData` 为空，从 buildings 扫描政府建筑作为 fallback

**fallback 的条件**（`ColonyApiImpl.java:177-180`）：
```java
if ("government".equals(bd.getCategory())
        && bd.isStructureIntact()   // ← 建筑必须是 intact！
        && bd.getColonyId() != null)
```

如果市政厅建筑因为 Bug 3 被标记为 `structureIntact=false`，fallback 迁移路径会**跳过**它
→ `ColonyApiImpl` 中没有任何 colony → 所有建筑的 `colonyId` 为 null。

#### 5b. ColonySavedData 持久化未能正确写入

`ColonySavedData` 可能在某些路径下未能成功持久化 colony 数据。
例如在 `rebuildFromSavedData()` 的 builders 创建途中，`ColonyApiImpl` 可能还没有初始化完成，
导致 `getColonySavedData()` 返回 null → createColony 不写入。

#### 5c. 交互查找失败

与 Bug 1 相同：如果仓库建筑也被旋转放置，右键查找可能失败（posIndex miss + structureIntact=false）。
即使 `getBuildingIdAt(pos)` 通过 bounds 查到了建筑，`state.getColonyId()` 也可能为 null。

---

## 问题间的级联关系

```
建筑旋转放置
  ↓
buildWorkItem() 旋转了 offsets/blocks ✅
  └─ tourist_interact_aabb/door_offset 未旋转 ❌  [Bug 3c]
  └─ demolishBuilding 未旋转 offsets ❌   [Bug 3d]
  ↓
NPC 在旋转后位置正确建好了方块 ✅
  ↓
build_complete 事件
  ↓
findDamagedBlocks() 用未旋转 pattern 验证 ❌  [Bug 3a]
  → 所有方块报为 damaged → building BROKEN
```

---

## 修复方向（概要，不涉及具体实现）

所有 bug 的根因可以分为两类，修复时应该优先修第一类，再修第二类。

### 第一类：旋转系统完整性（修复 Bug 1a, 1b, 3c, 3d, 4 的部分）

确保旋转后的所有数据路径都使用旋转后的坐标和数据：

- **posIndex 修复**: `registerIfAbsent` 在覆盖 patternPositions 后，需要同步更新 `BuildingSavedData.posIndex`
- **tourist_interact_aabb 旋转**: `buildWorkItem` 添加 `tourist_interact_aabb` 的旋转（类似 `rotateBlockMappingJson` 的做法），
  同时 `BuildingAreaRenderer` 和 `BuildingSavedData.getInteractPoint` 需要获取旋转后的 aabb
- **door_offset 旋转**: `buildWorkItem` 传递旋转后的 door offset 给 blueprint params，
  或 `BuildingSavedData.getEntryPoint` 在运行时根据 rotationSteps 旋转
- **demolishBuilding**: 需要根据 stored rotation steps 旋转 offsets
- **BuildingAreaRenderer**: 不再使用 `config.boundary()`，而是使用 `BuildingState.getBounds()`（已旋转）

### 第二类：验证/维修路径的旋转感知（修复 Bug 3a, 3b, 4 的主因）

`findDamagedBlocks` 和维修路径需要知道建筑的旋转信息：
- 方式一：在 `BuildingState` 中存储 `rotationSteps`，验证时按此旋转 pattern
- 方式二：在 `buildWorkItem` 的 params 中传递 rotation steps，验证时从 params 读取

（哪种方式更好需要讨论决定）

### 第三类：UI 渲染（修复 Bug 2）

- 侧边栏 tab icon 根据 `activeMode` 使用不同的着色

### 第四类：ColonySavedData 迁移健壮性（修复 Bug 5）

- fallback 迁移不要依赖 `isStructureIntact()`（政府建筑本身就是 colony 的起源）
- 或者在 registerIfAbsent 且建筑类别为 government 时，直接同步写入 ColonySavedData

### 第五类：测试验证

每个 bug 修复后需要验证：
- 旋转 0°/90°/180°/270° 四个方向
- 无旋转的建筑不受影响
- 建筑建成后正常变为 intact 状态
- 右键交互正常（市政厅、仓库、旅馆等）
- 毁灭（Destroy）作用于正确区域
- 侧边栏图标正确变色
- Warehouse UI 显示正确数据
