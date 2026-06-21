# 蓝图 DSL 与 JSON 驱动任务生成

文档编号：NEW-24
版本：2.0
状态：✅ 已实现。BuildingConfig → BlueprintRef → DSL BlueprintJSON → BlueprintInterpreter → TaskSequence 完整链路。DataDrivenSteps 保留为无 BlueprintRef 建筑的后备。
依赖：01-shared-api, 08-building-core, 19-engine-v1-baseline

---

## 一、核心架构：Building JSON + Blueprint JSON 分离

| | Building JSON | Blueprint JSON |
|---|---|---|
| **角色** | 数据容器 | 逻辑容器（DSL 函数） |
| **位置** | `data/wandscape/buildings/` | `data/wandscape/blueprints/` |
| **内容** | 静态属性（comfort/magic/pattern/boundary/…） | DSL 步骤序列 + 参数声明 |
| **运行时** | 字段打包进 TaskRequest.params | 解释器执行 → 生成 TaskSequence |

```
Building JSON                      Blueprint JSON
  pattern, block_mapping...  ──►    params + steps DSL
  bind: {offsets: $pattern...}     for_each / if / call / place...
       │                                    │
       └──── EnqueueHelper rename ──────────┘
                    │
              BlueprintInterpreter（运行时解释）
                    │
              TaskSequence(AtomicOp[])
```

---

## 二、数据流（运行时）

```
玩家命令 / GUI
  │
  │ 1. 读 BuildingConfig.blueprint.bind
  │ 2. EnqueueHelper: 绑定 $field_name → json value
  │ 3. anchor = [x, y, z]（硬编码）
  │ 4. 构建 WorkItem (params = Map<String, JsonElement>)
  ▼
BuildingTaskSource.poll()
  → TaskRequest(blueprintId="build:place_structure", params={offsets, blocks, name, anchor})
  → BlueprintRegistry.compile(request)
      → BlueprintInterpreter.interpret(dsl, params)
          → 求值表达式 → 展开 for_each / if / call
          → TaskSequence(AtomicOp[], label)
  → GlobalTask(sequence)
```

---

## 三、DSL 能力集（V1）

### 3.1 步骤类型（12 种）

| 类型 | 映射的 AtomicOp |
|------|----------------|
| `place` | TransformOp.place(at, block) |
| `remove` | TransformOp.remove(at, from, drops=[]) |
| `convert` | TransformOp.convert(at, from, to) |
| `block_interact` | BlockInteractOp(at, action) |
| `entity_interact` | EntityInteractOp(target, effect, strength, duration) |
| `ritual` | RitualOp(ritual, at, channelTicks) |
| `request_resource` | ResourceRequestOp(resource, amount) |
| `emit_event` | EmitEventOp(event, data) |
| `for_each` | 循环展开（list→逐个元素绑定 var→执行子步骤） |
| `if` | 条件分支 + IfConditionOp 跳转 |
| `call` | 宏展开子蓝图步骤（递归检测） |
| `log` | 引擎日志输出（不生成 AtomicOp） |

### 3.2 表达式系统

| 类别 | 操作符 | 示例 |
|------|--------|------|
| 字面量 | string, int, pos, list\<pos\>, list\<string\>, map | `"minecraft:stone"`, `42`, `[0,64,0]` |
| 变量引用 | `$var` / `{"$": "var"}` | `"$offsets"` |
| 域访问 | `$.field` | `{"$.field": ["$pos", "x"]}` |
| 算术 | `+`, `-`, `*` | `{"+": ["$anchor", "$off"]}` |
| 比较 | `==`, `!=`, `>`, `<`, `>=`, `<=` | `{"==": ["$a", "$b"]}` |
| Map 查询 | `get` + `keyof` | `{"get": ["$blocks", {"keyof": "$off"}]}` |
| 列表长度 | `size` | `{"size": "$offsets"}` |
| 格式 | `format` | `{"format": ["Build {}", "$name"]}` |
| 隐式转换 | int→string, pos→string | 自动应用 |

### 3.3 参数类型（6 种底层类型）

`string` / `int` / `pos` / `list<pos>` / `list<string>` / `map<string,string>`

---

## 四、Java 类型体系

