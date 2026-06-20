# architecture/ — 代码结构快照

按**模块**组织。查 bug 或了解某个模块时，打开对应文件即可看到：源文件用途、注册项、发布/订阅的事件、加载的 JSON 格式。

## 模块 → 文件

| 出问题时 | 打开 |
|---------|------|
| NPC 不生成 / 不寻路 / ECS 桥接失败 | `06-npc.md` |
| 法杖 NBT 不对 / 能力并集算错 | `03-wand.md` |
| 元素映射加载失败 / decompose 结果错 | `04-element.md` |
| 建筑放置不验证 / BE 不入队 / 配置解析错 | `05-building.md` |
| JSON 热重载不生效 / 数据加载框架问题 | `07-data-config.md` |
| 引擎调度不工作 / 任务不分配 / tick 卡死 | `00-core-engine.md` |
| MC 桥梁（方块操作 / 异步执行 / 传送）| `01-engine-bridge.md` |
| API 接口缺失 / 事件类找不到 | `02-shared-api.md` |
| 编码规范 + 反模式 | `08-conventions.md` |

## 问题 → 文件

| 症状 | 先看 |
|------|------|
| `Scheduler heartbeat - no idle NPCs` | `06-npc.md` → EntityComponentBridge, `00-core-engine.md` → SchedulerSystem |
| `Blueprint not found: build:xxx` | `01-engine-bridge.md` → 检查 BuildingConfig JSON 是否加载、是否有 blueprint ref（新 DSL）或无 ref 时的 DataDrivenSteps fallback（遗留） |
| `Unknown blueprint in call: xxx` | `01-engine-bridge.md` → BlueprintConfigLoader 是否注册了被引用的 DSL 蓝图 |
| `./gradlew test` 全红 | `00-core-engine.md` → 测试在 `src/test/java/.../core/` |
| 建筑放了没反应 | `05-building.md` → BlockPlaceHandler, EnqueueHelper, BuildingConfigLoader |
| NPC 位置不更新 | `06-npc.md` → EntityComponentBridge.syncPositions |
| 法杖预设加载失败 | `03-wand.md` → WandPresetLoader |
| 异步 Op 卡住不推进 | `01-engine-bridge.md` → AsyncTransformExecutor, `00-core-engine.md` → TaskExecutionSystem |

## 总览

```
src/main/java/com/wsteam/wandscape/
├── core/       (62 文件) → 00-core-engine.md   纯 Java ECS 引擎
├── engine/     ( 9 文件) → 01-engine-bridge.md MC 桥梁层
├── shared/     (39 文件) → 02-shared-api.md    接口 + 事件 + 数据类
├── wand/       ( 5 文件) → 03-wand.md          法杖
├── element/    ( 3 文件) → 04-element.md       元素映射
├── building/   (12 文件) → 05-building.md      建筑
├── npc/        ( 5 文件) → 06-npc.md           NPC
└── dataconfig/ ( 2 文件) → 07-data-config.md   JSON 加载框架
```

设计文档在 `docs/`（"应该做成什么样"），架构快照在 `architecture/`（"现在是什么样"）。
