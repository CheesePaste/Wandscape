# command/ — 调试命令

`src/main/java/com/wsteam/wandscape/command/`（+ guard/GuardCommand）

## 总览

根命令 `/wandscape`，`requires(src.hasPermission(2))`——**全部子命令至少需要 OP 2**。注册点：`Wandscape.onRegisterCommands`。

## 子命令清单

| 子命令 | 作用 |
|---|---|
| `generate_element_mappings` | 由 seeds + 配方生成 element_mappings JSON，支持 `--dry-run`/`--force`，直接写 src/main/resources |
| `audit_elements` | 扫描全部注册物品，报告缺元素值（无 seed/映射/配方）者 |
| `logfilter` | 运行时日志 tag 白名单 on/off/add/remove/clear/list/preview |
| `fill <type> <spacing> <count>` | 沿 +X 注册 N 栋建筑并向任务池提交 blueprint 任务 |
| `navtest` | 最近 NPC 寻路到最近绿宝石方块 |
| `colony create <name>` | 一人一殖民地，前置 town_hall 配置，spawn 3 个 builder NPC + 发材料 + ColonyCreatedEvent + 烟花 |
| `colony destroy` | 删除殖民地 |
| `publish <blueprint> [key=value...] [priority]` | 发布全局任务，含坐标/列表参数解析 |
| `recovery clear` / `recovery status` | 清任务池/建筑队列/复位 NPC；打印任务池统计 |
| `seed_warehouse` | 向仓库灌 9999×每个注册物品 + 每个元素 + builder_wand |
| `consume_warehouse` | 清空殖民地仓库全部物品/元素 |
| `stress <n> <m>` | spawn N NPC 网格 + M 个 town_hall 任务（3D 立方分布） |
| `tourist list/spawn/state/cooldown` | 游客调试：列出/生成/状态/跳过冷却 |
| `transport [spawn|fx fy fz [item] [tx ty tz [count]]]` | 物品飞行动画测试 |
| `magic [circle] [color]` | 施法阵 + 信标光束（默认 arcane_hexagram） |
| `guard status`（guard/GuardCommand） | 打印守卫区数/最近威胁/脱离区清空/活跃守卫任务数 |
| `guide` | 加载 `assets/wandscape/guide/test_guide.md` 发 GuideTestPacket 开 Markdown 引导测试屏 |
| `spline edit|done` | 进/出样条道路编辑器（SplineEditorEnterPacket） |

## 辅助说明

- `ColonyCommand` 提供静态 `createColonyAt`/`ensureColonyNear` 供面板复用（市政厅建城流程）。
- `GuideCommand` 是文档阅读器入口；`SplineEditorCommand` 触发样条编辑器进入/退出。