### 4.1 core 层 — DSL AST

```java
public record BlueprintDefinition(String id, Map<String, ParamType> params, List<StepNode> steps) {}
public sealed interface ParamType { StringType, IntType, PosType, ListPosType, ListStringType, MapStringStringType }
public sealed interface StepNode { PlaceStep, RemoveStep, ConvertStep, …, ForEachStep, IfStep, CallStep, LogStep }
public sealed interface ExprNode { LiteralString, LiteralInt, LiteralPos, …, Var, Add, Sub, MapGet, Size, Format, KeyOf }
```

### 4.2 core 层 — 参数值载体

`params` 全链路使用 `Map<String, JsonElement>`：
`WorkItem → TaskRequest → BlueprintSteps.generate() → GlobalTask.taskParams → TaskExecutor.taskParams`

Gson `JsonPrimitive` 包装标量值，`JsonArray` 包装列表/pos，`JsonObject` 包装 map。

### 4.3 engine 层 — 加载器

`engine/source/blueprint/BlueprintConfigLoader.java`：
- `registerWith(WandscapeDataLoader)` — 注册 `"blueprints"` 数据类别
- `parseDefinition(JsonElement)` — JSON → `BlueprintDefinition` AST
- `registerIn(BlueprintRegistry, BlueprintInterpreter)` — 注册为 `BlueprintSteps` lambda

### 4.4 building 层 — 入队助手

`building/internal/EnqueueHelper.java`：
- `buildWorkItem(config, pos, buildingTypeId, priority)` — 从 BlueprintRef 构建 WorkItem
- `resolveField(config, "$field_name")` — 将 building JSON 字段转为 JsonElement
- anchor 硬编码 `[x,y,z]`

### 4.5 BuildingConfig 新增

```java
public record BlueprintRef(String id, Map<String, String> bind) {}
// BuildingConfig 新增字段：@Nullable BlueprintRef blueprint
```

---

## 五、Blueprint JSON 示例

### 5.1 `build:place_structure` — 建筑放置

```json
{
  "id": "build:place_structure",
  "params": {
    "offsets": "list<pos>",
    "blocks": "map<string,string>",
    "name": "string",
    "anchor": "pos"
  },
  "steps": [
    {
      "type": "for_each",
      "list": "$offsets",
      "var": "off",
      "steps": [
        {
          "type": "place",
          "at": {"+": ["$anchor", "$off"]},
          "block": {"get": ["$blocks", {"keyof": "$off"}]}
        }
      ]
    },
    {
      "type": "emit_event",
      "event": "build_complete",
      "data": {
        "building_name": "$name",
        "blocks_placed": {"size": "$offsets"}
      }
    }
  ]
}
```

### 5.2 Building JSON 引用蓝图

```json
{
  "id": "town_hall",
  "type": "building",
  "pattern": [[-1,0,-1], [-1,0,0], ...],
  "block_mapping": {"-1,0,-1": "minecraft:stone_bricks", ...},
  "blueprint": {
    "id": "build:place_structure",
    "bind": {
      "offsets": "$pattern",
      "blocks": "$block_mapping",
      "name": "$display_name"
    }
  }
}
```

### 5.3 带条件的蓝图

```json
{
  "id": "gather:smart_harvest",
  "params": { "resource": "string", "target": "pos", "min_stock": "int" },
  "steps": [
    {
      "type": "if",
      "condition": "resource_below",
      "params": { "resource": "$resource", "threshold": "$min_stock" },
      "then": [
        {"type": "request_resource", "resource": "$resource", "amount": 10}
      ],
      "else": [
        {"type": "log", "level": "debug", "text": "Stock sufficient, skipping"}
      ]
    }
  ]
}
```

---

## 六、布线

`EngineBootstrap.bootstrap()` 流程：

```
1. BlueprintRegistry 创建
2. BlueprintConfigLoader.registerIn(registry, interpreter)
   → 所有 DSL 蓝图注册为 BlueprintSteps lambda
3. BuildingConfigLoader 遍历
   → 无 BlueprintRef 的建筑：DataDrivenSteps.fromConfig() fallback
   → 有 BlueprintRef 的建筑：依赖 DSL 蓝图（已在步骤 2 注册）
4. 其他蓝图注册 + CoreBootstrap
```

