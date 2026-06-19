# 多方块结构 JSON 导出工具

文档编号：NEW-18
版本：1.0
状态：开发工具——将世界中的建筑导出为 Wandscape 多方块 JSON
依赖：无（独立工具）

---

## 一、用途

在创造模式中搭建建筑后，一键导出为 `data/wandscape/buildings/<id>.json` 中的 `pattern` + `block_mapping` 格式。解决大型建筑手工写 JSON 的低效问题。

---

## 二、方案：游戏内命令

### 2.1 命令语法

```
/wandscape export-structure <building_id> <x1> <y1> <z1> <x2> <y2> <z2>
```

- `building_id`：导出后的建筑 ID（如 `forest_node`）
- 两个坐标定义矩形区域的**两个对角**（自动取 min/max）

### 2.2 导出逻辑

1. 遍历矩形区域内所有非空气方块
2. 以选区最小角为原点，每个非空气方块记录其相对坐标 `[x, y, z]` 和方块 ID
3. 按坐标排序后输出 BlockOffset 格式（与建筑 JSON 格式完全一致，可直接使用）
4. 输出 JSON 文件到 `config/wandscape/exports/<building_id>.json`
5. 玩家需要将文件移动到 `data/wandscape/buildings/` 目录，并补充 `display_name`、`category`、`comfort`、`magic`、`wonder`、`maintenance_cost`、`queue` 等非结构字段

### 2.3 输出示例

对 2×2×1 的简单节点建筑选区，生成的 JSON：

```json
{
  "id": "forest_node",
  "block_id": "wandscape:forest_node",
  "pattern": [
    [0, 0, 0], [1, 0, 0],
    [0, 0, 1], [1, 0, 1]
  ],
  "block_mapping": {
    "0,0,0": "minecraft:oak_log",
    "1,0,0": "minecraft:oak_log",
    "0,0,1": "minecraft:oak_log",
    "1,0,1": "wandscape:node_core"
  }
}
```

---

## 三、方块→坐标逻辑（不再使用字母代号）

导出工具直接记录每个方块的相对坐标和方块状态字符串，不经字母中转：

1. 确定选区原点 `(minX, minY, minZ)`
2. 遍历选区，每个非空气方块计算 `[x - minX, y - minY, z - minZ]`
3. 记录 `BlockState.toString()`（含方块状态如 `axis`）
4. 同一方块状态字符串出现多次 → 在 `block_mapping` 中仍只出现一次
5. 空气方块不输出

---

## 四、导出后人工补充

生成的 JSON 已包含 `pattern` + `block_mapping`（BlockOffset 格式），可直接被建造系统使用。需手动补充的字段：

```json
{
  "id": "forest_node",
  "display_name": "森林节点",        // 手动填写
  "category": "node",                // 手动选择
  "block_id": "wandscape:forest_node",
  "pattern": [ /* 自动生成 */ ],
  "block_mapping": { /* 自动生成 */ },
  "comfort": 1,                      // 手动
  "magic": 0,                        // 手动
  "wonder": 1,                       // 手动
  "maintenance_cost": 2,             // 手动
  "shutdown_penalty": {
    "output_reduction": 0.5,         // 手动
    "time_multiplier": 2.0           // 手动
  },
  "queue": {
    "capacity": 10,                  // 手动
    "task_types": ["gathering"]       // 手动
  },
  "unlock_requirement": {
    "min_wonder": 0                  // 手动
  },
  "node_config": {                   // 手动（节点专属）
    "element": "wood",
    "amount_per_harvest": 10,
    "channel_ticks": 200,
    "required_behavior": "gathering",
    "required_level": 1
  }
}
```

---

## 五、实现方案

```java
public class ExportStructureCommand {
    // 注册命令
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("wandscape")
                .then(Commands.literal("export-structure")
                    .then(Commands.argument("building_id", StringArgumentType.word())
                        .then(Commands.argument("x1", IntegerArgumentType.integer())
                            .then(Commands.argument("y1", IntegerArgumentType.integer())
                                .then(Commands.argument("z1", IntegerArgumentType.integer())
                                    .then(Commands.argument("x2", IntegerArgumentType.integer())
                                        .then(Commands.argument("y2", IntegerArgumentType.integer())
                                            .then(Commands.argument("z2", IntegerArgumentType.integer())
                                                .executes(ctx -> export(
                                                    ctx.getSource(),
                                                    StringArgumentType.getString(ctx, "building_id"),
                                                    new BlockPos(
                                                        IntegerArgumentType.getInteger(ctx, "x1"),
                                                        IntegerArgumentType.getInteger(ctx, "y1"),
                                                        IntegerArgumentType.getInteger(ctx, "z1")
                                                    ),
                                                    new BlockPos(
                                                        IntegerArgumentType.getInteger(ctx, "x2"),
                                                        IntegerArgumentType.getInteger(ctx, "y2"),
                                                        IntegerArgumentType.getInteger(ctx, "z2")
                                                    )
                                                ))
                                            )
                                        )
                                    )
                                )
                            )
                        )
                    )
                )
        );
    }

    private static int export(CommandSourceStack source, String id, BlockPos p1, BlockPos p2) {
        Level level = source.getLevel();

        // 确定矩形区域
        int minX = Math.min(p1.getX(), p2.getX());
        int minY = Math.min(p1.getY(), p2.getY());
        int minZ = Math.min(p1.getZ(), p2.getZ());
        int maxX = Math.max(p1.getX(), p2.getX());
        int maxY = Math.max(p1.getY(), p2.getY());
        int maxZ = Math.max(p1.getZ(), p2.getZ());

        // 收集非空气方块 → pattern (BlockOffset 数组) + block_mapping
        List<List<Integer>> pattern = new ArrayList<>();
        Map<String, String> blockMapping = new LinkedHashMap<>(); // 坐标key → BlockState字符串

        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    BlockState state = level.getBlockState(new BlockPos(x, y, z));
                    if (!state.isAir()) {
                        int rx = x - minX, ry = y - minY, rz = z - minZ;
                        pattern.add(List.of(rx, ry, rz));
                        String key = rx + "," + ry + "," + rz;
                        blockMapping.put(key, state.toString()); // 含方块状态
                    }
                }
            }
        }

        // 构建 JSON
        JsonObject json = new JsonObject();
        json.addProperty("id", id);
        json.add("pattern", gson.toJsonTree(pattern));
        json.add("block_mapping", gson.toJsonTree(blockMapping));

        // 写入 config/wandscape/exports/ 目录
        Path exportDir = Path.of("config/wandscape/exports");
        Files.createDirectories(exportDir);
        Path outFile = exportDir.resolve(id + ".json");
        Files.writeString(outFile, new GsonBuilder().setPrettyPrinting().create().toJson(json));

        source.sendSuccess(() -> Component.literal("Exported to config/wandscape/exports/" + id + ".json"), false);
        return 1;
    }
}
```

---

## 六、独立测试方案

1. 创造模式搭建 3×3×2 的祭坛 → 执行导出命令 → 检查 JSON 的 layers、grid 维度、mapping 正确
2. 包含不同朝向的原木 → 验证方块状态被正确记录（`axis=x` vs `axis=y` 分不同字母）
3. 导出后补全字段 → 将 JSON 放入 `data/wandscape/buildings/` → `/reload` → 远程建造该建筑 → 验证方块放置正确