---

## 七、与旧系统的关系

| 组件 | 状态 |
|------|------|
| `DataDrivenSteps` | **保留为 fallback**。无 `blueprint.ref` 的建筑仍用此自动生成 `build:<id>` |
| `BlueprintSteps` | **保留**。DSL 蓝图通过 `BlueprintInterpreter` 解释后也实现此接口 |
| `BlueprintRegistry` | **保留**。`compile()` 调用 `BlueprintSteps.generate()` |
| `BuildingBlueprints` | **已删除**（在 V1 中）。硬编码蓝图全部被 JSON 取代 |

---

## 八、关键设计决策

完整决策清单见 `spec/blueprint-dsl.md` §7（18 项决策）。

| # | 决策 |
|---|------|
| 1 | 运行时解释（不编译期预绑定，无 IR 缓存 V1） |
| 2 | 强类型参数（6 种底层类型 + 隐式转换） |
| 3 | 扁平步骤数组 + 控制流步骤内嵌子步骤 |
| 4 | 表达式 AST（JSON 节点运算符） |
| 5 | 编译器在 core/（纯 Java 21 + Gson） |
| 6 | call = 宏展开（子蓝图内联，独立任务走 emit_event） |
| 7 | 全数据打包进 params |
| 8 | EnqueueHelper 做 rename（解释器单一职责） |
| 9 | anchor 硬编码 |
| 10 | `Map<String, JsonElement>` 全链路 |
| 11 | 无 type 字段（folder 区分 building/blueprint） |
| 12 | bind = 简单 `$field_name` 重命名 |
| 13 | for_each 变量遮蔽检测 → 异常 |
| 14 | call 递归检测 → 异常 |
| 15 | 解释错误 → 异常 → 任务 FAILED |

---

## 九、测试

`src/test/java/com/wsteam/wandscape/core/task/BlueprintInterpreterTest.java`（26 用例）：

| 类别 | 用例数 |
|------|--------|
| 表达式求值（字面量、变量、算术、比较、size、format、keyof、map get） | 13 |
| 步骤展开（place、emit_event、request_resource、for_each、if、log、shadowing 检测、label） | 7 |
| 参数校验（缺失参数 → 异常、额外参数允许） | 2 |
| 递归检测（call self → 异常） | 1 |
| `DataDrivenStepsTest`（保留） | 8 |

`./gradlew test` — 全绿（249 用例）。

---

## 十、实现记录

### 新增文件

| 文件 | 位置 | 作用 |
|------|------|------|
| `BlueprintDefinition.java` | `core/task/` | DSL AST 根对象 |
| `BlueprintInterpreter.java` | `core/task/` | 运行时解释器 |
| `ExprNode.java` | `core/task/` | 21 种表达式 AST 节点 |
| `StepNode.java` | `core/task/` | 12 种 DSL 步骤类型 |
| `ParamType.java` | `core/task/` | 6 种参数类型声明 |
| `BlueprintConfigLoader.java` | `engine/source/blueprint/` | JSON → AST 解析 + 注册 |
| `EnqueueHelper.java` | `building/internal/` | 入队 rename + anchor 构造 |
| `build_place_structure.json` | `data/wandscape/blueprints/` | 首个 DSL 蓝图 |

### 修改文件（18 个）

`BlueprintSteps` / `TaskRequest` / `WorkItem` / `GlobalTask` / `TaskExecutor` / `GlobalTaskPool` / `DefaultOpExecutors` / `DataDrivenSteps` / `EventDrivenTaskSource` / `SystemBlueprintRegistry` / `BuildingConfig` / `BuildingSavedData` / `BuildingState` / `EnqueueHelper` / `EngineBootstrap` / `WandscapeEngine` / `Wandscape`

### 修改 JSON（3 个建筑）

`town_hall.json` / `earth_node.json` / `forest_node.json` — 均新增 `boundary` + `blueprint.ref`。

### 设计文档

`spec/blueprint-dsl.md` — 完整 DSL 规格（Schema、12 步类型、表达式语法、18 决策清单）
`spec/building-json.md` — 完整 Building JSON 规格（14 字段、runtime 状态、数据流）
